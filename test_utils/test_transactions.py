import os
import socket
import json
import sys
import time

import base_utils as bu
from base_utils import Conn, check, check_code, check_field, check_status, section

HOST = os.environ.get("TXN_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("TXN_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "txn_test_db"
COLL = "orders"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)



def send_only(c, payload: dict):
    """Send a request without waiting for the response (used to observe blocking)."""
    c.s.sendall((json.dumps(payload) + "\n").encode())


def result_ids(response: dict) -> list:
    return sorted(d.get("_id") for d in (response.get("results") or []))


def authed_conn():
    """Open a connection and authenticate as admin, returning the Conn."""
    conn = Conn()
    conn.authenticate()
    return conn


# ── operation wrappers ───────────────────────────────────────────────────────

def start_txn(c) -> dict:
    return c.send({"type": "START_TRANSACTION"})


def commit_txn(c) -> dict:
    return c.send({"type": "COMMIT_TRANSACTION"})


def rollback_txn(c) -> dict:
    return c.send({"type": "ROLLBACK_TRANSACTION"})


def save(c, obj, coll=COLL, db=DB) -> dict:
    return c.send({"type": "SAVE", "databaseName": db, "collectionName": coll, "object": obj})


def bulk_save(c, objs, coll=COLL, db=DB) -> dict:
    return c.send({"type": "BULK_SAVE", "databaseName": db, "collectionName": coll, "objects": objs})


def find_by_id(c, _id, coll=COLL, db=DB) -> dict:
    return c.send({"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": _id})


def delete(c, _id, coll=COLL, db=DB) -> dict:
    return c.send({"type": "DELETE", "databaseName": db, "collectionName": coll, "_id": _id})


def aggregate(c, steps=None, coll=COLL, db=DB) -> dict:
    return c.send({"type": "AGGREGATE", "databaseName": db, "collectionName": coll,
                       "aggregationSteps": steps or []})


# ── setup / teardown ─────────────────────────────────────────────────────────

def setup_fixtures(c):
    c.send({"type": "CREATE_DATABASE", "databaseName": DB})
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL})


def teardown_fixtures(c):
    c.send({"type": "DROP_DATABASE", "databaseName": DB})


# ══════════════════════════════════════════════════════════════════════════
# Tests
# ══════════════════════════════════════════════════════════════════════════

def test_commit_and_read_your_writes(c):
    section("Commit path + read-your-writes")

    r = start_txn(c)
    check_status("START_TRANSACTION returns OK", r, "OK")
    check("response carries a transactionId", isinstance(r.get("transactionId"), str) and r.get("transactionId"),
               f"transactionId={r.get('transactionId')!r}")

    check_status("SAVE inside transaction is accepted (buffered)",
          save(c, {"_id": "c1", "total": 42}), "OK")

    # Read-your-writes: the transaction sees its own uncommitted write.
    ryw = find_by_id(c, "c1")
    check_status("FIND_BY_ID inside txn sees the buffered document", ryw, "OK")
    check_field("read-your-writes returns the buffered value", ryw, "object.total", 42)

    check_status("COMMIT_TRANSACTION returns OK", commit_txn(c), "OK")

    # After commit the write is durable and visible on a fresh (no-transaction) connection.
    with authed_conn() as oc:
        after = find_by_id(oc, "c1")
        check_status("committed document is visible to other connections", after, "OK")
        check_field("committed value is correct", after, "object.total", 42)


def test_rollback_discards(c):
    section("Rollback discards buffered writes")

    check_status("START_TRANSACTION", start_txn(c), "OK")
    check_status("SAVE buffered", save(c, {"_id": "rb1", "total": 99}), "OK")
    check_status("FIND_BY_ID inside txn sees it", find_by_id(c, "rb1"), "OK")
    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")

    with authed_conn() as oc:
        check_status("rolled-back document is not found afterwards", find_by_id(oc, "rb1"), "NOT_FOUND")


