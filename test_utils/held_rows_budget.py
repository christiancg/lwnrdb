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
SKEW_BUDGET = "40mb"
SKEW_COLL = "held_skewed"
SKEW_DOCS = 300
SKEW_NUMBERS = 20_000
SKEW_LIGHT_HEAD = 110
FAN_LEFT = "held_fan_left"
FAN_REMOTE = "held_fan_remote"
FAN_DOCS = 1000
FAN_PAD = 60_000
CASE_REMOTE = "held_case_remote"
CASE_VARIANTS = 800


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


def skewed_doc(i: int) -> dict:
    light = i <= SKEW_LIGHT_HEAD or i % 64 == 0
    return {"_id": f"s{i:05d}", "x": i, "pad": "p" * (2 * SKEW_NUMBERS) if light else [1] * SKEW_NUMBERS}


def load_in_batches(c: Conn, coll: str, docs: list, batch: int) -> list:
    refused = []
    for start in range(0, len(docs), batch):
        r = send(c, {"type": "BULK_SAVE", "collectionName": coll, "objects": docs[start:start + batch]})
        if r.get("status") != "OK":
            refused.append(r.get("errorCode") or r.get("message"))
    return refused


def test_skewed_rows_and_an_indexed_join_fan_in_are_refused():
    section(f"Skewed rows and an indexed JOIN fan-in are charged under aggregateMaxResultBytes={SKEW_BUDGET}")
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-held-skew-")
    log_path = os.path.join(work_dir, "server.log")
    bu.write_config(work_dir, HELD_PORT, aggregateMaxResultBytes=SKEW_BUDGET, maxMemory="1mb", syncWrites=False)
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path, port=HELD_PORT)
        with Conn(port=HELD_PORT) as c:
            c.authenticate(bu.USERNAME, bu.PASSWORD)
            check_status("create the held-rows database", c.send({"type": "CREATE_DATABASE", "databaseName": HELD_DB}),
                         "OK")
            for coll in (SKEW_COLL, FAN_LEFT, FAN_REMOTE, CASE_REMOTE):
                check_status(f"create {coll}", send(c, {"type": "CREATE_COLLECTION", "collectionName": coll}), "OK")
            refused = load_in_batches(c, SKEW_COLL, [skewed_doc(i) for i in range(1, SKEW_DOCS + 1)], 40)
            check(f"every BULK_SAVE of the {SKEW_DOCS} skewed documents succeeds", not refused, detail=str(refused[:3]))
            check_code("an unbounded SORT over heavy rows between light sampled ones is refused with 400-36",
                       send(c, {"type": "AGGREGATE", "collectionName": SKEW_COLL,
                                "aggregationSteps": [{"type": "SORT", "fieldName": "x", "ascending": True}]}),
                       "ERROR", "400-36")
            check_status("the left side loads", send(c, {"type": "BULK_SAVE", "collectionName": FAN_LEFT, "objects": [
                {"_id": "l0", "n": 0}, {"_id": "l1", "n": 1}]}), "OK")
            refused = load_in_batches(c, FAN_REMOTE, [{"_id": f"r{i:05d}", "k": 1, "pad": "q" * FAN_PAD}
                                                      for i in range(FAN_DOCS)], 100)
            check(f"every BULK_SAVE of the {FAN_DOCS} remote documents succeeds", not refused, detail=str(refused[:3]))
            check_status("index the remote key",
                         send(c, {"type": "CREATE_INDEX", "collectionName": FAN_REMOTE, "fieldName": "k"}), "OK")
            check("the background settles", bu.wait_until(lambda: background_idle(c), timeout_s=60))
            check_code("an indexed JOIN whose fan-in no emitted row reaches is refused with 400-36",
                       send(c, {"type": "AGGREGATE", "collectionName": FAN_LEFT, "aggregationSteps": [
                           {"type": "JOIN", "joinCollection": FAN_REMOTE, "localField": "n", "remoteField": "k",
                            "asField": "m"},
                           {"type": "LIMIT", "limit": 1}]}), "ERROR", "400-36")
            check_case_variants_are_not_charged(c)
            check_status("the server stays healthy afterwards", c.send({"type": "LIST_DATABASES"}), "OK")
            check("the server never ran out of heap", "OutOfMemoryError" not in bu.read_log(log_path))
            c.send({"type": "DROP_DATABASE", "databaseName": HELD_DB}, timeout=120)
    finally:
        bu.stop_server(proc, port=HELD_PORT, wait_for_port=False)
        if bu.failure_count():
            bu.dump_log(log_path)
    shutil.rmtree(work_dir, ignore_errors=True)


