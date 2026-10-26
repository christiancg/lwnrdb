import os
import socket
import json
import sys
import tempfile
import time

import base_utils as bu
from base_utils import Conn, check, check_code, check_field, check_status, section

HOST = os.environ.get("TXN_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("TXN_TEST_PORT", "8989"))
TRIGGER_SERVER_PORT = int(os.environ.get("TXN_TRIGGER_TEST_PORT", "9448"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

DB = "txn_test_db"
COLL = "orders"
JOIN_COLL = "order_configs"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)


def send_only(c: Conn, payload: dict):
    c.s.sendall((json.dumps(payload) + "\n").encode())


def result_ids(response: dict) -> list:
    return sorted(d.get("_id") for d in (response.get("results") or []))


def authed_conn():
    conn = Conn()
    conn.authenticate()
    return conn


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


def setup_fixtures(c):
    c.send({"type": "CREATE_DATABASE", "databaseName": DB})
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL})
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": JOIN_COLL})


def teardown_fixtures(c):
    c.send({"type": "DROP_DATABASE", "databaseName": DB})


def test_commit_and_read_your_writes(c):
    section("Commit path + read-your-writes")

    r = start_txn(c)
    check_status("START_TRANSACTION returns OK", r, "OK")
    check("response carries a transactionId", isinstance(r.get("transactionId"), str) and r.get("transactionId"),
               f"transactionId={r.get('transactionId')!r}")

    check_status("SAVE inside transaction is accepted (buffered)",
          save(c, {"_id": "c1", "total": 42}), "OK")

    ryw = find_by_id(c, "c1")
    check_status("FIND_BY_ID inside txn sees the buffered document", ryw, "OK")
    check_field("read-your-writes returns the buffered value", ryw, "object.total", 42)

    check_status("COMMIT_TRANSACTION returns OK", commit_txn(c), "OK")

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

    check_status("START_TRANSACTION (seed)", start_txn(c), "OK")
    save(c, {"_id": "agg-upd", "status": "old"})
    save(c, {"_id": "agg-del", "status": "keep"})
    check_status("COMMIT seed", commit_txn(c), "OK")

    check_status("START_TRANSACTION", start_txn(c), "OK")
    save(c, {"_id": "agg-ins", "status": "new"})
    save(c, {"_id": "agg-upd", "status": "updated"})
    delete(c, "agg-del")

    agg = aggregate(c)
    ids = result_ids(agg)
    check("buffered insert appears in the transaction's own AGGREGATE", "agg-ins" in ids, f"ids={ids}")
    check("buffered delete is hidden from the transaction's own AGGREGATE", "agg-del" not in ids, f"ids={ids}")
    by_id = {d.get("_id"): d for d in (agg.get("results") or [])}
    check("buffered update is reflected", (by_id.get("agg-upd") or {}).get("status") == "updated",
               f"agg-upd={by_id.get('agg-upd')!r}")

    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")

    with authed_conn() as oc:
        agg2 = aggregate(oc)
        ids2 = result_ids(agg2)
        check("rollback restored the deleted document", "agg-del" in ids2, f"ids={ids2}")
        check("rollback discarded the inserted document", "agg-ins" not in ids2, f"ids={ids2}")
        by_id2 = {d.get("_id"): d for d in (agg2.get("results") or [])}
        check("rollback discarded the update", (by_id2.get("agg-upd") or {}).get("status") == "old",
                   f"agg-upd={by_id2.get('agg-upd')!r}")


REDUCE_COLL = "txn_reduce_order"


def test_reduce_inside_a_transaction_folds_in_scan_order(c):
    section("A REDUCE source inside a transaction folds in the same defined order as outside it")
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REDUCE_COLL})
    for doc_id in ("delta", "alpha", "charlie", "bravo", "echo", "foxtrot"):
        check_status(f"seed {doc_id}", save(c, {"_id": doc_id}, coll=REDUCE_COLL), "OK")
    fold = [{"type": "REDUCE", "script": "export default (acc, doc) => acc + '|' + doc._id;",
             "initialValue": "", "resultField": "folded"}]
    outside = ((aggregate(c, fold, coll=REDUCE_COLL).get("results") or [{}])[0]).get("folded")
    check("outside a transaction the fold walks the collection in _id order",
          outside == "|alpha|bravo|charlie|delta|echo|foxtrot", f"got {outside!r}")

    check_status("START_TRANSACTION", start_txn(c), "OK")
    save(c, {"_id": "golf"}, coll=REDUCE_COLL)
    inside = ((aggregate(c, fold, coll=REDUCE_COLL).get("results") or [{}])[0]).get("folded")
    check("inside a transaction the committed rows keep that order, then the buffered insert",
          inside == outside + "|golf", f"outside={outside!r} inside={inside!r}")
    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")


