import os
import sys
import threading
import time
from typing import Callable

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

HOST = os.environ.get("INDEX_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("INDEX_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

DB = "idxagg_db"
COLL = "idxagg_main"
JOIN_LEFT = "idxagg_left"
JOIN_BIG = "idxagg_big"

NUM_DOCS = int(os.environ.get("INDEX_TEST_DOCS", "8000"))
NUM_CATEGORIES = int(os.environ.get("INDEX_TEST_CATEGORIES", "20"))
LEFT_DOCS = int(os.environ.get("INDEX_TEST_LEFT_DOCS", "100"))
PAYLOAD_BYTES = int(os.environ.get("INDEX_TEST_PAYLOAD_BYTES", "256"))
BULK_BATCH_SIZE = int(os.environ.get("INDEX_TEST_BATCH_SIZE", "500"))
REPEATS = int(os.environ.get("INDEX_TEST_REPEATS", "5"))
WARMUPS = int(os.environ.get("INDEX_TEST_WARMUPS", "20"))
SPEED_TOLERANCE = float(os.environ.get("INDEX_TEST_SPEED_TOLERANCE", "1.25"))

CONS = "idxagg_cons"
CONS_SORT = "idxagg_cons_sort"
CONS_COUNT = "idxagg_cons_count"
CONS_JOIN = "idxagg_cons_join"
CONS_LEFT = "idxagg_cons_left"
CONS_CREATE = "idxagg_cons_create"
CONS_REPEATS = int(os.environ.get("INDEX_TEST_CONSISTENCY_REPEATS", "30"))

GEO = "idxagg_geo"
GEO_SCAN = "idxagg_geo_scan"
GEO_TARGET = "#geo(40.0,-74.0)"
GEO_CLUSTER = int(os.environ.get("INDEX_TEST_GEO_CLUSTER", "50"))
GEO_CLUSTER_POLYGON = ["#geo(39.99,-74.02)", "#geo(39.99,-73.98)", "#geo(40.02,-73.98)", "#geo(40.02,-74.02)"]

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)


def stats(c) -> dict:
    return c.send({"type": "GET_DATABASE_STATS"})


def agg(c, coll, steps) -> dict:
    return c.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                       "aggregationSteps": steps})


def agg_ids(c, coll, steps) -> list:
    return bu.ids_of(agg(c, coll, steps))


def agg_count(c, coll, steps):
    return bu.count_of(agg(c, coll, steps + [{"type": "COUNT"}]))


def filter_ids(c, coll, field, value, op="EQUALS") -> list:
    return agg_ids(c, coll, [bu.filter_step(field, op, value)])


def forced_scan(steps) -> list:
    return [{"type": "SKIP", "skip": 0}] + steps


def timed_once(c, coll, steps):
    t0 = time.perf_counter()
    resp = agg(c, coll, steps)
    return time.perf_counter() - t0, resp


def index_used(c, coll, steps):
    r = c.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll, "aggregationSteps": steps,
                "analyze": True})
    return bool((r.get("analyzeResult") or {}).get("indexUsed"))


def timed_scan_and_index(c, coll, steps, scan_steps):
    for _ in range(WARMUPS):
        agg(c, coll, scan_steps)
        agg(c, coll, steps)
    scan_best = None
    index_best = None
    index_resp = None
    for round_no in range(REPEATS):
        if round_no % 2 == 0:
            scan_dt, _ = timed_once(c, coll, scan_steps)
            index_dt, index_resp = timed_once(c, coll, steps)
        else:
            index_dt, index_resp = timed_once(c, coll, steps)
            scan_dt, _ = timed_once(c, coll, scan_steps)
        scan_best = scan_dt if scan_best is None else min(scan_best, scan_dt)
        index_best = index_dt if index_best is None else min(index_best, index_dt)
    return scan_best, index_best, index_resp


def rand_payload(size: int) -> str:
    return "x" * size


def category_for(v: int) -> str:
    return f"cat_{v % NUM_CATEGORIES}"


def location_for(v: int) -> str:
    if v < GEO_CLUSTER:
        return f"#geo({40.0 + (v % 10) * 0.001:.6f},{-74.0 + (v // 10) * 0.001:.6f})"
    lat = -(((v * 37) % 700) / 10.0) - 10.0
    lng = (((v * 53) % 3600) / 10.0) - 180.0
    return f"#geo({lat:.6f},{lng:.6f})"


def geo_distance_steps(comparator: str, distance: float, target: str = GEO_TARGET, field: str = "location"):
    return [{"type": "FILTER", "operator": {"customOperatorName": "distance", "field": field,
                                            "value": target, "comparator": comparator, "distance": distance}}]


def geo_within_steps(polygon, field: str = "location"):
    return [{"type": "FILTER", "operator": {"customOperatorName": "within", "field": field, "polygon": polygon}}]


def bulk_load(c, coll, docs):
    batch = []
    for doc in docs:
        batch.append(doc)
        if len(batch) >= BULK_BATCH_SIZE:
            r = c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll, "objects": batch})
            if r.get("status") != "OK":
                check_status(f"BULK_SAVE into {coll}", r, "OK")
                return
            batch = []
    if batch:
        c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll, "objects": batch})


def setup_fixtures(c):
    c.send({"type": "CREATE_DATABASE", "databaseName": DB})
    for coll in (COLL, JOIN_LEFT, JOIN_BIG):
        c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})

    bulk_load(c, COLL, ({
        "_id": f"doc_{v:06d}",
        "category": category_for(v),
        "score": v,
        "meta": {"n": v, "category": category_for(v)},
        "tags": ["t", str(v)],
        "location": location_for(v),
        "payload": rand_payload(PAYLOAD_BYTES),
    } for v in range(NUM_DOCS)))

    bulk_load(c, JOIN_LEFT, ({"_id": f"left_{v:06d}", "joinKey": "shared"} for v in range(LEFT_DOCS)))
    bulk_load(c, JOIN_BIG, ({
        "_id": f"big_{v:06d}",
        "joinKey": "shared" if v == 0 else f"key_{v}",
        "joinKeyPlain": "shared" if v == 0 else f"key_{v}",
        "label": "the-one" if v == 0 else f"label_{v}",
        "payload": rand_payload(PAYLOAD_BYTES),
    } for v in range(NUM_DOCS)))


def indexes_present(c, expected) -> bool:
    present = {}
    for db in stats(c).get("stats", {}).get("databases", []):
        if db.get("name") != DB:
            continue
        for coll in db.get("collections", []):
            present[coll.get("name")] = set(coll.get("indexes") or [])
    return all(field in present.get(coll, set()) for coll, field in expected)


def wait_for_indexes(c: Conn, expected, timeout_s=20.0):
    if bu.wait_until(lambda: indexes_present(c, expected), timeout_s, interval_s=0.5):
        return True
    time.sleep(3)
    return False


def build_indexes(c, expected):
    for coll, field in expected:
        c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": coll, "fieldName": field})
    wait_for_indexes(c, expected)


def build_index(c, coll, *fields):
    build_indexes(c, [(coll, field) for field in fields])


def scanned_then_indexed(c, expected, queries, settle=False):
    scanned = [query() for query in queries]
    build_indexes(c, expected)
    if settle:
        wait_for_background()
    return scanned, [query() for query in queries]


def create_indexes(c):
    build_indexes(c, [(COLL, "category"), (COLL, "score"), (COLL, "meta"), (COLL, "tags"),
                      (COLL, "location"), (JOIN_BIG, "joinKey")])


def teardown_fixtures(c):
    c.send({"type": "DROP_DATABASE", "databaseName": DB})


DISTINCT_STEPS = [{"type": "DISTINCT", "fieldName": "category"}]
SORT_STEPS = [{"type": "SORT", "fieldName": "score", "ascending": True}, {"type": "LIMIT", "limit": 10}]
JOIN_STEPS = [{"type": "JOIN", "joinCollection": JOIN_BIG, "localField": "joinKey",
               "remoteField": "joinKey", "asField": "joined"}]
JOIN_SCAN_STEPS = [dict(JOIN_STEPS[0], remoteField="joinKeyPlain")]

TARGET_OBJECT = {"n": 0, "category": category_for(0)}
TARGET_ARRAY = ["t", "0"]
FILTER_OBJECT_STEPS = [{"type": "FILTER",
                        "operator": {"fieldOperatorType": "EQUALS", "field": "meta", "value": TARGET_OBJECT}}]
FILTER_ARRAY_STEPS = [{"type": "FILTER",
                       "operator": {"fieldOperatorType": "EQUALS", "field": "tags", "value": TARGET_ARRAY}}]
FILTER_OBJECT_IN_STEPS = [{"type": "FILTER", "operator": {
    "fieldOperatorType": "IN", "field": "meta",
    "value": [{"n": v, "category": category_for(v)} for v in range(3)]}}]
FILTER_GEO_DISTANCE_STEPS = geo_distance_steps("SMALLER_THAN", 5000)
FILTER_GEO_WITHIN_STEPS = geo_within_steps(GEO_CLUSTER_POLYGON)


def distinct_signature(r):
    return sorted({d.get("category") for d in (r.get("results") or [])})


def sort_signature(r):
    return [d.get("score") for d in (r.get("results") or [])]


def join_signature(r):
    rows = r.get("results") or []
    labels = []
    for row in rows:
        joined = row.get("joined") or []
        labels.append(tuple(sorted(j.get("label") for j in joined)))
    return len(rows), sorted(labels)


def compare(label, scan_time, unindexed_resp, indexed_time, indexed_resp, signature, strict_speed):
    section(label)
    sig_unindexed = signature(unindexed_resp)
    sig_indexed = signature(indexed_resp)
    check(
        "results identical with and without the index",
        sig_unindexed == sig_indexed,
        detail=f"signature={sig_indexed!r}",
    )
    ratio = (indexed_time / scan_time) if scan_time > 0 else 0.0
    detail = (f"scan_best={scan_time * 1000:.2f}ms  indexed_best={indexed_time * 1000:.2f}ms  "
              f"ratio={ratio:.2f} (lower is better)")
    if strict_speed:
        check("index path is faster than the full scan", indexed_time < scan_time, detail=detail)
    else:
        check(
            f"index path is not slower than the full scan (within {SPEED_TOLERANCE:.2f}x)",
            indexed_time <= scan_time * SPEED_TOLERANCE,
            detail=detail,
        )


def save_doc(c, coll, obj) -> dict:
    return c.send({"type": "SAVE", "databaseName": DB, "collectionName": coll, "object": obj})


def setup_consistency(c):
    for coll in (CONS, CONS_SORT, CONS_COUNT, CONS_JOIN, CONS_LEFT, CONS_CREATE):
        c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
    save_doc(c, CONS_LEFT, {"_id": "left_shared", "joinKey": "shared"})
    build_indexes(c, [(CONS, "status"), (CONS_SORT, "score"), (CONS_JOIN, "joinKey")])


def filter_status(c, value):
    return filter_ids(c, CONS, "status", value)


def probe_filter_no_false_negative(c):
    bad = 0
    for i in range(CONS_REPEATS):
        value = f"fn_val_{i}"
        save_doc(c, CONS, {"_id": f"fn_{i}", "status": value})
        if filter_status(c, value) != [f"fn_{i}"]:
            bad += 1
    check("FILTER never misses a just-written match (no false negative)", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} immediate queries were stale")


def probe_filter_no_false_positive_on_update(c):
    save_doc(c, CONS, {"_id": "upd", "status": "upd_v0"})
    prev = "upd_v0"
    bad = 0
    for i in range(1, CONS_REPEATS + 1):
        cur = f"upd_v{i}"
        save_doc(c, CONS, {"_id": "upd", "status": cur})
        if "upd" in filter_status(c, prev):
            bad += 1
        if filter_status(c, cur) != ["upd"]:
            bad += 1
        prev = cur
    check("FILTER reflects updates immediately (no stale false positive / no false negative)",
               bad == 0, detail=f"{bad} inconsistent immediate queries")


def probe_count_with_filter(c):
    bad = 0
    for i in range(CONS_REPEATS):
        save_doc(c, CONS, {"_id": f"cf_{i}", "status": "countme"})
        got = agg_count(c, CONS, [bu.filter_step("status", "EQUALS", "countme")])
        if got != i + 1:
            bad += 1
    check("index-only COUNT (with filter) counts committed docs immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} counts were stale")


THROWING_MAP = {"type": "MAP", "operators": [{"fieldName": "x", "operator": {
    "type": "SCRIPT", "script": "export default () => { throw new Error('boom'); }"}}]}


def probe_count_after_a_failing_map_script(c):
    countme = {"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": "countme"}}
    indexed = agg(c, CONS, [countme, THROWING_MAP, {"type": "COUNT"}])
    scanned = agg(c, CONS, forced_scan([countme, THROWING_MAP, {"type": "COUNT"}]))
    check_code("a COUNT whose MAP script throws fails even when an index could count", indexed, "ERROR", "400-9")
    check_code("exactly as it fails when the filter is answered by a scan", scanned, "ERROR", "400-9")
    by_unindexed = agg(c, CONS, [{"type": "SORT", "fieldName": "unindexed", "ascending": True}, THROWING_MAP,
                                 {"type": "COUNT"}])
    by_indexed = agg(c, CONS, [{"type": "SORT", "fieldName": "status", "ascending": True}, THROWING_MAP,
                               {"type": "COUNT"}])
    grouped = agg(c, CONS, [{"type": "GROUP_BY", "fieldName": "status"}, THROWING_MAP, {"type": "COUNT"}])
    check_code("a COUNT after a scan SORT still runs the MAP script before it", by_unindexed, "ERROR", "400-9")
    check_code("as it does after an index-backed SORT", by_indexed, "ERROR", "400-9")
    check_code("and after a GROUP_BY, whose output size is known up front", grouped, "ERROR", "400-9")


