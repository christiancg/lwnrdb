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
JOIN_COLL = "order_configs"

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
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": JOIN_COLL})


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


def test_save_reports_whether_it_inserted(c):
    section("The inserted flag is the same inside and outside a transaction")
    coll = "inserted_flag"
    check_status("create the collection",
                 c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")

    outside = save(c, {"_id": "ins1", "total": 1}, coll=coll)
    check("a non-transactional SAVE of a new id reports inserted", outside.get("inserted") is True,
          f"got inserted={outside.get('inserted')!r}")
    check("a non-transactional SAVE of an existing id reports not inserted",
          save(c, {"_id": "ins1", "total": 2}, coll=coll).get("inserted") is False)

    check_status("START_TRANSACTION", start_txn(c), "OK")
    buffered_insert = save(c, {"_id": "ins2", "total": 1}, coll=coll)
    check("a buffered SAVE of a new id reports inserted", buffered_insert.get("inserted") is True,
          f"got inserted={buffered_insert.get('inserted')!r}")
    check("a buffered SAVE of an existing id reports not inserted",
          save(c, {"_id": "ins1", "total": 3}, coll=coll).get("inserted") is False)
    check("a second buffered SAVE of the same new id reports not inserted",
          save(c, {"_id": "ins2", "total": 2}, coll=coll).get("inserted") is False)
    check_status("COMMIT_TRANSACTION", commit_txn(c), "OK")

    check_status("START_TRANSACTION again", start_txn(c), "OK")
    check_status("buffered DELETE", delete(c, "ins2", coll=coll), "OK")
    check("a SAVE after a buffered DELETE reports inserted again",
          save(c, {"_id": "ins2", "total": 9}, coll=coll).get("inserted") is True)
    check_status("COMMIT_TRANSACTION again", commit_txn(c), "OK")


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


def test_lock_timeout_aborts_and_refuses_retries(c):
    section("A lock timeout aborts the transaction instead of silently ending it")

    check_status("holder: START_TRANSACTION", start_txn(c), "OK")
    check_status("holder: SAVE takes the collection write lock", save(c, {"_id": "to-holder", "v": 1}), "OK")

    with authed_conn() as bc:
        check_status("waiter: START_TRANSACTION", start_txn(bc), "OK")
        timed_out = save(bc, {"_id": "to-waiter", "v": 1})
        check("waiter: the contended write times out with 409-5",
              timed_out.get("errorCode") == "409-5", f"got {timed_out}")

        retried = save(bc, {"_id": "to-waiter", "v": 2})
        check("waiter: retrying the statement is refused rather than silently committed standalone",
              retried.get("errorCode") == "409-9", f"got {retried}")

        committed = commit_txn(bc)
        check("waiter: committing an aborted transaction fails",
              committed.get("errorCode") == "409-9", f"got {committed}")

    check_status("holder: COMMIT_TRANSACTION", commit_txn(c), "OK")

    with authed_conn() as reader:
        orphan = reader.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": "to-waiter"})
        check("the refused write never landed as a standalone document",
              orphan.get("status") == "NOT_FOUND", f"got {orphan}")


def test_crossed_read_and_write_sets_do_not_hang(c):
    section("Two transactions whose read and write sets cross time out instead of deadlocking")
    # A transaction holds its write locks until commit, and a read taken inside one used to park with
    # no timeout: T1 holding write(A) and reading B, against T2 holding write(B) and reading A, parked
    # on each other forever - and a write lock is thread-owned, so nothing could ever recover them.
    # Both transactions are aborted by the timeout, which tears their connections down, so this runs
    # on two of its own rather than on the suite's shared connection.
    other = "orders_crossed"
    check_status("create the second collection",
                 c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": other}), "OK")
    check_status("seed the second collection", c.send(
        {"type": "SAVE", "databaseName": DB, "collectionName": other, "object": {"_id": "seed", "v": 0}}), "OK")

    with authed_conn() as t1, authed_conn() as t2:
        check_status("T1: START_TRANSACTION", start_txn(t1), "OK")
        check_status("T1: SAVE takes write(orders)", save(t1, {"_id": "crossed-1", "v": 1}), "OK")
        check_status("T2: START_TRANSACTION", start_txn(t2), "OK")
        check_status("T2: SAVE takes write(orders_crossed)",
                     save(t2, {"_id": "crossed-2", "v": 1}, coll=other), "OK")

        # Each now reads the collection the other holds. The assertion is that neither request hangs:
        # a timeout answer is a correct outcome, a socket that never comes back is not.
        started = time.time()
        t1_reads_other = aggregate(t1, [], coll=other)
        t2_reads_orders = aggregate(t2, [])
        elapsed = time.time() - started

        check("both crossed reads answered rather than parking forever", elapsed < 60.0,
              f"took {elapsed:.1f}s")
        for label, response in (("T1 reading the collection T2 holds", t1_reads_other),
                                ("T2 reading the collection T1 holds", t2_reads_orders)):
            check(f"{label} is refused with 409-5 rather than hanging",
                  isinstance(response, dict) and response.get("errorCode") == "409-5", f"got {response}")

    with authed_conn() as after:
        check_status("the collections are usable once both transactions are gone",
                     after.send({"type": "SAVE", "databaseName": DB, "collectionName": other,
                                 "object": {"_id": "after", "v": 1}}), "OK")
        check_status("and so is the first one",
                     after.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                                 "object": {"_id": "after", "v": 1}}), "OK")


