import os
import select
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

# Geo (JsonGeo) fixtures — a target point, a nearby point (~140 m away), a far-away
# point (Los Angeles), and a small polygon enclosing the target/near cluster.
GEO_TARGET = "#geo(40.0,-74.0)"
GEO_NEAR = "#geo(40.001,-74.001)"
GEO_FAR = "#geo(34.05,-118.24)"
GEO_POLYGON = ["#geo(39.99,-74.02)", "#geo(39.99,-73.98)", "#geo(40.02,-73.98)", "#geo(40.02,-74.02)"]

# Vector (JsonVector) fixtures — a query vector, a very similar one, a moderately similar one, and an
# orthogonal (cosine 0) one, used for the "nearest" top-K semantic-search operator.
VEC_QUERY = "#vector(1.0,0.0,0.0)"
VEC_CLOSE = "#vector(0.9,0.1,0.0)"
VEC_MID = "#vector(0.5,0.5,0.0)"
VEC_FAR = "#vector(0.0,0.0,1.0)"

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
    """Try to read one line from the buffered file with a timeout.

    Uses select() instead of socket timeouts so the underlying SocketIO never
    enters its _timeout_occurred state, which would poison every subsequent
    read on the same BufferedReader.
    """
    ready, _, _ = select.select([f.raw._sock], [], [], timeout)
    if not ready:
        return None
    try:
        raw = f.readline().decode().strip()
        if not raw:
            return None
        return json.loads(raw)
    except OSError:
        return None


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
        self.f.close()
        self.s.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.f.close()
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


def geo_distance_steps(comparator: str, distance: float, target: str = GEO_TARGET, field: str = "location"):
    return [{"type": "FILTER", "operator": {"customOperatorName": "distance", "field": field,
                                            "value": target, "comparator": comparator, "distance": distance}}]


def geo_within_steps(polygon, field: str = "location"):
    return [{"type": "FILTER", "operator": {"customOperatorName": "within", "field": field, "polygon": polygon}}]


def vector_nearest_steps(query: str, k: int, exact: bool = False, field: str = "embedding"):
    operator = {"customOperatorName": "nearest", "field": field, "value": query, "k": k}
    if exact:
        operator["exact"] = True
    return [{"type": "FILTER", "operator": operator}]


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

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "watched"}}]
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

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "important"}}]
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

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "deletable"}}]
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