def probe_whole_collection_count(c):
    bad = 0
    for i in range(CONS_REPEATS):
        save_doc(c, CONS_COUNT, {"_id": f"wc_{i}", "n": i})
        got = agg_count(c, CONS_COUNT, [])
        if got != i + 1:
            bad += 1
    check("whole-collection COUNT is exact immediately after each save", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} counts were stale")


def probe_distinct_new_value(c):
    bad = 0
    for i in range(CONS_REPEATS):
        value = f"dv_{i}"
        save_doc(c, CONS, {"_id": f"di_{i}", "status": value})
        r = agg(c, CONS, DISTINCT_STATUS_STEPS)
        values = {d.get("status") for d in (r.get("results") or [])}
        if value not in values:
            bad += 1
    check("DISTINCT includes a just-written new value immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} distinct results were missing the new value")


def probe_group_by(c):
    bad = 0
    for i in range(CONS_REPEATS):
        value = f"gv_{i}"
        save_doc(c, CONS, {"_id": f"gi_{i}", "status": value})
        r = agg(c, CONS, [{"type": "GROUP_BY", "fieldName": "status"}])
        sizes = {d.get("status"): len(d.get("group") or []) for d in (r.get("results") or [])}
        if sizes.get(value) != 1:
            bad += 1
    check("GROUP_BY places a just-written doc in its group immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} group-by results were stale")


def probe_sort(c):
    bad = 0
    for i in range(CONS_REPEATS):
        save_doc(c, CONS_SORT, {"_id": f"s_{i}", "score": i})
        r = agg(c, CONS_SORT, [{"type": "SORT", "fieldName": "score", "ascending": True}])
        scores = [d.get("score") for d in (r.get("results") or [])]
        if scores != sorted(scores) or i not in scores or len(scores) != i + 1:
            bad += 1
    check("SORT includes and orders a just-written value immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} sort results were stale or misordered")


def probe_join(c):
    bad = 0
    for i in range(CONS_REPEATS):
        save_doc(c, CONS_JOIN, {"_id": f"r_{i}", "joinKey": "shared", "label": f"lbl_{i}"})
        r = agg(c, CONS_LEFT, [{"type": "JOIN", "joinCollection": CONS_JOIN, "localField": "joinKey",
                                   "remoteField": "joinKey", "asField": "joined"}])
        rows = r.get("results") or []
        joined = rows[0].get("joined") if rows else None
        if joined is None or len(joined) != i + 1:
            bad += 1
    check("index-backed JOIN matches a just-written remote doc immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} join results were stale")


def probe_delete_consistency(c):
    bad = 0
    for i in range(CONS_REPEATS):
        value = f"del_{i}"
        doc_id = f"del_doc_{i}"
        save_doc(c, CONS, {"_id": doc_id, "status": value})
        c.send({"type": "DELETE", "databaseName": DB, "collectionName": CONS, "_id": doc_id})
        if filter_status(c, value):
            bad += 1
        if agg_count(c, CONS, [bu.filter_step("status", "EQUALS", value)]) != 0:
            bad += 1
        d = agg(c, CONS, DISTINCT_STATUS_STEPS)
        if value in {row.get("status") for row in (d.get("results") or [])}:
            bad += 1
    check("DELETE removes a doc from FILTER, COUNT and DISTINCT immediately", bad == 0,
               detail=f"{bad} inconsistent immediate queries after delete")


def probe_same_id_rapid_updates_converge(c):
    iterations = max(CONS_REPEATS, 20)
    last_value = None
    for i in range(iterations):
        last_value = f"hammer_{i}"
        save_doc(c, CONS, {"_id": "hammer", "status": last_value})
    wait_for_background()
    bad = 0
    if filter_status(c, last_value) != ["hammer"]:
        bad += 1
    if filter_status(c, "hammer_0"):
        bad += 1
    if agg_count(c, CONS, [bu.filter_step("status", "EQUALS", last_value)]) != 1:
        bad += 1
    check("same-id rapid updates converge to the last value after the background settles", bad == 0,
               detail=f"{bad} stale results after hammering one id with {iterations} updates")


def probe_save_delete_converges(c):
    for i in range(CONS_REPEATS):
        value = f"sd_{i}"
        doc_id = f"sd_doc_{i}"
        save_doc(c, CONS, {"_id": doc_id, "status": value})
        c.send({"type": "DELETE", "databaseName": DB, "collectionName": CONS, "_id": doc_id})
    wait_for_background()
    bad = sum(1 for i in range(CONS_REPEATS) if filter_status(c, f"sd_{i}"))
    check("save+delete of the same id converges to absent after the background settles", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} deleted values still present after settling")


def probe_concurrent_save_during_create_index(c: Conn):
    seed = 40
    for i in range(seed):
        save_doc(c, CONS_CREATE, {"_id": f"seed_{i}", "tag": f"t{i % 5}"})

    saved_ids = []
    saved_lock = threading.Lock()
    stop = threading.Event()

    def writer():
        with Conn() as wc:
            wc.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            written = 0
            while not stop.is_set():
                doc_id = f"conc_{written}"
                r = save_doc(wc, CONS_CREATE, {"_id": doc_id, "tag": "concurrent"})
                if r.get("status") == "OK":
                    with saved_lock:
                        saved_ids.append(doc_id)
                written += 1

    t = threading.Thread(target=writer)
    t.start()
    try:
        time.sleep(0.05)
        c.send({"type": "CREATE_INDEX", "databaseName": DB,
                    "collectionName": CONS_CREATE, "fieldName": "tag"})
        time.sleep(0.05)
    finally:
        stop.set()
        t.join()
    wait_for_background()

    found = set(filter_ids(c, CONS_CREATE, "tag", "concurrent"))
    with saved_lock:
        expected = list(saved_ids)
    missing = [i for i in expected if i not in found]
    check("no document saved concurrently with CREATE_INDEX is missing from the index",
               not missing,
               detail=f"{len(missing)} of {len(expected)} concurrently-saved docs missing from the index")


def probe_drop_burst_no_background_errors(c):
    burst_db = "idxagg_drop_burst"
    c.send({"type": "CREATE_DATABASE", "databaseName": burst_db})
    n = max(CONS_REPEATS, 12)
    for i in range(n):
        coll = f"burst_{i}"
        c.send({"type": "CREATE_COLLECTION", "databaseName": burst_db, "collectionName": coll})
        for j in range(5):
            c.send({"type": "SAVE", "databaseName": burst_db, "collectionName": coll,
                        "object": {"_id": f"d{j}", "k": f"v{j}"}})
        c.send({"type": "DROP_COLLECTION", "databaseName": burst_db, "collectionName": coll})
    c.send({"type": "CREATE_COLLECTION", "databaseName": burst_db, "collectionName": "keep"})
    for j in range(5):
        c.send({"type": "SAVE", "databaseName": burst_db, "collectionName": "keep",
                    "object": {"_id": f"k{j}", "k": f"v{j}"}})
    wait_for_background()
    r = c.send({"type": "AGGREGATE", "databaseName": burst_db, "collectionName": "keep",
                    "aggregationSteps": [{"type": "COUNT"}]})
    count = bu.count_of(r)
    c.send({"type": "DROP_DATABASE", "databaseName": burst_db})
    check("drop burst keeps survivors intact (no stale-position corruption)", count == 5,
               detail=f"survivor count={count} (expected 5)")


def probe_bulk_update_same_page_no_corruption(c):
    coll = "idxagg_bulk_upd"
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
    ids = [f"bu_{i}" for i in range(6)]
    c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll,
                "objects": [{"_id": i, "v": "short"} for i in ids]})
    c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll,
                "objects": [{"_id": i, "v": f"updated-longer-value-for-{i}"} for i in ids]})
    bad = 0
    for i in ids:
        r = c.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": coll, "_id": i})
        if (r.get("object") or {}).get("v") != f"updated-longer-value-for-{i}":
            bad += 1
    c.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": coll})
    check("bulk update of multiple same-page docs reads back intact (no page corruption)", bad == 0,
               detail=f"{bad}/{len(ids)} docs corrupted/stale after bulk update")


def probe_convergence(c):
    save_doc(c, CONS, {"_id": "converge", "status": "converged"})
    wait_for_background()
    check("index converges after the background settles", filter_status(c, "converged") == ["converge"])


def wait_for_background():
    time.sleep(2)


DISTINCT_STATUS_STEPS = [{"type": "DISTINCT", "fieldName": "status"}]


def consistency_suite(c):
    section("Consistency under asynchronous index maintenance (pending-write window)")
    setup_consistency(c)
    probe_filter_no_false_negative(c)
    probe_filter_no_false_positive_on_update(c)
    probe_count_with_filter(c)
    probe_count_after_a_failing_map_script(c)
    probe_whole_collection_count(c)
    probe_distinct_new_value(c)
    probe_group_by(c)
    probe_sort(c)
    probe_join(c)
    probe_delete_consistency(c)
    probe_same_id_rapid_updates_converge(c)
    probe_save_delete_converges(c)
    probe_concurrent_save_during_create_index(c)
    probe_drop_burst_no_background_errors(c)
    probe_bulk_update_same_page_no_corruption(c)
    probe_convergence(c)


GEO_SEED = [
    ("near", "#geo(40.0,-74.0)"),
    ("close", "#geo(40.001,-74.001)"),
    ("mid", "#geo(40.05,-74.0)"),
    ("far", "#geo(34.05,-118.24)"),
]
GEO_POLY = ["#geo(39.99,-74.1)", "#geo(39.99,-73.9)", "#geo(40.1,-73.9)", "#geo(40.1,-74.1)"]
GEO_POLY_MID = ["#geo(40.04,-74.01)", "#geo(40.04,-73.99)", "#geo(40.06,-73.99)", "#geo(40.06,-74.01)"]


def agg_analyze(c, coll, steps) -> dict:
    return c.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                       "analyze": True, "aggregationSteps": steps})


def setup_geo(c):
    for coll in (GEO, GEO_SCAN):
        c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
        for _id, loc in GEO_SEED:
            save_doc(c, coll, {"_id": _id, "location": loc})
    build_index(c, GEO, "location")


def geo_compare_case(c, label, steps, expected):
    idx = agg_ids(c, GEO, steps)
    scan = agg_ids(c, GEO_SCAN, steps)
    check(label, idx == scan == expected, detail=f"indexed={idx}  scan={scan}  expected={expected}")


def probe_geo_distance_comparators(c):
    geo_compare_case(c, "distance SMALLER_THAN 1 km -> {close, near}",
                     geo_distance_steps("SMALLER_THAN", 1000), ["close", "near"])
    geo_compare_case(c, "distance SMALLER_THAN_EQUALS 6 km -> {close, mid, near}",
                     geo_distance_steps("SMALLER_THAN_EQUALS", 6000), ["close", "mid", "near"])
    geo_compare_case(c, "distance GREATER_THAN 1 km -> {far, mid}",
                     geo_distance_steps("GREATER_THAN", 1000), ["far", "mid"])
    geo_compare_case(c, "distance GREATER_THAN_EQUALS 6 km -> {far}",
                     geo_distance_steps("GREATER_THAN_EQUALS", 6000), ["far"])
    geo_compare_case(c, "distance EQUALS 0 m -> only the exact point {near}",
                     geo_distance_steps("EQUALS", 0.0), ["near"])


def probe_geo_within(c):
    geo_compare_case(c, "within polygon -> {close, mid, near}", geo_within_steps(GEO_POLY),
                     ["close", "mid", "near"])


def probe_geo_conjunctions(c):
    and_steps = [{"type": "FILTER", "operator": {"conjunctionType": "AND", "operators": [
        {"customOperatorName": "within", "field": "location", "polygon": GEO_POLY},
        {"customOperatorName": "distance", "field": "location", "value": GEO_TARGET,
         "comparator": "SMALLER_THAN", "distance": 1000}]}}]
    check("AND(within, distance<1km) -> {close, near}",
               agg_ids(c, GEO, and_steps) == ["close", "near"],
               detail=f"got={agg_ids(c, GEO, and_steps)}")
    or_steps = [{"type": "FILTER", "operator": {"conjunctionType": "OR", "operators": [
        {"customOperatorName": "distance", "field": "location", "value": GEO_TARGET,
         "comparator": "SMALLER_THAN", "distance": 1000},
        {"customOperatorName": "within", "field": "location", "polygon": GEO_POLY_MID}]}}]
    check("OR(distance<1km, within-mid) -> {close, mid, near}",
               agg_ids(c, GEO, or_steps) == ["close", "mid", "near"],
               detail=f"got={agg_ids(c, GEO, or_steps)}")


def probe_geo_count(c):
    got = agg_count(c, GEO, geo_distance_steps("SMALLER_THAN", 1000))
    check("COUNT after a geo distance filter -> 2", got == 2, detail=f"count={got}")


