import json
import os
import select
import socket
import tempfile
import threading
import time

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("CONNLIMIT_TEST_PORT", "9444"))
AUTH_TIMEOUT_S = 1.5
IDLE_TIMEOUT_S = 1.5
MAX_CONNECTIONS = 6
DB = "connlimits_db"
COLL = "items"

bu.configure(host=HOST, port=PORT)


def write_config(work_dir: str):
    bu.write_config(work_dir, PORT, maxRequestSize="128Kb", maxEntrySize="64Kb", maxPageSize="256Kb",
                    authTimeoutMs=int(AUTH_TIMEOUT_S * 1000), transactionIdleTimeoutMs=int(IDLE_TIMEOUT_S * 1000),
                    maxConnections=MAX_CONNECTIONS)


def raw_socket(timeout: float = 10.0) -> socket.socket:
    return socket.create_connection((HOST, PORT), timeout=timeout)


def read_frame(sock: socket.socket, timeout: float):
    deadline = time.time() + timeout
    data = b""
    while b"\n" not in data:
        remaining = deadline - time.time()
        if remaining <= 0:
            return None
        ready, _, _ = select.select([sock], [], [], remaining)
        if not ready:
            return None
        try:
            chunk = sock.recv(65536)
        except OSError:
            return None
        if not chunk:
            break
        data += chunk
    line = data.split(b"\n", 1)[0].decode(errors="replace").strip()
    if not line:
        return None
    try:
        return json.loads(line)
    except json.JSONDecodeError:
        return {"status": "ERROR", "message": line}


def reaches_eof(sock: socket.socket, timeout: float = 5.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        ready, _, _ = select.select([sock], [], [], deadline - time.time())
        if not ready:
            return False
        try:
            if not sock.recv(65536):
                return True
        except OSError:
            return True
    return False


def send_in_background(sock: socket.socket, payload: bytes) -> threading.Thread:
    def run():
        try:
            sock.sendall(payload)
        except OSError:
            pass

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    return thread


def dribble_in_background(sock: socket.socket, interval: float, duration: float) -> threading.Thread:
    def run():
        end = time.time() + duration
        while time.time() < end:
            try:
                sock.sendall(b"x")
            except OSError:
                return
            time.sleep(interval)

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    return thread


def send_only(conn: Conn, payload: dict):
    conn.s.sendall((json.dumps(payload) + "\n").encode())


def save(doc_id: str) -> dict:
    return {"type": "SAVE", "databaseName": DB, "collectionName": COLL, "object": {"_id": doc_id, "v": 1}}


def find(doc_id: str) -> dict:
    return {"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": doc_id}


def padded_list_databases(size: int) -> str:
    prefix = '{"type":"LIST_DATABASES","pad":"'
    return prefix + "a" * (size - len(prefix) - 2) + '"}'


def setup_collection():
    with Conn.authenticated() as admin:
        check_status("CREATE_DATABASE for the suite", admin.send({"type": "CREATE_DATABASE", "databaseName": DB}),
                     "OK")
        check_status("CREATE_COLLECTION for the suite",
                     admin.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL}), "OK")


def test_an_oversized_line_is_answered_400_22_and_closed():
    section("An oversized line is answered 400-22 and closed")
    with Conn.authenticated() as conn:
        started = time.time()
        send_in_background(conn.s, b"a" * 200_000)
        frame = read_frame(conn.s, 5.0)
        check_code("200 000 bytes with no newline -> 400-22", frame or {}, "ERROR", "400-22")
        check("The refusal arrives without a terminator", time.time() - started < 5.0)
        check("The refusal frame is typed CLOSE_CONNECTION", (frame or {}).get("type") == "CLOSE_CONNECTION",
              f"frame={frame}")
        check("The server closes the connection", reaches_eof(conn.s))
    with Conn.authenticated() as fresh:
        check_status("A fresh connection is still served", fresh.send({"type": "LIST_DATABASES"}), "OK")


def test_the_unauthenticated_line_limit_is_64kb():
    section("Before authentication a line is capped at 64Kb")
    sock = raw_socket()
    try:
        send_in_background(sock, (padded_list_databases(100_000) + "\n").encode())
        frame = read_frame(sock, 5.0)
        check_code("Unauthenticated 100 000-byte LIST_DATABASES -> 400-22", frame or {}, "ERROR", "400-22")
        check("The unauthenticated connection is closed", reaches_eof(sock))
    finally:
        sock.close()
    with Conn.authenticated() as conn:
        check_status("The same line after AUTHENTICATE is served", conn.send_raw(padded_list_databases(100_000)),
                     "OK")


