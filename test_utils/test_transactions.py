from __future__ import annotations

import os
import select
import socket
import json
import sys
import time

HOST = os.environ.get("TXN_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("TXN_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "txn_test_db"
COLL = "orders"

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


def send_only(s, payload: dict):
    """Send a request without waiting for the response (used to observe blocking)."""
    s.sendall((json.dumps(payload) + "\n").encode())


def recv_nonblocking(f, timeout: float = 1.5) -> dict | None:
    """Read one response line with a timeout, or None if nothing arrives in time.

    Uses select() rather than a socket timeout so the underlying SocketIO never
    enters its _timeout_occurred state (which would poison later reads).
    """
    ready, _, _ = select.select([f.raw._sock], [], [], timeout)
    if not ready:
        return None
    raw = f.readline().decode().strip()
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"status": "ERROR", "message": raw}


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


def _dig(obj, path: str):
    """Walk a dotted/indexed path like 'results.0.count' or 'object.total'."""
    cur = obj
    for part in path.split("."):
        if cur is None:
            return None
        if part.isdigit() and isinstance(cur, list):
            idx = int(part)
            cur = cur[idx] if 0 <= idx < len(cur) else None
        elif isinstance(cur, dict):
            cur = cur.get(part)
        else:
            return None
    return cur


def check_field(label: str, response: dict, path: str, expected):
    global failures
    actual = _dig(response, path)
    ok = actual == expected
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         path={path}  expected={expected!r}  got={actual!r}")
    if not ok:
        failures += 1


def check_code(label: str, response: dict, expected_status: str, expected_code: str):
    """Assert both the status and the errorCode of an error response."""
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


def result_ids(response: dict) -> list:
    return sorted(d.get("_id") for d in (response.get("results") or []))


class Conn:
    def __init__(self):
        self.s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.s.connect((HOST, PORT))
        self.f = self.s.makefile("rb")

    def __enter__(self):
        return self.s, self.f

    def __exit__(self, *_):
        self.s.close()


def new_conn():
    return Conn()


def authed_conn():
    """Open a connection and authenticate as admin, returning the Conn."""
    conn = Conn()
    authenticate(conn.s, conn.f, ADMIN_USERNAME, ADMIN_PASSWORD)
    return conn


def section(title: str):
    print(f"\n{'─' * 60}")
    print(f"  {title}")
    print(f"{'─' * 60}")


# ── operation wrappers ───────────────────────────────────────────────────────

def authenticate(s, f, username: str, password: str) -> dict:
    return send(s, f, {"type": "AUTHENTICATE", "username": username, "password": password})


def start_txn(s, f) -> dict:
    return send(s, f, {"type": "START_TRANSACTION"})


def commit_txn(s, f) -> dict:
    return send(s, f, {"type": "COMMIT_TRANSACTION"})


def rollback_txn(s, f) -> dict:
    return send(s, f, {"type": "ROLLBACK_TRANSACTION"})


def save(s, f, obj, coll=COLL, db=DB) -> dict:
    return send(s, f, {"type": "SAVE", "databaseName": db, "collectionName": coll, "object": obj})


def bulk_save(s, f, objs, coll=COLL, db=DB) -> dict:
    return send(s, f, {"type": "BULK_SAVE", "databaseName": db, "collectionName": coll, "objects": objs})


def find_by_id(s, f, _id, coll=COLL, db=DB) -> dict:
    return send(s, f, {"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": _id})


def delete(s, f, _id, coll=COLL, db=DB) -> dict:
    return send(s, f, {"type": "DELETE", "databaseName": db, "collectionName": coll, "_id": _id})


def aggregate(s, f, steps=None, coll=COLL, db=DB) -> dict:
    return send(s, f, {"type": "AGGREGATE", "databaseName": db, "collectionName": coll,
                       "aggregationSteps": steps or []})


# ── setup / teardown ─────────────────────────────────────────────────────────