def probe_geo_analyze(c):
    ar = agg_analyze(c, GEO, geo_distance_steps("SMALLER_THAN", 1000)).get("analyzeResult") or {}
    check("analyze: distance within-radius uses the location index",
               ar.get("indexUsed") is True and "location" in (ar.get("indexesUsed") or []),
               detail=f"indexUsed={ar.get('indexUsed')} indexesUsed={ar.get('indexesUsed')}")
    ar = agg_analyze(c, GEO, geo_within_steps(GEO_POLY)).get("analyzeResult") or {}
    check("analyze: within uses the location index", ar.get("indexUsed") is True,
               detail=f"indexUsed={ar.get('indexUsed')}")
    ar = agg_analyze(c, GEO, geo_distance_steps("GREATER_THAN", 1000)).get("analyzeResult") or {}
    check("analyze: distance GREATER_THAN falls back to a scan (no index)", ar.get("indexUsed") is False,
               detail=f"indexUsed={ar.get('indexUsed')}")


def probe_geo_pending_write(c):
    bad = 0
    for i in range(CONS_REPEATS):
        doc_id = f"geo_pending_{i}"
        save_doc(c, GEO, {"_id": doc_id, "location": "#geo(40.0,-74.0)"})
        if doc_id not in agg_ids(c, GEO, geo_distance_steps("SMALLER_THAN", 1000)):
            bad += 1
    check("distance filter finds a just-written geo point immediately (pending-write overlay)",
               bad == 0, detail=f"{bad}/{CONS_REPEATS} immediate geo queries were stale")


def probe_geo_non_geo_field_no_match(c):
    save_doc(c, GEO_SCAN, {"_id": "not_geo", "location": "just a string"})
    res = agg_ids(c, GEO_SCAN, geo_distance_steps("SMALLER_THAN", 1000))
    check("distance filter ignores a non-geo field value (no match, no error)", "not_geo" not in res,
               detail=f"got={res}")


def geo_suite(c):
    section("Geo type: distance & within custom operators")
    setup_geo(c)
    probe_geo_distance_comparators(c)
    probe_geo_within(c)
    probe_geo_conjunctions(c)
    probe_geo_count(c)
    probe_geo_analyze(c)
    probe_geo_non_geo_field_no_match(c)
    probe_geo_pending_write(c)


AGREE_CONTAINS_NUM = "idxagg_agree_contains_num"
AGREE_CONTAINS_BOOL = "idxagg_agree_contains_bool"
AGREE_NOT_IN_OBJ = "idxagg_agree_notin_obj"
AGREE_NOT_IN_ARR = "idxagg_agree_notin_arr"
AGREE_JOIN_REMOTE = "idxagg_agree_join_remote"
AGREE_JOIN_LEFT = "idxagg_agree_join_left"
AGREE_JOIN_NULL_REMOTE = "idxagg_agree_join_null_remote"
AGREE_JOIN_NULL_LEFT = "idxagg_agree_join_null_left"
AGREE_SORT_BOOL = "idxagg_agree_sort_bool"
AGREE_SORT_BOOL_DESC = "idxagg_agree_sort_bool_desc"
AGREE_SORT_MIXED = "idxagg_agree_sort_mixed"
AGREE_SORT_TIES = "idxagg_agree_sort_ties"
AGREE_SIBLING = "idxagg_agree_sibling"
AGREE_OBJ_SORT = "idxagg_agree_obj_sort"
AGREE_CUSTOM = "idxagg_agree_custom"
AGREE_MIXED_BOX = "idxagg_agree_mixed_box"
AGREE_GEO = "idxagg_agree_geo"
AGREE_GEO_TARGET = "#geo(0.000000,0.000000)"
AGREE_IN_CASE = "idxagg_agree_in_case"
AGREE_NOT_IN_CASE = "idxagg_agree_notin_case"
AGREE_IN_CUSTOM = "idxagg_agree_in_custom"
AGREE_NOT_IN_CUSTOM = "idxagg_agree_notin_custom"
AGREE_CUSTOM_SORT = "idxagg_agree_custom_sort"
AGREE_CUSTOM_TIES = "idxagg_agree_custom_ties"
AGREE_SURROGATE = "idxagg_agree_surrogate"
AGREE_SURROGATE_CONTAINER = "idxagg_agree_surrogate_container"
AGREE_SURROGATE_FOLD = "idxagg_agree_surrogate_fold"
AGREE_ARRAY_CUSTOM = "idxagg_agree_array_custom"
AGREE_OBJECT_CUSTOM = "idxagg_agree_object_custom"
AGREE_CONTAINS_CUSTOM = "idxagg_agree_contains_custom"
AGREE_NOT_EQUALS_MIXED = "idxagg_agree_ne_mixed"
AGREE_NOT_EQUALS_HOMOGENEOUS = "idxagg_agree_ne_homogeneous"
AGREE_DOTTED_GROUP = "idxagg_agree_dotted_group"
AGREE_DOTTED_DISTINCT = "idxagg_agree_dotted_distinct"

AGREE_COLLECTIONS = (AGREE_CONTAINS_NUM, AGREE_CONTAINS_BOOL, AGREE_NOT_IN_OBJ, AGREE_NOT_IN_ARR,
                     AGREE_JOIN_REMOTE, AGREE_JOIN_LEFT, AGREE_JOIN_NULL_REMOTE, AGREE_JOIN_NULL_LEFT,
                     AGREE_SORT_BOOL, AGREE_SORT_BOOL_DESC, AGREE_SORT_MIXED, AGREE_SORT_TIES,
                     AGREE_SIBLING, AGREE_OBJ_SORT, AGREE_CUSTOM, AGREE_MIXED_BOX, AGREE_GEO,
                     AGREE_IN_CASE, AGREE_NOT_IN_CASE, AGREE_IN_CUSTOM, AGREE_NOT_IN_CUSTOM,
                     AGREE_CUSTOM_SORT, AGREE_CUSTOM_TIES, AGREE_SURROGATE, AGREE_SURROGATE_CONTAINER, AGREE_SURROGATE_FOLD,
                     AGREE_ARRAY_CUSTOM,
                     AGREE_OBJECT_CUSTOM, AGREE_CONTAINS_CUSTOM, AGREE_NOT_EQUALS_MIXED,
                     AGREE_NOT_EQUALS_HOMOGENEOUS, AGREE_DOTTED_GROUP, AGREE_DOTTED_DISTINCT)


def agree_ids(r):
    status = r.get("status")
    if status not in ("OK", "NOT_FOUND"):
        return f"<{status}/{r.get('errorCode')}>"
    return bu.ids_of(r)


def agree_ordered_ids(r):
    status = r.get("status")
    if status not in ("OK", "NOT_FOUND"):
        return f"<{status}/{r.get('errorCode')}>"
    return [d.get("_id") for d in (r.get("results") or [])]


def agree_joined(r):
    status = r.get("status")
    if status not in ("OK", "NOT_FOUND"):
        return f"<{status}/{r.get('errorCode')}>"
    rows = []
    for d in (r.get("results") or []):
        attached = d.get("cfg")
        rows.append((d.get("_id"), len(attached) if isinstance(attached, list) else 0))
    return sorted(rows)


def agree_nested_keys(field_path):
    def extract(r):
        status = r.get("status")
        if status not in ("OK", "NOT_FOUND"):
            return f"<{status}/{r.get('errorCode')}>"
        rows = []
        for d in (r.get("results") or []):
            node = d
            for segment in field_path.split("."):
                node = node.get(segment) if isinstance(node, dict) else None
            members = sorted(m.get("_id") for m in (d.get("group") or []))
            rows.append((node, members, field_path in d))
        return sorted(rows)
    return extract


def agree(c, label, query_coll, steps, index_coll, index_field, extract=agree_ids, expected=None):
    (scanned,), (indexed,) = scanned_then_indexed(c, [(index_coll, index_field)],
                                                  [lambda: extract(agg(c, query_coll, steps))])
    check(f"{label}: index agrees with scan", scanned == indexed,
          f"scan={scanned!r}  indexed={indexed!r}")
    if expected is not None:
        check(f"{label}: answer is correct", scanned == expected,
              f"expected={expected!r}  got={scanned!r}")


def index_matches_scan(c: Conn, coll, steps, extract: Callable[[dict], object] = agree_ids):
    return extract(agg(c, coll, steps)), extract(agg(c, coll, forced_scan(steps)))


def setup_agreement(c):
    for coll in AGREE_COLLECTIONS:
        c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
    save_doc(c, AGREE_CONTAINS_NUM, {"_id": "scalar", "tags": 5})
    save_doc(c, AGREE_CONTAINS_NUM, {"_id": "inArray", "tags": [5, 7]})
    save_doc(c, AGREE_CONTAINS_BOOL, {"_id": "scalar", "flags": True})
    save_doc(c, AGREE_CONTAINS_BOOL, {"_id": "inArray", "flags": [True]})
    save_doc(c, AGREE_NOT_IN_OBJ, {"_id": "obj1", "tag": {"a": 1}})
    save_doc(c, AGREE_NOT_IN_OBJ, {"_id": "str1", "tag": "x"})
    save_doc(c, AGREE_NOT_IN_OBJ, {"_id": "obj2", "tag": {"b": 2}})
    save_doc(c, AGREE_NOT_IN_ARR, {"_id": "arr1", "tag": [1]})
    save_doc(c, AGREE_NOT_IN_ARR, {"_id": "str1", "tag": "x"})
    save_doc(c, AGREE_NOT_IN_ARR, {"_id": "arr2", "tag": [2]})
    save_doc(c, AGREE_JOIN_REMOTE, {"_id": "cfg1", "key": {"region": "eu"}})
    save_doc(c, AGREE_JOIN_LEFT, {"_id": "row1", "key": {"region": "eu"}})
    save_doc(c, AGREE_JOIN_NULL_REMOTE, {"_id": "cfg1", "key": None})
    save_doc(c, AGREE_JOIN_NULL_LEFT, {"_id": "row1", "key": None})
    save_doc(c, AGREE_SORT_BOOL, {"_id": "isFalse", "active": False})
    save_doc(c, AGREE_SORT_BOOL, {"_id": "isTrue", "active": True})
    save_doc(c, AGREE_SORT_BOOL_DESC, {"_id": "isFalse", "active": False})
    save_doc(c, AGREE_SORT_BOOL_DESC, {"_id": "isTrue", "active": True})
    save_doc(c, AGREE_SORT_MIXED, {"_id": "bool1", "x": True})
    save_doc(c, AGREE_SORT_MIXED, {"_id": "num1", "x": 5})
    save_doc(c, AGREE_SORT_MIXED, {"_id": "num2", "x": 7})
    save_doc(c, AGREE_SORT_MIXED, {"_id": "str1", "x": "abc"})
    save_doc(c, AGREE_SORT_TIES, {"_id": "tieA", "score": 5})
    save_doc(c, AGREE_SORT_TIES, {"_id": "tieB", "score": 5})
    save_doc(c, AGREE_SORT_TIES, {"_id": "high", "score": 9})
    save_doc(c, AGREE_GEO, {"_id": "onTheRim", "location": "#geo(0.899000,0.000000)"})
    save_doc(c, AGREE_GEO, {"_id": "farAway", "location": "#geo(-40.000000,100.000000)"})
    for coll in (AGREE_IN_CASE, AGREE_NOT_IN_CASE):
        save_doc(c, coll, {"_id": "upper", "tag": "Alpha"})
        save_doc(c, coll, {"_id": "lower", "tag": "alpha"})
        save_doc(c, coll, {"_id": "other", "tag": "beta"})
    for coll in (AGREE_IN_CUSTOM, AGREE_NOT_IN_CUSTOM):
        save_doc(c, coll, {"_id": "d1", "when": "#datetime(2024-01-01T10:00)"})
        save_doc(c, coll, {"_id": "d2", "when": "#datetime(2024-02-02T11:00:00)"})


def probe_contains_agrees_with_scan_per_type(c):
    agree(c, "CONTAINS over a number-indexed field", AGREE_CONTAINS_NUM,
          [{"type": "FILTER", "operator": {"fieldOperatorType": "CONTAINS", "field": "tags", "value": 5}}],
          AGREE_CONTAINS_NUM, "tags", expected=["inArray"])
    agree(c, "CONTAINS over a boolean-indexed field", AGREE_CONTAINS_BOOL,
          [{"type": "FILTER", "operator": {"fieldOperatorType": "CONTAINS", "field": "flags", "value": True}}],
          AGREE_CONTAINS_BOOL, "flags", expected=["inArray"])


def probe_not_in_agrees_with_scan(c):
    agree(c, "NOT_IN with an object operand on a mixed-type field", AGREE_NOT_IN_OBJ,
          [{"type": "FILTER", "operator": {"fieldOperatorType": "NOT_IN", "field": "tag",
                                           "value": [{"a": 1}]}}],
          AGREE_NOT_IN_OBJ, "tag", expected=["obj2", "str1"])
    agree(c, "NOT_IN with an array operand on a mixed-type field", AGREE_NOT_IN_ARR,
          [{"type": "FILTER", "operator": {"fieldOperatorType": "NOT_IN", "field": "tag",
                                           "value": [[1]]}}],
          AGREE_NOT_IN_ARR, "tag", expected=["arr2", "str1"])