def test_read_your_writes_aggregate(c):
    section("Read-your-writes for AGGREGATE (insert / update / delete overlay)")

    # Seed two committed documents.
    check_status("START_TRANSACTION (seed)", start_txn(c), "OK")
    save(c, {"_id": "agg-upd", "status": "old"})
    save(c, {"_id": "agg-del", "status": "keep"})
    check_status("COMMIT seed", commit_txn(c), "OK")

    check_status("START_TRANSACTION", start_txn(c), "OK")
    save(c, {"_id": "agg-ins", "status": "new"})      # buffered insert
    save(c, {"_id": "agg-upd", "status": "updated"})  # buffered update
    delete(c, "agg-del")                               # buffered delete

    agg = aggregate(c)
    ids = result_ids(agg)
    check("buffered insert appears in the transaction's own AGGREGATE", "agg-ins" in ids, f"ids={ids}")
    check("buffered delete is hidden from the transaction's own AGGREGATE", "agg-del" not in ids, f"ids={ids}")
    by_id = {d.get("_id"): d for d in (agg.get("results") or [])}
    check("buffered update is reflected", (by_id.get("agg-upd") or {}).get("status") == "updated",
               f"agg-upd={by_id.get('agg-upd')!r}")

    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")

    # After rollback the committed state is intact: agg-del is back, agg-ins is gone, agg-upd is 'old'.
    with authed_conn() as oc:
        agg2 = aggregate(oc)
        ids2 = result_ids(agg2)
        check("rollback restored the deleted document", "agg-del" in ids2, f"ids={ids2}")
        check("rollback discarded the inserted document", "agg-ins" not in ids2, f"ids={ids2}")
        by_id2 = {d.get("_id"): d for d in (agg2.get("results") or [])}
        check("rollback discarded the update", (by_id2.get("agg-upd") or {}).get("status") == "old",
                   f"agg-upd={by_id2.get('agg-upd')!r}")


def test_buffered_delete_reads_as_not_found(c):
    section("Buffered DELETE reads as not-found within the transaction")

    check_status("START_TRANSACTION (seed)", start_txn(c), "OK")
    save(c, {"_id": "del1", "v": 1})
    check_status("COMMIT seed", commit_txn(c), "OK")

    check_status("START_TRANSACTION", start_txn(c), "OK")
    check_status("DELETE inside txn returns OK", delete(c, "del1"), "OK")
    check_status("FIND_BY_ID after buffered delete reads as NOT_FOUND", find_by_id(c, "del1"), "NOT_FOUND")
    check_status("DELETE of a non-existent id returns NOT_FOUND", delete(c, "does-not-exist"), "NOT_FOUND")
    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")

    with authed_conn() as oc:
        check_status("rolled-back delete leaves the document in place", find_by_id(oc, "del1"), "OK")


def test_bulk_save_in_transaction(c):
    section("BULK_SAVE inside a transaction")

    check_status("START_TRANSACTION", start_txn(c), "OK")
    r = bulk_save(c, [{"_id": "b1"}, {"_id": "b2"}])
    check_status("BULK_SAVE buffered returns OK", r, "OK")
    check("both ids reported as inserted", sorted(r.get("inserted") or []) == ["b1", "b2"],
               f"inserted={r.get('inserted')!r}")
    check_status("COMMIT_TRANSACTION", commit_txn(c), "OK")

    with authed_conn() as oc:
        check_status("bulk-saved doc b1 is committed", find_by_id(oc, "b1"), "OK")
        check_status("bulk-saved doc b2 is committed", find_by_id(oc, "b2"), "OK")


def test_control_operation_errors(c):
    section("Transaction control errors (409-3 / 409-4)")

    check_code("COMMIT with no active transaction returns 409-4", commit_txn(c), "ERROR", "409-4")
    check_code("ROLLBACK with no active transaction returns 409-4", rollback_txn(c), "ERROR", "409-4")

    check_status("START_TRANSACTION", start_txn(c), "OK")
    check_code("START while already active returns 409-3", start_txn(c), "ERROR", "409-3")
    check_status("ROLLBACK_TRANSACTION (cleanup)", rollback_txn(c), "OK")