def test_a_flat_array_over_the_token_budget_is_refused_and_the_connection_survives():
    section("A line denser than the token budget is refused; the connection survives")
    prefix = '{"type":"LIST_DATABASES","x":[1'
    line = prefix + ",1" * ((100_000 - len(prefix) - 2) // 2) + "]}"
    with Conn.authenticated() as conn:
        refused = conn.send_raw(line)
        check("A 100 000-byte flat array -> The command is not valid",
              refused.get("status") == "ERROR" and refused.get("message") == "The command is not valid",
              f"got={str(refused)[:200]}")
        check_status("The next LIST_DATABASES on the same connection", conn.send({"type": "LIST_DATABASES"}), "OK")


def test_an_unauthenticated_idle_socket_is_closed_with_408_4():
    section("An unauthenticated idle socket is closed with 408-4")
    sock = raw_socket()
    try:
        started = time.time()
        frame = read_frame(sock, AUTH_TIMEOUT_S + 3)
        elapsed = time.time() - started
        check_code("Silence -> 408-4", frame or {}, "ERROR", "408-4")
        check("Answered within authTimeoutMs + 3 s", elapsed < AUTH_TIMEOUT_S + 3, f"elapsed={elapsed:.2f}s")
        check("The socket is closed afterwards", reaches_eof(sock))
    finally:
        sock.close()


def test_a_dribbling_unauthenticated_client_is_still_cut_off():
    section("Dribbling bytes does not extend the authentication deadline")
    sock = raw_socket()
    try:
        started = time.time()
        dribble_in_background(sock, 0.4, 3.0)
        frame = read_frame(sock, 5.0)
        elapsed = time.time() - started
        check_code("A never-finished line -> 408-4", frame or {}, "ERROR", "408-4")
        check("Cut off near authTimeoutMs", 1.2 <= elapsed < 2.6, f"elapsed={elapsed:.2f}s")
    finally:
        sock.close()


def test_a_failed_authenticate_does_not_extend_the_deadline():
    section("A failed AUTHENTICATE does not extend the deadline")
    conn = Conn()
    try:
        started = time.time()
        time.sleep(1.0)
        failed = conn.authenticate(password="not-the-password")
        check("The wrong password is refused", failed.get("status") != "OK", f"got={failed}")
        frame = read_frame(conn.s, 3.0)
        elapsed = time.time() - started
        check_code("Then silence -> 408-4", frame or {}, "ERROR", "408-4")
        check("Closed by the original deadline", elapsed < 2.4, f"elapsed={elapsed:.2f}s")
    finally:
        conn.close()


def test_an_authenticated_idle_connection_survives_the_timeout():
    section("An authenticated idle connection is not timed")
    with Conn.authenticated() as conn:
        time.sleep(3.0)
        check_status("LIST_DATABASES after 3 s of silence", conn.send({"type": "LIST_DATABASES"}), "OK")


def test_a_listen_connection_survives_the_idle_timeout():
    section("A LISTEN connection is not timed")
    with Conn.authenticated() as listener, Conn.authenticated() as writer:
        steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "late"}}]
        registered = listener.send({"type": "LISTEN", "databaseName": DB, "collectionName": COLL,
                                    "aggregationSteps": steps})
        check_status("LISTEN registered", registered, "OK")
        time.sleep(3.0)
        check_status("A matching write from another connection",
                     writer.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                                  "object": {"_id": "late-1", "kind": "late"}}), "OK")
        pushed = listener.recv(timeout=5.0)
        check("The listener still receives the notification after 3 s idle",
              pushed is not None and pushed.get("listenId") == registered.get("listenId"), f"pushed={pushed}")
        writer.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "late-1"})


def open_transaction_with_one_write(doc_id: str) -> Conn:
    holder = Conn.authenticated()
    check_status("START_TRANSACTION", holder.send({"type": "START_TRANSACTION"}), "OK")
    check_status(f"SAVE {doc_id} inside the transaction", holder.send(save(doc_id)), "OK")
    return holder


def test_an_idle_transaction_is_rolled_back_and_unblocks_the_writer():
    section("An idle transaction is rolled back and unblocks a waiting writer")
    holder = open_transaction_with_one_write("held-1")
    try:
        with Conn.authenticated() as writer:
            send_only(writer, save("standalone-1"))
            check("The standalone SAVE waits on the transaction's lock", writer.recv(timeout=0.5) is None)
            unblocked = writer.recv(timeout=IDLE_TIMEOUT_S + 3)
            check_status("The standalone SAVE completes once the transaction is reaped", unblocked or {}, "OK")
        check_code("COMMIT_TRANSACTION after the reap -> 409-16", holder.send({"type": "COMMIT_TRANSACTION"}),
                   "ERROR", "409-16")
        check_status("START_TRANSACTION afterwards works", holder.send({"type": "START_TRANSACTION"}), "OK")
        check_status("ROLLBACK_TRANSACTION of the fresh transaction",
                     holder.send({"type": "ROLLBACK_TRANSACTION"}), "OK")
        check_status("The reaped transaction's document was discarded", holder.send(find("held-1")), "NOT_FOUND")
    finally:
        holder.close()


