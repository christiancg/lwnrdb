import os
import socket
import json
import sys
import time

HOST = os.environ.get("LISTEN_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("LISTEN_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "listen_test_db"
COLL = "listen_coll"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

failures = 0


def send(s, f, payload: dict) -> dict:
    try:
        s.sendall((json.dumps(payload) + "\n").encode())
    except (BrokenPipeError, OSError):
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    raw = f.readline().decode().strip()
    if not raw:
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"status": "ERROR", "message": raw}


def recv_nonblocking(f, timeout: float = 2.0) -> dict | None:
    """Try to read one line from the buffered file with a timeout."""
    # BufferedReader wraps a SocketIO; the actual socket is at f.raw._sock
    underlying = f.raw._sock
    underlying.settimeout(timeout)
    try:
        raw = f.readline().decode().strip()
        if not raw:
            return None
        return json.loads(raw)
    except (TimeoutError, socket.timeout, OSError):
        return None
    finally:
        underlying.settimeout(None)


def check(label: str, response: dict, expected_status: str):
    global failures
    actual = response.get("status")
    ok = actual == expected_status
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         expected={expected_status}  got={actual}  msg={response.get('message', '')!r}")
    if not ok:
        failures += 1


def check_true(label: str, ok: bool, detail: str = ""):
    global failures
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    if detail:
        print(f"         {detail}")
    if not ok:
        failures += 1


def check_code(label: str, response: dict, expected_status: str, expected_code: str):
    global failures
    actual_status = response.get("status")
    actual_code = response.get("errorCode")
    ok = actual_status == expected_status and actual_code == expected_code
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         expected={expected_status}/{expected_code}  "
          f"got={actual_status}/{actual_code}  msg={response.get('message', '')!r}")
    if not ok:
        failures += 1


class Conn:
    def __init__(self):
        self.s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.s.connect((HOST, PORT))
        self.f = self.s.makefile("rb")

    def send(self, payload: dict) -> dict:
        return send(self.s, self.f, payload)

    def recv(self, timeout: float = 2.0) -> dict | None:
        return recv_nonblocking(self.f, timeout)

    def close(self):
        self.s.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.s.close()


def section(title: str):
    print(f"\n{'─' * 60}")
    print(f"  {title}")
    print(f"{'─' * 60}")


def authenticate(conn: Conn, username: str = ADMIN_USERNAME, password: str = ADMIN_PASSWORD) -> dict:
    return conn.send({"type": "AUTHENTICATE", "username": username, "password": password})


def save_doc(conn: Conn, doc: dict) -> dict:
    return conn.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL, "object": doc})


def delete_doc(conn: Conn, id_: str) -> dict:
    return conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": id_})


def listen(conn: Conn, steps: list) -> dict:
    return conn.send({
        "type": "LISTEN",
        "databaseName": DB,
        "collectionName": COLL,
        "aggregationSteps": steps,
    })


def stop_listen(conn: Conn, listen_id: str) -> dict:
    return conn.send({"type": "STOP_LISTEN", "listenId": listen_id})


def aggregate(conn: Conn, steps: list) -> dict:
    return conn.send({
        "type": "AGGREGATE",
        "databaseName": DB,
        "collectionName": COLL,
        "aggregationSteps": steps,
    })


# ── setup helpers ────────────────────────────────────────────────────────────

def setup(admin: Conn):
    admin.send({"type": "CREATE_DATABASE", "databaseName": DB})
    admin.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL})


def teardown(admin: Conn):
    admin.send({"type": "DROP_DATABASE", "databaseName": DB})


# ── test suites ──────────────────────────────────────────────────────────────

def test_validation(admin: Conn):
    section("Validation: LISTEN and STOP_LISTEN")

    # Missing aggregationSteps
    r = admin.send({"type": "LISTEN", "databaseName": DB, "collectionName": COLL})
    check_code("LISTEN without aggregationSteps → VALIDATION_ERROR", r, "ERROR", "400-1")

    # Missing databaseName
    r = admin.send({"type": "LISTEN", "collectionName": COLL, "aggregationSteps": []})
    check_code("LISTEN without databaseName → VALIDATION_ERROR", r, "ERROR", "400-1")

    # STOP_LISTEN without listenId
    r = admin.send({"type": "STOP_LISTEN"})
    check_code("STOP_LISTEN without listenId → VALIDATION_ERROR", r, "ERROR", "400-1")

    # STOP_LISTEN with invalid UUID
    r = admin.send({"type": "STOP_LISTEN", "listenId": "not-a-uuid"})
    check_code("STOP_LISTEN with invalid UUID → VALIDATION_ERROR", r, "ERROR", "400-1")

    # STOP_LISTEN with unknown UUID
    r = admin.send({"type": "STOP_LISTEN", "listenId": "00000000-0000-0000-0000-000000000000"})
    check_code("STOP_LISTEN with unknown UUID → NOT_FOUND", r, "NOT_FOUND", "404-7")


