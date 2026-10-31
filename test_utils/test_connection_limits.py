import json
import os
import select
import socket
import tempfile
import threading
import time
import weakref

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("CONNLIMIT_TEST_PORT", "9444"))
AUTH_TIMEOUT_S = 1.5
IDLE_TIMEOUT_S = 1.5
MAX_CONNECTIONS = 6
TRANSACTION_MAX_OPS = 20
TRANSACTION_MAX_AGE_S = 6.0
DB = "connlimits_db"
COLL = "items"
CJK_COLL = "cjk_items"
CJK_DOCS = 40
CJK_CHARS_PER_DOC = 20_000
CJK_UNREAD_AGGREGATES = 16
OUTBOUND_CAP_BYTES = 16 * 1024 * 1024
OVERFLOW_LOG = "exceeded clientOutboundQueueMaxBytes"
LISTEN_RETAINED_BYTES = 2 * 1024 * 1024
LISTEN_PAD_CHARS = 100_000
BUDGET_COLL = "budget_items"
BUDGET_DOCS = 60
BUDGET_DOC_CHARS = 50_000
BUDGET_NON_READERS = 3
UNSENT_RESPONSE_BYTES = 10 * 1024 * 1024
WRITE_STALL_TIMEOUT_S = 2.0
STALL_LOG = "made no progress for"
PIPELINED_FINDS = 500

bu.configure(host=HOST, port=PORT)

_unread: "weakref.WeakKeyDictionary[socket.socket, bytes]" = weakref.WeakKeyDictionary()


def write_config(work_dir: str):
    bu.write_config(work_dir, PORT, maxRequestSize="128Kb", maxEntrySize="64Kb", maxPageSize="256Kb",
                    authTimeoutMs=int(AUTH_TIMEOUT_S * 1000), transactionIdleTimeoutMs=int(IDLE_TIMEOUT_S * 1000),
                    maxConnections=MAX_CONNECTIONS, transactionMaxOps=TRANSACTION_MAX_OPS,
                    transactionMaxAgeMs=int(TRANSACTION_MAX_AGE_S * 1000),
                    listenMaxRetainedBytes=f"{LISTEN_RETAINED_BYTES}")


def raw_socket(timeout: float = 10.0) -> socket.socket:
    return socket.create_connection((HOST, PORT), timeout=timeout)


def read_frame(sock: socket.socket, timeout: float):
    deadline = time.time() + timeout
    data = _unread.pop(sock, b"")
    while b"\n" not in data:
        remaining = deadline - time.time()
        ready, _, _ = select.select([sock], [], [], remaining) if remaining > 0 else ([], [], [])
        if not ready:
            _unread[sock] = data
            return None
        try:
            chunk = sock.recv(65536)
        except OSError:
            return None
        if not chunk:
            break
        data += chunk
    line, _, rest = data.partition(b"\n")
    if rest:
        _unread[sock] = rest
    line = line.decode(errors="replace").strip()
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


def padded_listen(tag: str) -> dict:
    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "pad",
                                             "value": tag + "p" * LISTEN_PAD_CHARS}}]
    return {"type": "LISTEN", "databaseName": DB, "collectionName": COLL, "aggregationSteps": steps}