def probe_join_agrees_with_scan_for_non_scalar_keys(c):
    agree(c, "JOIN on an object key", AGREE_JOIN_LEFT,
          [{"type": "JOIN", "joinCollection": AGREE_JOIN_REMOTE, "localField": "key",
            "remoteField": "key", "asField": "cfg"}],
          AGREE_JOIN_REMOTE, "key", extract=agree_joined, expected=[("row1", 1)])
    agree(c, "JOIN on a null key", AGREE_JOIN_NULL_LEFT,
          [{"type": "JOIN", "joinCollection": AGREE_JOIN_NULL_REMOTE, "localField": "key",
            "remoteField": "key", "asField": "cfg"}],
          AGREE_JOIN_NULL_REMOTE, "key", extract=agree_joined)


def probe_sort_agrees_with_scan(c):
    agree(c, "SORT ascending on a boolean field", AGREE_SORT_BOOL,
          [{"type": "SORT", "fieldName": "active", "ascending": True}],
          AGREE_SORT_BOOL, "active", extract=agree_ordered_ids, expected=["isFalse", "isTrue"])
    agree(c, "SORT descending on a boolean field", AGREE_SORT_BOOL_DESC,
          [{"type": "SORT", "fieldName": "active", "ascending": False}],
          AGREE_SORT_BOOL_DESC, "active", extract=agree_ordered_ids, expected=["isTrue", "isFalse"])
    agree(c, "SORT over a mixed-type field", AGREE_SORT_MIXED,
          [{"type": "SORT", "fieldName": "x", "ascending": True}],
          AGREE_SORT_MIXED, "x", extract=agree_ordered_ids,
          expected=["bool1", "num1", "num2", "str1"])


def probe_sort_limit_is_deterministic(c):
    steps = [{"type": "SORT", "fieldName": "score", "ascending": True}, {"type": "LIMIT", "limit": 1}]
    scanned = agree_ordered_ids(agg(c, AGREE_SORT_TIES, steps))
    build_index(c, AGREE_SORT_TIES, "score")
    picks = {tuple(agree_ordered_ids(agg(c, AGREE_SORT_TIES, steps))) for _ in range(10)}
    check("SORT + LIMIT over tied keys picks the same document every run", len(picks) == 1,
          f"scan={scanned!r}  indexed picks across 10 runs={sorted(picks)!r}")
    for i in range(30):
        save_doc(c, AGREE_SORT_TIES, {"_id": f"churn{i}", "score": 5})
    for i in range(30):
        c.send({"type": "DELETE", "databaseName": DB, "collectionName": AGREE_SORT_TIES,
                "_id": f"churn{i}"})
    after = {tuple(agree_ordered_ids(agg(c, AGREE_SORT_TIES, steps))) for _ in range(10)}
    check("SORT + LIMIT over tied keys is stable across churn in the tied bucket",
          picks == after, f"before={sorted(picks)!r}  after={sorted(after)!r}")


def probe_geo_distance_agrees_with_scan_at_the_rim(c):
    agree(c, "geo distance just inside the radius", AGREE_GEO,
          geo_distance_steps("SMALLER_THAN", 100000, target=AGREE_GEO_TARGET),
          AGREE_GEO, "location", expected=["onTheRim"])


def probe_dropping_an_index_spares_a_sibling_field(c):
    save_doc(c, AGREE_SIBLING, {"_id": "s1", "first": "ada", "first-name": "ada lovelace"})
    save_doc(c, AGREE_SIBLING, {"_id": "s2", "first": "alan", "first-name": "alan turing"})
    build_index(c, AGREE_SIBLING, "first-name")
    steps = [bu.filter_step("first-name", "EQUALS", "ada lovelace")]
    before = agree_ids(agg(c, AGREE_SIBLING, steps))
    build_index(c, AGREE_SIBLING, "first")
    save_doc(c, AGREE_SIBLING, {"_id": "s3", "first": "grace", "first-name": "grace hopper"})
    after = agree_ids(agg(c, AGREE_SIBLING, steps))
    check("indexing a field never deletes a sibling whose name extends it",
          before == ["s1"] and after == ["s1"],
          f"before={before!r}  after indexing 'first'={after!r}")


def probe_object_sort_keys_break_ties_on_id(c):
    for doc_id, inner in (("z", 1), ("a", 2), ("m", 3)):
        save_doc(c, AGREE_OBJ_SORT, {"_id": doc_id, "meta": {"x": inner}})
    steps = [{"type": "SORT", "fieldName": "meta", "ascending": True}, {"type": "LIMIT", "limit": 2}]
    agree(c, "SORT + LIMIT over object-valued keys", AGREE_OBJ_SORT, steps,
          AGREE_OBJ_SORT, "meta", extract=agree_ordered_ids, expected=["a", "m"])


def probe_custom_values_bucket_the_same_either_way(c):
    save_doc(c, AGREE_CUSTOM, {"_id": "d1", "when": "#datetime(2024-01-01T10:00)"})
    save_doc(c, AGREE_CUSTOM, {"_id": "d2", "when": "#datetime(2024-01-01T10:00:00)"})
    steps = [{"type": "DISTINCT", "fieldName": "when"}]
    scanned = len(agg(c, AGREE_CUSTOM, steps).get("results") or [])
    build_index(c, AGREE_CUSTOM, "when")
    save_doc(c, AGREE_CUSTOM, {"_id": "d3", "when": "#datetime(2024-02-02T11:00)"})
    indexed = len(agg(c, AGREE_CUSTOM, steps).get("results") or [])
    check("DISTINCT over a custom-typed field buckets the same with and without an index",
          indexed == scanned + 1, f"scan over 2 docs={scanned}  indexed over 3 docs={indexed}")


def probe_custom_sort_keys_order_by_the_type_comparator(c):
    for doc_id, location in (("x", "#geo(10.0,5.0)"), ("y", "#geo(9.0,5.0)"), ("z", "#geo(8.0,5.0)")):
        save_doc(c, AGREE_CUSTOM_SORT, {"_id": doc_id, "location": location})
    sort_steps = [{"type": "SORT", "fieldName": "location", "ascending": True}]
    ordered = agree_ordered_ids(agg(c, AGREE_CUSTOM_SORT, sort_steps))
    above = filter_ids(c, AGREE_CUSTOM_SORT, "location", "#geo(9.0,5.0)", "GREATER_THAN")
    check("SORT over geo values follows the same order as GREATER_THAN",
          ordered == ["z", "y", "x"] and above == ["x"],
          detail=f"sorted={ordered} above the middle point={above}")
    agree(c, "SORT over geo values", AGREE_CUSTOM_SORT, sort_steps,
          AGREE_CUSTOM_SORT, "location", extract=agree_ordered_ids, expected=["z", "y", "x"])

    for doc_id, when in (("b", "#datetime(2024-01-01T10:00)"), ("a", "#datetime(2024-01-01T10:00:00)"),
                         ("c", "#datetime(2024-06-01T10:00:00)")):
        save_doc(c, AGREE_CUSTOM_TIES, {"_id": doc_id, "when": when})
    agree(c, "SORT + LIMIT over two spellings of one instant", AGREE_CUSTOM_TIES,
          [{"type": "SORT", "fieldName": "when", "ascending": True}, {"type": "LIMIT", "limit": 1}],
          AGREE_CUSTOM_TIES, "when", extract=agree_ordered_ids, expected=["a"])


def probe_membership_uses_the_same_equality_as_equals(c):
    agree(c, "IN with a case-mismatched string operand", AGREE_IN_CASE,
          [{"type": "FILTER", "operator": {"fieldOperatorType": "IN", "field": "tag",
                                           "value": ["ALPHA"]}}],
          AGREE_IN_CASE, "tag", expected=["lower", "upper"])
    agree(c, "NOT_IN with a case-mismatched string operand", AGREE_NOT_IN_CASE,
          [{"type": "FILTER", "operator": {"fieldOperatorType": "NOT_IN", "field": "tag",
                                           "value": ["ALPHA"]}}],
          AGREE_NOT_IN_CASE, "tag", expected=["other"])
    agree(c, "IN with a custom operand spelled differently from the stored value", AGREE_IN_CUSTOM,
          [{"type": "FILTER", "operator": {"fieldOperatorType": "IN", "field": "when",
                                           "value": ["#datetime(2024-01-01T10:00:00)"]}}],
          AGREE_IN_CUSTOM, "when", expected=["d1"])
    agree(c, "NOT_IN with a custom operand spelled differently from the stored value", AGREE_NOT_IN_CUSTOM,
          [{"type": "FILTER", "operator": {"fieldOperatorType": "NOT_IN", "field": "when",
                                           "value": ["#datetime(2024-01-01T10:00:00)"]}}],
          AGREE_NOT_IN_CUSTOM, "when", expected=["d2"])


def probe_array_equals_uses_the_same_equality_as_scalar_equals(c):
    long_spelling = "#datetime(2024-01-01T10:00:00)"
    short_spelling = "#datetime(2024-01-01T10:00)"
    for coll in (AGREE_ARRAY_CUSTOM, AGREE_CONTAINS_CUSTOM):
        save_doc(c, coll, {"_id": "d1", "times": [long_spelling]})
        save_doc(c, coll, {"_id": "d2", "times": ["#datetime(2024-02-02T11:00)"]})
    save_doc(c, AGREE_OBJECT_CUSTOM, {"_id": "d1", "meta": {"when": long_spelling}})
    save_doc(c, AGREE_OBJECT_CUSTOM, {"_id": "d2", "meta": {"when": "#datetime(2024-02-02T11:00)"}})

    array_steps = [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "times",
                                                   "value": [short_spelling]}}]
    agree(c, "EQUALS with an array holding a custom value spelled differently", AGREE_ARRAY_CUSTOM,
          array_steps, AGREE_ARRAY_CUSTOM, "times", expected=["d1"])
    agree(c, "EQUALS with an object holding a custom value spelled differently", AGREE_OBJECT_CUSTOM,
          [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "meta",
                                           "value": {"when": short_spelling}}}],
          AGREE_OBJECT_CUSTOM, "meta", expected=["d1"])
    agree(c, "CONTAINS with a custom operand spelled differently from the array element",
          AGREE_CONTAINS_CUSTOM,
          [{"type": "FILTER", "operator": {"fieldOperatorType": "CONTAINS", "field": "times",
                                           "value": short_spelling}}],
          AGREE_CONTAINS_CUSTOM, "times", expected=["d1"])

    save_doc(c, AGREE_ARRAY_CUSTOM, {"_id": "d3", "times": [short_spelling]})
    wait_for_indexes(c, [(AGREE_ARRAY_CUSTOM, "times")])
    indexed, scanned = index_matches_scan(c, AGREE_ARRAY_CUSTOM, array_steps)
    check("a document saved after the index exists joins the bucket of its equal spelling",
          indexed == scanned == ["d1", "d3"], f"index={indexed!r} scan={scanned!r}")


def probe_a_scalar_membership_operand_is_refused(c):
    steps = [{"type": "FILTER",
              "operator": {"fieldOperatorType": "IN", "field": "score", "value": 2}}]
    r = agg(c, AGREE_MIXED_BOX, steps)
    check("a scalar IN operand is refused rather than answered differently per path",
          r.get("status") == "ERROR" and str(r.get("errorCode", "")).startswith("400"),
          f"status={r.get('status')} errorCode={r.get('errorCode')} message={r.get('message')!r}")


def probe_mixed_number_boxes_group_the_same_either_way(c):
    save_doc(c, AGREE_MIXED_BOX, {"_id": "w1", "score": 2})
    save_doc(c, AGREE_MIXED_BOX, {"_id": "w2", "score": 2.0})
    save_doc(c, AGREE_MIXED_BOX, {"_id": "w3", "score": 3})
    steps = [{"type": "GROUP_BY", "fieldName": "score"}]
    agree(c, "GROUP_BY over mixed integer and fractional spellings", AGREE_MIXED_BOX, steps,
          AGREE_MIXED_BOX, "score", extract=lambda r: len(r.get("results") or []), expected=2)


def probe_lone_surrogate_values_index_the_same_as_they_scan(c):
    values = {"hi": "a\ud800b", "lo": "a\udc00b", "plain": "a?b", "emoji": "a\U0001f600b"}
    for doc_id, value in values.items():
        save_doc(c, AGREE_SURROGATE, {"_id": doc_id, "v": value})
    build_index(c, AGREE_SURROGATE, "v")

    for doc_id, value in values.items():
        steps = [bu.filter_step("v", "EQUALS", value)]
        indexed, scanned = index_matches_scan(c, AGREE_SURROGATE, steps)
        rows = len(indexed) if isinstance(indexed, list) else -1
        counted_rows = agg_count(c, AGREE_SURROGATE, steps)
        check(f"an index-backed EQUALS on the {doc_id} value equals the scan",
              indexed == scanned == [doc_id], f"index={indexed!r} scan={scanned!r}")
        check(f"index-only COUNT agrees with the rows for the {doc_id} value", counted_rows == rows,
              f"count={counted_rows} rows={rows}")

    distinct_indexed, distinct_scanned = index_matches_scan(
        c, AGREE_SURROGATE, [{"type": "DISTINCT", "fieldName": "v"}],
        extract=lambda r: sorted(d.get("v") for d in (r.get("results") or [])))
    check("DISTINCT keeps every surrogate value distinct", distinct_indexed == distinct_scanned == sorted(
        values.values()), f"index={distinct_indexed!r} scan={distinct_scanned!r}")

    save_doc(c, AGREE_SURROGATE, {"_id": "hi", "v": "zzz"})
    wait_for_indexes(c, [(AGREE_SURROGATE, "v")])
    after = agree_ids(agg(c, AGREE_SURROGATE, [bu.filter_step("v", "EQUALS", "a?b")]))
    check("updating a surrogate-valued document leaves an unrelated document indexed",
          after == ["plain"], f"got {after!r}")


