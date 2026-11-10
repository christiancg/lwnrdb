import time

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

DRIP_CHUNK_BYTES = 16 * 1024
TRANSACTION_POOL = "200kb"
TRANSACTION_DOC_CHARS = 60_000
TRANSACTION_DOCS_PER_HOLDER = 3


def test_a_drip_reader_is_closed_and_frees_the_response_budget(log_path: str, aggregate: dict, stall_timeout_s: float,
                                                               stall_log: str):
    section("A client reading 16 KB per half stall timeout is closed like a non-reader, freeing its unsent bytes")
    stalls_before = bu.read_log(log_path).count(stall_log)
    drip = bu.non_reading_conn()
    try:
        check("The large AGGREGATE is sent", bu.send_without_reading(drip, aggregate))
        deadline = time.time() + 2 * stall_timeout_s + 15
        while time.time() < deadline and bu.read_log(log_path).count(stall_log) == stalls_before:
            time.sleep(stall_timeout_s / 2)
            drip.s.recv(DRIP_CHUNK_BYTES)
        check("The server closes the drip reader for moving too few bytes",
              bu.read_log(log_path).count(stall_log) > stalls_before, "no stall line within twice the stall timeout")
        check("Its connection then ends", bu.read_until_closed(drip, timeout=30.0), "the connection stayed open")
    finally:
        drip.close()
    with Conn.authenticated() as reader:
        check_status("A large read is answered once the drip reader is gone", reader.send(aggregate, timeout=60),
                     "OK")


def transactional_save(conn: Conn, db: str, coll: str, doc_id: str) -> dict:
    return conn.send({"type": "SAVE", "databaseName": db, "collectionName": coll,
                      "object": {"_id": doc_id, "blob": "t" * TRANSACTION_DOC_CHARS}})


def holder_with_buffered_documents(db: str, coll: str, prefix: str) -> Conn:
    holder = Conn.authenticated()
    check_status(f"START_TRANSACTION for {prefix}", holder.send({"type": "START_TRANSACTION"}), "OK")
    for i in range(TRANSACTION_DOCS_PER_HOLDER):
        check_status(f"{prefix} buffers document {i}", transactional_save(holder, db, coll, f"{prefix}-{i}"), "OK")
    return holder


def test_buffered_transactions_share_one_budget(db: str, coll: str):
    section(f"Open transactions share transactionTotalMaxBytes={TRANSACTION_POOL}")
    other = f"{coll}_second"
    with Conn.authenticated() as admin:
        check_status("CREATE_COLLECTION for the second holder",
                     admin.send({"type": "CREATE_COLLECTION", "databaseName": db, "collectionName": other}), "OK")
    first = holder_with_buffered_documents(db, coll, "pool-first")
    second = Conn.authenticated()
    try:
        check_status("START_TRANSACTION for the second holder", second.send({"type": "START_TRANSACTION"}), "OK")
        check_code("Its SAVE past the shared budget -> 503-21", transactional_save(second, db, other, "pool-second"),
                   "ERROR", "503-21")
        check_status("The first holder commits what it buffered", first.send({"type": "COMMIT_TRANSACTION"}), "OK")
        check_status("The second holder's retry buffers once the first ended",
                     transactional_save(second, db, other, "pool-second"), "OK")
        check_status("The second holder commits", second.send({"type": "COMMIT_TRANSACTION"}), "OK")
        check_status("The retried document is durable", second.send(
            {"type": "FIND_BY_ID", "databaseName": db, "collectionName": other, "_id": "pool-second"}), "OK")
    finally:
        first.close()
        second.close()
    departing = holder_with_buffered_documents(db, coll, "pool-departing")
    departing.close()
    time.sleep(1.0)
    after = holder_with_buffered_documents(db, coll, "pool-after")
    try:
        check_status("A disconnect gave its bytes back, so a new holder buffers as much",
                     after.send({"type": "ROLLBACK_TRANSACTION"}), "OK")
    finally:
        after.close()