def test_listen_retained_budget_503_15():
    section("LISTEN: the node-wide retained-bytes budget answers 503-15 and is given back on disconnect")
    holder = Conn.authenticated()
    responses = []
    for i in range(LISTEN_RETAINED_BYTES // LISTEN_PAD_CHARS + 10):
        response = holder.send(padded_listen(f"r{i}-"))
        responses.append(response)
        if response.get("status") != "OK":
            break
    accepted = sum(1 for r in responses if r.get("status") == "OK")
    expected = LISTEN_RETAINED_BYTES // LISTEN_PAD_CHARS
    check(f"About {expected} registrations of ~100Kb fit in listenMaxRetainedBytes=2Mb",
          expected - 3 <= accepted <= expected, f"accepted={accepted}")
    check_code("The next LISTEN -> 503-15", responses[-1], "ERROR", "503-15")
    holder.close()
    with Conn.authenticated() as fresh:
        deadline = time.time() + 5.0
        retried = fresh.send(padded_listen("after-"))
        while retried.get("status") != "OK" and time.time() < deadline:
            time.sleep(0.1)
            retried = fresh.send(padded_listen("after-"))
        check_status("Once the holder disconnects, its bytes are given back", retried, "OK")


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


def test_a_transaction_past_its_operation_limit_is_refused():
    section("A transaction refuses the write that would take it past transactionMaxOps")
    with Conn.authenticated() as holder:
        check_status("START_TRANSACTION", holder.send({"type": "START_TRANSACTION"}), "OK")
        objects = [{"_id": f"many-{i}", "n": i} for i in range(TRANSACTION_MAX_OPS)]
        check_status(f"BULK_SAVE of {TRANSACTION_MAX_OPS} documents fits",
                     holder.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL,
                                  "objects": objects}), "OK")
        check_code("One more SAVE -> 400-26", holder.send(save("many-overflow")), "ERROR", "400-26")
        check_status("The transaction still commits what fitted", holder.send({"type": "COMMIT_TRANSACTION"}), "OK")
        check_status("A fitted document is durable", holder.send(find("many-0")), "OK")
        check_status("The refused one was never written", holder.send(find("many-overflow")), "NOT_FOUND")


def test_a_busy_transaction_is_still_reaped_at_its_maximum_age():
    section("A transaction that keeps sending requests is rolled back at transactionMaxAgeMs")
    holder = open_transaction_with_one_write("aged-1")
    try:
        answers = []
        end = time.time() + TRANSACTION_MAX_AGE_S + 4
        while time.time() < end:
            answer = holder.send(find("aged-1"))
            answers.append(answer.get("errorCode") or answer.get("status"))
            if answer.get("errorCode") == "408-5":
                break
            time.sleep(0.5)
        check("A keepalive request is answered 408-5 once the age passes", "408-5" in answers, f"answers={answers}")
        check_status("The aged transaction's document was discarded", holder.send(find("aged-1")), "NOT_FOUND")
    finally:
        holder.close()


def test_unparseable_lines_before_authentication_close_the_connection():
    section("Five unparseable lines before authenticating close the connection")
    with raw_socket() as sock:
        sock.sendall(b"garbage\n" * 6)
        answers = [read_frame(sock, 3.0) for _ in range(5)]
        check("The first five are answered", all(answer is not None for answer in answers), f"answers={answers}")
        check("Then the server closes the connection", reaches_eof(sock))


def test_well_formed_requests_before_authentication_close_the_connection():
    section("Five refused requests before authenticating close the connection, not only unparseable ones")
    with raw_socket() as sock:
        sock.sendall(b'{"type":"LIST_DATABASES"}\n' * 6)
        answers = [read_frame(sock, 3.0) for _ in range(5)]
        check("The first five are answered 401-1",
              all(answer is not None and answer.get("errorCode") == "401-1" for answer in answers),
              f"answers={answers}")
        check("Then the server closes the connection", reaches_eof(sock))


def test_an_idle_transaction_is_reaped_while_its_client_is_not_reading():
    section("An idle transaction is reaped even while its client leaves responses unread")
    with Conn.authenticated() as seeder:
        for i in range(40):
            seeder.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                         "object": {"_id": f"unread-bulky-{i}", "kind": "bulky", "blob": "x" * 50_000}})
    holder = bu.non_reading_conn()
    try:
        check_status("START_TRANSACTION", holder.send({"type": "START_TRANSACTION"}), "OK")
        check_status("SAVE held-unread inside the transaction", holder.send(save("held-unread")), "OK")
        for _ in range(6):
            bu.send_without_reading(holder, {"type": "AGGREGATE", "databaseName": DB, "collectionName": COLL,
                                             "aggregationSteps": []})
        with Conn.authenticated() as writer:
            send_only(writer, save("standalone-unread"))
            unblocked = writer.recv(timeout=IDLE_TIMEOUT_S + 10)
            check_status("The standalone SAVE completes once the unread transaction is reaped", unblocked or {},
                         "OK")
            check_status("The reaped transaction's document was discarded", writer.send(find("held-unread")),
                         "NOT_FOUND")
    finally:
        holder.close()


