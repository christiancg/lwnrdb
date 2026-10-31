import os
import random
import shutil
import tempfile

import base_utils as bu
from base_utils import Conn, check, check_status, section

HOST = "127.0.0.1"
REFUSED_PORT = int(os.environ.get("MEMORY_TEST_REFUSED_PORT", "9451"))
REFUSED_MAX_MEMORY = "1mb"
REFUSED_DB = "refused_db"
REFUSED_COLL = "refused_coll"
REFUSED_DOCS = int(os.environ.get("MEMORY_TEST_REFUSED_DOCS", "40000"))
BULK = 1000
CONVERGE_S = 90.0
PK_REFUSED_WARNING = f"The primary-key index of {REFUSED_DB}|{REFUSED_COLL} needs about"
FIELD_REFUSED_WARNING = f"of {REFUSED_DB}|{REFUSED_COLL} needs about"


def doc_id(i: int) -> str:
    return f"r{i:06d}"


def document(i: int, n, s) -> dict:
    return {"_id": doc_id(i), "n": n, "s": s, "u": i, "pad": "p" * 48}


def send(c: Conn, request: dict) -> dict:
    request.setdefault("databaseName", REFUSED_DB)
    request.setdefault("collectionName", REFUSED_COLL)
    return c.send(request, timeout=300)


def rows(c: Conn, steps: list) -> list:
    r = send(c, {"type": "AGGREGATE", "aggregationSteps": steps})
    return sorted(row.get("_id", str(row)) for row in (r.get("results") or []))


def probes() -> list:
    return [[bu.filter_step("n", "EQUALS", 7)], [bu.filter_step("n", "EQUALS", "seven")],
            [bu.filter_step("s", "EQUALS", "s3")], [bu.filter_step("s", "EQUALS", 3)],
            [bu.filter_step("n", "GREATER_THAN_EQUALS", 45)], [bu.filter_step("s", "IN", ["s1", "s2", "moved"])],
            [bu.filter_step("u", "EQUALS", 12345)]]


def mismatched(c: Conn) -> list:
    return [steps[0]["operator"] for steps in probes()
            if rows(c, steps) != rows(c, [{"type": "SKIP", "skip": 0}] + steps)]


def load(c: Conn) -> bool:
    check_status("create the refused database", c.send({"type": "CREATE_DATABASE", "databaseName": REFUSED_DB}), "OK")
    check_status("create the refused collection", send(c, {"type": "CREATE_COLLECTION"}), "OK")
    for start in range(0, REFUSED_DOCS, BULK):
        r = send(c, {"type": "BULK_SAVE", "objects": [document(i, i % 50, f"s{i % 40}")
                                                       for i in range(start, min(REFUSED_DOCS, start + BULK))]})
        if r.get("status") != "OK":
            return check_status("BULK_SAVE the refused collection", r, "OK")
    for field in ("n", "s", "u"):
        check_status(f"CREATE_INDEX {field}", send(c, {"type": "CREATE_INDEX", "fieldName": field}), "OK")
    return True