def test_initial_response(admin: Conn):
    section("LISTEN: initial response and result hash")

    # Seed one document
    save_doc(admin, {"_id": "init-1", "score": 10})

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "score", "value": 10}}]
    r = listen(admin, steps)
    check("LISTEN returns OK", r, "OK")

    listen_id = r.get("listenId")
    check_true("listenId is present", listen_id is not None, f"listenId={listen_id!r}")
    check_true("results is a list", isinstance(r.get("results"), list),
               f"results={r.get('results')!r}")
    check_true("resultHash is a 64-char hex string",
               isinstance(r.get("resultHash"), str) and len(r.get("resultHash", "")) == 64,
               f"resultHash={r.get('resultHash')!r}")
    check_true("initial results contain seeded document",
               any(d.get("_id") == "init-1" for d in (r.get("results") or [])),
               "missing init-1 from initial results")

    # Clean up listen registration and document
    stop_listen(admin, listen_id)
    delete_doc(admin, "init-1")


def test_no_push_on_unrelated_write(admin: Conn):
    section("LISTEN: no push when unrelated collection changes")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "no-push-kind"}}]
    r = listen(admin, steps)
    check("LISTEN registered for no-push test", r, "OK")
    listen_id = r.get("listenId")

    # Write to a different collection (admin) — not related to COLL
    admin.send({"type": "LIST_DATABASES"})

    # Give the background worker time to (not) fire
    pushed = admin.recv(timeout=1.5)
    check_true("no push received on unrelated activity", pushed is None,
               f"unexpected push: {pushed!r}")

    stop_listen(admin, listen_id)


