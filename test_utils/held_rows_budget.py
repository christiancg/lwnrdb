import os
import random
import shutil
import tempfile
import threading

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

HOST = "127.0.0.1"
HELD_PORT = int(os.environ.get("MEMORY_TEST_HELD_PORT", "9457"))
HELD_XMX = "256m"
HELD_BUDGET = "8mb"
HELD_DB = "held_rows_db"
HELD_COLL = "held_rows_coll"
HELD_DOCS = int(os.environ.get("MEMORY_TEST_HELD_DOCS", "60000"))
HELD_GROUPS = 10
BULK = 5000


def send(c: Conn, request: dict) -> dict:
    request.setdefault("databaseName", HELD_DB)
    request.setdefault("collectionName", HELD_COLL)
    return c.send(request, timeout=300)


def aggregate(c: Conn, steps: list) -> dict:
    return send(c, {"type": "AGGREGATE", "aggregationSteps": steps})


def load(c: Conn) -> list:
    check_status("create the held-rows database", c.send({"type": "CREATE_DATABASE", "databaseName": HELD_DB}), "OK")
    check_status("create the held-rows collection", send(c, {"type": "CREATE_COLLECTION"}), "OK")
    refused = []
    for start in range(0, HELD_DOCS, BULK):
        r = send(c, {"type": "BULK_SAVE", "objects": [
            {"_id": f"h{i:06d}", "cat": f"c{i % HELD_GROUPS}", "n": i, "pad": "p" * 200}
            for i in range(start, min(HELD_DOCS, start + BULK))]})
        if r.get("status") != "OK":
            refused.append(r.get("errorCode") or r.get("message"))
    return refused


class PointReader(threading.Thread):

    def __init__(self):
        super().__init__(daemon=True)
        self.stop = threading.Event()
        self.reads = 0
        self.failures = []

    def run(self):
        rng = random.Random(20261102)
        with Conn(port=HELD_PORT) as c:
            c.authenticate(bu.USERNAME, bu.PASSWORD)
            while not self.stop.is_set():
                doc_id = f"h{rng.randrange(HELD_DOCS):06d}"
                r = send(c, {"type": "FIND_BY_ID", "_id": doc_id})
                self.reads += 1
                if r.get("status") != "OK":
                    self.failures.append(r.get("errorCode") or r.get("message"))
                    if len(self.failures) > 5:
                        return


def test_group_by_over_budget_is_refused_not_oom():
    section(f"GROUP_BY over {HELD_DOCS} documents with aggregateMaxResultBytes={HELD_BUDGET} under -Xmx{HELD_XMX}")
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-held-rows-")
    log_path = os.path.join(work_dir, "server.log")
    bu.write_config(work_dir, HELD_PORT, aggregateMaxResultBytes=HELD_BUDGET, syncWrites=False)
    proc = None
    reader = None
    try:
        proc = bu.start_server(work_dir, log_path, xmx=HELD_XMX, port=HELD_PORT)
        with Conn(port=HELD_PORT) as c:
            c.authenticate(bu.USERNAME, bu.PASSWORD)
            refused = load(c)
            check(f"every BULK_SAVE of {HELD_DOCS} documents succeeds", not refused, detail=str(refused[:5]))
            reader = PointReader()
            reader.start()
            for _ in range(3):
                check_code("GROUP_BY over the whole collection is refused with 400-36",
                           aggregate(c, [{"type": "GROUP_BY", "fieldName": "cat"}]), "ERROR", "400-36")
            check_code("an unbounded SORT over the whole collection is refused with 400-36",
                       aggregate(c, [{"type": "SORT", "fieldName": "n", "ascending": False},
                                     {"type": "SKIP", "skip": 1}]), "ERROR", "400-36")
            counted = aggregate(c, [{"type": "GROUP_BY", "fieldName": "cat"}, {"type": "COUNT"}])
            check_status("GROUP_BY followed by COUNT still answers", counted, "OK")
            check("it counts every group", (counted.get("results") or [{}])[0].get("count") == HELD_GROUPS,
                  detail=str(counted.get("results")))
            limited = aggregate(c, [{"type": "LIMIT", "limit": 100},
                                    {"type": "GROUP_BY", "fieldName": "cat"}])
            check("a GROUP_BY over 100 documents still answers",
                  limited.get("status") == "OK" and len(limited.get("results") or []) == HELD_GROUPS,
                  detail=f"status={limited.get('status')} code={limited.get('errorCode')}")
            reader.stop.set()
            reader.join(30)
            check(f"a concurrent FIND_BY_ID client kept working ({reader.reads} reads)",
                  reader.reads > 0 and not reader.failures, detail=str(reader.failures[:5]))
            check_status("the server stays healthy afterwards", c.send({"type": "LIST_DATABASES"}), "OK")
            check("the server never ran out of heap", "OutOfMemoryError" not in bu.read_log(log_path))
            c.send({"type": "DROP_DATABASE", "databaseName": HELD_DB}, timeout=120)
    finally:
        if reader is not None:
            reader.stop.set()
        bu.stop_server(proc, port=HELD_PORT, wait_for_port=False)
        if bu.failure_count():
            bu.dump_log(log_path)
    shutil.rmtree(work_dir, ignore_errors=True)


if __name__ == "__main__":
    test_group_by_over_budget_is_refused_not_oom()
    bu.summary()