def write_everything(c: Conn, rng: random.Random) -> dict:
    expected = {}
    for _ in range(150):
        i = rng.randrange(REFUSED_DOCS)
        value = rng.choice([rng.randrange(60), "seven", None, True])
        r = send(c, {"type": "SAVE", "object": document(i, value, f"s{rng.randrange(45)}")})
        if r.get("status") != "OK" or r.get("inserted") is True:
            check(f"SAVE update {doc_id(i)} answers OK as an update", False, detail=str(r))
            return expected
        expected[doc_id(i)] = value
    for k in range(60):
        i = REFUSED_DOCS + k
        r = send(c, {"type": "SAVE", "object": document(i, 7, "moved")})
        if r.get("status") != "OK" or r.get("inserted") is not True:
            check(f"SAVE insert {doc_id(i)} answers OK as a create", False, detail=str(r))
            return expected
        expected[doc_id(i)] = 7
    for k in range(0, 80, 2):
        r = send(c, {"type": "DELETE", "_id": doc_id(k * 97)})
        if r.get("status") != "OK":
            check(f"DELETE {doc_id(k * 97)}", False, detail=str(r))
            return expected
        expected[doc_id(k * 97)] = "deleted"
    missing = send(c, {"type": "DELETE", "_id": "missing-id"})
    check("DELETE of a missing id answers NOT_FOUND", missing.get("status") == "NOT_FOUND", detail=str(missing))
    objects = [document(i, "seven", 3) for i in range(5, 2005, 4)]
    objects += [document(REFUSED_DOCS + 100 + k, 45 + k % 5, "s1") for k in range(200)]
    objects += [document(97 * 2, 46, "s2")]
    bulk = send(c, {"type": "BULK_SAVE", "objects": objects})
    check_status("BULK_SAVE mixing updates, inserts and a re-created document", bulk, "OK")
    check("BULK_SAVE reports 201 inserts", len(bulk.get("inserted") or []) == 201, detail=str(bulk)[:300])
    check("BULK_SAVE reports 500 updates", len(bulk.get("updated") or []) == 500, detail=str(bulk)[:300])
    for o in objects:
        expected[o["_id"]] = o["n"]
    return expected


def stored_answers_match(c: Conn, expected: dict) -> list:
    wrong = []
    for _id, value in expected.items():
        r = send(c, {"type": "FIND_BY_ID", "_id": _id})
        if value == "deleted":
            if r.get("status") != "NOT_FOUND":
                wrong.append(f"{_id} should be deleted: {r.get('status')}")
        elif r.get("status") != "OK" or (r.get("object") or {}).get("n") != value:
            wrong.append(f"{_id} should hold n={value}: {str(r)[:120]}")
    return wrong


def test_writes_on_a_refused_pk_and_refused_index_types(c: Conn, log_path: str):
    section(f"Writes on {REFUSED_DOCS} documents whose pk and index types maxMemory={REFUSED_MAX_MEMORY} refuses")
    if not load(c):
        return
    expected = write_everything(c, random.Random(20261030))
    wrong = stored_answers_match(c, expected)
    check("every written, inserted and deleted document reads back as written", not wrong, detail=str(wrong[:5]))
    count = send(c, {"type": "AGGREGATE", "aggregationSteps": [{"type": "SKIP", "skip": 0}, {"type": "COUNT"}]})
    deleted = sum(1 for value in expected.values() if value == "deleted")
    check("the scan count is the loaded and inserted documents minus the deleted ones",
          bu.count_of(count) == REFUSED_DOCS + 60 + 201 - (deleted + 1), detail=str(count))
    converged = bu.wait_until(lambda: not mismatched(c), CONVERGE_S, interval_s=1.0)
    check("every indexed query answers like a scan once the background batches ran", converged,
          detail=str(mismatched(c)))
    with open(log_path, "rb") as fp:
        log = fp.read().decode(errors="replace")
    check("the primary-key index was refused, so writers took the point-scan path", PK_REFUSED_WARNING in log)
    check("a field-index type was refused, so maintenance took the log-only path",
          "The field index " in log and FIELD_REFUSED_WARNING in log)


def run_refused_index_phase():
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-refused-index-")
    log_path = os.path.join(work_dir, "server.log")
    bu.write_config(work_dir, REFUSED_PORT, maxMemory=REFUSED_MAX_MEMORY)
    print(f"\n  Starting an own server on {HOST}:{REFUSED_PORT} with maxMemory={REFUSED_MAX_MEMORY} ...")
    proc = bu.start_server(work_dir, log_path, port=REFUSED_PORT)
    try:
        with Conn(port=REFUSED_PORT) as c:
            c.authenticate(bu.USERNAME, bu.PASSWORD)
            test_writes_on_a_refused_pk_and_refused_index_types(c, log_path)
    finally:
        bu.stop_server(proc, port=REFUSED_PORT)
        if bu.failure_count():
            bu.dump_log(log_path)
    shutil.rmtree(work_dir, ignore_errors=True)


if __name__ == "__main__":
    run_refused_index_phase()
    bu.summary()