def test_reduce_after_a_filter_inside_a_transaction_folds_in_id_order(c):
    section("A REDUCE after a FILTER inside a transaction folds the overlay and the committed rows in _id order")
    fold = [{"type": "FILTER", "operator": {"fieldOperatorType": "NOT_EQUALS", "field": "_id", "value": "zzz"}},
            {"type": "REDUCE", "script": "export default (acc, doc) => acc + '|' + doc._id;",
             "initialValue": "", "resultField": "folded"}]
    check_status("START_TRANSACTION", start_txn(c), "OK")
    save(c, {"_id": "aardvark"}, coll=REDUCE_COLL)
    save(c, {"_id": "golf"}, coll=REDUCE_COLL)
    inside = ((aggregate(c, fold, coll=REDUCE_COLL).get("results") or [{}])[0]).get("folded")
    check("buffered inserts take their _id place among the committed rows",
          inside == "|aardvark|alpha|bravo|charlie|delta|echo|foxtrot|golf", f"got {inside!r}")
    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")


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


def test_several_ops_on_one_id_commit_in_buffer_order(c):
    section("Several ops on one id commit in the order they were buffered")

    check_status("seed B outside the transaction", save(c, {"_id": "ord_b", "v": 0}), "OK")
    check_status("START_TRANSACTION", start_txn(c), "OK")
    check_status("SAVE A v=1", save(c, {"_id": "ord_a", "v": 1}), "OK")
    check_status("DELETE A", delete(c, "ord_a"), "OK")
    check_status("SAVE A v=2", save(c, {"_id": "ord_a", "v": 2}), "OK")
    check_status("SAVE B v=1", save(c, {"_id": "ord_b", "v": 1}), "OK")
    check_status("DELETE B", delete(c, "ord_b"), "OK")
    check_status("BULK_SAVE C and D", bulk_save(c, [{"_id": "ord_c", "v": 1}, {"_id": "ord_d", "v": 1}]), "OK")
    check_status("SAVE C v=3", save(c, {"_id": "ord_c", "v": 3}), "OK")
    check_status("COMMIT_TRANSACTION", commit_txn(c), "OK")

    with authed_conn() as oc:
        check_field("A ends at its last save", find_by_id(oc, "ord_a"), "object.v", 2)
        check_status("B ends deleted", find_by_id(oc, "ord_b"), "NOT_FOUND")
        check_field("C ends at the save after the bulk", find_by_id(oc, "ord_c"), "object.v", 3)
        check_field("D keeps its bulk value", find_by_id(oc, "ord_d"), "object.v", 1)


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

    check_status("CREATE_COLLECTION works again after the transaction ends",
          c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": "after_txn"}),
          "OK")
    c.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": "after_txn"})


def test_table_locking_blocks_other_clients(c):
    section("An open transaction locks the table against other connections")

    check_status("START_TRANSACTION", start_txn(c), "OK")
    check_status("SAVE buffered (acquires the collection write lock)", save(c, {"_id": "lock1", "v": 1}), "OK")

    with authed_conn() as bc:
        send_only(bc, {"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": "lock1"})
        blocked = bc.recv(timeout=1.5)
        check("other client's read blocks while the transaction holds the table",
                   blocked is None, f"unexpectedly got a response: {blocked!r}")

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


def test_crossed_read_and_write_sets_do_not_hang(c: Conn):
    section("Two transactions whose read and write sets cross time out instead of deadlocking")
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


def create_user(c, username: str, password: str, level: str) -> dict:
    return c.send({"type": "CREATE_USER", "username": username, "password": password, "admin": False,
                   "globalPermissions": [], "databasePermissions": {DB: level}, "collectionPermissions": {}})