def test_outbound_cap_counts_utf8_bytes(log_path: str):
    section("clientOutboundQueueMaxBytes counts a CJK backlog in UTF-8 bytes, not characters")
    with Conn.authenticated() as admin:
        check_status("CREATE_COLLECTION for the CJK documents",
                     admin.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": CJK_COLL}), "OK")
        saved = [admin.send({"type": "SAVE", "databaseName": DB, "collectionName": CJK_COLL,
                             "object": {"_id": f"cjk-{i}", "text": "界" * CJK_CHARS_PER_DOC}})
                 for i in range(CJK_DOCS)]
        check("Every CJK document is saved", all(r.get("status") == "OK" for r in saved), f"first={saved[0]}")
    backlog_chars = CJK_UNREAD_AGGREGATES * CJK_DOCS * CJK_CHARS_PER_DOC
    check("Counted in characters the unread backlog would stay under the default 16Mb cap",
          backlog_chars < OUTBOUND_CAP_BYTES, f"chars={backlog_chars}")
    overflows_before = bu.read_log(log_path).count(OVERFLOW_LOG)
    reader = bu.non_reading_conn()
    try:
        for _ in range(CJK_UNREAD_AGGREGATES):
            bu.send_without_reading(reader, {"type": "AGGREGATE", "databaseName": DB, "collectionName": CJK_COLL,
                                             "aggregationSteps": []})
        deadline = time.time() + 30.0
        while bu.read_log(log_path).count(OVERFLOW_LOG) == overflows_before and time.time() < deadline:
            time.sleep(0.2)
        check("The unread backlog passes the cap counted in UTF-8 bytes (3 per character)",
              bu.read_log(log_path).count(OVERFLOW_LOG) > overflows_before, "the server never logged the overflow")
        check("The connection is then closed", bu.read_until_closed(reader, timeout=30.0),
              "the server still held the connection open")
    finally:
        reader.close()
    with Conn.authenticated() as fresh:
        found = fresh.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": CJK_COLL, "_id": "cjk-0"})
        check_status("Another connection still reads the CJK documents", found, "OK")
        check("The document comes back intact", (found.get("object") or {}).get("text") == "界" * CJK_CHARS_PER_DOC)


def test_an_unauthenticated_request_is_answered_401_1_without_validation():
    section("A request before authenticating is answered 401-1, never validated")
    with raw_socket() as sock:
        sock.sendall(b'{"type":"SET_DATABASE_OWNERS","databaseName":"anyDb","owners":["nobodyHere"]}\n')
        frame = read_frame(sock, 3.0) or {}
        check_code("SET_DATABASE_OWNERS with an unknown owner", frame, "UNAUTHENTICATED", "401-1")
        check("The answer does not reveal whether the owner exists", "does not exist" not in json.dumps(frame),
              f"got={frame}")


def write_budget_config(work_dir: str):
    bu.write_config(work_dir, PORT, maxRequestSize="128Kb", maxEntrySize="64Kb",
                    maxUnsentResponseBytes=f"{UNSENT_RESPONSE_BYTES}",
                    clientWriteStallTimeoutMs=int(WRITE_STALL_TIMEOUT_S * 1000))


def budget_aggregate() -> dict:
    return {"type": "AGGREGATE", "databaseName": DB, "collectionName": BUDGET_COLL, "aggregationSteps": []}


def setup_budget_collection():
    with Conn.authenticated() as admin:
        check_status("CREATE_DATABASE for the budget phase",
                     admin.send({"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
        check_status("CREATE_COLLECTION for the budget phase",
                     admin.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": BUDGET_COLL}),
                     "OK")
        saved = [admin.send({"type": "SAVE", "databaseName": DB, "collectionName": BUDGET_COLL,
                             "object": {"_id": f"large-{i}", "blob": chr(ord("a") + i % 26) * BUDGET_DOC_CHARS}})
                 for i in range(BUDGET_DOCS)]
        check("Every large document is saved", all(r.get("status") == "OK" for r in saved), f"first={saved[0]}")
        small = [admin.send({"type": "SAVE", "databaseName": DB, "collectionName": BUDGET_COLL,
                             "object": {"_id": f"small-{i}", "n": i}}) for i in range(10)]
        check("Every small document is saved", all(r.get("status") == "OK" for r in small), f"first={small[0]}")


def test_unread_large_reads_exhaust_the_response_budget(log_path: str):
    section("Unread responses past maxUnsentResponseBytes refuse a large read with 503-17, never a write")
    stalls_before = bu.read_log(log_path).count(STALL_LOG)
    reader = Conn.authenticated()
    non_readers = [bu.non_reading_conn() for _ in range(BUDGET_NON_READERS)]
    try:
        for conn in non_readers:
            bu.send_without_reading(conn, budget_aggregate())
        time.sleep(0.5)
        refused = reader.send(budget_aggregate())
        check_code(f"A fourth ~3 MB AGGREGATE behind {BUDGET_NON_READERS} unread ones", refused, "ERROR", "503-17")
        check("The refusal names the read it refused", refused.get("type") == "AGGREGATE", f"got={refused}")
        check_status("A small FIND_BY_ID is still answered",
                     reader.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": BUDGET_COLL,
                                  "_id": "small-1"}), "OK")
        check_status("A write is still answered",
                     reader.send({"type": "SAVE", "databaseName": DB, "collectionName": BUDGET_COLL,
                                  "object": {"_id": "small-written", "n": 1}}), "OK")
        check("The server gives up on every non-reader once its write stalls past clientWriteStallTimeoutMs",
              bu.wait_until(lambda: bu.read_log(log_path).count(STALL_LOG) - stalls_before >= BUDGET_NON_READERS,
                            WRITE_STALL_TIMEOUT_S + 15),
              f"stall lines={bu.read_log(log_path).count(STALL_LOG) - stalls_before}")
        closed = [bu.read_until_closed(conn, timeout=15) for conn in non_readers]
        check("Each non-reader then finds its connection closed", all(closed), f"closed={closed}")
        answered = reader.send(budget_aggregate())
        check_status("Once they are gone the same AGGREGATE is answered", answered, "OK")
        check("It carries every document", len(answered.get("results") or []) == BUDGET_DOCS + 11,
              f"results={len(answered.get('results') or [])}")
    finally:
        for conn in non_readers:
            conn.close()
        reader.close()


def test_pipelined_small_requests_answer_in_order():
    section("Pipelined small requests are answered in order")
    with Conn.authenticated() as conn:
        lines = [json.dumps({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": BUDGET_COLL,
                             "_id": f"small-{i % 10}"}) for i in range(PIPELINED_FINDS)]
        conn.s.sendall(("\n".join(lines) + "\n").encode())
        answers = [conn._read_response() for _ in range(PIPELINED_FINDS)]
        in_order = sum(1 for i, answer in enumerate(answers)
                       if (answer.get("object") or {}).get("_id") == f"small-{i % 10}")
        check(f"All {PIPELINED_FINDS} answers arrive, each matching its request", in_order == PIPELINED_FINDS,
              f"in order={in_order}")


def run_budget_phase():
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-connlimits-budget-")
    log_path = os.path.join(work_dir, "server.log")
    write_budget_config(work_dir)
    print(f"  Budget phase working dir: {work_dir}")
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path, port=PORT)
        setup_budget_collection()
        test_unread_large_reads_exhaust_the_response_budget(log_path)
        test_pipelined_small_requests_answer_in_order()
    finally:
        bu.stop_server(proc, port=PORT, wait_for_port=True)
    return log_path


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
        test_listen_retained_budget_503_15()
        test_an_idle_transaction_is_rolled_back_and_unblocks_the_writer()
        test_any_request_after_a_reap_answers_409_16_once()
        test_a_busy_transaction_is_not_reaped()
        test_a_dribbling_transaction_holder_is_reaped()
        test_close_connection_is_always_accepted_after_a_reap()
        test_a_transaction_past_its_operation_limit_is_refused()
        test_a_busy_transaction_is_still_reaped_at_its_maximum_age()
        test_unparseable_lines_before_authentication_close_the_connection()
        test_an_unauthenticated_request_is_answered_401_1_without_validation()
        test_well_formed_requests_before_authentication_close_the_connection()
        test_an_idle_transaction_is_reaped_while_its_client_is_not_reading()
        test_outbound_cap_counts_utf8_bytes(log_path)
        test_max_connections_admits_exactly_the_limit_under_concurrency()
    finally:
        bu.stop_server(proc, port=PORT, wait_for_port=True)
    budget_log_path = run_budget_phase()
    bu.summary(on_failure=lambda: (bu.dump_log(log_path), bu.dump_log(budget_log_path)))


if __name__ == "__main__":
    main()