def test_any_request_after_a_reap_answers_409_16_once():
    section("Exactly one request after a reap answers 409-16 and is not executed")
    holder = open_transaction_with_one_write("held-2")
    try:
        time.sleep(IDLE_TIMEOUT_S + 1)
        check_code("The next SAVE -> 409-16", holder.send(save("lost-2")), "ERROR", "409-16")
        check_status("It was not written as a standalone save", holder.send(find("lost-2")), "NOT_FOUND")
        check_status("The following SAVE is served", holder.send(save("kept-2")), "OK")
        check_status("And it is durable", holder.send(find("kept-2")), "OK")
    finally:
        holder.close()


def test_a_busy_transaction_is_not_reaped():
    section("A transaction that keeps sending requests is not reaped")
    holder = open_transaction_with_one_write("busy-1")
    try:
        all_ok = True
        end = time.time() + 4.0
        while time.time() < end:
            all_ok = holder.send(find("busy-1")).get("status") == "OK" and all_ok
            time.sleep(0.5)
        check("Every FIND_BY_ID inside the transaction was served", all_ok)
        check_status("COMMIT_TRANSACTION after 4 s of activity", holder.send({"type": "COMMIT_TRANSACTION"}), "OK")
    finally:
        holder.close()


def test_a_dribbling_transaction_holder_is_reaped():
    section("A transaction holder dribbling an unfinished line is still reaped")
    holder = open_transaction_with_one_write("dribble-1")
    try:
        dribble_in_background(holder.s, 0.4, 4.0)
        with Conn.authenticated() as writer:
            started = time.time()
            send_only(writer, save("dribble-standalone"))
            unblocked = writer.recv(timeout=IDLE_TIMEOUT_S + 3)
            check_status("The blocked standalone SAVE proceeds", unblocked or {}, "OK")
            check("Within the idle timeout plus slack", time.time() - started < IDLE_TIMEOUT_S + 3)
    finally:
        holder.close()


def test_close_connection_is_always_accepted_after_a_reap():
    section("CLOSE_CONNECTION is never refused after a reap")
    holder = open_transaction_with_one_write("close-1")
    try:
        time.sleep(IDLE_TIMEOUT_S + 1)
        bye = holder.send({"type": "CLOSE_CONNECTION"})
        check("CLOSE_CONNECTION is answered, not 409-16",
              bye.get("type") == "CLOSE_CONNECTION" and bye.get("errorCode") != "409-16", f"got={bye}")
        check("The server closes the connection", reaches_eof(holder.s))
    finally:
        holder.close()


def test_max_connections_admits_exactly_the_limit_under_concurrency():
    section("maxConnections admits exactly the limit under a concurrent burst")
    time.sleep(0.5)
    barrier = threading.Barrier(30)
    outcomes = [None] * 30
    sockets = [None] * 30

    def connect(index: int):
        barrier.wait()
        try:
            sock = raw_socket()
        except OSError:
            outcomes[index] = "error"
            return
        sockets[index] = sock
        frame = read_frame(sock, 0.8)
        outcomes[index] = "refused" if frame and frame.get("errorCode") == "503-1" else (
            "admitted" if frame is None else f"other:{frame}")

    threads = [threading.Thread(target=connect, args=(i,)) for i in range(30)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(10)
    admitted = outcomes.count("admitted")
    refused = outcomes.count("refused")
    check(f"Exactly {MAX_CONNECTIONS} connections are admitted", admitted == MAX_CONNECTIONS,
          f"outcomes={outcomes}")
    check(f"The other {30 - MAX_CONNECTIONS} receive 503-1", refused == 30 - MAX_CONNECTIONS, f"outcomes={outcomes}")
    for opened in sockets:
        if opened is not None:
            opened.close()
    time.sleep(0.5)
    with Conn.authenticated() as fresh:
        check_status("A new connection is admitted once they closed", fresh.send({"type": "LIST_DATABASES"}), "OK")


def main():
    bu.banner("Connection limits test suite", HOST, PORT)
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-connlimits-")
    log_path = os.path.join(work_dir, "server.log")
    write_config(work_dir)
    print(f"  Working dir: {work_dir}")
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path, port=PORT)
        setup_collection()
        test_an_oversized_line_is_answered_400_22_and_closed()
        test_the_unauthenticated_line_limit_is_64kb()
        test_a_flat_array_over_the_token_budget_is_refused_and_the_connection_survives()
        test_an_unauthenticated_idle_socket_is_closed_with_408_4()
        test_a_dribbling_unauthenticated_client_is_still_cut_off()
        test_a_failed_authenticate_does_not_extend_the_deadline()
        test_an_authenticated_idle_connection_survives_the_timeout()
        test_a_listen_connection_survives_the_idle_timeout()
        test_an_idle_transaction_is_rolled_back_and_unblocks_the_writer()
        test_any_request_after_a_reap_answers_409_16_once()
        test_a_busy_transaction_is_not_reaped()
        test_a_dribbling_transaction_holder_is_reaped()
        test_close_connection_is_always_accepted_after_a_reap()
        test_max_connections_admits_exactly_the_limit_under_concurrency()
    finally:
        bu.stop_server(proc, port=PORT, wait_for_port=False)
    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