def test_ddl_forbidden_during_transaction(c):
    section("DDL / non-data operations are rejected during a transaction (409-6)")

    check_status("START_TRANSACTION", start_txn(c), "OK")
    check_code("CREATE_COLLECTION during txn → 409-6",
               c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": "nope"}),
               "ERROR", "409-6")
    check_code("CREATE_INDEX during txn → 409-6",
               c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "x"}),
               "ERROR", "409-6")
    check_code("LISTEN during txn → 409-6",
               c.send({"type": "LISTEN", "databaseName": DB, "collectionName": COLL, "aggregationSteps": []}),
               "ERROR", "409-6")
    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")

    # After rollback the connection is free to run DDL again.
    check_status("CREATE_COLLECTION works again after the transaction ends",
          c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": "after_txn"}),
          "OK")
    c.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": "after_txn"})


def test_table_locking_blocks_other_clients(c):
    section("An open transaction locks the table against other connections")

    # This connection (c) opens a transaction and writes, taking the collection's write lock.
    check_status("START_TRANSACTION", start_txn(c), "OK")
    check_status("SAVE buffered (acquires the collection write lock)", save(c, {"_id": "lock1", "v": 1}), "OK")

    with authed_conn() as bc:
        # The other connection's read of the same collection must block while the lock is held.
        send_only(bc, {"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": "lock1"})
        blocked = bc.recv(timeout=1.5)
        check("other client's read blocks while the transaction holds the table",
                   blocked is None, f"unexpectedly got a response: {blocked!r}")

        # Committing releases the lock; the pending read then completes and sees the committed doc.
        check_status("COMMIT_TRANSACTION releases the lock", commit_txn(c), "OK")
        resumed = bc.recv(timeout=5.0)
        check("the blocked read completes once the transaction commits",
                   resumed is not None and resumed.get("status") == "OK",
                   f"resumed={resumed!r}")


def test_auto_rollback_on_disconnect():
    section("Disconnecting with an open transaction auto-rolls-back")

    # Open a connection, start a transaction, buffer a write (taking the lock), then drop the socket.
    victim = authed_conn()
    check_status("START_TRANSACTION", start_txn(victim), "OK")
    check_status("SAVE buffered", save(victim, {"_id": "disc1", "v": 1}), "OK")
    # shutdown() (not just close()) forces the FIN so the server sees EOF immediately: makefile("rb")
    # dups the socket fd, so close() alone would leave the connection half-open until the process exits.
    victim.s.shutdown(socket.SHUT_RDWR)
    victim.f.close()
    victim.s.close()  # abrupt disconnect — no COMMIT/ROLLBACK

    # Give the server a moment to detect the closed connection and run auto-rollback.
    time.sleep(0.5)

    with authed_conn() as (c):
        check_status("the uncommitted write did not survive the disconnect", find_by_id(c, "disc1"), "NOT_FOUND")
        # The lock was released on disconnect, so a normal write to the same collection succeeds.
        check_status("the collection is writable again (lock was released)",
              save(c, {"_id": "disc2", "v": 2}), "OK")
        c.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "disc2"})


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    bu.banner("Transactions test suite", HOST, PORT)

    with Conn() as c:
        r = c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            print("        Make sure the server is running and lwnrdb.cfg has the correct")
            print(f"        defaultAdminUsername={ADMIN_USERNAME!r} / defaultAdminPassword set.\n")
            sys.exit(1)
        print("\n  Setting up fixtures...")
        setup_fixtures(c)

    # Each group runs on its own authenticated connection.
    with authed_conn() as (c):
        test_commit_and_read_your_writes(c)
    with authed_conn() as (c):
        test_rollback_discards(c)
    with authed_conn() as (c):
        test_read_your_writes_aggregate(c)
    with authed_conn() as (c):
        test_buffered_delete_reads_as_not_found(c)
    with authed_conn() as (c):
        test_bulk_save_in_transaction(c)
    with authed_conn() as (c):
        test_control_operation_errors(c)
    with authed_conn() as (c):
        test_ddl_forbidden_during_transaction(c)
    with authed_conn() as (c):
        test_table_locking_blocks_other_clients(c)
    test_auto_rollback_on_disconnect()

    with authed_conn() as (c):
        teardown_fixtures(c)

    bu.summary()


if __name__ == "__main__":
    main()