def test_push_on_matching_insert(writer: Conn, listener: Conn):
    section("LISTEN: push received when new document matches query")

    steps = [{"type": "FILTER", "operator": {"type": "EQUALS", "field": "kind", "value": "watched"}}]
    r = listen(listener, steps)
    check("LISTEN registered", r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")

    # Insert a document matching the filter via the writer connection
    save_doc(writer, {"_id": "watched-1", "kind": "watched"})

    # Wait for the push with generous timeout (background worker + index lag)
    pushed = listener.recv(timeout=5.0)
    check_true("push received after matching insert", pushed is not None,
               "no push message within 5 s")

    if pushed is not None:
        check("push has OK status", pushed, "OK")
        check_true("push listenId matches", pushed.get("listenId") == listen_id,
                   f"expected {listen_id!r}, got {pushed.get('listenId')!r}")
        check_true("push resultHash differs from initial",
                   pushed.get("resultHash") != initial_hash,
                   f"hash unchanged: {pushed.get('resultHash')!r}")
        check_true("push results contain inserted document",
                   any(d.get("_id") == "watched-1" for d in (pushed.get("results") or [])),
                   "watched-1 missing from pushed results")

    stop_listen(listener, listen_id)
    delete_doc(writer, "watched-1")


def test_no_push_on_non_matching_insert(writer: Conn, listener: Conn):
    section("LISTEN: no push when insert does not affect query results")

    steps = [{"type": "FILTER", "operator": {"type": "EQUALS", "field": "kind", "value": "important"}}]
    r = listen(listener, steps)
    check("LISTEN registered for non-match test", r, "OK")
    listen_id = r.get("listenId")

    # Insert a document that does NOT match the filter
    save_doc(writer, {"_id": "irrelevant-1", "kind": "unimportant"})
    time.sleep(0.5)

    # No push should arrive
    pushed = listener.recv(timeout=2.0)
    check_true("no push for non-matching insert", pushed is None,
               f"unexpected push: {pushed!r}")

    stop_listen(listener, listen_id)
    delete_doc(writer, "irrelevant-1")


def test_push_on_delete(writer: Conn, listener: Conn):
    section("LISTEN: push received when matching document is deleted")

    # Start with one matching document
    save_doc(writer, {"_id": "del-target", "kind": "deletable"})
    time.sleep(0.3)

    steps = [{"type": "FILTER", "operator": {"type": "EQUALS", "field": "kind", "value": "deletable"}}]
    r = listen(listener, steps)
    check("LISTEN registered for delete test", r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")

    # Delete the matching document
    delete_doc(writer, "del-target")

    pushed = listener.recv(timeout=5.0)
    check_true("push received after delete", pushed is not None,
               "no push message within 5 s")

    if pushed is not None:
        check_true("push resultHash differs after delete",
                   pushed.get("resultHash") != initial_hash,
                   f"hash unchanged: {pushed.get('resultHash')!r}")
        check_true("push results no longer contain deleted document",
                   not any(d.get("_id") == "del-target" for d in (pushed.get("results") or [])),
                   "del-target still present in pushed results")

    stop_listen(listener, listen_id)


def test_stop_listen(writer: Conn, listener: Conn):
    section("LISTEN: STOP_LISTEN cancels subscription")

    steps = [{"type": "FILTER", "operator": {"type": "EQUALS", "field": "kind", "value": "stoppable"}}]
    r = listen(listener, steps)
    check("LISTEN registered for stop test", r, "OK")
    listen_id = r.get("listenId")

    # Stop the listener
    r_stop = stop_listen(listener, listen_id)
    check("STOP_LISTEN returns OK", r_stop, "OK")

    # Now insert a matching document — no push should arrive
    save_doc(writer, {"_id": "stoppable-1", "kind": "stoppable"})
    time.sleep(0.5)

    pushed = listener.recv(timeout=2.0)
    check_true("no push after STOP_LISTEN", pushed is None,
               f"unexpected push: {pushed!r}")

    delete_doc(writer, "stoppable-1")


def test_multiple_listeners(writer: Conn, listener: Conn):
    section("LISTEN: multiple listeners on same collection")

    steps_a = [{"type": "FILTER", "operator": {"type": "EQUALS", "field": "tag", "value": "alpha"}}]
    steps_b = [{"type": "FILTER", "operator": {"type": "EQUALS", "field": "tag", "value": "beta"}}]

    r_a = listen(listener, steps_a)
    check("LISTEN A registered", r_a, "OK")
    listen_id_a = r_a.get("listenId")

    # Use a second listener connection
    with Conn() as l2:
        auth_r = l2.send({"type": "AUTHENTICATE", "username": ADMIN_USERNAME, "password": ADMIN_PASSWORD})
        check("Listener-2 authenticated", auth_r, "OK")

        r_b = l2.send({
            "type": "LISTEN",
            "databaseName": DB,
            "collectionName": COLL,
            "aggregationSteps": steps_b,
        })
        check("LISTEN B registered", r_b, "OK")
        listen_id_b = r_b.get("listenId")

        # Insert a document matching only LISTEN A
        save_doc(writer, {"_id": "alpha-doc", "tag": "alpha"})

        pushed_a = listener.recv(timeout=5.0)
        check_true("Listener A received push for alpha doc", pushed_a is not None,
                   "no push to listener A")

        pushed_b = recv_nonblocking(l2.f, timeout=2.0)
        check_true("Listener B did NOT receive push for alpha doc", pushed_b is None,
                   f"unexpected push to B: {pushed_b!r}")

        # Clean up
        stop_listen(listener, listen_id_a)
        l2.send({"type": "STOP_LISTEN", "listenId": listen_id_b})
        delete_doc(writer, "alpha-doc")


def test_disconnect_cleanup(writer: Conn):
    section("LISTEN: listener cleanup on client disconnect")

    # Open a temporary listener connection, register, then disconnect
    with Conn() as tmp:
        authenticate(tmp)
        steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "cleanup"}}]
        r = listen(tmp, steps)
        check("Temporary LISTEN registered", r, "OK")
        listen_id = r.get("listenId")
        # tmp disconnects (socket closed by context manager __exit__)

    # Wait briefly for the server to process the disconnect
    time.sleep(0.3)

    # STOP_LISTEN for the now-cleaned-up ID should return NOT_FOUND
    # (we use the writer connection which is still alive)
    r_stop = stop_listen(writer, listen_id)
    check_code("STOP_LISTEN after disconnect → NOT_FOUND", r_stop, "NOT_FOUND", "404-7")


def test_unauthenticated_listen():
    section("LISTEN: unauthenticated client is rejected")

    with Conn() as c:
        steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "x"}}]
        r = listen(c, steps)
        check_code("LISTEN without auth → MUST_AUTHENTICATE_FIRST", r, "UNAUTHENTICATED", "401-1")


# ── main ─────────────────────────────────────────────────────────────────────

def main():
    global failures

    with Conn() as admin_conn:
        authenticate(admin_conn)
        setup(admin_conn)

        try:
            test_validation(admin_conn)
            test_initial_response(admin_conn)
            test_no_push_on_unrelated_write(admin_conn)

            with Conn() as writer_conn, Conn() as listener_conn:
                authenticate(writer_conn)
                authenticate(listener_conn)

                test_push_on_matching_insert(writer_conn, listener_conn)
                test_no_push_on_non_matching_insert(writer_conn, listener_conn)
                test_push_on_delete(writer_conn, listener_conn)
                test_stop_listen(writer_conn, listener_conn)
                test_multiple_listeners(writer_conn, listener_conn)
                test_disconnect_cleanup(writer_conn)

            test_unauthenticated_listen()

        finally:
            teardown(admin_conn)

    print(f"\n{'═' * 60}")
    if failures == 0:
        print(f"  All tests PASSED")
    else:
        print(f"  {failures} test(s) FAILED")
    print(f"{'═' * 60}\n")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
