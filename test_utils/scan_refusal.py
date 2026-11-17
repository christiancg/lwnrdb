import os
import shutil
import tempfile

import base_utils as bu
from base_utils import Conn, check, check_status, section

HOST = "127.0.0.1"
SCAN_PORT = int(os.environ.get("MEMORY_TEST_SCAN_PORT", "9453"))
SCAN_MAX_MEMORY = "2mb"
SCAN_DB = "scan_refusal_db"
SCAN_COLL = "scan_refusal_coll"
SCAN_DOCS = int(os.environ.get("MEMORY_TEST_SCAN_DOCS", "30000"))
FITTING_DOCS = 300
SCANS = 10
BULK = 1000


def send(c: Conn, request: dict) -> dict:
    request.setdefault("databaseName", SCAN_DB)
    request.setdefault("collectionName", SCAN_COLL)
    return c.send(request, timeout=300)


def load(c: Conn, documents: int) -> bool:
    check_status(f"create {SCAN_COLL} for {documents} documents", send(c, {"type": "CREATE_COLLECTION"}), "OK")
    for start in range(0, documents, BULK):
        objects = [{"_id": f"s{i:06d}", "k": i, "pad": "q" * 100} for i in range(start, min(documents, start + BULK))]
        r = send(c, {"type": "BULK_SAVE", "objects": objects})
        if r.get("status") != "OK":
            return check_status(f"BULK_SAVE into {SCAN_COLL}", r, "OK")
    return True


def unindexed_count(c: Conn):
    r = send(c, {"type": "AGGREGATE", "aggregationSteps": [bu.filter_step("k", "GREATER_THAN_EQUALS", 0),
                                                            {"type": "COUNT"}]})
    return bu.count_of(r) if r.get("status") == "OK" else r.get("status")


def memory_and_collection(c: Conn):
    stats = c.send({"type": "GET_DATABASE_STATS"}).get("stats", {})
    collection = {}
    for database in stats.get("databases", []):
        if database.get("name") == SCAN_DB:
            collection = next((coll for coll in database.get("collections", []) if coll.get("name") == SCAN_COLL),
                              {})
    return stats.get("memory", {}), collection


def test_a_scan_too_big_to_cache_is_never_admitted_and_answers_the_same(c: Conn):
    section(f"{SCANS} unindexed scans of {SCAN_DOCS} documents under maxMemory={SCAN_MAX_MEMORY}")
    check_status("create the scan database", c.send({"type": "CREATE_DATABASE", "databaseName": SCAN_DB}), "OK")
    if not load(c, SCAN_DOCS):
        return
    counts = []
    resident = []
    for _ in range(SCANS):
        counts.append(unindexed_count(c))
        memory, collection = memory_and_collection(c)
        resident.append(memory.get("userCacheBytes", 0) >= collection.get("sizeBytes", 0))
    check(f"every scan counts {SCAN_DOCS} documents", counts == [SCAN_DOCS] * SCANS, detail=str(counts))
    check("the cache never holds the collection a scan read", not any(resident), detail=str(resident))


def recreate_at_a_fitting_size(c: Conn) -> bool:
    section(f"After a drop, the same collection recreated with {FITTING_DOCS} documents is admitted by its scan")
    check_status("drop the scanned collection", send(c, {"type": "DROP_COLLECTION"}), "OK")
    return load(c, FITTING_DOCS)


def test_after_a_restart_the_scan_of_the_recreated_collection_admits(c: Conn):
    before, collection = memory_and_collection(c)
    check(f"the recreated collection counts {FITTING_DOCS} documents", unindexed_count(c) == FITTING_DOCS)
    after, _ = memory_and_collection(c)
    grown = after.get("userCacheBytes", 0) - before.get("userCacheBytes", 0)
    check(f"the scan admitted the collection (cache grew {grown} bytes for {collection.get('sizeBytes')} live bytes)",
          grown >= collection.get("sizeBytes", 1))
    check(f"the next scan still counts {FITTING_DOCS}", unindexed_count(c) == FITTING_DOCS)


def run_scan_refusal_phase():
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-scan-refusal-")
    log_path = os.path.join(work_dir, "server.log")
    bu.write_config(work_dir, SCAN_PORT, maxMemory=SCAN_MAX_MEMORY)
    print(f"\n  Starting an own server on {HOST}:{SCAN_PORT} with maxMemory={SCAN_MAX_MEMORY} ...")
    proc = bu.start_server(work_dir, log_path, port=SCAN_PORT)
    try:
        with Conn(port=SCAN_PORT) as c:
            c.authenticate(bu.USERNAME, bu.PASSWORD)
            test_a_scan_too_big_to_cache_is_never_admitted_and_answers_the_same(c)
            recreated = recreate_at_a_fitting_size(c)
        if recreated:
            bu.stop_server(proc, port=SCAN_PORT)
            proc = bu.start_server(work_dir, log_path, port=SCAN_PORT)
            with Conn(port=SCAN_PORT) as c:
                c.authenticate(bu.USERNAME, bu.PASSWORD)
                test_after_a_restart_the_scan_of_the_recreated_collection_admits(c)
    finally:
        bu.stop_server(proc, port=SCAN_PORT)
        if bu.failure_count():
            bu.dump_log(log_path)
    shutil.rmtree(work_dir, ignore_errors=True)