def probe_lone_surrogates_inside_containers_index_the_same_as_they_scan(c):
    values = {"hi": ["\ud800"], "hi2": ["\ud801"], "plain": ["?"]}
    for doc_id, value in values.items():
        save_doc(c, AGREE_SURROGATE_CONTAINER, {"_id": doc_id, "x": value})
    build_index(c, AGREE_SURROGATE_CONTAINER, "x")

    for doc_id, value in values.items():
        for operator, operand in (("EQUALS", value), ("NOT_EQUALS", value), ("NOT_IN", [value])):
            indexed, scanned = index_matches_scan(c, AGREE_SURROGATE_CONTAINER,
                                                  [bu.filter_step("x", operator, operand)])
            check(f"an index-backed {operator} on the {doc_id} array equals the scan", indexed == scanned,
                  f"index={indexed!r} scan={scanned!r}")
    check("NOT_EQUALS [\"?\"] keeps both lone-surrogate arrays",
          agree_ids(agg(c, AGREE_SURROGATE_CONTAINER, [bu.filter_step("x", "NOT_EQUALS", ["?"])])) == ["hi", "hi2"])


def probe_lone_surrogate_before_a_pair_orders_the_index_consistently(c):
    values = {"emoji": "\U0001f600", "fullwidth": "\uff21", "prefixed": "aa\uff21", "lone": "\ud83d\U0001f600"}
    for doc_id, value in values.items():
        save_doc(c, AGREE_SURROGATE_FOLD, {"_id": doc_id, "v": value})
    build_index(c, AGREE_SURROGATE_FOLD, "v")

    def compare_every_operator(label):
        for probe_id, probe_value in values.items():
            for operator, operand in (("EQUALS", probe_value), ("NOT_EQUALS", probe_value), ("IN", [probe_value])):
                indexed, scanned = index_matches_scan(c, AGREE_SURROGATE_FOLD,
                                                      [bu.filter_step("v", operator, operand)])
                check(f"{label}: an index-backed {operator} on the {probe_id} value equals the scan",
                      indexed == scanned, f"index={indexed!r} scan={scanned!r}")
            counted, scanned_count = index_matches_scan(c, AGREE_SURROGATE_FOLD,
                                                        [bu.filter_step("v", "EQUALS", probe_value), {"type": "COUNT"}],
                                                        extract=bu.count_of)
            check(f"{label}: index-only COUNT on the {probe_id} value equals the scan count",
                  counted == scanned_count, f"index={counted} scan={scanned_count}")

    compare_every_operator("after CREATE_INDEX")
    save_doc(c, AGREE_SURROGATE_FOLD, {"_id": "late", "v": "b\ud83d\ud83d"})
    wait_for_indexes(c, [(AGREE_SURROGATE_FOLD, "v")])
    compare_every_operator("after a save rewrote the index file")


def not_equals_filter(value):
    return [bu.filter_step("x", "NOT_EQUALS", value)]


def probe_not_equals_matches_every_other_kind(c):
    mixed = {"num": 5, "str": "a", "bool": True, "nul": None, "arr": [5], "obj": {"k": 5}}
    for doc_id, value in mixed.items():
        save_doc(c, AGREE_NOT_EQUALS_MIXED, {"_id": doc_id, "x": value})
    save_doc(c, AGREE_NOT_EQUALS_MIXED, {"_id": "missing"})
    every_holder = sorted(mixed)
    agree(c, "NOT_EQUALS a string on a mixed-kind field", AGREE_NOT_EQUALS_MIXED, not_equals_filter("b"),
          AGREE_NOT_EQUALS_MIXED, "x", expected=every_holder)

    for operand, excluded in ((5, "num"), (True, "bool"), ("A", "str")):
        expected = [doc_id for doc_id in every_holder if doc_id != excluded]
        steps = not_equals_filter(operand)
        indexed, scanned = index_matches_scan(c, AGREE_NOT_EQUALS_MIXED, steps)
        not_in = agree_ids(agg(c, AGREE_NOT_EQUALS_MIXED, [bu.filter_step("x", "NOT_IN", [operand])]))
        counted_rows = agg_count(c, AGREE_NOT_EQUALS_MIXED, steps)
        check(f"NOT_EQUALS {operand!r} on a mixed-kind field matches every other kind",
              indexed == scanned == expected, f"index={indexed!r} scan={scanned!r} expected={expected!r}")
        check(f"NOT_EQUALS {operand!r} agrees with NOT_IN [{operand!r}]", indexed == not_in,
              f"not_equals={indexed!r} not_in={not_in!r}")
        check(f"index-only COUNT agrees with the rows for NOT_EQUALS {operand!r}",
              counted_rows == len(expected), f"count={counted_rows} rows={len(expected)}")

    for doc_id, value in (("n1", 1), ("n2", 2), ("n3", 3)):
        save_doc(c, AGREE_NOT_EQUALS_HOMOGENEOUS, {"_id": doc_id, "x": value})
    agree(c, "NOT_EQUALS on a homogeneous, fully covered field", AGREE_NOT_EQUALS_HOMOGENEOUS,
          not_equals_filter(2), AGREE_NOT_EQUALS_HOMOGENEOUS, "x", expected=["n1", "n3"])


def probe_dotted_group_by_and_distinct_agree_with_scan(c):
    for coll in (AGREE_DOTTED_GROUP, AGREE_DOTTED_DISTINCT):
        for doc_id, value in (("p1", "x"), ("p2", "y"), ("p3", "x")):
            save_doc(c, coll, {"_id": doc_id, "a": {"b": value}})
    agree(c, "GROUP_BY on a dotted field emits nested keys", AGREE_DOTTED_GROUP,
          [{"type": "GROUP_BY", "fieldName": "a.b"}], AGREE_DOTTED_GROUP, "a.b",
          extract=agree_nested_keys("a.b"), expected=[("x", ["p1", "p3"], False), ("y", ["p2"], False)])
    agree(c, "DISTINCT on a dotted field emits nested keys", AGREE_DOTTED_DISTINCT,
          [{"type": "DISTINCT", "fieldName": "a.b"}], AGREE_DOTTED_DISTINCT, "a.b",
          extract=agree_nested_keys("a.b"), expected=[("x", [], False), ("y", [], False)])


def agreement_suite(c):
    section("Index / scan agreement: an index-backed answer must equal the full-scan answer")
    setup_agreement(c)
    probe_contains_agrees_with_scan_per_type(c)
    probe_not_in_agrees_with_scan(c)
    probe_join_agrees_with_scan_for_non_scalar_keys(c)
    probe_sort_agrees_with_scan(c)
    probe_sort_limit_is_deterministic(c)
    probe_geo_distance_agrees_with_scan_at_the_rim(c)
    probe_dropping_an_index_spares_a_sibling_field(c)
    probe_object_sort_keys_break_ties_on_id(c)
    probe_custom_values_bucket_the_same_either_way(c)
    probe_custom_sort_keys_order_by_the_type_comparator(c)
    probe_a_scalar_membership_operand_is_refused(c)
    probe_membership_uses_the_same_equality_as_equals(c)
    probe_array_equals_uses_the_same_equality_as_scalar_equals(c)
    probe_mixed_number_boxes_group_the_same_either_way(c)
    probe_lone_surrogate_values_index_the_same_as_they_scan(c)
    probe_lone_surrogates_inside_containers_index_the_same_as_they_scan(c)
    probe_lone_surrogate_before_a_pair_orders_the_index_consistently(c)
    probe_not_equals_matches_every_other_kind(c)
    probe_dotted_group_by_and_distinct_agree_with_scan(c)


REG_UNICODE = "idxagg_reg_unicode"
REG_DELIMITER = "idxagg_reg_delimiter"
REG_IDEMPOTENT = "idxagg_reg_idempotent"
REG_SINGLE = "idxagg_reg_single"
REG_CONJ = "idxagg_reg_conj"
REG_NUMERIC = "idxagg_reg_numeric"
REG_BULK = "idxagg_reg_bulk"
REG_CASE = "idxagg_reg_case"
REG_SORT = "idxagg_reg_sort"
REG_MIXED = "idxagg_reg_mixed"
REG_CUSTOM = "idxagg_reg_custom"
REG_NOID = "idxagg_reg_noid"
REG_IN = "idxagg_reg_in"
REG_NOTIN = "idxagg_reg_notin"
REG_MINVALUE = "idxagg_reg_minvalue"
REG_GEO_WRAP = "idxagg_reg_geowrap"
REG_GEO_NAN = "idxagg_reg_geonan"
REG_TIES = "idxagg_reg_ties"
REG_VALUELESS = "idxagg_reg_valueless"
REG_CAST = "idxagg_reg_cast"
REG_NULLFOLD = "idxagg_reg_nullfold"
REG_COMPLEMENT_KINDS = "idxagg_reg_complkinds"
REG_COMPLEMENT_NULL = "idxagg_reg_complnull"
REG_CONJ_ORDER = "idxagg_reg_conjorder"
REG_NOID_DUP = "idxagg_reg_noiddup"
REG_NEAREST_TIES = "idxagg_reg_nearestties"
REG_NEAREST_SCALE = "idxagg_reg_nearestscale"


def probe_non_ascii_indexed_values(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_UNICODE})
    for doc_id, city in (("u1", "café"), ("u2", "日本語"), ("u3", "plain"), ("u4", "a😀b")):
        save_doc(c, REG_UNICODE, {"_id": doc_id, "city": city})
    build_index(c, REG_UNICODE, "city")
    save_doc(c, REG_UNICODE, {"_id": "u3", "city": "日本語"})
    save_doc(c, REG_UNICODE, {"_id": "u5", "city": "café"})
    wait_for_background()

    for city, expected in (("café", ["u1", "u5"]), ("日本語", ["u2", "u3"]), ("a😀b", ["u4"])):
        got = filter_ids(c, REG_UNICODE, "city", city)
        check(f"non-ASCII indexed value {city!r} is queryable after an in-place index rewrite",
              got == expected, detail=f"expected {expected}, got {got}")
    check("re-pointed document no longer answers under its old non-ASCII value",
          "u3" not in filter_ids(c, REG_UNICODE, "city", "plain"))

    check_status("REINDEX the non-ASCII collection",
                 c.send({"type": "REINDEX", "databaseName": DB, "collectionName": REG_UNICODE}), "OK")
    wait_for_background()
    for city, expected in (("café", ["u1", "u5"]), ("日本語", ["u2", "u3"]), ("a😀b", ["u4"])):
        got = filter_ids(c, REG_UNICODE, "city", city)
        scanned = agg_ids(c, REG_UNICODE, forced_scan([bu.filter_step("city", "EQUALS", city)]))
        check(f"non-ASCII indexed value {city!r} survives a REINDEX and agrees with the scan",
              got == expected and scanned == expected, detail=f"expected {expected}, index={got}, scan={scanned}")


def probe_index_values_containing_delimiters(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_DELIMITER})
    values = {
        "d1": "line\nbreak",
        "d2": "carriage\rreturn",
        "d3": "unitseparator",
        "d4": "back\\slash",
        "d5": "plain",
    }
    for doc_id, note in values.items():
        save_doc(c, REG_DELIMITER, {"_id": doc_id, "note": note})

    scanned, indexed = scanned_then_indexed(
        c, [(REG_DELIMITER, "note")],
        [lambda expected=expected: filter_ids(c, REG_DELIMITER, "note", expected) for expected in values.values()], settle=True)
    for (doc_id, note), got in zip(values.items(), scanned):
        check(f"{doc_id}: unindexed scan finds {note!r}", got == [doc_id], detail=f"got {got}")
    for (doc_id, note), got in zip(values.items(), indexed):
        check(f"{doc_id}: an index value containing a delimiter stays queryable ({note!r})",
              got == [doc_id], detail=f"expected ['{doc_id}'], got {got}")


def probe_repeated_create_index_is_idempotent(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_IDEMPOTENT})
    for doc_id, status in (("i0", "active"), ("i1", "active"), ("i2", "archived")):
        save_doc(c, REG_IDEMPOTENT, {"_id": doc_id, "status": status})

    baseline_eq = filter_ids(c, REG_IDEMPOTENT, "status", "active")
    baseline_ne = filter_ids(c, REG_IDEMPOTENT, "status", "active", op="NOT_EQUALS")

    for attempt in range(3):
        build_index(c, REG_IDEMPOTENT, "status")
        wait_for_background()
        got_eq = filter_ids(c, REG_IDEMPOTENT, "status", "active")
        got_ne = filter_ids(c, REG_IDEMPOTENT, "status", "active", op="NOT_EQUALS")
        check(f"EQUALS is unchanged after CREATE_INDEX x{attempt + 1}", got_eq == baseline_eq,
              detail=f"expected {baseline_eq}, got {got_eq}")
        check(f"NOT_EQUALS is unchanged after CREATE_INDEX x{attempt + 1}", got_ne == baseline_ne,
              detail=f"expected {baseline_ne}, got {got_ne}")

    got = agg_count(c, REG_IDEMPOTENT, [bu.filter_step("status", "NOT_EQUALS", "active")])
    check("index-only COUNT is not inflated by repeated CREATE_INDEX", got == len(baseline_ne),
          detail=f"expected {len(baseline_ne)}, got {got}")


