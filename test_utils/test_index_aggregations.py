import os
import sys
import threading
import time

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

HOST = os.environ.get("INDEX_TEST_HOST", "127.0.0.1")
# Overridable so CI can point this suite at a dedicated caching-disabled server
# on its own port (so index gains are visible) without disturbing the shared server.
PORT = int(os.environ.get("INDEX_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

# Knobs — tune NUM_DOCS (and maxMemory in lwnrdb.cfg) to make the scan paths expensive enough
# that the index fast-paths show a clear win. CI runners are slower, so everything is overridable.
DB = "idxagg_db"
COLL = "idxagg_main"            # category (low-cardinality) + score (unique) -> DISTINCT/GROUP_BY/SORT
JOIN_LEFT = "idxagg_left"       # small left side for JOIN; every row shares the same join key
JOIN_BIG = "idxagg_big"         # large right side; only one row matches the shared key

NUM_DOCS = int(os.environ.get("INDEX_TEST_DOCS", "8000"))
NUM_CATEGORIES = int(os.environ.get("INDEX_TEST_CATEGORIES", "20"))
LEFT_DOCS = int(os.environ.get("INDEX_TEST_LEFT_DOCS", "100"))
PAYLOAD_BYTES = int(os.environ.get("INDEX_TEST_PAYLOAD_BYTES", "256"))
BULK_BATCH_SIZE = int(os.environ.get("INDEX_TEST_BATCH_SIZE", "500"))
REPEATS = int(os.environ.get("INDEX_TEST_REPEATS", "5"))
# SORT reads the same documents either way, so its win is CPU/structure rather than IO;
# allow a small tolerance there. DISTINCT/JOIN have a clear algorithmic win and must be faster.
# GROUP_BY is intentionally not in the perf suite: it must read every grouped document regardless
# of the index, so on a dense field there is no win to assert (only sparse fields benefit). Its
# correctness under async index maintenance is still covered by probe_group_by in the consistency suite.
SPEED_TOLERANCE = float(os.environ.get("INDEX_TEST_SPEED_TOLERANCE", "1.25"))

# Consistency suite (separate from the perf suite): index maintenance is asynchronous, so right after
# a write the field index may not yet reflect it. These probes write then *immediately* query the
# index-backed path (no sleep) and assert the answer is correct, repeating to land inside the
# committed-but-not-yet-indexed window. Each iteration must be correct whether or not the background
# has caught up, so a single stale/missing/duplicate result fails the probe.
CONS = "idxagg_cons"            # status-indexed: FILTER, COUNT-with-filter, DISTINCT, GROUP_BY
CONS_SORT = "idxagg_cons_sort"  # score-indexed: SORT
CONS_COUNT = "idxagg_cons_count"  # no index: whole-collection COUNT (from PK index size)
CONS_JOIN = "idxagg_cons_join"  # joinKey-indexed remote side
CONS_LEFT = "idxagg_cons_left"  # left side of the JOIN (one shared-key row)
CONS_CREATE = "idxagg_cons_create"  # built mid-flight: CREATE_INDEX racing concurrent saves
CONS_REPEATS = int(os.environ.get("INDEX_TEST_CONSISTENCY_REPEATS", "30"))

# Geo suite: the main collection also carries a "location" #geo field so the geohash spatial index
# can prune candidate reads. GEO_CLUSTER documents sit in a tight cluster around GEO_TARGET; the rest
# are scattered far away (southern hemisphere), so a small-radius distance / small polygon selects
# exactly the cluster — a clear win for the index over the full scan.
GEO = "idxagg_geo"              # small, hand-placed points with a location index (exact assertions)
GEO_SCAN = "idxagg_geo_scan"    # same points, no index (indexed == scan correctness cross-check)
GEO_TARGET = "#geo(40.0,-74.0)"
GEO_CLUSTER = int(os.environ.get("INDEX_TEST_GEO_CLUSTER", "50"))
# A box comfortably containing the whole main-collection cluster (used by the perf within case).
GEO_CLUSTER_POLYGON = ["#geo(39.99,-74.02)", "#geo(39.99,-73.98)", "#geo(40.02,-73.98)", "#geo(40.02,-74.02)"]

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)



def stats(c) -> dict:
    return c.send({"type": "GET_DATABASE_STATS"})


def agg(c, coll, steps) -> dict:
    return c.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                       "aggregationSteps": steps})


def timed_agg(c, coll, steps):
    """Run an aggregation REPEATS times and return (best_seconds, last_response)."""
    best = None
    last = None
    for _ in range(REPEATS):
        t0 = time.perf_counter()
        last = agg(c, coll, steps)
        dt = time.perf_counter() - t0
        best = dt if best is None else min(best, dt)
    return best, last


def rand_payload(size: int) -> str:
    return "x" * size


def category_for(v: int) -> str:
    return f"cat_{v % NUM_CATEGORIES}"


def location_for(v: int) -> str:
    """A #geo location per doc: the first GEO_CLUSTER docs form a tight cluster around GEO_TARGET
    (40, -74); the rest are scattered across the southern hemisphere, far from the target."""
    if v < GEO_CLUSTER:
        return f"#geo({40.0 + (v % 10) * 0.001:.6f},{-74.0 + (v // 10) * 0.001:.6f})"
    lat = -(((v * 37) % 700) / 10.0) - 10.0   # -10 .. -80 (never near the +40 target)
    lng = (((v * 53) % 3600) / 10.0) - 180.0  # -180 .. 180
    return f"#geo({lat:.6f},{lng:.6f})"


def geo_distance_steps(comparator: str, distance: float, target: str = GEO_TARGET, field: str = "location"):
    return [{"type": "FILTER", "operator": {"customOperatorName": "distance", "field": field,
                                            "value": target, "comparator": comparator, "distance": distance}}]


def geo_within_steps(polygon, field: str = "location"):
    return [{"type": "FILTER", "operator": {"customOperatorName": "within", "field": field, "polygon": polygon}}]


# ── fixtures ───────────────────────────────────────────────────────────────

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

    # Main collection: category is low-cardinality (good for GROUP_BY/DISTINCT), score is unique (SORT).
    # meta (object) and tags (array) are unique per doc so an element-match FILTER hits a single row,
    # giving the hashed object/array index a clear win over the full scan.
    bulk_load(c, COLL, ({
        "_id": f"doc_{v:06d}",
        "category": category_for(v),
        "score": v,
        "meta": {"n": v, "category": category_for(v)},
        "tags": ["t", str(v)],
        "location": location_for(v),
        "payload": rand_payload(PAYLOAD_BYTES),
    } for v in range(NUM_DOCS)))

    # JOIN: small left side, every row shares the same key; large right side where only one row matches.
    bulk_load(c, JOIN_LEFT, ({"_id": f"left_{v:06d}", "joinKey": "shared"} for v in range(LEFT_DOCS)))
    bulk_load(c, JOIN_BIG, ({
        "_id": f"big_{v:06d}",
        "joinKey": "shared" if v == 0 else f"key_{v}",
        "label": "the-one" if v == 0 else f"label_{v}",
        "payload": rand_payload(PAYLOAD_BYTES),
    } for v in range(NUM_DOCS)))


