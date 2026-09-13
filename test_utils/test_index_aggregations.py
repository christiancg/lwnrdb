import os
import sys
import threading
import time

import base_utils as bu
from base_utils import Conn, check, check_status, section

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
# Correctness regressions
# ══════════════════════════════════════════════════════════════════════════

REG_UNICODE = "idxagg_reg_unicode"
REG_SINGLE = "idxagg_reg_single"
REG_CONJ = "idxagg_reg_conj"
REG_NUMERIC = "idxagg_reg_numeric"


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


def regression_suite(c):
    section("Correctness regressions: non-ASCII index values, single-valued index ranges, "
            "low-cardinality numeric indexes, conjunctions over a filtered stream")
    probe_non_ascii_indexed_values(c)
    probe_single_valued_index_ranges(c)
    probe_low_cardinality_numeric_index(c)
    probe_conjunction_after_a_filter_step(c)


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

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown_fixtures(c)

    bu.summary()


if __name__ == "__main__":
    main()