def setup_fixtures(s, f):
    send(s, f, {"type": "CREATE_DATABASE", "databaseName": DB})
    send(s, f, {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL})


def teardown_fixtures(s, f):
    send(s, f, {"type": "DROP_DATABASE", "databaseName": DB})


# ══════════════════════════════════════════════════════════════════════════
# Tests
# ══════════════════════════════════════════════════════════════════════════

def test_commit_and_read_your_writes(s, f):
    section("Commit path + read-your-writes")

    r = start_txn(s, f)
    check("START_TRANSACTION returns OK", r, "OK")
    check_true("response carries a transactionId", isinstance(r.get("transactionId"), str) and r.get("transactionId"),
               f"transactionId={r.get('transactionId')!r}")

    check("SAVE inside transaction is accepted (buffered)",
          save(s, f, {"_id": "c1", "total": 42}), "OK")

    # Read-your-writes: the transaction sees its own uncommitted write.
    ryw = find_by_id(s, f, "c1")
    check("FIND_BY_ID inside txn sees the buffered document", ryw, "OK")
    check_field("read-your-writes returns the buffered value", ryw, "object.total", 42)

    check("COMMIT_TRANSACTION returns OK", commit_txn(s, f), "OK")

    # After commit the write is durable and visible on a fresh (no-transaction) connection.
    with authed_conn() as (os_, of_):
        after = find_by_id(os_, of_, "c1")
        check("committed document is visible to other connections", after, "OK")
        check_field("committed value is correct", after, "object.total", 42)


def test_rollback_discards(s, f):
    section("Rollback discards buffered writes")

    check("START_TRANSACTION", start_txn(s, f), "OK")
    check("SAVE buffered", save(s, f, {"_id": "rb1", "total": 99}), "OK")
    check("FIND_BY_ID inside txn sees it", find_by_id(s, f, "rb1"), "OK")
    check("ROLLBACK_TRANSACTION", rollback_txn(s, f), "OK")

    with authed_conn() as (os_, of_):
        check("rolled-back document is not found afterwards", find_by_id(os_, of_, "rb1"), "NOT_FOUND")


def test_read_your_writes_aggregate(s, f):
    section("Read-your-writes for AGGREGATE (insert / update / delete overlay)")

    # Seed two committed documents.
    check("START_TRANSACTION (seed)", start_txn(s, f), "OK")
    save(s, f, {"_id": "agg-upd", "status": "old"})
    save(s, f, {"_id": "agg-del", "status": "keep"})
    check("COMMIT seed", commit_txn(s, f), "OK")

    check("START_TRANSACTION", start_txn(s, f), "OK")
    save(s, f, {"_id": "agg-ins", "status": "new"})      # buffered insert
    save(s, f, {"_id": "agg-upd", "status": "updated"})  # buffered update
    delete(s, f, "agg-del")                               # buffered delete

    agg = aggregate(s, f)
    ids = result_ids(agg)
    check_true("buffered insert appears in the transaction's own AGGREGATE", "agg-ins" in ids, f"ids={ids}")
    check_true("buffered delete is hidden from the transaction's own AGGREGATE", "agg-del" not in ids, f"ids={ids}")
    by_id = {d.get("_id"): d for d in (agg.get("results") or [])}
    check_true("buffered update is reflected", (by_id.get("agg-upd") or {}).get("status") == "updated",
               f"agg-upd={by_id.get('agg-upd')!r}")

    check("ROLLBACK_TRANSACTION", rollback_txn(s, f), "OK")

    # After rollback the committed state is intact: agg-del is back, agg-ins is gone, agg-upd is 'old'.
    with authed_conn() as (os_, of_):
        agg2 = aggregate(os_, of_)
        ids2 = result_ids(agg2)
        check_true("rollback restored the deleted document", "agg-del" in ids2, f"ids={ids2}")
        check_true("rollback discarded the inserted document", "agg-ins" not in ids2, f"ids={ids2}")
        by_id2 = {d.get("_id"): d for d in (agg2.get("results") or [])}
        check_true("rollback discarded the update", (by_id2.get("agg-upd") or {}).get("status") == "old",
                   f"agg-upd={by_id2.get('agg-upd')!r}")