def test_push_on_geo_distance_match(writer: Conn, listener: Conn):
    section("LISTEN: push received when geo distance query matches (JsonGeo)")

    steps = geo_distance_steps("SMALLER_THAN", 5000, GEO_TARGET)
    r = listen(listener, steps)
    check("LISTEN registered for geo distance", r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")

    # Insert a document within 5 km of the target -> should push
    save_doc(writer, {"_id": "geo-near-1", "location": GEO_NEAR})

    pushed = listener.recv(timeout=5.0)
    check_true("push received after nearby insert", pushed is not None,
               "no push message within 5 s")
    if pushed is not None:
        check("geo distance push has OK status", pushed, "OK")
        check_true("geo distance push listenId matches", pushed.get("listenId") == listen_id,
                   f"expected {listen_id!r}, got {pushed.get('listenId')!r}")
        check_true("geo distance push resultHash differs from initial",
                   pushed.get("resultHash") != initial_hash,
                   f"hash unchanged: {pushed.get('resultHash')!r}")
        check_true("geo distance push results contain nearby doc",
                   any(d.get("_id") == "geo-near-1" for d in (pushed.get("results") or [])),
                   "geo-near-1 missing from pushed results")

    stop_listen(listener, listen_id)
    delete_doc(writer, "geo-near-1")


def test_no_push_on_geo_distance_non_match(writer: Conn, listener: Conn):
    section("LISTEN: no push when geo distance query does not match (JsonGeo)")

    steps = geo_distance_steps("SMALLER_THAN", 5000, GEO_TARGET)
    r = listen(listener, steps)
    check("LISTEN registered for geo distance non-match", r, "OK")
    listen_id = r.get("listenId")

    # Insert a far-away document (Los Angeles) -> outside the 5 km radius, no push
    save_doc(writer, {"_id": "geo-far-1", "location": GEO_FAR})
    time.sleep(0.5)

    pushed = listener.recv(timeout=2.0)
    check_true("no push for far-away insert", pushed is None,
               f"unexpected push: {pushed!r}")

    stop_listen(listener, listen_id)
    delete_doc(writer, "geo-far-1")


def test_push_on_geo_within_match(writer: Conn, listener: Conn):
    section("LISTEN: push on geo within-polygon match, none for outside (JsonGeo)")

    steps = geo_within_steps(GEO_POLYGON)
    r = listen(listener, steps)
    check("LISTEN registered for geo within", r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")

    # Insert a document inside the polygon -> should push
    save_doc(writer, {"_id": "geo-in-1", "location": GEO_NEAR})

    pushed = listener.recv(timeout=5.0)
    check_true("push received after in-polygon insert", pushed is not None,
               "no push message within 5 s")
    if pushed is not None:
        check("geo within push has OK status", pushed, "OK")
        check_true("geo within push resultHash differs from initial",
                   pushed.get("resultHash") != initial_hash,
                   f"hash unchanged: {pushed.get('resultHash')!r}")
        check_true("geo within push results contain in-polygon doc",
                   any(d.get("_id") == "geo-in-1" for d in (pushed.get("results") or [])),
                   "geo-in-1 missing from pushed results")

    # Insert a document outside the polygon -> result set unchanged, no push
    save_doc(writer, {"_id": "geo-out-1", "location": GEO_FAR})
    time.sleep(0.5)

    pushed_2 = listener.recv(timeout=2.0)
    check_true("no push for out-of-polygon insert", pushed_2 is None,
               f"unexpected push: {pushed_2!r}")

    stop_listen(listener, listen_id)
    delete_doc(writer, "geo-in-1")
    delete_doc(writer, "geo-out-1")


def test_aggregate_vector_nearest(conn: Conn):
    section("AGGREGATE: vector nearest returns top-K ordered by similarity (JsonVector)")

    save_doc(conn, {"_id": "vec-a", "embedding": VEC_CLOSE})
    save_doc(conn, {"_id": "vec-b", "embedding": VEC_MID})
    save_doc(conn, {"_id": "vec-c", "embedding": VEC_FAR})

    r = aggregate(conn, vector_nearest_steps(VEC_QUERY, 2))
    check("AGGREGATE nearest returns OK", r, "OK")
    ids = [d.get("_id") for d in (r.get("results") or [])]
    check_true("nearest top-2 are the two most similar, ordered", ids == ["vec-a", "vec-b"],
               f"got {ids!r}")

    r_exact = aggregate(conn, vector_nearest_steps(VEC_QUERY, 2, exact=True))
    ids_exact = [d.get("_id") for d in (r_exact.get("results") or [])]
    check_true("nearest exact top-2 match the approximate result", ids_exact == ["vec-a", "vec-b"],
               f"got {ids_exact!r}")

    delete_doc(conn, "vec-a")
    delete_doc(conn, "vec-b")
    delete_doc(conn, "vec-c")


def test_push_on_vector_nearest_closer(writer: Conn, listener: Conn):
    section("LISTEN: push when a closer vector enters the top-K (JsonVector)")

    # Seed a far vector so the initial top-1 is that document.
    save_doc(writer, {"_id": "vec-seed-far", "embedding": VEC_FAR})
    time.sleep(0.5)

    r = listen(listener, vector_nearest_steps(VEC_QUERY, 1))
    check("LISTEN registered for vector nearest", r, "OK")
    listen_id = r.get("listenId")
    initial_hash = r.get("resultHash")

    # Insert a much closer vector -> the top-1 changes -> push.
    save_doc(writer, {"_id": "vec-closer", "embedding": VEC_CLOSE})

    pushed = listener.recv(timeout=5.0)
    check_true("push received after closer vector insert", pushed is not None, "no push within 5 s")
    if pushed is not None:
        check("vector nearest push has OK status", pushed, "OK")
        check_true("vector nearest push resultHash differs from initial",
                   pushed.get("resultHash") != initial_hash, f"hash unchanged: {pushed.get('resultHash')!r}")
        check_true("vector nearest push top-1 is the closer vector",
                   [d.get("_id") for d in (pushed.get("results") or [])] == ["vec-closer"],
                   f"unexpected results: {pushed.get('results')!r}")

    stop_listen(listener, listen_id)
    delete_doc(writer, "vec-seed-far")
    delete_doc(writer, "vec-closer")


def test_no_push_on_vector_farther(writer: Conn, listener: Conn):
    section("LISTEN: no push when a farther vector stays outside the top-K (JsonVector)")

    save_doc(writer, {"_id": "vec-top", "embedding": VEC_CLOSE})
    time.sleep(0.5)

    r = listen(listener, vector_nearest_steps(VEC_QUERY, 1))
    check("LISTEN registered for vector nearest non-match", r, "OK")
    listen_id = r.get("listenId")

    # Insert a farther vector that does not displace the current top-1 -> result set unchanged, no push.
    save_doc(writer, {"_id": "vec-farther", "embedding": VEC_FAR})
    time.sleep(0.5)

    pushed = listener.recv(timeout=2.0)
    check_true("no push for farther vector", pushed is None, f"unexpected push: {pushed!r}")

    stop_listen(listener, listen_id)
    delete_doc(writer, "vec-top")
    delete_doc(writer, "vec-farther")


def test_stop_listen(writer: Conn, listener: Conn):
    section("LISTEN: STOP_LISTEN cancels subscription")

    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "stoppable"}}]
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

    steps_a = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "tag", "value": "alpha"}}]
    steps_b = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "tag", "value": "beta"}}]

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

    # Wait for the server to detect the disconnect and clean up the registration.
    time.sleep(0.5)

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
            test_aggregate_vector_nearest(admin_conn)

            with Conn() as writer_conn, Conn() as listener_conn:
                authenticate(writer_conn)
                authenticate(listener_conn)

                test_push_on_matching_insert(writer_conn, listener_conn)
                test_no_push_on_non_matching_insert(writer_conn, listener_conn)
                test_push_on_delete(writer_conn, listener_conn)
                test_push_on_geo_distance_match(writer_conn, listener_conn)
                test_no_push_on_geo_distance_non_match(writer_conn, listener_conn)
                test_push_on_geo_within_match(writer_conn, listener_conn)
                test_push_on_vector_nearest_closer(writer_conn, listener_conn)
                test_no_push_on_vector_farther(writer_conn, listener_conn)
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