def test_a_non_admin_user_can_use_transactions(c):
    section("A user who is not an admin can open, commit and roll back a transaction")

    for name in ("txn_writer", "txn_reader"):
        c.send({"type": "DELETE_USER", "username": name})
    check_status("CREATE_USER 'txn_writer' with READ_WRITE", create_user(c, "txn_writer", "txn_writer1234",
                                                                          "READ_WRITE"), "OK")
    check_status("CREATE_USER 'txn_reader' with READ", create_user(c, "txn_reader", "txn_reader1234", "READ"),
                 "OK")

    with Conn.authenticated("txn_writer", "txn_writer1234") as writer:
        check_status("START_TRANSACTION as a non-admin", start_txn(writer), "OK")
        check_status("SAVE inside it", save(writer, {"_id": "nonadmin_commit", "v": 1}), "OK")
        check_status("COMMIT_TRANSACTION as a non-admin", commit_txn(writer), "OK")
        check_status("START_TRANSACTION again", start_txn(writer), "OK")
        check_status("SAVE inside it", save(writer, {"_id": "nonadmin_rollback", "v": 1}), "OK")
        check_status("ROLLBACK_TRANSACTION as a non-admin", rollback_txn(writer), "OK")
    check_status("the committed write is visible", find_by_id(c, "nonadmin_commit"), "OK")
    check_status("the rolled-back write is not", find_by_id(c, "nonadmin_rollback"), "NOT_FOUND")

    with Conn.authenticated("txn_reader", "txn_reader1234") as reader:
        check_status("a READ user may open a transaction", start_txn(reader), "OK")
        check_code("but a write inside it is still authorized on its own",
                   save(reader, {"_id": "nonadmin_forbidden", "v": 1}), "FORBIDDEN", "403-1")
        check_status("ROLLBACK_TRANSACTION", rollback_txn(reader), "OK")
    check_status("the refused write never landed", find_by_id(c, "nonadmin_forbidden"), "NOT_FOUND")

    for name in ("txn_writer", "txn_reader"):
        c.send({"type": "DELETE_USER", "username": name})


def test_a_commit_after_the_write_grant_is_revoked_is_refused(c):
    section("COMMIT_TRANSACTION re-checks the write grant and rolls back once it was revoked")

    c.send({"type": "DELETE_USER", "username": "txn_revoked"})
    check_status("CREATE_USER 'txn_revoked' with READ_WRITE",
                 create_user(c, "txn_revoked", "txn_revoked1234", "READ_WRITE"), "OK")
    with Conn.authenticated("txn_revoked", "txn_revoked1234") as writer:
        check_status("START_TRANSACTION", start_txn(writer), "OK")
        check_status("SAVE inside it", save(writer, {"_id": "revoked_commit", "v": 1}), "OK")
        check_status("The admin lowers the grant to READ",
                     c.send({"type": "CHANGE_PERMISSIONS", "username": "txn_revoked", "admin": False,
                             "globalPermissions": [], "databasePermissions": {DB: "READ"},
                             "collectionPermissions": {}}), "OK")
        check_code("COMMIT_TRANSACTION -> 403-1", commit_txn(writer), "FORBIDDEN", "403-1")
    check_status("The buffered write never landed", find_by_id(c, "revoked_commit"), "NOT_FOUND")
    check_status("The collection lock was released", save(c, {"_id": "after_revoke", "v": 1}), "OK")
    delete(c, "after_revoke")
    c.send({"type": "DELETE_USER", "username": "txn_revoked"})


def test_entry_size_is_checked_after_the_id_is_assigned(c):
    section("An oversized-once-identified document is refused at buffer time")

    padding = "x" * (1024 * 1024 - 30)
    check_status("START_TRANSACTION", start_txn(c), "OK")
    buffered = save(c, {"pad": padding})
    check("the buffer refuses it rather than reporting success",
          buffered.get("errorCode") == "400-2", f"got errorCode={buffered.get('errorCode')!r}")
    check_status("ROLLBACK_TRANSACTION", rollback_txn(c), "OK")


def test_auto_rollback_on_disconnect():
    section("Disconnecting with an open transaction auto-rolls-back")

    victim = authed_conn()
    check_status("START_TRANSACTION", start_txn(victim), "OK")
    check_status("SAVE buffered", save(victim, {"_id": "disc1", "v": 1}), "OK")
    victim.s.shutdown(socket.SHUT_RDWR)
    victim.f.close()
    victim.s.close()

    time.sleep(0.5)

    with authed_conn() as (c):
        check_status("the uncommitted write did not survive the disconnect", find_by_id(c, "disc1"), "NOT_FOUND")
        check_status("the collection is writable again (lock was released)",
              save(c, {"_id": "disc2", "v": 2}), "OK")
        c.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "disc2"})


def test_a_committed_transaction_survives_the_disconnect():
    section("Disconnecting after a commit leaves the committed writes in place")

    victim = authed_conn()
    check_status("START_TRANSACTION", start_txn(victim), "OK")
    check_status("SAVE buffered", save(victim, {"_id": "disc3", "v": 3}), "OK")
    check_status("COMMIT_TRANSACTION", commit_txn(victim), "OK")
    victim.s.shutdown(socket.SHUT_RDWR)
    victim.f.close()
    victim.s.close()

    time.sleep(0.5)

    with authed_conn() as (c):
        check_status("the committed write survives the disconnect", find_by_id(c, "disc3"), "OK")
        check_status("the collection is writable again (lock was released)",
                     save(c, {"_id": "disc4", "v": 4}), "OK")
        for stale in ("disc3", "disc4"):
            c.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": stale})