def check_case_variants_are_not_charged(c: Conn):
    check_status("a left row names the exact key", send(c, {"type": "SAVE", "collectionName": FAN_LEFT,
                                                            "object": {"_id": "case", "s": "g1"}}), "OK")
    docs = [{"_id": "exact", "s": "g1"}] + [{"_id": f"v{i:05d}", "s": "G1", "pad": "q" * FAN_PAD}
                                            for i in range(CASE_VARIANTS)]
    refused = load_in_batches(c, CASE_REMOTE, docs, 100)
    check(f"every BULK_SAVE of the {CASE_VARIANTS} case-variant documents succeeds", not refused,
          detail=str(refused[:3]))
    steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "_id", "value": "case"}},
             {"type": "JOIN", "joinCollection": CASE_REMOTE, "localField": "s", "remoteField": "s", "asField": "m"}]
    scanned = send(c, {"type": "AGGREGATE", "collectionName": FAN_LEFT, "aggregationSteps": steps})
    check_status("the scanned JOIN keeps only the exact key", scanned, "OK")
    check_status("index the case-variant key",
                 send(c, {"type": "CREATE_INDEX", "collectionName": CASE_REMOTE, "fieldName": "s"}), "OK")
    check("the background settles", bu.wait_until(lambda: background_idle(c), timeout_s=60))
    indexed = send(c, {"type": "AGGREGATE", "collectionName": FAN_LEFT, "aggregationSteps": steps})
    check_status("the indexed JOIN never charges the case variants the index hands back", indexed, "OK")
    check("the indexed JOIN joins what the scan joins", joined(indexed) == joined(scanned) == ["exact"],
          detail=f"{joined(indexed)} vs {joined(scanned)}")


def joined(response: dict) -> list:
    results = (response or {}).get("results") or [{}]
    return [row.get("_id") for row in results[0].get("m", [])]


def background_idle(c: Conn) -> bool:
    background = c.send({"type": "GET_DATABASE_STATS"}).get("stats", {}).get("background", {})
    return background.get("maintenance", 1) == 0 and background.get("pending", 1) == 0


if __name__ == "__main__":
    test_group_by_over_budget_is_refused_not_oom()
    test_skewed_rows_and_an_indexed_join_fan_in_are_refused()
    bu.summary()


POOL_BUDGET = "14mb"
POOL_PER_REQUEST = "16mb"
POOL_DOCS = 15_000
POOL_TRIES = 10
BOUNDED_COLL = "held_bounded"
BOUNDED_DOCS = 300
BOUNDED_PAD = 200_000