def test_buffered_delete_reads_as_not_found(s, f):
    section("Buffered DELETE reads as not-found within the transaction")

    check("START_TRANSACTION (seed)", start_txn(s, f), "OK")
    save(s, f, {"_id": "del1", "v": 1})
    check("COMMIT seed", commit_txn(s, f), "OK")

    check("START_TRANSACTION", start_txn(s, f), "OK")
    check("DELETE inside txn returns OK", delete(s, f, "del1"), "OK")
    check("FIND_BY_ID after buffered delete reads as NOT_FOUND", find_by_id(s, f, "del1"), "NOT_FOUND")
    check("DELETE of a non-existent id returns NOT_FOUND", delete(s, f, "does-not-exist"), "NOT_FOUND")
    check("ROLLBACK_TRANSACTION", rollback_txn(s, f), "OK")

    with authed_conn() as (os_, of_):
        check("rolled-back delete leaves the document in place", find_by_id(os_, of_, "del1"), "OK")


def test_bulk_save_in_transaction(s, f):
    section("BULK_SAVE inside a transaction")

    check("START_TRANSACTION", start_txn(s, f), "OK")
    r = bulk_save(s, f, [{"_id": "b1"}, {"_id": "b2"}])
    check("BULK_SAVE buffered returns OK", r, "OK")
    check_true("both ids reported as inserted", sorted(r.get("inserted") or []) == ["b1", "b2"],
               f"inserted={r.get('inserted')!r}")
    check("COMMIT_TRANSACTION", commit_txn(s, f), "OK")

    with authed_conn() as (os_, of_):
        check("bulk-saved doc b1 is committed", find_by_id(os_, of_, "b1"), "OK")
        check("bulk-saved doc b2 is committed", find_by_id(os_, of_, "b2"), "OK")


def test_control_operation_errors(s, f):
    section("Transaction control errors (409-3 / 409-4)")

    check_code("COMMIT with no active transaction returns 409-4", commit_txn(s, f), "ERROR", "409-4")
    check_code("ROLLBACK with no active transaction returns 409-4", rollback_txn(s, f), "ERROR", "409-4")

    check("START_TRANSACTION", start_txn(s, f), "OK")
    check_code("START while already active returns 409-3", start_txn(s, f), "ERROR", "409-3")
    check("ROLLBACK_TRANSACTION (cleanup)", rollback_txn(s, f), "OK")