BULKY_DOCS = 20
UNREAD_AGGREGATES = 60


def test_a_client_that_stops_reading_mid_transaction_releases_its_locks():
    section("A transaction whose client stops reading is rolled back once its backlog overflows")

    with authed_conn() as (c):
        bulk_save(c, [{"_id": f"bulky-{i}", "kind": "bulky", "blob": "x" * 50_000} for i in range(BULKY_DOCS)])
    slow = bu.non_reading_conn()
    check_status("START_TRANSACTION", start_txn(slow), "OK")
    check_status("SAVE buffered (takes the collection write lock)", save(slow, {"_id": "unread-1", "v": 1}), "OK")
    bulky = {"type": "AGGREGATE", "databaseName": DB, "collectionName": COLL,
             "aggregationSteps": [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "kind",
                                                                   "value": "bulky"}}]}
    for _ in range(UNREAD_AGGREGATES):
        if not bu.send_without_reading(slow, bulky):
            break

    with authed_conn() as (other):
        send_only(other, {"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": "unread-1"})
        unblocked = other.recv(timeout=30.0)
        check_code("another connection gets the collection back, without the rolled-back write",
                   unblocked or {}, "NOT_FOUND", "404-2")
        check("the non-reading connection is closed", bu.read_until_closed(slow, timeout=30.0))
        slow.close()
        for i in range(BULKY_DOCS):
            delete(other, f"bulky-{i}")


def test_join_reads_your_own_writes(c):
    section("A JOIN inside a transaction reads the transaction's own writes")
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


def test_a_transaction_over_four_megabytes_commits_whole(c):
    section("A transaction whose buffered ops pass 4 MB goes out as several op records and commits whole")
    padding = "y" * 600_000
    singles = [f"big_single_{i}" for i in range(8)]
    bulk = [{"_id": f"big_bulk_{i}", "p": padding} for i in range(8)]
    check_status("start a transaction", start_txn(c), "OK")
    for doc_id in singles:
        check_status(f"buffer {doc_id}", save(c, {"_id": doc_id, "p": padding}), "OK")
    check_status("buffer one BULK_SAVE op that alone passes 4 MB", bulk_save(c, bulk), "OK")
    check_status("commit it", commit_txn(c), "OK")
    found = [doc_id for doc_id in singles + [d["_id"] for d in bulk]
             if find_by_id(c, doc_id).get("status") == "OK"]
    check("every buffered document committed", len(found) == len(singles) + len(bulk), f"found {len(found)}")
    check_status("start a second large transaction", start_txn(c), "OK")
    for i in range(8):
        check_status(f"buffer rolled_{i}", save(c, {"_id": f"rolled_{i}", "p": padding}), "OK")
    check_status("roll it back", rollback_txn(c), "OK")
    check("nothing of the rolled-back transaction landed",
          all(find_by_id(c, f"rolled_{i}").get("status") == "NOT_FOUND" for i in range(8)))
    check_status("delete the large documents in one more transaction", start_txn(c), "OK")
    for doc_id in singles + [d["_id"] for d in bulk]:
        delete(c, doc_id)
    check_status("commit the deletes", commit_txn(c), "OK")


def test_transactional_deletes_count_deleted_document_bytes():
    section("A transactional DELETE with a delete trigger counts the deleted document's bytes")
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-txn-triggers-")
    log_path = os.path.join(work_dir, "server.log")
    bu.write_config(work_dir, TRIGGER_SERVER_PORT, transactionMaxBytes="64Kb", scriptsEnabled=True,
                    triggersEnabled=True, maxMemory="512mb")
    proc = bu.start_server(work_dir, log_path, port=TRIGGER_SERVER_PORT)
    padding = "x" * 40_000
    try:
        with Conn(port=TRIGGER_SERVER_PORT) as c:
            check_status("authenticate", c.authenticate(), "OK")
            setup_fixtures(c)
            for doc_id in ("fat1", "fat2", "fat3", "fat4"):
                check_status(f"save {doc_id} outside a transaction", save(c, {"_id": doc_id, "p": padding}), "OK")

            check_status("start a transaction without a delete trigger", start_txn(c), "OK")
            check_status("delete fat1", delete(c, "fat1"), "OK")
            check_status("delete fat2: only the ids are buffered", delete(c, "fat2"), "OK")
            check_status("roll it back", rollback_txn(c), "OK")

            check_status("store the audit procedure",
                         c.send({"type": "SAVE_PROCEDURE", "databaseName": DB, "name": "on_delete",
                                 "script": "return 1;"}), "OK")
            check_status("install a delete trigger",
                         c.send({"type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": COLL,
                                 "name": "audit_deletes", "events": ["DELETED"],
                                 "procedureName": "on_delete"}), "OK")
            check_status("start a transaction with the delete trigger", start_txn(c), "OK")
            check_status("delete fat3: its document rides in the buffered payload", delete(c, "fat3"), "OK")
            check_code("delete fat4 pushes the transaction past transactionMaxBytes", delete(c, "fat4"),
                       "ERROR", "400-26")
            check_status("the transaction still commits what it buffered", commit_txn(c), "OK")
            check_status("fat3 is gone", find_by_id(c, "fat3"), "NOT_FOUND")
            check_status("fat4 was never deleted", find_by_id(c, "fat4"), "OK")
            teardown_fixtures(c)
    finally:
        bu.stop_server(proc, port=TRIGGER_SERVER_PORT)