def wait_for_indexes(c, expected, timeout_s=20.0):
    """Indexes are built in the background; poll stats until they appear, with a sleep fallback."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        r = stats(c)
        present = {}
        for db in r.get("stats", {}).get("databases", []):
            if db.get("name") != DB:
                continue
            for coll in db.get("collections", []):
                present[coll.get("name")] = set(coll.get("indexes") or [])
        if all(field in present.get(coll, set()) for coll, field in expected):
            return True
        time.sleep(0.5)
    # Fallback grace period in case stats lag the actual index files.
    time.sleep(3)
    return False


def create_indexes(c):
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "category"})
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "score"})
    # Element-match indexes for object- and array-valued fields (hashed object/array indexes).
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "meta"})
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "tags"})
    # Geohash-backed spatial index on the geo field.
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "location"})
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": JOIN_BIG, "fieldName": "joinKey"})
    wait_for_indexes(c, [(COLL, "category"), (COLL, "score"), (COLL, "meta"), (COLL, "tags"),
                            (COLL, "location"), (JOIN_BIG, "joinKey")])


def teardown_fixtures(c):
    c.send({"type": "DROP_DATABASE", "databaseName": DB})


# ── step definitions (each step is the pipeline source, so the index fast-path applies) ──

DISTINCT_STEPS = [{"type": "DISTINCT", "fieldName": "category"}]
SORT_STEPS = [{"type": "SORT", "fieldName": "score", "ascending": True}, {"type": "LIMIT", "limit": 10}]
JOIN_STEPS = [{"type": "JOIN", "joinCollection": JOIN_BIG, "localField": "joinKey",
               "remoteField": "joinKey", "asField": "joined"}]

# Element-match: the whole object/array is the operand. EQUALS hits a single unique doc; the hashed
# object/array index resolves it to one id (one positioned read) instead of scanning every document.
TARGET_OBJECT = {"n": 0, "category": category_for(0)}
TARGET_ARRAY = ["t", "0"]
FILTER_OBJECT_STEPS = [{"type": "FILTER",
                        "operator": {"fieldOperatorType": "EQUALS", "field": "meta", "value": TARGET_OBJECT}}]
FILTER_ARRAY_STEPS = [{"type": "FILTER",
                       "operator": {"fieldOperatorType": "EQUALS", "field": "tags", "value": TARGET_ARRAY}}]
# IN over a list of objects: each candidate object is hashed and resolved through the object index.
FILTER_OBJECT_IN_STEPS = [{"type": "FILTER", "operator": {
    "fieldOperatorType": "IN", "field": "meta",
    "value": [{"n": v, "category": category_for(v)} for v in range(3)]}}]
# Geo: a small-radius distance and a small polygon each select only the tight cluster around
# GEO_TARGET, so the geohash spatial index prunes candidate reads instead of scanning every document.
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
    return (len(rows), sorted(labels))


def filter_signature(r):
    return sorted(d.get("_id") for d in (r.get("results") or []))


# ── the comparison harness ───────────────────────────────────────────────────

def compare(label, unindexed_time, unindexed_resp, indexed_time, indexed_resp, signature, strict_speed):
    section(label)
    sig_unindexed = signature(unindexed_resp)
    sig_indexed = signature(indexed_resp)
    check(
        "results identical with and without the index",
        sig_unindexed == sig_indexed,
        detail=f"signature={sig_indexed!r}",
    )
    ratio = (indexed_time / unindexed_time) if unindexed_time > 0 else 0.0
    detail = (f"unindexed_best={unindexed_time * 1000:.2f}ms  indexed_best={indexed_time * 1000:.2f}ms  "
              f"ratio={ratio:.2f} (lower is better)")
    if strict_speed:
        check("index path is faster than the full scan", indexed_time < unindexed_time, detail=detail)
    else:
        check(
            f"index path is not slower than the full scan (within {SPEED_TOLERANCE:.2f}x)",
            indexed_time <= unindexed_time * SPEED_TOLERANCE,
            detail=detail,
        )


# ══════════════════════════════════════════════════════════════════════════
# Consistency under asynchronous index maintenance
# ══════════════════════════════════════════════════════════════════════════

def save_doc(c, coll, obj) -> dict:
    return c.send({"type": "SAVE", "databaseName": DB, "collectionName": coll, "object": obj})


def setup_consistency(c):
    for coll in (CONS, CONS_SORT, CONS_COUNT, CONS_JOIN, CONS_LEFT, CONS_CREATE):
        c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
    # JOIN left side: a single row that shares the key every remote row will be saved with.
    save_doc(c, CONS_LEFT, {"_id": "left_shared", "joinKey": "shared"})
    # Build the indexes on the (empty) collections, so every subsequent write exercises the
    # committed-but-not-yet-indexed path.
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": CONS, "fieldName": "status"})
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": CONS_SORT, "fieldName": "score"})
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": CONS_JOIN, "fieldName": "joinKey"})
    wait_for_indexes(c, [(CONS, "status"), (CONS_SORT, "score"), (CONS_JOIN, "joinKey")])


def filter_status(c, value):
    r = agg(c, CONS, [{"type": "FILTER",
                          "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": value}}])
    return sorted(d.get("_id") for d in (r.get("results") or []))


def probe_filter_no_false_negative(c):
    # A just-saved matching document must be returned immediately, even before it is indexed.
    bad = 0
    for i in range(CONS_REPEATS):
        value = f"fn_val_{i}"
        save_doc(c, CONS, {"_id": f"fn_{i}", "status": value})
        if filter_status(c, value) != [f"fn_{i}"]:
            bad += 1
    check("FILTER never misses a just-written match (no false negative)", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} immediate queries were stale")


def probe_filter_no_false_positive_on_update(c):
    # Re-pointing a document's indexed value must not leave it visible under the old value, and it
    # must be visible under the new value — immediately.
    save_doc(c, CONS, {"_id": "upd", "status": "upd_v0"})
    prev = "upd_v0"
    bad = 0
    for i in range(1, CONS_REPEATS + 1):
        cur = f"upd_v{i}"
        save_doc(c, CONS, {"_id": "upd", "status": cur})
        if "upd" in filter_status(c, prev):
            bad += 1  # stale false positive under the old value
        if filter_status(c, cur) != ["upd"]:
            bad += 1  # missing under the new value
        prev = cur
    check("FILTER reflects updates immediately (no stale false positive / no false negative)",
               bad == 0, detail=f"{bad} inconsistent immediate queries")


def probe_count_with_filter(c):
    # An index-only COUNT with a filter must reflect every committed matching document.
    bad = 0
    for i in range(CONS_REPEATS):
        save_doc(c, CONS, {"_id": f"cf_{i}", "status": "countme"})
        r = agg(c, CONS, [{"type": "FILTER",
                              "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": "countme"}},
                             {"type": "COUNT"}])
        got = (r.get("results") or [{}])[0].get("count")
        if got != i + 1:
            bad += 1
    check("index-only COUNT (with filter) counts committed docs immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} counts were stale")


def probe_whole_collection_count(c):
    # The no-filter COUNT comes from the synchronously-maintained PK index, so it is exact at once.
    bad = 0
    for i in range(CONS_REPEATS):
        save_doc(c, CONS_COUNT, {"_id": f"wc_{i}", "n": i})
        r = agg(c, CONS_COUNT, [{"type": "COUNT"}])
        got = (r.get("results") or [{}])[0].get("count")
        if got != i + 1:
            bad += 1
    check("whole-collection COUNT is exact immediately after each save", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} counts were stale")


def probe_distinct_new_value(c):
    # A brand-new indexed value must appear in DISTINCT immediately.
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
    # A just-written document must land in its group immediately.
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
    # SORT must include and correctly order a just-written value immediately.
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
    # A just-written remote document must be matched by an index-backed JOIN immediately.
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
    # Deleting the only doc with a unique value must remove it from index-only COUNT and DISTINCT
    # immediately (not just from FILTER), even before the async index removal runs.
    bad = 0
    for i in range(CONS_REPEATS):
        value = f"del_{i}"
        doc_id = f"del_doc_{i}"
        save_doc(c, CONS, {"_id": doc_id, "status": value})
        c.send({"type": "DELETE", "databaseName": DB, "collectionName": CONS, "_id": doc_id})
        # Immediately: the value must be gone from FILTER, COUNT and DISTINCT.
        if filter_status(c, value):
            bad += 1
        r = agg(c, CONS, [{"type": "FILTER",
                              "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": value}},
                             {"type": "COUNT"}])
        if (r.get("results") or [{}])[0].get("count") != 0:
            bad += 1
        d = agg(c, CONS, DISTINCT_STATUS_STEPS)
        if value in {row.get("status") for row in (d.get("results") or [])}:
            bad += 1
    check("DELETE removes a doc from FILTER, COUNT and DISTINCT immediately", bad == 0,
               detail=f"{bad} inconsistent immediate queries after delete")


def probe_same_id_rapid_updates_converge(c):
    # Hammer one _id with alternating values so the worker pool processes its events concurrently
    # (and possibly out of order). After the background settles, the index must reflect the LAST
    # committed value — not a reordered stale one.
    iterations = max(CONS_REPEATS, 20)
    last_value = None
    for i in range(iterations):
        last_value = f"hammer_{i}"
        save_doc(c, CONS, {"_id": "hammer", "status": last_value})
    wait_for_background()
    bad = 0
    if filter_status(c, last_value) != ["hammer"]:
        bad += 1
    if filter_status(c, "hammer_0"):  # an earlier value must no longer resolve to the doc
        bad += 1
    r = agg(c, CONS, [{"type": "FILTER",
                          "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": last_value}},
                         {"type": "COUNT"}])
    if (r.get("results") or [{}])[0].get("count") != 1:
        bad += 1
    check("same-id rapid updates converge to the last value after the background settles", bad == 0,
               detail=f"{bad} stale results after hammering one id with {iterations} updates")


def probe_save_delete_converges(c):
    # Flood save+delete pairs for distinct ids; after the background settles none of the values may
    # remain in the index (catches a save event applied after its delete event).
    for i in range(CONS_REPEATS):
        value = f"sd_{i}"
        doc_id = f"sd_doc_{i}"
        save_doc(c, CONS, {"_id": doc_id, "status": value})
        c.send({"type": "DELETE", "databaseName": DB, "collectionName": CONS, "_id": doc_id})
    wait_for_background()
    bad = sum(1 for i in range(CONS_REPEATS) if filter_status(c, f"sd_{i}"))
    check("save+delete of the same id converges to absent after the background settles", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} deleted values still present after settling")


def probe_concurrent_save_during_create_index(c):
    # A document saved concurrently with CREATE_INDEX must not be lost from the new index. The build
    # holds the collection write lock and registers the field synchronously, so each concurrent save is
    # serialized: it is either captured by the build's whole-collection read or indexed afterwards
    # because the field is already a known index. Without the fix, saves landing in the build window
    # were silently missing from the index forever.
    seed = 40
    for i in range(seed):
        save_doc(c, CONS_CREATE, {"_id": f"seed_{i}", "tag": f"t{i % 5}"})

    saved_ids = []
    saved_lock = threading.Lock()
    stop = threading.Event()

    def writer():
        with Conn() as wc:
            wc.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            i = 0
            while not stop.is_set():
                doc_id = f"conc_{i}"
                r = save_doc(wc, CONS_CREATE, {"_id": doc_id, "tag": "concurrent"})
                if r.get("status") == "OK":
                    with saved_lock:
                        saved_ids.append(doc_id)
                i += 1

    t = threading.Thread(target=writer)
    t.start()
    try:
        # Let several concurrent writes land, then build the index mid-flight, then let a few more
        # land after the build — exercising both sides of the build's write-locked section.
        time.sleep(0.05)
        c.send({"type": "CREATE_INDEX", "databaseName": DB,
                    "collectionName": CONS_CREATE, "fieldName": "tag"})
        time.sleep(0.05)
    finally:
        stop.set()
        t.join()
    wait_for_background()

    r = agg(c, CONS_CREATE, [{"type": "FILTER",
                                 "operator": {"fieldOperatorType": "EQUALS", "field": "tag",
                                              "value": "concurrent"}}])
    found = {row.get("_id") for row in (r.get("results") or [])}
    with saved_lock:
        expected = list(saved_ids)
    missing = [i for i in expected if i not in found]
    check("no document saved concurrently with CREATE_INDEX is missing from the index",
               not missing,
               detail=f"{len(missing)} of {len(expected)} concurrently-saved docs missing from the index")


def probe_drop_burst_no_background_errors(c):
    # Create, populate and drop many collections (plus the database) in a burst while background
    # events for those collections are still in flight. This stresses the shared admin-collection
    # files (collections/databases) with repeated compaction; with the PK-position fix the survivors
    # stay readable and no NegativeArraySizeException / null-collection error is raised.
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
    # A survivor collection that is NOT dropped must remain fully readable afterwards.
    c.send({"type": "CREATE_COLLECTION", "databaseName": burst_db, "collectionName": "keep"})
    for j in range(5):
        c.send({"type": "SAVE", "databaseName": burst_db, "collectionName": "keep",
                    "object": {"_id": f"k{j}", "k": f"v{j}"}})
    wait_for_background()
    r = c.send({"type": "AGGREGATE", "databaseName": burst_db, "collectionName": "keep",
                    "aggregationSteps": [{"type": "COUNT"}]})
    count = (r.get("results") or [{}])[0].get("count")
    c.send({"type": "DROP_DATABASE", "databaseName": burst_db})
    check("drop burst keeps survivors intact (no stale-position corruption)", count == 5,
               detail=f"survivor count={count} (expected 5)")


def probe_bulk_update_same_page_no_corruption(c):
    # Bulk-insert several docs (they pack onto one page), then BULK_SAVE updating all of them to
    # different, longer values. Each must read back intact afterwards — regression for the
    # multi-same-page bulk-update page-corruption bug.
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
    # After the background settles, the document is found via the (now-updated, re-evicted) index.
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


# ══════════════════════════════════════════════════════════════════════════
# Geo type: distance & within custom operators (correctness, analyze, consistency)
# ══════════════════════════════════════════════════════════════════════════

# Hand-placed points with known separations from GEO_TARGET (40, -74):
#   near  -> 0 m       close -> ~140 m     mid -> ~5.6 km      far -> Los Angeles (~3900 km)
GEO_SEED = [
    ("near", "#geo(40.0,-74.0)"),
    ("close", "#geo(40.001,-74.001)"),
    ("mid", "#geo(40.05,-74.0)"),
    ("far", "#geo(34.05,-118.24)"),
]
# A polygon around the near/close/mid points but excluding far.
GEO_POLY = ["#geo(39.99,-74.1)", "#geo(39.99,-73.9)", "#geo(40.1,-73.9)", "#geo(40.1,-74.1)"]
# A tiny polygon enclosing only the mid point (used to prove OR of two custom operators).
GEO_POLY_MID = ["#geo(40.04,-74.01)", "#geo(40.04,-73.99)", "#geo(40.06,-73.99)", "#geo(40.06,-74.01)"]


def agg_analyze(c, coll, steps) -> dict:
    return c.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                       "analyze": True, "aggregationSteps": steps})


def geo_filter(c, coll, steps):
    return sorted(d.get("_id") for d in (agg(c, coll, steps).get("results") or []))


def setup_geo(c):
    for coll in (GEO, GEO_SCAN):
        c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
        for _id, loc in GEO_SEED:
            save_doc(c, coll, {"_id": _id, "location": loc})
    # Only GEO is indexed; GEO_SCAN stays a full scan for the indexed==scan cross-check.
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": GEO, "fieldName": "location"})
    wait_for_indexes(c, [(GEO, "location")])


def geo_compare_case(c, label, steps, expected):
    idx = geo_filter(c, GEO, steps)
    scan = geo_filter(c, GEO_SCAN, steps)
    check(label, idx == scan == expected, detail=f"indexed={idx}  scan={scan}  expected={expected}")


def probe_geo_distance_comparators(c):
    # Each comparator, cross-checked indexed vs full scan against the known-correct answer.
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
    # AND of two custom operators: within(near,close,mid) ∩ distance<1km(near,close) = {near,close}.
    and_steps = [{"type": "FILTER", "operator": {"conjunctionType": "AND", "operators": [
        {"customOperatorName": "within", "field": "location", "polygon": GEO_POLY},
        {"customOperatorName": "distance", "field": "location", "value": GEO_TARGET,
         "comparator": "SMALLER_THAN", "distance": 1000}]}}]
    check("AND(within, distance<1km) -> {close, near}",
               geo_filter(c, GEO, and_steps) == ["close", "near"],
               detail=f"got={geo_filter(c, GEO, and_steps)}")
    # OR of two custom operators: distance<1km(near,close) ∪ within(mid) = {near,close,mid}.
    or_steps = [{"type": "FILTER", "operator": {"conjunctionType": "OR", "operators": [
        {"customOperatorName": "distance", "field": "location", "value": GEO_TARGET,
         "comparator": "SMALLER_THAN", "distance": 1000},
        {"customOperatorName": "within", "field": "location", "polygon": GEO_POLY_MID}]}}]
    check("OR(distance<1km, within-mid) -> {close, mid, near}",
               geo_filter(c, GEO, or_steps) == ["close", "mid", "near"],
               detail=f"got={geo_filter(c, GEO, or_steps)}")


def probe_geo_count(c):
    r = agg(c, GEO, geo_distance_steps("SMALLER_THAN", 1000) + [{"type": "COUNT"}])
    got = (r.get("results") or [{}])[0].get("count")
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
    # A just-saved geo point must be found by the index-backed distance filter immediately, before the
    # background field-index update runs (the pending-write overlay adds it as a candidate).
    bad = 0
    for i in range(CONS_REPEATS):
        doc_id = f"geo_pending_{i}"
        save_doc(c, GEO, {"_id": doc_id, "location": "#geo(40.0,-74.0)"})
        if doc_id not in geo_filter(c, GEO, geo_distance_steps("SMALLER_THAN", 1000)):
            bad += 1
    check("distance filter finds a just-written geo point immediately (pending-write overlay)",
               bad == 0, detail=f"{bad}/{CONS_REPEATS} immediate geo queries were stale")


def probe_geo_non_geo_field_no_match(c):
    # A distance operator on a field that is not a geo value must simply not match (no error).
    save_doc(c, GEO_SCAN, {"_id": "not_geo", "location": "just a string"})
    res = geo_filter(c, GEO_SCAN, geo_distance_steps("SMALLER_THAN", 1000))
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



# ══════════════════════════════════════════════════════════════════════════
# Index / scan agreement
#
# An index-backed answer must equal the answer the same query gives against a full scan. Each case
# runs the query with no index, builds the index, and runs it again: the two answers must match.
# These are the shapes where an index used to answer differently — a scalar index answering a
# type-agnostic operator, a complement taken from one index of a mixed-type field, a join key the
# index cannot look up, and an ordering the index computed with its own comparator.
# ══════════════════════════════════════════════════════════════════════════

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

AGREE_COLLECTIONS = (AGREE_CONTAINS_NUM, AGREE_CONTAINS_BOOL, AGREE_NOT_IN_OBJ, AGREE_NOT_IN_ARR,
                     AGREE_JOIN_REMOTE, AGREE_JOIN_LEFT, AGREE_JOIN_NULL_REMOTE, AGREE_JOIN_NULL_LEFT,
                     AGREE_SORT_BOOL, AGREE_SORT_BOOL_DESC, AGREE_SORT_MIXED, AGREE_SORT_TIES,
                     AGREE_SIBLING, AGREE_OBJ_SORT, AGREE_CUSTOM, AGREE_MIXED_BOX, AGREE_GEO,
                     AGREE_IN_CASE, AGREE_NOT_IN_CASE, AGREE_IN_CUSTOM, AGREE_NOT_IN_CUSTOM)


def agree_ids(r):
    """Sorted ids, or a marker for a real error so it can never look like an empty answer."""
    status = r.get("status")
    if status not in ("OK", "NOT_FOUND"):
        return f"<{status}/{r.get('errorCode')}>"
    return sorted(d.get("_id") for d in (r.get("results") or []))


def agree_ordered_ids(r):
    status = r.get("status")
    if status not in ("OK", "NOT_FOUND"):
        return f"<{status}/{r.get('errorCode')}>"
    return [d.get("_id") for d in (r.get("results") or [])]


def agree_joined(r):
    """Each row as (id, how many documents were attached) — a dropped join shows up as 0."""
    status = r.get("status")
    if status not in ("OK", "NOT_FOUND"):
        return f"<{status}/{r.get('errorCode')}>"
    rows = []
    for d in (r.get("results") or []):
        attached = d.get("cfg")
        rows.append((d.get("_id"), len(attached) if isinstance(attached, list) else 0))
    return sorted(rows)


def agree(c, label, query_coll, steps, index_coll, index_field, extract=agree_ids, expected=None):
    scanned = extract(agg(c, query_coll, steps))
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": index_coll,
            "fieldName": index_field})
    wait_for_indexes(c, [(index_coll, index_field)])
    indexed = extract(agg(c, query_coll, steps))
    check(f"{label}: index agrees with scan", scanned == indexed,
          f"scan={scanned!r}  indexed={indexed!r}")
    if expected is not None:
        check(f"{label}: answer is correct", scanned == expected,
              f"expected={expected!r}  got={scanned!r}")


def setup_agreement(c):
    for coll in AGREE_COLLECTIONS:
        c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
    # CONTAINS is type-agnostic on the scan side: it matches an array-valued document holding the
    # operand, which a scalar index of that field cannot see.
    save_doc(c, AGREE_CONTAINS_NUM, {"_id": "scalar", "tags": 5})
    save_doc(c, AGREE_CONTAINS_NUM, {"_id": "inArray", "tags": [5, 7]})
    save_doc(c, AGREE_CONTAINS_BOOL, {"_id": "scalar", "flags": True})
    save_doc(c, AGREE_CONTAINS_BOOL, {"_id": "inArray", "flags": [True]})
    # NOT_IN is a complement: taken from one index of a mixed-type field it silently omits every
    # document held only by another index of the same field.
    save_doc(c, AGREE_NOT_IN_OBJ, {"_id": "obj1", "tag": {"a": 1}})
    save_doc(c, AGREE_NOT_IN_OBJ, {"_id": "str1", "tag": "x"})
    save_doc(c, AGREE_NOT_IN_OBJ, {"_id": "obj2", "tag": {"b": 2}})
    save_doc(c, AGREE_NOT_IN_ARR, {"_id": "arr1", "tag": [1]})
    save_doc(c, AGREE_NOT_IN_ARR, {"_id": "str1", "tag": "x"})
    save_doc(c, AGREE_NOT_IN_ARR, {"_id": "arr2", "tag": [2]})
    # A join key the index cannot be looked up by: an object, and an explicit JSON null.
    save_doc(c, AGREE_JOIN_REMOTE, {"_id": "cfg1", "key": {"region": "eu"}})
    save_doc(c, AGREE_JOIN_LEFT, {"_id": "row1", "key": {"region": "eu"}})
    save_doc(c, AGREE_JOIN_NULL_REMOTE, {"_id": "cfg1", "key": None})
    save_doc(c, AGREE_JOIN_NULL_LEFT, {"_id": "row1", "key": None})
    # Ordering: booleans, and values of different types that must rank the same way either path.
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
    # A point 99.96 km from the target: inside a 100 km radius, but outside a candidate box sized
    # with a rounded metres-per-degree constant rather than the sphere the distance itself uses.
    save_doc(c, AGREE_GEO, {"_id": "onTheRim", "location": "#geo(0.899000,0.000000)"})
    save_doc(c, AGREE_GEO, {"_id": "farAway", "location": "#geo(-40.000000,100.000000)"})
    # Membership must use the equality EQUALS uses: case-insensitive for strings, semantic for
    # custom types. Index and scan agreed with each other before, and disagreed with EQUALS.
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
    """Two documents tied on the sort key: SORT + LIMIT 1 must not pick a different one per run."""
    steps = [{"type": "SORT", "fieldName": "score", "ascending": True}, {"type": "LIMIT", "limit": 1}]
    scanned = agree_ordered_ids(agg(c, AGREE_SORT_TIES, steps))
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": AGREE_SORT_TIES,
            "fieldName": "score"})
    wait_for_indexes(c, [(AGREE_SORT_TIES, "score")])
    picks = {tuple(agree_ordered_ids(agg(c, AGREE_SORT_TIES, steps))) for _ in range(10)}
    check("SORT + LIMIT over tied keys picks the same document every run", len(picks) == 1,
          f"scan={scanned!r}  indexed picks across 10 runs={sorted(picks)!r}")
    # Perturb the tied bucket so its backing set rehashes, then delete the additions again: an
    # order that depends on set iteration moves, a deterministic one does not.
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
    """CREATE_INDEX on `first` used to delete every `first-name` index file: the anchored prefix
    still matched a longer field, and hyphens are legal in field names."""
    save_doc(c, AGREE_SIBLING, {"_id": "s1", "first": "ada", "first-name": "ada lovelace"})
    save_doc(c, AGREE_SIBLING, {"_id": "s2", "first": "alan", "first-name": "alan turing"})
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": AGREE_SIBLING,
            "fieldName": "first-name"})
    wait_for_indexes(c, [(AGREE_SIBLING, "first-name")])
    steps = [{"type": "FILTER",
              "operator": {"fieldOperatorType": "EQUALS", "field": "first-name", "value": "ada lovelace"}}]
    before = agree_ids(agg(c, AGREE_SIBLING, steps))
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": AGREE_SIBLING,
            "fieldName": "first"})
    wait_for_indexes(c, [(AGREE_SIBLING, "first")])
    save_doc(c, AGREE_SIBLING, {"_id": "s3", "first": "grace", "first-name": "grace hopper"})
    after = agree_ids(agg(c, AGREE_SIBLING, steps))
    check("indexing a field never deletes a sibling whose name extends it",
          before == ["s1"] and after == ["s1"],
          f"before={before!r}  after indexing 'first'={after!r}")


def probe_object_sort_keys_break_ties_on_id(c):
    """Every object-valued sort key compares equal, so the index path must still order ties by _id
    the way the scan does - otherwise SORT + LIMIT returns a different page."""
    for doc_id, inner in (("z", 1), ("a", 2), ("m", 3)):
        save_doc(c, AGREE_OBJ_SORT, {"_id": doc_id, "meta": {"x": inner}})
    steps = [{"type": "SORT", "fieldName": "meta", "ascending": True}, {"type": "LIMIT", "limit": 2}]
    agree(c, "SORT + LIMIT over object-valued keys", AGREE_OBJ_SORT, steps,
          AGREE_OBJ_SORT, "meta", extract=agree_ordered_ids, expected=["a", "m"])


def probe_custom_values_bucket_the_same_either_way(c):
    """The build path buckets custom values by raw wire text while incremental maintenance used to
    match them semantically, so DISTINCT disagreed with the scan and with itself."""
    save_doc(c, AGREE_CUSTOM, {"_id": "d1", "when": "#datetime(2024-01-01T10:00)"})
    save_doc(c, AGREE_CUSTOM, {"_id": "d2", "when": "#datetime(2024-01-01T10:00:00)"})
    steps = [{"type": "DISTINCT", "fieldName": "when"}]
    scanned = len(agg(c, AGREE_CUSTOM, steps).get("results") or [])
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": AGREE_CUSTOM,
            "fieldName": "when"})
    wait_for_indexes(c, [(AGREE_CUSTOM, "when")])
    save_doc(c, AGREE_CUSTOM, {"_id": "d3", "when": "#datetime(2024-02-02T11:00)"})
    indexed = len(agg(c, AGREE_CUSTOM, steps).get("results") or [])
    check("DISTINCT over a custom-typed field buckets the same with and without an index",
          indexed == scanned + 1, f"scan over 2 docs={scanned}  indexed over 3 docs={indexed}")


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


def probe_a_scalar_membership_operand_is_refused(c):
    """IN with a non-array operand reached SearchUtils and threw, so the same query answered 500
    with an index and NO_RESULTS without one. It must now be refused before either path."""
    steps = [{"type": "FILTER",
              "operator": {"fieldOperatorType": "IN", "field": "score", "value": 2}}]
    r = agg(c, AGREE_MIXED_BOX, steps)
    check("a scalar IN operand is refused rather than answered differently per path",
          r.get("status") == "ERROR" and str(r.get("errorCode", "")).startswith("400"),
          f"status={r.get('status')} errorCode={r.get('errorCode')} message={r.get('message')!r}")


def probe_mixed_number_boxes_group_the_same_either_way(c):
    """equals compares numbers by double value while hashCode returned the boxed hash, so a
    hash-based GROUP_BY split one logical value across two buckets on the scan path."""
    save_doc(c, AGREE_MIXED_BOX, {"_id": "w1", "score": 2})
    save_doc(c, AGREE_MIXED_BOX, {"_id": "w2", "score": 2.0})
    save_doc(c, AGREE_MIXED_BOX, {"_id": "w3", "score": 3})
    steps = [{"type": "GROUP_BY", "fieldName": "score"}]
    agree(c, "GROUP_BY over mixed integer and fractional spellings", AGREE_MIXED_BOX, steps,
          AGREE_MIXED_BOX, "score", extract=lambda r: len(r.get("results") or []), expected=2)


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
    probe_a_scalar_membership_operand_is_refused(c)
    probe_membership_uses_the_same_equality_as_equals(c)
    probe_mixed_number_boxes_group_the_same_either_way(c)


# ══════════════════════════════════════════════════════════════════════════
# Correctness regressions
# ══════════════════════════════════════════════════════════════════════════

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
REG_TIES = "idxagg_reg_ties"
REG_VALUELESS = "idxagg_reg_valueless"
REG_CAST = "idxagg_reg_cast"
REG_NULLFOLD = "idxagg_reg_nullfold"


def reg_filter(c, coll, field, value, op="EQUALS"):
    r = agg(c, coll, [{"type": "FILTER",
                       "operator": {"fieldOperatorType": op, "field": field, "value": value}}])
    return sorted(d.get("_id") for d in (r.get("results") or []))


def probe_non_ascii_indexed_values(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_UNICODE})
    for doc_id, city in (("u1", "café"), ("u2", "日本語"), ("u3", "plain"), ("u4", "a😀b")):
        save_doc(c, REG_UNICODE, {"_id": doc_id, "city": city})
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_UNICODE, "fieldName": "city"})
    wait_for_indexes(c, [(REG_UNICODE, "city")])
    save_doc(c, REG_UNICODE, {"_id": "u3", "city": "日本語"})
    save_doc(c, REG_UNICODE, {"_id": "u5", "city": "café"})
    wait_for_background()

    for city, expected in (("café", ["u1", "u5"]), ("日本語", ["u2", "u3"]), ("a😀b", ["u4"])):
        got = reg_filter(c, REG_UNICODE, "city", city)
        check(f"non-ASCII indexed value {city!r} is queryable after an in-place index rewrite",
              got == expected, detail=f"expected {expected}, got {got}")
    check("re-pointed document no longer answers under its old non-ASCII value",
          "u3" not in reg_filter(c, REG_UNICODE, "city", "plain"))


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

    for doc_id, note in values.items():
        got = reg_filter(c, REG_DELIMITER, "note", note)
        check(f"{doc_id}: unindexed scan finds {note!r}", got == [doc_id], detail=f"got {got}")

    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_DELIMITER, "fieldName": "note"})
    wait_for_indexes(c, [(REG_DELIMITER, "note")])
    wait_for_background()

    for doc_id, note in values.items():
        got = reg_filter(c, REG_DELIMITER, "note", note)
        check(f"{doc_id}: an index value containing a delimiter stays queryable ({note!r})",
              got == [doc_id], detail=f"expected ['{doc_id}'], got {got}")


def probe_repeated_create_index_is_idempotent(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_IDEMPOTENT})
    for doc_id, status in (("i0", "active"), ("i1", "active"), ("i2", "archived")):
        save_doc(c, REG_IDEMPOTENT, {"_id": doc_id, "status": status})

    baseline_eq = reg_filter(c, REG_IDEMPOTENT, "status", "active")
    baseline_ne = reg_filter(c, REG_IDEMPOTENT, "status", "active", op="NOT_EQUALS")

    for attempt in range(3):
        c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_IDEMPOTENT,
                "fieldName": "status"})
        wait_for_indexes(c, [(REG_IDEMPOTENT, "status")])
        wait_for_background()
        got_eq = reg_filter(c, REG_IDEMPOTENT, "status", "active")
        got_ne = reg_filter(c, REG_IDEMPOTENT, "status", "active", op="NOT_EQUALS")
        check(f"EQUALS is unchanged after CREATE_INDEX x{attempt + 1}", got_eq == baseline_eq,
              detail=f"expected {baseline_eq}, got {got_eq}")
        check(f"NOT_EQUALS is unchanged after CREATE_INDEX x{attempt + 1}", got_ne == baseline_ne,
              detail=f"expected {baseline_ne}, got {got_ne}")

    counted = agg(c, REG_IDEMPOTENT, [
        {"type": "FILTER", "operator": {"fieldOperatorType": "NOT_EQUALS", "field": "status", "value": "active"}},
        {"type": "COUNT"}])
    got = ((counted.get("results") or [{}])[0]).get("count")
    check("index-only COUNT is not inflated by repeated CREATE_INDEX", got == len(baseline_ne),
          detail=f"expected {len(baseline_ne)}, got {got}")


def probe_single_valued_index_ranges(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_SINGLE})
    for i in range(5):
        save_doc(c, REG_SINGLE, {"_id": f"s{i}", "score": 10})
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_SINGLE, "fieldName": "score"})
    wait_for_indexes(c, [(REG_SINGLE, "score")])
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
        got = reg_filter(c, REG_SINGLE, "score", value, op)
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
        r = agg(c, REG_CONJ, [
            {"type": "FILTER",
             "operator": {"fieldOperatorType": "NOT_EQUALS", "field": "role", "value": "ghost"}},
            {"type": "FILTER", "operator": {"conjunctionType": conj, "operators": leaves}}])
        got = sorted(d.get("_id") for d in (r.get("results") or []))
        check(f"{conj} conjunction with two children runs on an already-filtered stream",
              got == expected, detail=f"expected {expected}, got {got}")

    nested = agg(c, REG_CONJ, [
        {"type": "FILTER",
         "operator": {"fieldOperatorType": "NOT_EQUALS", "field": "role", "value": "ghost"}},
        {"type": "FILTER", "operator": {"conjunctionType": "AND", "operators": [
            {"conjunctionType": "OR", "operators": leaves},
            {"fieldOperatorType": "EQUALS", "field": "active", "value": "yes"}]}}])
    got = sorted(d.get("_id") for d in (nested.get("results") or []))
    check("nested conjunction runs on an already-filtered stream", got == ["a", "c"],
          detail=f"expected ['a', 'c'], got {got}")



def probe_low_cardinality_numeric_index(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_NUMERIC})
    save_doc(c, REG_NUMERIC, {"_id": "seed", "bucket": 99, "big": 1, "ratio": 0.5})
    for field in ("bucket", "big", "ratio"):
        c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_NUMERIC, "fieldName": field})
    wait_for_indexes(c, [(REG_NUMERIC, f) for f in ("bucket", "big", "ratio")])

    for i in range(6):
        save_doc(c, REG_NUMERIC, {"_id": f"n{i}", "bucket": i % 2, "big": 10000000000, "ratio": 0.25})
    wait_for_background()

    for value, expected in ((0, ["n0", "n2", "n4"]), (1, ["n1", "n3", "n5"])):
        got = reg_filter(c, REG_NUMERIC, "bucket", value)
        check(f"every document written after CREATE_INDEX with bucket={value} stays in the numeric index",
              got == expected, detail=f"expected {expected}, got {got}")

    all_new = sorted(f"n{i}" for i in range(6))
    got = reg_filter(c, REG_NUMERIC, "big", 10000000000)
    check("an integral value above Integer.MAX_VALUE indexes every document sharing it",
          got == all_new, detail=f"expected {all_new}, got {got}")

    got = reg_filter(c, REG_NUMERIC, "ratio", 0.25)
    check("a fractional value indexes every document sharing it",
          got == all_new, detail=f"expected {all_new}, got {got}")

    with_seed = sorted(all_new + ["seed"])
    got = reg_filter(c, REG_NUMERIC, "bucket", 0, "GREATER_THAN_EQUALS")
    check("a range query over a low-cardinality numeric index sees every document",
          got == with_seed, detail=f"expected {with_seed}, got {got}")

    save_doc(c, REG_NUMERIC, {"_id": "n1", "bucket": 5, "big": 10000000000, "ratio": 0.25})
    wait_for_background()
    got = reg_filter(c, REG_NUMERIC, "bucket", 1)
    check("re-pointing one document leaves the other ids under the old numeric value",
          got == ["n3", "n5"], detail=f"expected ['n3', 'n5'], got {got}")
    check("the re-pointed document answers under its new numeric value",
          reg_filter(c, REG_NUMERIC, "bucket", 5) == ["n1"])


def probe_bulk_save_indexes_every_doc_sharing_a_value(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_BULK})
    save_doc(c, REG_BULK, {"_id": "seed", "status": "seeded"})
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_BULK, "fieldName": "status"})
    wait_for_indexes(c, [(REG_BULK, "status")])
    wait_for_background()

    documents = [{"_id": f"b{i}", "status": "active" if i % 2 == 0 else "archived"} for i in range(5)]
    check_status("BULK_SAVE five documents over two status values",
                 c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": REG_BULK,
                         "objects": documents}), "OK")
    wait_for_background()

    for status, expected in (("active", ["b0", "b2", "b4"]), ("archived", ["b1", "b3"])):
        got = reg_filter(c, REG_BULK, "status", status)
        check(f"every bulk-saved doc with status={status} is still indexed", got == expected,
              detail=f"expected {expected}, got {got}")
        counted = agg(c, REG_BULK, [
            {"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": status}},
            {"type": "COUNT"}])
        got_count = ((counted.get("results") or [{}])[0]).get("count")
        check(f"the index-only COUNT for status={status} matches", got_count == len(expected),
              detail=f"expected {len(expected)}, got {got_count}")


def probe_case_variants_agree_between_index_and_scan(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_CASE})
    for doc_id, name in (("a", "Bob"), ("b", "bob"), ("c", "bob"), ("d", "carol")):
        save_doc(c, REG_CASE, {"_id": doc_id, "name": name})

    scan_equals = reg_filter(c, REG_CASE, "name", "bob")
    scan_not_equals = reg_filter(c, REG_CASE, "name", "bob", op="NOT_EQUALS")
    scan_count = agg(c, REG_CASE, [
        {"type": "FILTER", "operator": {"fieldOperatorType": "NOT_EQUALS", "field": "name", "value": "bob"}},
        {"type": "COUNT"}])
    scan_count = ((scan_count.get("results") or [{}])[0]).get("count")

    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_CASE, "fieldName": "name"})
    wait_for_indexes(c, [(REG_CASE, "name")])
    wait_for_background()

    got_equals = reg_filter(c, REG_CASE, "name", "bob")
    got_not_equals = reg_filter(c, REG_CASE, "name", "bob", op="NOT_EQUALS")
    counted = agg(c, REG_CASE, [
        {"type": "FILTER", "operator": {"fieldOperatorType": "NOT_EQUALS", "field": "name", "value": "bob"}},
        {"type": "COUNT"}])
    got_count = ((counted.get("results") or [{}])[0]).get("count")

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

    scanned = agg(c, REG_SORT, [{"type": "SORT", "fieldName": "score", "ascending": True}])
    scanned_ids = sorted(d.get("_id") for d in (scanned.get("results") or []))

    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_SORT, "fieldName": "score"})
    wait_for_indexes(c, [(REG_SORT, "score")])
    wait_for_background()

    indexed = agg(c, REG_SORT, [{"type": "SORT", "fieldName": "score", "ascending": True}])
    indexed_ids = sorted(d.get("_id") for d in (indexed.get("results") or []))

    check("an indexed SORT returns the whole collection, not only the documents that have the field",
          len(indexed_ids) == total, detail=f"expected {total}, got {len(indexed_ids)}")
    check("an indexed SORT returns exactly what the full scan returns", indexed_ids == scanned_ids,
          detail=f"scan={len(scanned_ids)} indexed={len(indexed_ids)}")


def probe_mixed_type_field_falls_back_to_scan(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_MIXED})
    save_doc(c, REG_MIXED, {"_id": "m1", "tags": "alpha"})
    save_doc(c, REG_MIXED, {"_id": "m2", "tags": ["alpha", "beta"]})
    save_doc(c, REG_MIXED, {"_id": "m3", "tags": "beta"})

    scan_contains = reg_filter(c, REG_MIXED, "tags", "alpha", op="CONTAINS")

    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_MIXED, "fieldName": "tags"})
    wait_for_indexes(c, [(REG_MIXED, "tags")])
    wait_for_background()

    indexed_contains = reg_filter(c, REG_MIXED, "tags", "alpha", op="CONTAINS")

    check("CONTAINS on a mixed-type field answers the same with and without the index",
          indexed_contains == scan_contains, detail=f"scan={scan_contains} indexed={indexed_contains}")
    check("CONTAINS still finds the array-valued document", "m2" in indexed_contains,
          detail=f"got {indexed_contains}")


def _ids(response) -> list:
    return sorted(d.get("_id") for d in (response.get("results") or []))


def _in_filter(c, coll, field, values, op="IN"):
    return _ids(agg(c, coll, [{"type": "FILTER",
                               "operator": {"fieldOperatorType": op, "field": field, "value": values}}]))


def probe_numeric_in_agrees_between_index_and_scan(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_IN})
    for i, score in enumerate((1, 2, 3, 5, 9)):
        save_doc(c, REG_IN, {"_id": f"n{i}", "score": score})

    # The operands arrive through the real parser, which narrows an integral value inside the int
    # range to an Integer, while a number index entry is always a Double. Boxed set membership then
    # matched nothing and every numeric IN on an indexed field silently returned no rows.
    scan_in = _in_filter(c, REG_IN, "score", [2, 9])
    scan_mixed = _in_filter(c, REG_IN, "score", [5, 2.5])
    scan_count = ((agg(c, REG_IN, [
        {"type": "FILTER", "operator": {"fieldOperatorType": "IN", "field": "score", "value": [2, 9]}},
        {"type": "COUNT"}]).get("results") or [{}])[0]).get("count")

    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_IN, "fieldName": "score"})
    wait_for_indexes(c, [(REG_IN, "score")])
    wait_for_background()

    indexed_in = _in_filter(c, REG_IN, "score", [2, 9])
    indexed_mixed = _in_filter(c, REG_IN, "score", [5, 2.5])
    indexed_count = ((agg(c, REG_IN, [
        {"type": "FILTER", "operator": {"fieldOperatorType": "IN", "field": "score", "value": [2, 9]}},
        {"type": "COUNT"}]).get("results") or [{}])[0]).get("count")

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
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_NOTIN, "fieldName": "score"})
    wait_for_indexes(c, [(REG_NOTIN, "score")])
    wait_for_background()

    # NOT_IN probes whether another index covers the field. Probing the scalar kinds through the hash
    # loader parsed the same .idx file into String values and cached them under the typed key, so the
    # next EQUALS threw a ClassCastException and the following write replaced the line instead of
    # merging into it, permanently dropping ids.
    not_in = _in_filter(c, REG_NOTIN, "score", [1], op="NOT_IN")
    equals = _ids(agg(c, REG_NOTIN, [
        {"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "score", "value": 2}}]))

    check("NOT_IN on a scalar-only indexed field answers", not_in == ["s1", "s2", "s3"],
          detail=f"got={not_in}")
    check("EQUALS still works after the NOT_IN probe", equals == ["s1", "s2"], detail=f"got={equals}")

    save_doc(c, REG_NOTIN, {"_id": "s4", "score": 2})
    wait_for_background()
    after = _ids(agg(c, REG_NOTIN, [
        {"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "score", "value": 2}}]))
    check("a write after the NOT_IN probe does not drop the ids already on that line",
          after == ["s1", "s2", "s4"], detail=f"got={after}")


def probe_group_by_integer_min_value(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_MINVALUE})
    for i in range(3):
        save_doc(c, REG_MINVALUE, {"_id": f"m{i}", "n": -2147483648})

    scanned = agg(c, REG_MINVALUE, [{"type": "GROUP_BY", "fieldName": "n"}])
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_MINVALUE, "fieldName": "n"})
    wait_for_indexes(c, [(REG_MINVALUE, "n")])
    wait_for_background()
    indexed = agg(c, REG_MINVALUE, [{"type": "GROUP_BY", "fieldName": "n"}])

    # The codec admitted Integer.MIN_VALUE where the parser does not, so the same value hashed two
    # ways and the index-backed grouping emitted two groups under one key.
    check("an indexed GROUP_BY on Integer.MIN_VALUE emits one group, as the scan does",
          len(indexed.get("results") or []) == len(scanned.get("results") or []) == 1,
          detail=f"scan={scanned.get('results')} indexed={indexed.get('results')}")


def probe_geo_distance_across_the_antimeridian(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_GEO_WRAP})
    save_doc(c, REG_GEO_WRAP, {"_id": "east", "location": "#geo(0.000000,179.950000)"})
    save_doc(c, REG_GEO_WRAP, {"_id": "west", "location": "#geo(0.000000,-179.950000)"})
    save_doc(c, REG_GEO_WRAP, {"_id": "mid", "location": "#geo(0.000000,0.000000)"})

    def _within():
        return _ids(agg(c, REG_GEO_WRAP,
                        geo_distance_steps("SMALLER_THAN", 50000, target="#geo(0.000000,179.990000)")))

    scanned = _within()
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_GEO_WRAP,
            "fieldName": "location"})
    wait_for_indexes(c, [(REG_GEO_WRAP, "location")])
    wait_for_background()
    indexed = _within()

    # clampLng truncates at +/-180 and contains() cannot express a wrapped interval, so the pre-filter
    # dropped every true match on the far side - and FILTER re-tests candidates but never adds one back.
    check("a geo radius spanning the antimeridian returns both sides", scanned == ["east", "west"],
          detail=f"scan={scanned}")
    check("the indexed geo radius returns what the scan returns", indexed == scanned,
          detail=f"scan={scanned} indexed={indexed}")


def probe_sort_ties_do_not_depend_on_the_index(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_TIES})
    for i in range(10):
        save_doc(c, REG_TIES, {"_id": f"p{i:02d}", "score": 5})

    def _page():
        return [d.get("_id") for d in (agg(c, REG_TIES, [
            {"type": "SORT", "fieldName": "score", "ascending": True},
            {"type": "SKIP", "skip": 3},
            {"type": "LIMIT", "limit": 3}]).get("results") or [])]

    scanned = _page()
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_TIES, "fieldName": "score"})
    wait_for_indexes(c, [(REG_TIES, "score")])
    wait_for_background()
    indexed = _page()

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
        direct = _ids(agg(c, REG_CUSTOM, [step]))
        after_map = _ids(agg(c, REG_CUSTOM, [passthrough_map, step]))
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

    def conjunction(kind):
        return {"type": "FILTER", "operator": {"conjunctionType": kind, "operators": [
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


def probe_a_field_operator_without_a_value_is_refused(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_VALUELESS})
    save_doc(c, REG_VALUELESS, {"_id": "v1", "name": "alice"})
    wait_for_background()

    def valueless(field, operator_type):
        return agg(c, REG_VALUELESS,
                   [{"type": "FILTER", "operator": {"fieldOperatorType": operator_type, "field": field}}])

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


CAST_NUMBER_STEPS = [{"type": "MAP", "operators": [
    {"fieldName": "n", "operator": {"type": "CAST", "fieldName": "raw", "toType": "NUMBER"}}]}]
CAST_BOOLEAN_STEPS = [{"type": "MAP", "operators": [
    {"fieldName": "b", "operator": {"type": "CAST", "fieldName": "raw", "toType": "BOOLEAN"}}]}]


def probe_cast_keeps_the_value_boundary(c):
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REG_CAST})
    numbers = {"inf": "Infinity", "nan": "NaN", "hex": "0x1p3", "exp": "1e3"}
    for doc_id, raw in numbers.items():
        save_doc(c, REG_CAST, {"_id": doc_id, "raw": raw})
    wait_for_background()

    rows = {row["_id"]: row.get("n") for row in (agg(c, REG_CAST, CAST_NUMBER_STEPS).get("results") or [])}
    for doc_id, expected in (("inf", None), ("nan", None), ("hex", None), ("exp", 1000)):
        check(f"CAST {numbers[doc_id]!r} to NUMBER answers {expected!r}", rows.get(doc_id) == expected,
              detail=f"got {rows.get(doc_id)!r}")

    selected = agg(c, REG_CAST, CAST_NUMBER_STEPS + [{"type": "FILTER", "operator": {
        "fieldOperatorType": "GREATER_THAN", "field": "n", "value": 100}}])
    selected_ids = sorted(row["_id"] for row in (selected.get("results") or []))
    check("a numeric filter after the cast selects only the row that really is a number",
          selected_ids == ["exp"], detail=f"got {selected_ids}")


def probe_cast_to_boolean_answers_null_for_an_unparseable_string(c):
    booleans = {"btrue": "true", "bfalse": "FALSE", "bjunk": "banana"}
    for doc_id, raw in booleans.items():
        save_doc(c, REG_CAST, {"_id": doc_id, "raw": raw})
    wait_for_background()

    rows = {row["_id"]: row.get("b") for row in (agg(c, REG_CAST, CAST_BOOLEAN_STEPS).get("results") or [])}
    for doc_id, expected in (("btrue", True), ("bfalse", False), ("bjunk", None)):
        check(f"CAST {booleans[doc_id]!r} to BOOLEAN answers {expected!r}", rows.get(doc_id) == expected,
              detail=f"got {rows.get(doc_id)!r}")


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

    selected = agg(c, REG_NULLFOLD, AVG_FOLD_STEPS + [{"type": "FILTER", "operator": {
        "fieldOperatorType": "GREATER_THAN", "field": "derived", "value": 0}}])
    selected_ids = sorted(row["_id"] for row in (selected.get("results") or []))
    check("a numeric filter after that MAP keeps only the row that really is a number",
          selected_ids == ["priced"], detail=f"got {selected_ids}")

    is_null = agg(c, REG_NULLFOLD, AVG_FOLD_STEPS + [{"type": "FILTER", "operator": {
        "fieldOperatorType": "EQUALS", "field": "derived", "value": None}}])
    null_ids = sorted(row["_id"] for row in (is_null.get("results") or []))
    check("EQUALS null matches the row the same response showed as null",
          null_ids == ["unpriced"], detail=f"got {null_ids}")


def probe_indexing_a_field_written_as_null_is_consistent(c):
    save_doc(c, REG_NULLFOLD, {"_id": "explicit", "price": None})
    wait_for_background()

    before = reg_filter(c, REG_NULLFOLD, "price", 10)
    created = c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REG_NULLFOLD,
                      "fieldName": "price"})
    check("an index builds over a collection holding a null value", created.get("status") == "OK",
          detail=f"got {created.get('status')!r} {created.get('message')!r}")
    wait_for_background()

    check("the indexed answer agrees with the scan", reg_filter(c, REG_NULLFOLD, "price", 10) == before,
          detail=f"scan={before} index={reg_filter(c, REG_NULLFOLD, 'price', 10)}")
    is_null = reg_filter(c, REG_NULLFOLD, "price", None)
    check("EQUALS null still names only the null-valued document", is_null == ["explicit"],
          detail=f"got {is_null}")


def regression_suite(c):
    section("Correctness regressions: non-ASCII index values, index values containing the file's own "
            "delimiters, repeated CREATE_INDEX, single-valued index ranges, low-cardinality numeric "
            "indexes, conjunctions over a filtered stream, custom values across a MAP step, "
            "conjunctions over rows with no _id, valueless field operands, the MAP CAST value boundary, "
            "one spelling of JSON null across a MAP step and an index build")
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
    probe_sort_ties_do_not_depend_on_the_index(c)
    probe_a_custom_filter_survives_a_map_step(c)
    probe_a_conjunction_after_a_row_reshaping_step(c)
    probe_a_field_operator_without_a_value_is_refused(c)
    probe_cast_keeps_the_value_boundary(c)
    probe_cast_to_boolean_answers_null_for_an_unparseable_string(c)
    probe_a_map_null_result_is_a_real_json_null(c)
    probe_indexing_a_field_written_as_null_is_consistent(c)


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    bu.banner("index-backed aggregation performance test suite", HOST, PORT)
    print(f"  Plan: load {NUM_DOCS} docs into {COLL} ({NUM_CATEGORIES} categories, each with an object "
          f"meta + array tags), {LEFT_DOCS} left + {NUM_DOCS} right docs for JOIN.")
    print(f"        Measure JOIN/SORT/DISTINCT and object/array element-match FILTER unindexed, "
          f"then create indexes and re-measure.")

    with Conn() as c:
        r = c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            sys.exit(1)
        teardown_fixtures(c)
        print("\nLoading fixtures (this can take a moment)...")
        setup_fixtures(c)

    cases = [
        ("DISTINCT on category", COLL, DISTINCT_STEPS, distinct_signature, True),
        ("SORT on score (+LIMIT 10)", COLL, SORT_STEPS, sort_signature, False),
        ("JOIN against a large remote collection", JOIN_LEFT, JOIN_STEPS, join_signature, True),
        ("FILTER element-match on object field", COLL, FILTER_OBJECT_STEPS, filter_signature, True),
        ("FILTER element-match on array field", COLL, FILTER_ARRAY_STEPS, filter_signature, True),
        ("FILTER IN over a list of objects", COLL, FILTER_OBJECT_IN_STEPS, filter_signature, True),
        # Geo pre-filter reads the matched cluster (a few % of docs) via positioned reads; the win grows
        # with collection size, so assert "not slower" (within tolerance) rather than strictly faster.
        ("FILTER geo distance (within radius)", COLL, FILTER_GEO_DISTANCE_STEPS, filter_signature, False),
        ("FILTER geo within polygon", COLL, FILTER_GEO_WITHIN_STEPS, filter_signature, False),
    ]

    # Phase 1 — measure every case while no index exists (full scan path).
    unindexed = {}
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        for name, coll, steps, _sig, _strict in cases:
            best, resp = timed_agg(c, coll, steps)
            check_status(f"[unindexed] {name} (OK)", resp, "OK")
            unindexed[name] = (best, resp)

    # Build the indexes, then re-measure.
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        print("\nCreating indexes and waiting for the background build...")
        create_indexes(c)

    indexed = {}
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        for name, coll, steps, _sig, _strict in cases:
            best, resp = timed_agg(c, coll, steps)
            check_status(f"[indexed] {name} (OK)", resp, "OK")
            indexed[name] = (best, resp)

    for name, coll, steps, sig, strict in cases:
        u_time, u_resp = unindexed[name]
        i_time, i_resp = indexed[name]
        compare(name, u_time, u_resp, i_time, i_resp, sig, strict)

    # Phase 3 — correctness under asynchronous index maintenance (the consistency fixes).
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        consistency_suite(c)

    # Phase 4 — geo type: distance & within custom operators (correctness, analyze, consistency).
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        geo_suite(c)

    # Phase 5 — correctness regressions for previously-fixed index and conjunction defects.
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        regression_suite(c)

    # Phase 6 — an index-backed answer must equal the full-scan answer.
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        agreement_suite(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown_fixtures(c)

    bu.summary()


if __name__ == "__main__":
    main()