def test_entry_size_is_checked_after_the_id_is_assigned(c):
    section("An oversized-once-identified document is refused at buffer time")

    # 1Mb is the shipped maxEntrySize. A document that fits only until the generated _id is
    # injected used to pass the buffer check and then be dropped at commit, which reported OK.
    padding = "x" * (1024 * 1024 - 30)
    check_status("START_TRANSACTION", start_txn(c), "OK")
    buffered = save(c, {"pad": padding})
    check("the buffer refuses it rather than reporting success",
          buffered.get("errorCode") == "400-2", f"got errorCode={buffered.get('errorCode')!r}")
    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")


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


def test_join_reads_your_own_writes(c):
    section("A JOIN inside a transaction reads the transaction's own writes")
    # The joined collection is read through the same overlay as the source: a document the
    # transaction wrote there must join, and one it deleted there must not.
    save(c, {"_id": "jrw_left", "k": "shared"})
    save(c, {"_id": "jrw_committed", "k": "shared"}, coll=JOIN_COLL)

    join_steps = [{"type": "JOIN", "joinCollection": JOIN_COLL, "localField": "k",
                   "remoteField": "k", "asField": "cfg"}]

    check_status("START_TRANSACTION", start_txn(c), "OK")
    save(c, {"_id": "jrw_buffered", "k": "shared"}, coll=JOIN_COLL)
    r = aggregate(c, join_steps)
    rows = r.get("results") or []
    attached = sorted(d.get("_id") for d in (rows[0].get("cfg") or [])) if rows else []
    check("a document written to the joined collection in this transaction joins",
          attached == ["jrw_buffered", "jrw_committed"],
          f"expected=['jrw_buffered', 'jrw_committed']  got={attached!r}")
    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")

    check_status("START_TRANSACTION", start_txn(c), "OK")
    check_status("DELETE the committed config inside the transaction",
                 delete(c, "jrw_committed", coll=JOIN_COLL), "OK")
    r = aggregate(c, join_steps)
    rows = r.get("results") or []
    attached = sorted(d.get("_id") for d in (rows[0].get("cfg") or [])) if rows else []
    check("a document deleted from the joined collection in this transaction no longer joins",
          attached == [], f"expected=[]  got={attached!r}")
    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")

    # After the rollback the joined collection is back to its committed state.
    r = aggregate(c, join_steps)
    rows = r.get("results") or []
    attached = sorted(d.get("_id") for d in (rows[0].get("cfg") or [])) if rows else []
    check("the rollback restores the joined collection's committed state",
          attached == ["jrw_committed"], f"expected=['jrw_committed']  got={attached!r}")


def test_custom_typed_writes_are_readable_inside_the_transaction(c):
    section("Read-your-writes keeps custom types (#datetime / #geo) typed in the overlay")

    when = "#datetime(2024-01-01T10:00:00)"
    where = "#geo(40.0,-74.0)"

    check_status("START_TRANSACTION", start_txn(c), "OK")
    save(c, {"_id": "custom-1", "when": when, "where": where})

    found = find_by_id(c, "custom-1")
    check_status("FIND_BY_ID sees the buffered custom-typed document", found, "OK")
    obj = found.get("object") or {}
    check("the buffered #datetime value round-trips", obj.get("when") == when, f"got {obj.get('when')!r}")
    check("the buffered #geo value round-trips", obj.get("where") == where, f"got {obj.get('where')!r}")

    equality = aggregate(c, [{"type": "FILTER", "operator": {
        "fieldOperatorType": "EQUALS", "field": "when", "value": when}}])
    check("a #datetime EQUALS filter inside the transaction sees its own write",
          result_ids(equality) == ["custom-1"], f"ids={result_ids(equality)}")

    geo = aggregate(c, [{"type": "FILTER", "operator": {
        "customOperatorName": "distance", "field": "where", "value": where,
        "comparator": "SMALLER_THAN", "distance": 1000}}])
    check("a geo distance filter inside the transaction sees its own write",
          result_ids(geo) == ["custom-1"], f"ids={result_ids(geo)}")

    check_status("COMMIT_TRANSACTION", commit_txn(c), "OK")

    with authed_conn() as oc:
        committed = aggregate(oc, [{"type": "FILTER", "operator": {
            "fieldOperatorType": "EQUALS", "field": "when", "value": when}}])
        check("the committed custom-typed document is still matched by the same filter",
              result_ids(committed) == ["custom-1"], f"ids={result_ids(committed)}")
        delete(oc, "custom-1")


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
        test_save_reports_whether_it_inserted(c)
    with authed_conn() as (c):
        test_rollback_discards(c)
    with authed_conn() as (c):
        test_read_your_writes_aggregate(c)
    with authed_conn() as (c):
        test_buffered_delete_reads_as_not_found(c)
    with authed_conn() as (c):
        test_custom_typed_writes_are_readable_inside_the_transaction(c)
    with authed_conn() as (c):
        test_join_reads_your_own_writes(c)
    with authed_conn() as (c):
        test_bulk_save_in_transaction(c)
    with authed_conn() as (c):
        test_control_operation_errors(c)
    with authed_conn() as (c):
        test_ddl_forbidden_during_transaction(c)
    with authed_conn() as (c):
        test_table_locking_blocks_other_clients(c)
    with authed_conn() as (c):
        test_lock_timeout_aborts_and_refuses_retries(c)
    with authed_conn() as (c):
        test_crossed_read_and_write_sets_do_not_hang(c)
    with authed_conn() as (c):
        test_entry_size_is_checked_after_the_id_is_assigned(c)
    test_auto_rollback_on_disconnect()

    with authed_conn() as (c):
        teardown_fixtures(c)

    bu.summary()


if __name__ == "__main__":
    main()