def test_an_aggregate_inside_a_transaction_obeys_the_deadline():
    section("An aggregate over a transaction's overlay stops at aggregateTimeoutMs")
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-txn-deadline-")
    log_path = os.path.join(work_dir, "server.log")
    bu.write_config(work_dir, TRIGGER_SERVER_PORT, aggregateTimeoutMs=1, maxMemory="512mb")
    proc = bu.start_server(work_dir, log_path, port=TRIGGER_SERVER_PORT)
    try:
        with Conn(port=TRIGGER_SERVER_PORT) as c:
            check_status("authenticate", c.authenticate(), "OK")
            setup_fixtures(c)
            for chunk in range(5):
                docs = [{"_id": f"d{chunk}-{i}", "n": i} for i in range(10_000)]
                check_status(f"bulk save chunk {chunk}", bulk_save(c, docs), "OK")
            check_status("start a transaction", start_txn(c), "OK")
            check_status("buffer one save so the overlay is not empty", save(c, {"_id": "buffered", "n": -1}), "OK")
            check_code("a FILTER matching nothing is cut at the deadline",
                       aggregate(c, [{"type": "FILTER", "operator": {
                           "fieldOperatorType": "EQUALS", "field": "missing", "value": 1}}]),
                       "ERROR", "408-6")
            check_status("roll it back", rollback_txn(c), "OK")
            teardown_fixtures(c)
    finally:
        bu.stop_server(proc, port=TRIGGER_SERVER_PORT)


def main():
    bu.banner("Transactions test suite", HOST, PORT)

    with Conn() as c:
        r = c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            print("        Make sure the server is running and lwnrdb.cfg has the correct")
            print(f"        defaultAdminUsername={ADMIN_USERNAME} / defaultAdminPassword={ADMIN_PASSWORD} (base_utils.PASSWORD).\n")
            sys.exit(1)
        print("\n  Setting up fixtures...")
        setup_fixtures(c)

    with authed_conn() as (c):
        test_commit_and_read_your_writes(c)
    with authed_conn() as (c):
        test_save_reports_whether_it_inserted(c)
    with authed_conn() as (c):
        test_rollback_discards(c)
    with authed_conn() as (c):
        test_read_your_writes_aggregate(c)
    with authed_conn() as (c):
        test_reduce_inside_a_transaction_folds_in_scan_order(c)
        test_reduce_after_a_filter_inside_a_transaction_folds_in_id_order(c)
    with authed_conn() as (c):
        test_buffered_delete_reads_as_not_found(c)
    with authed_conn() as (c):
        test_custom_typed_writes_are_readable_inside_the_transaction(c)
    with authed_conn() as (c):
        test_join_reads_your_own_writes(c)
    with authed_conn() as (c):
        test_bulk_save_in_transaction(c)
    with authed_conn() as (c):
        test_several_ops_on_one_id_commit_in_buffer_order(c)
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
    with authed_conn() as (c):
        test_a_transaction_over_four_megabytes_commits_whole(c)
    with authed_conn() as (c):
        test_a_non_admin_user_can_use_transactions(c)
    with authed_conn() as (c):
        test_a_commit_after_the_write_grant_is_revoked_is_refused(c)
    test_auto_rollback_on_disconnect()
    test_a_committed_transaction_survives_the_disconnect()
    test_a_client_that_stops_reading_mid_transaction_releases_its_locks()
    test_transactional_deletes_count_deleted_document_bytes()
    test_an_aggregate_inside_a_transaction_obeys_the_deadline()

    with authed_conn() as (c):
        teardown_fixtures(c)

    bu.summary()


if __name__ == "__main__":
    main()