def concurrent_group_bys(port: int) -> list:
    start = threading.Barrier(2)
    answers = [None, None]

    def run(slot: int):
        with Conn(port=port) as c:
            c.authenticate(bu.USERNAME, bu.PASSWORD)
            start.wait(30)
            answers[slot] = aggregate(c, [{"type": "GROUP_BY", "fieldName": "cat"}])

    threads = [threading.Thread(target=run, args=(slot,), daemon=True) for slot in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(300)
    return [answer.get("errorCode") or answer.get("status") for answer in answers if answer is not None]


def test_concurrent_aggregations_share_one_budget():
    section(f"Concurrent pipelines share aggregateTotalHeldBytes={POOL_BUDGET}; a bounded SORT is charged too")
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-held-pool-")
    log_path = os.path.join(work_dir, "server.log")
    bu.write_config(work_dir, HELD_PORT, aggregateMaxResultBytes=POOL_PER_REQUEST,
                    aggregateTotalHeldBytes=POOL_BUDGET, syncWrites=False)
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path, xmx=HELD_XMX, port=HELD_PORT)
        with Conn(port=HELD_PORT) as c:
            c.authenticate(bu.USERNAME, bu.PASSWORD)
            check_status("create the pool database", c.send({"type": "CREATE_DATABASE", "databaseName": HELD_DB}),
                         "OK")
            check_status("create the pool collection", send(c, {"type": "CREATE_COLLECTION"}), "OK")
            refused = load_in_batches(c, HELD_COLL, [
                {"_id": f"q{i:06d}", "cat": f"c{i % HELD_GROUPS}", "pad": "p" * 200} for i in range(POOL_DOCS)], BULK)
            check(f"every BULK_SAVE of {POOL_DOCS} documents succeeds", not refused, detail=str(refused[:5]))
            check_status("one GROUP_BY alone fits both budgets",
                         aggregate(c, [{"type": "GROUP_BY", "fieldName": "cat"}]), "OK")
            tries = []
            for _ in range(POOL_TRIES):
                tries.append(concurrent_group_bys(HELD_PORT))
                if "503-20" in tries[-1] and "OK" in tries[-1]:
                    break
            check("two concurrent GROUP_BYs that each fit alone pass the shared pool, which refuses at least one"
                  " with 503-20 and never answers otherwise",
                  any("503-20" in outcomes for outcomes in tries)
                  and all(len(outcomes) == 2 and set(outcomes) <= {"OK", "503-20"} for outcomes in tries),
                  detail=str(tries))
            check_status("the refused GROUP_BY answers once it runs alone",
                         aggregate(c, [{"type": "GROUP_BY", "fieldName": "cat"}]), "OK")
            check_status("create the bounded-sort collection", send(c, {"type": "CREATE_COLLECTION",
                                                                         "collectionName": BOUNDED_COLL}), "OK")
            refused = load_in_batches(c, BOUNDED_COLL, [
                {"_id": f"b{i:04d}", "n": i, "pad": "p" * BOUNDED_PAD} for i in range(BOUNDED_DOCS)], 20)
            check(f"every BULK_SAVE of {BOUNDED_DOCS} large documents succeeds", not refused,
                  detail=str(refused[:5]))
            bounded = send(c, {"type": "AGGREGATE", "collectionName": BOUNDED_COLL, "aggregationSteps": [
                {"type": "SORT", "fieldName": "n", "ascending": True}, {"type": "LIMIT", "limit": BOUNDED_DOCS}]})
            check(f"[SORT, LIMIT {BOUNDED_DOCS}] over 200 KB documents is refused by a budget, not held",
                  bounded.get("errorCode") in ("400-36", "503-20"), detail=str(bounded.get("errorCode")))
            check_status("[SORT, LIMIT 10] over the same documents answers",
                         send(c, {"type": "AGGREGATE", "collectionName": BOUNDED_COLL, "aggregationSteps": [
                             {"type": "SORT", "fieldName": "n", "ascending": True},
                             {"type": "LIMIT", "limit": 10}]}), "OK")
            check("the server never ran out of heap", "OutOfMemoryError" not in bu.read_log(log_path))
            c.send({"type": "DROP_DATABASE", "databaseName": HELD_DB}, timeout=120)
    finally:
        bu.stop_server(proc, port=HELD_PORT, wait_for_port=False)
        if bu.failure_count():
            bu.dump_log(log_path)
    shutil.rmtree(work_dir, ignore_errors=True)