def probe_single_valued_index_ranges(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_SINGLE})
    for i in range(5):
        save_doc(c, REG_SINGLE, {"_id": f"s{i}", "score": 10})
    build_index(c, REG_SINGLE, "score")
    wait_for_background()

    everything = sorted(f"s{i}" for i in range(5))
    for op, value, expected in (("GREATER_THAN", 5, everything),
                                ("GREATER_THAN_EQUALS", 10, everything),
                                ("SMALLER_THAN", 20, everything),
                                ("SMALLER_THAN_EQUALS", 10, everything),
                                ("GREATER_THAN", 10, []),
                                ("SMALLER_THAN", 10, []),
                                ("GREATER_THAN_EQUALS", 15, []),
                                ("SMALLER_THAN_EQUALS", 5, [])):
        got = filter_ids(c, REG_SINGLE, "score", value, op)
        check(f"single-valued index answers {op} {value}", got == expected,
              detail=f"expected {expected}, got {got}")


def probe_conjunction_after_a_filter_step(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_CONJ})
    for doc_id, role, active in (("a", "admin", "yes"), ("b", "admin", "no"),
                                 ("c", "user", "yes"), ("ghost", "ghost", "yes")):
        save_doc(c, REG_CONJ, {"_id": doc_id, "role": role, "active": active})
    wait_for_background()

    leaves = [{"fieldOperatorType": "EQUALS", "field": "role", "value": "admin"},
              {"fieldOperatorType": "EQUALS", "field": "active", "value": "yes"}]
    for conj, expected in (("AND", ["a"]), ("OR", ["a", "b", "c"]), ("XOR", ["b", "c"]),
                           ("NAND", ["b", "c"]), ("NOR", [])):
        got = agg_ids(c, REG_CONJ, [bu.filter_step("role", "NOT_EQUALS", "ghost"),
                                    {"type": "FILTER", "operator": {"conjunctionType": conj, "operators": leaves}}])
        check(f"{conj} conjunction with two children runs on an already-filtered stream",
              got == expected, detail=f"expected {expected}, got {got}")

    got = agg_ids(c, REG_CONJ, [
        bu.filter_step("role", "NOT_EQUALS", "ghost"),
        {"type": "FILTER", "operator": {"conjunctionType": "AND", "operators": [
            {"conjunctionType": "OR", "operators": leaves},
            {"fieldOperatorType": "EQUALS", "field": "active", "value": "yes"}]}}])
    check("nested conjunction runs on an already-filtered stream", got == ["a", "c"],
          detail=f"expected ['a', 'c'], got {got}")


def probe_low_cardinality_numeric_index(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_NUMERIC})
    save_doc(c, REG_NUMERIC, {"_id": "seed", "bucket": 99, "big": 1, "ratio": 0.5})
    build_index(c, REG_NUMERIC, "bucket", "big", "ratio")

    for i in range(6):
        save_doc(c, REG_NUMERIC, {"_id": f"n{i}", "bucket": i % 2, "big": 10000000000, "ratio": 0.25})
    wait_for_background()

    for value, expected in ((0, ["n0", "n2", "n4"]), (1, ["n1", "n3", "n5"])):
        got = filter_ids(c, REG_NUMERIC, "bucket", value)
        check(f"every document written after CREATE_INDEX with bucket={value} stays in the numeric index",
              got == expected, detail=f"expected {expected}, got {got}")

    all_new = sorted(f"n{i}" for i in range(6))
    got = filter_ids(c, REG_NUMERIC, "big", 10000000000)
    check("an integral value above Integer.MAX_VALUE indexes every document sharing it",
          got == all_new, detail=f"expected {all_new}, got {got}")

    got = filter_ids(c, REG_NUMERIC, "ratio", 0.25)
    check("a fractional value indexes every document sharing it",
          got == all_new, detail=f"expected {all_new}, got {got}")

    with_seed = sorted(all_new + ["seed"])
    got = filter_ids(c, REG_NUMERIC, "bucket", 0, "GREATER_THAN_EQUALS")
    check("a range query over a low-cardinality numeric index sees every document",
          got == with_seed, detail=f"expected {with_seed}, got {got}")

    save_doc(c, REG_NUMERIC, {"_id": "n1", "bucket": 5, "big": 10000000000, "ratio": 0.25})
    wait_for_background()
    got = filter_ids(c, REG_NUMERIC, "bucket", 1)
    check("re-pointing one document leaves the other ids under the old numeric value",
          got == ["n3", "n5"], detail=f"expected ['n3', 'n5'], got {got}")
    check("the re-pointed document answers under its new numeric value",
          filter_ids(c, REG_NUMERIC, "bucket", 5) == ["n1"])


def probe_bulk_save_indexes_every_doc_sharing_a_value(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_BULK})
    save_doc(c, REG_BULK, {"_id": "seed", "status": "seeded"})
    build_index(c, REG_BULK, "status")
    wait_for_background()

    documents = [{"_id": f"b{i}", "status": "active" if i % 2 == 0 else "archived"} for i in range(5)]
    check_status("BULK_SAVE five documents over two status values",
                 c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": REG_BULK,
                         "objects": documents}), "OK")
    wait_for_background()

    for status, expected in (("active", ["b0", "b2", "b4"]), ("archived", ["b1", "b3"])):
        got = filter_ids(c, REG_BULK, "status", status)
        check(f"every bulk-saved doc with status={status} is still indexed", got == expected,
              detail=f"expected {expected}, got {got}")
        got_count = agg_count(c, REG_BULK, [bu.filter_step("status", "EQUALS", status)])
        check(f"the index-only COUNT for status={status} matches", got_count == len(expected),
              detail=f"expected {len(expected)}, got {got_count}")


def probe_case_variants_agree_between_index_and_scan(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_CASE})
    for doc_id, name in (("a", "Bob"), ("b", "bob"), ("c", "bob"), ("d", "carol")):
        save_doc(c, REG_CASE, {"_id": doc_id, "name": name})

    (scan_equals, scan_not_equals, scan_count), (got_equals, got_not_equals, got_count) = scanned_then_indexed(
        c, [(REG_CASE, "name")],
        [lambda: filter_ids(c, REG_CASE, "name", "bob"),
         lambda: filter_ids(c, REG_CASE, "name", "bob", op="NOT_EQUALS"),
         lambda: agg_count(c, REG_CASE, [bu.filter_step("name", "NOT_EQUALS", "bob")])], settle=True)

    check("indexed EQUALS returns every case variant", got_equals == scan_equals == ["a", "b", "c"],
          detail=f"scan={scan_equals} indexed={got_equals}")
    check("indexed NOT_EQUALS excludes every case variant", got_not_equals == scan_not_equals == ["d"],
          detail=f"scan={scan_not_equals} indexed={got_not_equals}")
    check("the index-only COUNT matches the scan for case variants", got_count == scan_count == 1,
          detail=f"scan={scan_count} indexed={got_count}")


def probe_sort_keeps_documents_without_the_field(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_SORT})
    total = 20
    for i in range(total):
        if i % 3 == 0:
            save_doc(c, REG_SORT, {"_id": f"s{i:03d}", "other": i})
        else:
            save_doc(c, REG_SORT, {"_id": f"s{i:03d}", "score": i})

    (scanned_ids,), (indexed_ids,) = scanned_then_indexed(
        c, [(REG_SORT, "score")],
        [lambda: agg_ids(c, REG_SORT, [{"type": "SORT", "fieldName": "score", "ascending": True}])], settle=True)

    check("an indexed SORT returns the whole collection, not only the documents that have the field",
          len(indexed_ids) == total, detail=f"expected {total}, got {len(indexed_ids)}")
    check("an indexed SORT returns exactly what the full scan returns", indexed_ids == scanned_ids,
          detail=f"scan={len(scanned_ids)} indexed={len(indexed_ids)}")


def probe_mixed_type_field_falls_back_to_scan(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_MIXED})
    save_doc(c, REG_MIXED, {"_id": "m1", "tags": "alpha"})
    save_doc(c, REG_MIXED, {"_id": "m2", "tags": ["alpha", "beta"]})
    save_doc(c, REG_MIXED, {"_id": "m3", "tags": "beta"})

    (scan_contains,), (indexed_contains,) = scanned_then_indexed(
        c, [(REG_MIXED, "tags")], [lambda: filter_ids(c, REG_MIXED, "tags", "alpha", op="CONTAINS")], settle=True)

    check("CONTAINS on a mixed-type field answers the same with and without the index",
          indexed_contains == scan_contains, detail=f"scan={scan_contains} indexed={indexed_contains}")
    check("CONTAINS still finds the array-valued document", "m2" in indexed_contains,
          detail=f"got {indexed_contains}")


def probe_numeric_in_agrees_between_index_and_scan(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_IN})
    for i, score in enumerate((1, 2, 3, 5, 9)):
        save_doc(c, REG_IN, {"_id": f"n{i}", "score": score})

    (scan_in, scan_mixed, scan_count), (indexed_in, indexed_mixed, indexed_count) = scanned_then_indexed(
        c, [(REG_IN, "score")],
        [lambda: filter_ids(c, REG_IN, "score", [2, 9], "IN"),
         lambda: filter_ids(c, REG_IN, "score", [5, 2.5], "IN"),
         lambda: agg_count(c, REG_IN, [bu.filter_step("score", "IN", [2, 9])])], settle=True)

    check("an indexed numeric IN returns what the scan returns",
          indexed_in == scan_in == ["n1", "n4"], detail=f"scan={scan_in} indexed={indexed_in}")
    check("an IN list mixing integral and fractional operands agrees too",
          indexed_mixed == scan_mixed, detail=f"scan={scan_mixed} indexed={indexed_mixed}")
    check("the index-only COUNT after a numeric IN matches the scan",
          indexed_count == scan_count == 2, detail=f"scan={scan_count} indexed={indexed_count}")


def probe_not_in_then_equals_on_a_scalar_only_field(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_NOTIN})
    for i, score in enumerate((1, 2, 2, 7)):
        save_doc(c, REG_NOTIN, {"_id": f"s{i}", "score": score})
    build_index(c, REG_NOTIN, "score")
    wait_for_background()

    not_in = filter_ids(c, REG_NOTIN, "score", [1], "NOT_IN")
    equals = filter_ids(c, REG_NOTIN, "score", 2)

    check("NOT_IN on a scalar-only indexed field answers", not_in == ["s1", "s2", "s3"],
          detail=f"got={not_in}")
    check("EQUALS still works after the NOT_IN probe", equals == ["s1", "s2"], detail=f"got={equals}")

    save_doc(c, REG_NOTIN, {"_id": "s4", "score": 2})
    wait_for_background()
    after = filter_ids(c, REG_NOTIN, "score", 2)
    check("a write after the NOT_IN probe does not drop the ids already on that line",
          after == ["s1", "s2", "s4"], detail=f"got={after}")


def probe_group_by_integer_min_value(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_MINVALUE})
    for i in range(3):
        save_doc(c, REG_MINVALUE, {"_id": f"m{i}", "n": -2147483648})

    (scanned,), (indexed,) = scanned_then_indexed(
        c, [(REG_MINVALUE, "n")], [lambda: agg(c, REG_MINVALUE, [{"type": "GROUP_BY", "fieldName": "n"}])],
        settle=True)

    check("an indexed GROUP_BY on Integer.MIN_VALUE emits one group, as the scan does",
          len(indexed.get("results") or []) == len(scanned.get("results") or []) == 1,
          detail=f"scan={scanned.get('results')} indexed={indexed.get('results')}")


def probe_geo_distance_across_the_antimeridian(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_GEO_WRAP})
    save_doc(c, REG_GEO_WRAP, {"_id": "east", "location": "#geo(0.000000,179.950000)"})
    save_doc(c, REG_GEO_WRAP, {"_id": "west", "location": "#geo(0.000000,-179.950000)"})
    save_doc(c, REG_GEO_WRAP, {"_id": "mid", "location": "#geo(0.000000,0.000000)"})

    (scanned,), (indexed,) = scanned_then_indexed(
        c, [(REG_GEO_WRAP, "location")],
        [lambda: agg_ids(c, REG_GEO_WRAP, geo_distance_steps("SMALLER_THAN", 50000,
                                                             target="#geo(0.000000,179.990000)"))], settle=True)

    check("a geo radius spanning the antimeridian returns both sides", scanned == ["east", "west"],
          detail=f"scan={scanned}")
    check("the indexed geo radius returns what the scan returns", indexed == scanned,
          detail=f"scan={scanned} indexed={indexed}")