def test_ddl_forbidden_during_transaction(s, f):
    section("DDL / non-data operations are rejected during a transaction (409-6)")

    check("START_TRANSACTION", start_txn(s, f), "OK")
    check_code("CREATE_COLLECTION during txn → 409-6",
               send(s, f, {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": "nope"}),
               "ERROR", "409-6")
    check_code("CREATE_INDEX during txn → 409-6",
               send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "x"}),
               "ERROR", "409-6")
    check_code("LISTEN during txn → 409-6",
               send(s, f, {"type": "LISTEN", "databaseName": DB, "collectionName": COLL, "aggregationSteps": []}),
               "ERROR", "409-6")
    check("ROLLBACK_TRANSACTION", rollback_txn(s, f), "OK")

    # After rollback the connection is free to run DDL again.
    check("CREATE_COLLECTION works again after the transaction ends",
          send(s, f, {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": "after_txn"}),
          "OK")
    send(s, f, {"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": "after_txn"})


def test_table_locking_blocks_other_clients(s, f):
    section("An open transaction locks the table against other connections")

    # This connection (s, f) opens a transaction and writes, taking the collection's write lock.
    check("START_TRANSACTION", start_txn(s, f), "OK")
    check("SAVE buffered (acquires the collection write lock)", save(s, f, {"_id": "lock1", "v": 1}), "OK")

    with authed_conn() as (bs, bf):
        # The other connection's read of the same collection must block while the lock is held.
        send_only(bs, {"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": "lock1"})
        blocked = recv_nonblocking(bf, timeout=1.5)
        check_true("other client's read blocks while the transaction holds the table",
                   blocked is None, f"unexpectedly got a response: {blocked!r}")

        # Committing releases the lock; the pending read then completes and sees the committed doc.
        check("COMMIT_TRANSACTION releases the lock", commit_txn(s, f), "OK")
        resumed = recv_nonblocking(bf, timeout=5.0)
        check_true("the blocked read completes once the transaction commits",
                   resumed is not None and resumed.get("status") == "OK",
                   f"resumed={resumed!r}")


def test_auto_rollback_on_disconnect():
    section("Disconnecting with an open transaction auto-rolls-back")

    # Open a connection, start a transaction, buffer a write (taking the lock), then drop the socket.
    victim = authed_conn()
    check("START_TRANSACTION", start_txn(victim.s, victim.f), "OK")
    check("SAVE buffered", save(victim.s, victim.f, {"_id": "disc1", "v": 1}), "OK")
    # shutdown() (not just close()) forces the FIN so the server sees EOF immediately: makefile("rb")
    # dups the socket fd, so close() alone would leave the connection half-open until the process exits.
    victim.s.shutdown(socket.SHUT_RDWR)
    victim.f.close()
    victim.s.close()  # abrupt disconnect — no COMMIT/ROLLBACK

    # Give the server a moment to detect the closed connection and run auto-rollback.
    time.sleep(0.5)

    with authed_conn() as (s, f):
        check("the uncommitted write did not survive the disconnect", find_by_id(s, f, "disc1"), "NOT_FOUND")
        # The lock was released on disconnect, so a normal write to the same collection succeeds.
        check("the collection is writable again (lock was released)",
              save(s, f, {"_id": "disc2", "v": 2}), "OK")
        send(s, f, {"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "disc2"})


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    global failures

    print("\n" + "═" * 60)
    print("  LWNRDB — Transactions test suite")
    print("═" * 60)
    print(f"  Connecting to {HOST}:{PORT}")

    with new_conn() as (s, f):
        r = authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            print("        Make sure the server is running and lwnrdb.cfg has the correct")
            print(f"        defaultAdminUsername={ADMIN_USERNAME!r} / defaultAdminPassword set.\n")
            sys.exit(1)
        print("\n  Setting up fixtures...")
        setup_fixtures(s, f)

    # Each group runs on its own authenticated connection.
    with authed_conn() as (s, f):
        test_commit_and_read_your_writes(s, f)
    with authed_conn() as (s, f):
        test_rollback_discards(s, f)
    with authed_conn() as (s, f):
        test_read_your_writes_aggregate(s, f)
    with authed_conn() as (s, f):
        test_buffered_delete_reads_as_not_found(s, f)
    with authed_conn() as (s, f):
        test_bulk_save_in_transaction(s, f)
    with authed_conn() as (s, f):
        test_control_operation_errors(s, f)
    with authed_conn() as (s, f):
        test_ddl_forbidden_during_transaction(s, f)
    with authed_conn() as (s, f):
        test_table_locking_blocks_other_clients(s, f)
    test_auto_rollback_on_disconnect()

    with authed_conn() as (s, f):
        teardown_fixtures(s, f)

    print("\n" + "═" * 60)
    if failures == 0:
        print("  \033[92mAll checks passed.\033[0m")
    else:
        print(f"  \033[91m{failures} check(s) FAILED.\033[0m")
    print("═" * 60 + "\n")

    sys.exit(0 if failures == 0 else 1)


if __name__ == "__main__":
    main()