def probe_a_non_finite_geo_operand_is_refused(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_GEO_NAN})
    save_doc(c, REG_GEO_NAN, {"_id": "inside", "location": "#geo(2.000000,5.000000)"})
    nan_polygon = ["#geo(0,0)", "#geo(10,0)", "#geo(NaN,NaN)"]
    nan_target = geo_distance_steps("SMALLER_THAN", 50000, target="#geo(NaN,0)")

    def _probe(label):
        check_code(f"a within polygon with a NaN vertex is refused ({label})",
                   agg(c, REG_GEO_NAN, geo_within_steps(nan_polygon)), "ERROR", "400-1")
        check_code(f"a distance target holding NaN is refused ({label})",
                   agg(c, REG_GEO_NAN, nan_target), "ERROR", "400-1")

    _probe("scan")
    build_index(c, REG_GEO_NAN, "location")
    wait_for_background()
    _probe("indexed")


def probe_sort_ties_do_not_depend_on_the_index(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_TIES})
    for i in range(10):
        save_doc(c, REG_TIES, {"_id": f"p{i:02d}", "score": 5})

    def _page():
        return [d.get("_id") for d in (agg(c, REG_TIES, [
            {"type": "SORT", "fieldName": "score", "ascending": True},
            {"type": "SKIP", "skip": 3},
            {"type": "LIMIT", "limit": 3}]).get("results") or [])]

    (scanned,), (indexed,) = scanned_then_indexed(c, [(REG_TIES, "score")], [_page], settle=True)

    check("a SORT + SKIP + LIMIT page does not change when the field gains an index",
          indexed == scanned, detail=f"scan={scanned} indexed={indexed}")


REG_CUSTOM_WHEN = "#datetime(2024-01-01T10:00:00)"
REG_CUSTOM_WHERE = "#geo(40.0,-74.0)"


def probe_a_custom_filter_survives_a_map_step(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_CUSTOM})
    save_doc(c, REG_CUSTOM, {"_id": "cu1", "when": REG_CUSTOM_WHEN, "where": REG_CUSTOM_WHERE, "note": "keep"})
    save_doc(c, REG_CUSTOM, {"_id": "cu2", "when": "#datetime(2025-06-01T08:30:00)",
                             "where": "#geo(34.05,-118.24)", "note": "keep"})
    wait_for_background()

    when_filter = {"type": "FILTER",
                   "operator": {"fieldOperatorType": "EQUALS", "field": "when", "value": REG_CUSTOM_WHEN}}
    geo_filter = {"type": "FILTER", "operator": {"customOperatorName": "distance", "field": "where",
                                                 "value": REG_CUSTOM_WHERE, "comparator": "SMALLER_THAN",
                                                 "distance": 1000}}
    passthrough_map = {"type": "MAP", "operators": [{"fieldName": "absent"}]}

    for label, step in (("datetime EQUALS", when_filter), ("geo distance", geo_filter)):
        direct = agg_ids(c, REG_CUSTOM, [step])
        after_map = agg_ids(c, REG_CUSTOM, [passthrough_map, step])
        check(f"a {label} filter answers the same before and after a MAP step",
              direct == after_map == ["cu1"], detail=f"direct={direct}, after MAP={after_map}")

    distinct_all = agg(c, REG_CUSTOM, [{"type": "DISTINCT"}])
    values = sorted(d.get("when") for d in (distinct_all.get("results") or []))
    check("a DISTINCT step keeps custom values in their wire form",
          values == sorted([REG_CUSTOM_WHEN, "#datetime(2025-06-01T08:30:00)"]),
          detail=f"got {values}")


def probe_a_conjunction_after_a_row_reshaping_step(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_NOID})
    for doc_id, category in (("n1", "books"), ("n2", "music"), ("n3", "books")):
        save_doc(c, REG_NOID, {"_id": doc_id, "category": category})
    wait_for_background()

    def conjunction(conjunction_type):
        return {"type": "FILTER", "operator": {"conjunctionType": conjunction_type, "operators": [
            {"fieldOperatorType": "EQUALS", "field": "category", "value": "books"},
            {"fieldOperatorType": "NOT_EQUALS", "field": "category", "value": "music"}]}}

    for reshaper, label in (({"type": "DISTINCT", "fieldName": "category"}, "DISTINCT"),
                            ({"type": "GROUP_BY", "fieldName": "category"}, "GROUP_BY")):
        for kind in ("AND", "XOR", "NAND", "NOR", "OR"):
            r = agg(c, REG_NOID, [reshaper, conjunction(kind)])
            check(f"a {kind} conjunction after {label} answers instead of erroring",
                  r.get("status") == "OK" or r.get("errorCode") == "404-3",
                  detail=f"status={r.get('status')} code={r.get('errorCode')} msg={r.get('message', '')!r}")

        and_rows = agg(c, REG_NOID, [reshaper, conjunction("AND")]).get("results") or []
        check(f"a AND conjunction after {label} keeps only the matching row",
              [row.get("category") for row in and_rows] == ["books"],
              detail=f"got {[row.get('category') for row in and_rows]}")


def probe_a_sorted_conjunction_keeps_its_order(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_CONJ_ORDER})
    rows = (("o1", 9, "admin", "yes"), ("o2", 3, "user", "yes"), ("o3", 7, "admin", "no"),
            ("o4", 1, "admin", "yes"), ("o5", 5, "user", "no"), ("o6", 8, "user", "yes"))
    for doc_id, score, role, active in rows:
        save_doc(c, REG_CONJ_ORDER, {"_id": doc_id, "score": score, "role": role, "active": active})
    wait_for_background()

    def conjunction(conjunction_type):
        return {"type": "FILTER", "operator": {"conjunctionType": conjunction_type, "operators": [
            {"fieldOperatorType": "EQUALS", "field": "role", "value": "admin"},
            {"fieldOperatorType": "EQUALS", "field": "active", "value": "yes"}]}}

    sort = {"type": "SORT", "fieldName": "score", "ascending": True}
    limit = {"type": "LIMIT", "limit": 3}
    for indexed in (False, True):
        if indexed:
            build_index(c, REG_CONJ_ORDER, "score", "role")
        label = "indexed" if indexed else "scan"
        for kind in ("AND", "OR", "XOR", "NOR", "NAND"):
            sorted_first = agree_ordered_ids(agg(c, REG_CONJ_ORDER, [sort, conjunction(kind), limit]))
            filtered_first = agree_ordered_ids(agg(c, REG_CONJ_ORDER, [conjunction(kind), sort, limit]))
            check(f"SORT then {kind} keeps the sort order ({label})", sorted_first == filtered_first,
                  detail=f"SORT first={sorted_first}  FILTER first={filtered_first}")


def probe_a_conjunction_keeps_equal_rows_without_an_id(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_NOID_DUP})
    for doc_id, extra in (("d1", "a"), ("d2", "b"), ("d3", "c")):
        save_doc(c, REG_NOID_DUP, {"_id": doc_id, "category": "books", "extra": extra})
    wait_for_background()
    reshape = {"type": "MAP", "operators": [{"fieldName": "_id"}, {"fieldName": "extra"}]}
    books = {"fieldOperatorType": "EQUALS", "field": "category", "value": "books"}
    plain_count = agg_count(c, REG_NOID_DUP, [reshape, {"type": "FILTER", "operator": books}])
    for kind in ("AND", "OR"):
        count = agg_count(c, REG_NOID_DUP, [reshape, {"type": "FILTER", "operator": {"conjunctionType": kind,
                                                                                     "operators": [books]}}])
        check(f"a single-operand {kind} counts the same equal rows a plain FILTER does",
              count == plain_count == 3, detail=f"FILTER={plain_count}  {kind}={count}")


def probe_nearest_ties_are_broken_on_id(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_NEAREST_TIES})
    for doc_id, vector in (("t3", "#vector(3.0,0.0)"), ("t1", "#vector(1.0,0.0)"), ("t2", "#vector(2.0,0.0)"),
                           ("t9", "#vector(0.0,1.0)")):
        save_doc(c, REG_NEAREST_TIES, {"_id": doc_id, "embedding": vector})
    wait_for_background()

    def nearest(k):
        return [{"type": "FILTER", "operator": {"customOperatorName": "nearest", "field": "embedding",
                                                "value": "#vector(1.0,0.0)", "k": k, "exact": True}}]

    picks = {tuple(agree_ordered_ids(agg(c, REG_NEAREST_TIES, nearest(1)))) for _ in range(5)}
    check("nearest k=1 over equally-scored documents picks the smallest _id", picks == {("t1",)},
          detail=f"picks={sorted(picks)}")
    check("nearest lists equally-scored documents in _id order",
          agree_ordered_ids(agg(c, REG_NEAREST_TIES, nearest(3))) == ["t1", "t2", "t3"],
          detail=f"got {agree_ordered_ids(agg(c, REG_NEAREST_TIES, nearest(3)))}")


def probe_nearest_scores_huge_and_tiny_vectors(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_NEAREST_SCALE})
    for doc_id, vector in (("huge", "#vector(1e200,1e200)"), ("tiny", "#vector(1e-200,1e-200)"),
                           ("axis", "#vector(1.0,0.0)")):
        save_doc(c, REG_NEAREST_SCALE, {"_id": doc_id, "embedding": vector})
    wait_for_background()

    def nearest(exact_scan):
        return [{"type": "FILTER", "operator": {"customOperatorName": "nearest", "field": "embedding",
                                                "value": "#vector(1.0,1.0)", "k": 2, "exact": exact_scan}}]

    exact = agree_ids(agg(c, REG_NEAREST_SCALE, nearest(True)))
    check("exact nearest scores vectors whose squared norm overflows or underflows by their true cosine",
          exact == ["huge", "tiny"], detail=f"got {exact}")
    build_index(c, REG_NEAREST_SCALE, "embedding")
    indexed = agree_ids(agg(c, REG_NEAREST_SCALE, nearest(False)))
    check("index-backed nearest agrees with the exact scan on huge and tiny vectors",
          indexed == exact, detail=f"exact={exact}  indexed={indexed}")


def probe_a_field_operator_without_a_value_is_refused(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_VALUELESS})
    save_doc(c, REG_VALUELESS, {"_id": "v1", "name": "alice"})
    wait_for_background()

    def valueless(field, operator):
        return agg(c, REG_VALUELESS,
                   [{"type": "FILTER", "operator": {"fieldOperatorType": operator, "field": field}}])

    for indexed in (False, True):
        if indexed:
            c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_VALUELESS,
                    "fieldName": "name"})
            wait_for_background()
        label = "indexed" if indexed else "unindexed"
        for operator_type in ("EQUALS", "NOT_EQUALS", "CONTAINS", "GREATER_THAN", "SMALLER_THAN_EQUALS"):
            check_code(f"[{label}] a valueless {operator_type} on an ordinary field is a validation error",
                       valueless("name", operator_type), "ERROR", "400-1")
            check_code(f"[{label}] a valueless {operator_type} on _id is a validation error",
                       valueless("_id", operator_type), "ERROR", "400-1")

    check_status("an explicit null operand is still accepted",
                 agg(c, REG_VALUELESS, [{"type": "FILTER", "operator": {
                     "fieldOperatorType": "NOT_EQUALS", "field": "name", "value": None}}]), "OK")


def probe_a_trailing_dot_cannot_alias_the_primary_key(c):
    save_doc(c, REG_VALUELESS, {"_id": "alias-abc", "name": "x"})
    save_doc(c, REG_VALUELESS, {"_id": "ALIAS-ABC", "name": "y"})
    wait_for_background()

    def rows(field, operator_type="EQUALS"):
        return agg(c, REG_VALUELESS, [{"type": "FILTER", "operator": {
            "fieldOperatorType": operator_type, "field": field, "value": "alias-abc"}}])

    check("an EQUALS on _id is case-sensitive", bu.ids_of(rows("_id")) == ["alias-abc"])
    for alias in ("_id.", "_id..", "name."):
        check_code(f"a FILTER on '{alias}' is a validation error", rows(alias), "ERROR", "400-1")
    for alias in ("_id.", "_id.."):
        check_code(f"a CREATE_INDEX on '{alias}' is refused",
                   c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_VALUELESS,
                           "fieldName": alias}), "ERROR", "400-1")


def cast_steps(field, to_type):
    return [{"type": "MAP", "operators": [
        {"fieldName": field, "operator": {"type": "CAST", "fieldName": "raw", "toType": to_type}}]}]


CAST_CASES = {
    "NUMBER": ("n", {"inf": ("Infinity", None), "nan": ("NaN", None), "hex": ("0x1p3", None),
                     "exp": ("1e3", 1000)}),
    "BOOLEAN": ("b", {"btrue": ("true", True), "bfalse": ("FALSE", False), "bjunk": ("banana", None)}),
}


def check_casts(c, to_type):
    field, cases = CAST_CASES[to_type]
    for doc_id, (raw, _) in cases.items():
        save_doc(c, REG_CAST, {"_id": doc_id, "raw": raw})
    wait_for_background()
    rows = {row["_id"]: row.get(field) for row in (agg(c, REG_CAST, cast_steps(field, to_type)).get("results") or [])}
    for doc_id, (raw, expected) in cases.items():
        check(f"CAST {raw!r} to {to_type} answers {expected!r}", rows.get(doc_id) == expected,
              detail=f"got {rows.get(doc_id)!r}")


def probe_cast_keeps_the_value_boundary(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_CAST})
    check_casts(c, "NUMBER")
    selected_ids = agg_ids(c, REG_CAST, cast_steps("n", "NUMBER") + [bu.filter_step("n", "GREATER_THAN", 100)])
    check("a numeric filter after the cast selects only the row that really is a number",
          selected_ids == ["exp"], detail=f"got {selected_ids}")


def probe_cast_to_boolean_answers_null_for_an_unparseable_string(c):
    check_casts(c, "BOOLEAN")


AVG_FOLD_STEPS = [{"type": "MAP", "operators": [
    {"fieldName": "derived", "operator": {"type": "AVG", "operands": ["price"]}}]}]


def probe_a_map_null_result_is_a_real_json_null(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_NULLFOLD})
    save_doc(c, REG_NULLFOLD, {"_id": "priced", "price": 10})
    save_doc(c, REG_NULLFOLD, {"_id": "unpriced", "label": "no price here"})
    wait_for_background()

    rows = {row["_id"]: row for row in (agg(c, REG_NULLFOLD, AVG_FOLD_STEPS).get("results") or [])}
    check("a fold with no valid operand answers null",
          "derived" in rows.get("unpriced", {}) and rows.get("unpriced", {}).get("derived") is None,
          detail=f"got {rows.get('unpriced')!r}")

    ordered = agg(c, REG_NULLFOLD, AVG_FOLD_STEPS + [{"type": "SORT", "fieldName": "derived", "ascending": True}])
    check("a SORT after that MAP is not a server error", ordered.get("status") == "OK",
          detail=f"got {ordered.get('status')!r} {ordered.get('message')!r}")

    selected_ids = agg_ids(c, REG_NULLFOLD, AVG_FOLD_STEPS + [bu.filter_step("derived", "GREATER_THAN", 0)])
    check("a numeric filter after that MAP keeps only the row that really is a number",
          selected_ids == ["priced"], detail=f"got {selected_ids}")

    null_ids = agg_ids(c, REG_NULLFOLD, AVG_FOLD_STEPS + [bu.filter_step("derived", "EQUALS", None)])
    check("EQUALS null matches the row the same response showed as null",
          null_ids == ["unpriced"], detail=f"got {null_ids}")


def probe_indexing_a_field_written_as_null_is_consistent(c):
    save_doc(c, REG_NULLFOLD, {"_id": "explicit", "price": None})
    wait_for_background()

    before = filter_ids(c, REG_NULLFOLD, "price", 10)
    created = c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_NULLFOLD,
                      "fieldName": "price"})
    check("an index builds over a collection holding a null value", created.get("status") == "OK",
          detail=f"got {created.get('status')!r} {created.get('message')!r}")
    wait_for_background()

    check("the indexed answer agrees with the scan", filter_ids(c, REG_NULLFOLD, "price", 10) == before,
          detail=f"scan={before} index={filter_ids(c, REG_NULLFOLD, 'price', 10)}")
    is_null = filter_ids(c, REG_NULLFOLD, "price", None)
    check("EQUALS null still names only the null-valued document", is_null == ["explicit"],
          detail=f"got {is_null}")


def _complement_answers(c, coll, field, op, value):
    return filter_ids(c, coll, field, value, op), agg_count(c, coll, [bu.filter_step(field, op, value)])


def probe_complements_agree_between_index_and_scan(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_COMPLEMENT_KINDS})
    save_doc(c, REG_COMPLEMENT_KINDS, {"_id": "obj", "x": {"a": 1}})
    save_doc(c, REG_COMPLEMENT_KINDS, {"_id": "arr", "x": [1, 2, 3]})
    save_doc(c, REG_COMPLEMENT_KINDS, {"_id": "str", "x": "hello"})
    save_doc(c, REG_COMPLEMENT_KINDS, {"_id": "nul", "x": None})
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_COMPLEMENT_NULL})
    save_doc(c, REG_COMPLEMENT_NULL, {"_id": "a", "x": "a"})
    save_doc(c, REG_COMPLEMENT_NULL, {"_id": "b", "x": "b"})
    save_doc(c, REG_COMPLEMENT_NULL, {"_id": "n", "x": None})

    queries = (
        (REG_COMPLEMENT_KINDS, "NOT_EQUALS", {"a": 1}),
        (REG_COMPLEMENT_KINDS, "NOT_EQUALS", [1, 2, 3]),
        (REG_COMPLEMENT_KINDS, "NOT_IN", [{"a": 1}]),
        (REG_COMPLEMENT_NULL, "NOT_IN", ["a"]),
    )
    scanned = [_complement_answers(c, coll, "x", op, value) for coll, op, value in queries]

    build_indexes(c, [(REG_COMPLEMENT_KINDS, "x"), (REG_COMPLEMENT_NULL, "x")])
    wait_for_background()

    check("NOT_EQUALS against an object matches every other kind of value, null included",
          scanned[0][0] == ["arr", "nul", "str"], detail=f"scan={scanned[0][0]}")
    check("NOT_IN keeps an explicit null on a single-type field", scanned[3][0] == ["b", "n"],
          detail=f"scan={scanned[3][0]}")
    for (coll, op, value), before in zip(queries, scanned):
        after = _complement_answers(c, coll, "x", op, value)
        check(f"an indexed {op} {value!r} returns what the scan returns, rows and COUNT alike", after == before,
              detail=f"scan={before} indexed={after}")


def regression_suite(c):
    section("Correctness regressions: non-ASCII index values, index values containing the file's own "
            "delimiters, repeated CREATE_INDEX, single-valued index ranges, low-cardinality numeric "
            "indexes, conjunctions over a filtered stream, custom values across a MAP step, "
            "conjunctions over rows with no _id, valueless field operands, the MAP CAST value boundary, "
            "one spelling of JSON null across a MAP step and an index build, custom-typed SORT order, "
            "complement operators over mixed kinds and explicit nulls")
    probe_non_ascii_indexed_values(c)
    probe_index_values_containing_delimiters(c)
    probe_repeated_create_index_is_idempotent(c)
    probe_single_valued_index_ranges(c)
    probe_low_cardinality_numeric_index(c)
    probe_conjunction_after_a_filter_step(c)
    probe_bulk_save_indexes_every_doc_sharing_a_value(c)
    probe_case_variants_agree_between_index_and_scan(c)
    probe_sort_keeps_documents_without_the_field(c)
    probe_mixed_type_field_falls_back_to_scan(c)
    probe_numeric_in_agrees_between_index_and_scan(c)
    probe_not_in_then_equals_on_a_scalar_only_field(c)
    probe_group_by_integer_min_value(c)
    probe_geo_distance_across_the_antimeridian(c)
    probe_a_non_finite_geo_operand_is_refused(c)
    probe_sort_ties_do_not_depend_on_the_index(c)
    probe_a_custom_filter_survives_a_map_step(c)
    probe_a_conjunction_after_a_row_reshaping_step(c)
    probe_a_field_operator_without_a_value_is_refused(c)
    probe_a_trailing_dot_cannot_alias_the_primary_key(c)
    probe_cast_keeps_the_value_boundary(c)
    probe_cast_to_boolean_answers_null_for_an_unparseable_string(c)
    probe_a_map_null_result_is_a_real_json_null(c)
    probe_indexing_a_field_written_as_null_is_consistent(c)
    probe_complements_agree_between_index_and_scan(c)
    probe_a_sorted_conjunction_keeps_its_order(c)
    probe_a_conjunction_keeps_equal_rows_without_an_id(c)
    probe_nearest_ties_are_broken_on_id(c)
    probe_nearest_scores_huge_and_tiny_vectors(c)


def limits_suite(c):
    section("Request limits on custom literals, filter operands and MAP results")
    over = "#vector(" + ",".join(["0.5"] * 16385) + ")"
    refused = c.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                      "object": {"_id": "vec-over", "embedding": over}})
    check("A vector past maxVectorDimensions is not a valid command",
          refused.get("status") == "ERROR" and refused.get("message") == "The command is not valid",
          f"got={str(refused)[:200]}")
    check_status("It was never stored",
                 c.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": "vec-over"}),
                 "NOT_FOUND")
    check_code("IN with more than maxFilterOperands values -> 400-27",
               agg(c, COLL, [{"type": "FILTER", "operator": {"fieldOperatorType": "IN", "field": "category",
                                                             "value": list(range(10001))}}]), "ERROR", "400-27")
    check_status("IN at maxFilterOperands values still answers",
                 agg(c, COLL, [{"type": "FILTER", "operator": {"fieldOperatorType": "IN", "field": "category",
                                                               "value": list(range(10000))}}, {"type": "COUNT"}]),
                 "OK")
    operators = [{"fieldName": "g0", "operator": {"type": "CONCAT", "operands": ["-AAAAAAAA", "-AAAAAAAA"]}}]
    operators += [{"fieldName": f"g{i}", "operator": {"type": "CONCAT", "operands": [f"g{i - 1}", f"g{i - 1}"]}}
                  for i in range(1, 31)]
    check_code("A doubling CONCAT chain stops at the maximum entry size -> 400-28",
               agg(c, COLL, [{"type": "LIMIT", "limit": 1}, {"type": "MAP", "operators": operators}]),
               "ERROR", "400-28")
    counted = agg(c, COLL, [{"type": "MAP", "operators": operators}, {"type": "COUNT"}])
    scanned = agg(c, COLL, forced_scan([{"type": "MAP", "operators": operators}, {"type": "COUNT"}]))
    check_code("[MAP doubling-CONCAT, COUNT] is refused like its scan, not counted from the index", counted,
               "ERROR", "400-28")
    check("and its forced-scan twin answers the same", scanned.get("errorCode") == counted.get("errorCode"),
          f"index={counted.get('errorCode')} scan={scanned.get('errorCode')}")
    check_status("A geo point written with a trailing comma is accepted",
                 c.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                         "object": {"_id": "geo-trailing", "location": "#geo(40.0,-74.0,)"}}), "OK")
    check_status("and stays readable", c.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL,
                                               "_id": "geo-trailing"}), "OK")
    c.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "geo-trailing"})


def main():
    bu.banner("index-backed aggregation performance test suite", HOST, PORT)
    print(f"  Plan: load {NUM_DOCS} docs into {COLL} ({NUM_CATEGORIES} categories, each with an object "
          f"meta + array tags), {LEFT_DOCS} left + {NUM_DOCS} right docs for JOIN.")
    print(f"        Run JOIN/SORT/DISTINCT and object/array element-match FILTER unindexed, then create indexes "
          f"and time each against a scan baseline (the query behind a SKIP 0, or for JOIN the same join on an "
          f"unindexed copy of the key), alternating which goes first.")

    with Conn() as c:
        r = c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            sys.exit(1)
        teardown_fixtures(c)
        print("\nLoading fixtures (this can take a moment)...")
        setup_fixtures(c)

    cases = [
        ("DISTINCT on category", COLL, DISTINCT_STEPS, forced_scan(DISTINCT_STEPS), distinct_signature, True),
        ("SORT on score (+LIMIT 10)", COLL, SORT_STEPS, forced_scan(SORT_STEPS), sort_signature, False),
        ("JOIN against a large remote collection", JOIN_LEFT, JOIN_STEPS, JOIN_SCAN_STEPS, join_signature, True),
        ("FILTER element-match on object field", COLL, FILTER_OBJECT_STEPS, forced_scan(FILTER_OBJECT_STEPS),
         bu.ids_of, True),
        ("FILTER element-match on array field", COLL, FILTER_ARRAY_STEPS, forced_scan(FILTER_ARRAY_STEPS),
         bu.ids_of, True),
        ("FILTER IN over a list of objects", COLL, FILTER_OBJECT_IN_STEPS, forced_scan(FILTER_OBJECT_IN_STEPS),
         bu.ids_of, True),
        ("FILTER geo distance (within radius)", COLL, FILTER_GEO_DISTANCE_STEPS,
         forced_scan(FILTER_GEO_DISTANCE_STEPS), bu.ids_of, False),
        ("FILTER geo within polygon", COLL, FILTER_GEO_WITHIN_STEPS, forced_scan(FILTER_GEO_WITHIN_STEPS),
         bu.ids_of, False),
    ]

    unindexed = {}
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        for name, coll, steps, _scan_steps, _sig, _strict in cases:
            resp = agg(c, coll, steps)
            check_status(f"[unindexed] {name} (OK)", resp, "OK")
            unindexed[name] = resp

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        print("\nCreating indexes and waiting for the background build...")
        create_indexes(c)

    indexed = {}
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        for name, coll, steps, scan_steps, _sig, _strict in cases:
            check(f"[indexed] {name} uses an index", index_used(c, coll, steps))
            check(f"[indexed] {name} scan baseline uses none", not index_used(c, coll, scan_steps))
            scan_time, index_time, resp = timed_scan_and_index(c, coll, steps, scan_steps)
            check_status(f"[indexed] {name} (OK)", resp, "OK")
            indexed[name] = (scan_time, index_time, resp)

    for name, coll, steps, _scan_steps, sig, strict in cases:
        scan_time, index_time, i_resp = indexed[name]
        compare(name, scan_time, unindexed[name], index_time, i_resp, sig, strict)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        consistency_suite(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        geo_suite(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        regression_suite(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        agreement_suite(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        limits_suite(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown_fixtures(c)

    bu.summary()


if __name__ == "__main__":
    main()
