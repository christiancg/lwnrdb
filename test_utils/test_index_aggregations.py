import os
import socket
import json
import sys
import threading
import time

HOST = "127.0.0.1"
PORT = 8989

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
# GROUP_BY/SORT read the same documents either way, so their win is CPU/structure rather than IO;
# allow a small tolerance there. DISTINCT/JOIN have a clear algorithmic win and must be faster.
SPEED_TOLERANCE = float(os.environ.get("INDEX_TEST_SPEED_TOLERANCE", "1.25"))

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

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

failures = 0


def send(s, f, payload: dict) -> dict:
    try:
        s.sendall((json.dumps(payload) + "\n").encode())
    except (BrokenPipeError, OSError):
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    raw = f.readline().decode().strip()
    if not raw:
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    return json.loads(raw)


def check(label: str, response: dict, expected_status: str):
    global failures
    actual = response.get("status")
    ok = actual == expected_status
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         expected={expected_status}  got={actual}  msg={response.get('message', '')!r}")
    if not ok:
        failures += 1


def check_true(label: str, ok: bool, detail: str = ""):
    global failures
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    if detail:
        print(f"         {detail}")
    if not ok:
        failures += 1


class Conn:
    def __init__(self):
        self.s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.s.connect((HOST, PORT))
        self.f = self.s.makefile("rb")

    def __enter__(self):
        return self.s, self.f

    def __exit__(self, *_):
        self.s.close()


def new_conn():
    return Conn()


def section(title: str):
    print(f"\n{'─' * 60}")
    print(f"  {title}")
    print(f"{'─' * 60}")


def authenticate(s, f, username: str, password: str) -> dict:
    return send(s, f, {"type": "AUTHENTICATE", "username": username, "password": password})


def stats(s, f) -> dict:
    return send(s, f, {"type": "GET_DATABASE_STATS"})


def agg(s, f, coll, steps) -> dict:
    return send(s, f, {"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                       "aggregationSteps": steps})


def timed_agg(s, f, coll, steps):
    """Run an aggregation REPEATS times and return (best_seconds, last_response)."""
    best = None
    last = None
    for _ in range(REPEATS):
        t0 = time.perf_counter()
        last = agg(s, f, coll, steps)
        dt = time.perf_counter() - t0
        best = dt if best is None else min(best, dt)
    return best, last


def rand_payload(size: int) -> str:
    return "x" * size


def category_for(v: int) -> str:
    return f"cat_{v % NUM_CATEGORIES}"


# ── fixtures ───────────────────────────────────────────────────────────────

def bulk_load(s, f, coll, docs):
    batch = []
    for doc in docs:
        batch.append(doc)
        if len(batch) >= BULK_BATCH_SIZE:
            r = send(s, f, {"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll, "objects": batch})
            if r.get("status") != "OK":
                check(f"BULK_SAVE into {coll}", r, "OK")
                return
            batch = []
    if batch:
        send(s, f, {"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll, "objects": batch})


def setup_fixtures(s, f):
    send(s, f, {"type": "CREATE_DATABASE", "databaseName": DB})
    for coll in (COLL, JOIN_LEFT, JOIN_BIG):
        send(s, f, {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})

    # Main collection: category is low-cardinality (good for GROUP_BY/DISTINCT), score is unique (SORT).
    # meta (object) and tags (array) are unique per doc so an element-match FILTER hits a single row,
    # giving the hashed object/array index a clear win over the full scan.
    bulk_load(s, f, COLL, ({
        "_id": f"doc_{v:06d}",
        "category": category_for(v),
        "score": v,
        "meta": {"n": v, "category": category_for(v)},
        "tags": ["t", str(v)],
        "payload": rand_payload(PAYLOAD_BYTES),
    } for v in range(NUM_DOCS)))

    # JOIN: small left side, every row shares the same key; large right side where only one row matches.
    bulk_load(s, f, JOIN_LEFT, ({"_id": f"left_{v:06d}", "joinKey": "shared"} for v in range(LEFT_DOCS)))
    bulk_load(s, f, JOIN_BIG, ({
        "_id": f"big_{v:06d}",
        "joinKey": "shared" if v == 0 else f"key_{v}",
        "label": "the-one" if v == 0 else f"label_{v}",
        "payload": rand_payload(PAYLOAD_BYTES),
    } for v in range(NUM_DOCS)))


def wait_for_indexes(s, f, expected, timeout_s=20.0):
    """Indexes are built in the background; poll stats until they appear, with a sleep fallback."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        r = stats(s, f)
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


def create_indexes(s, f):
    send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "category"})
    send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "score"})
    # Element-match indexes for object- and array-valued fields (hashed object/array indexes).
    send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "meta"})
    send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "tags"})
    send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": JOIN_BIG, "fieldName": "joinKey"})
    wait_for_indexes(s, f, [(COLL, "category"), (COLL, "score"), (COLL, "meta"), (COLL, "tags"),
                            (JOIN_BIG, "joinKey")])


def teardown_fixtures(s, f):
    send(s, f, {"type": "DROP_DATABASE", "databaseName": DB})


# ── step definitions (each step is the pipeline source, so the index fast-path applies) ──

DISTINCT_STEPS = [{"type": "DISTINCT", "fieldName": "category"}]
GROUP_BY_STEPS = [{"type": "GROUP_BY", "fieldName": "category"}]
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


def distinct_signature(r):
    return sorted({d.get("category") for d in (r.get("results") or [])})


def group_by_signature(r):
    return {d.get("category"): d.get("group") and len(d["group"]) for d in (r.get("results") or [])}


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
    check_true(
        "results identical with and without the index",
        sig_unindexed == sig_indexed,
        detail=f"signature={sig_indexed!r}",
    )
    ratio = (indexed_time / unindexed_time) if unindexed_time > 0 else 0.0
    detail = (f"unindexed_best={unindexed_time * 1000:.2f}ms  indexed_best={indexed_time * 1000:.2f}ms  "
              f"ratio={ratio:.2f} (lower is better)")
    if strict_speed:
        check_true("index path is faster than the full scan", indexed_time < unindexed_time, detail=detail)
    else:
        check_true(
            f"index path is not slower than the full scan (within {SPEED_TOLERANCE:.2f}x)",
            indexed_time <= unindexed_time * SPEED_TOLERANCE,
            detail=detail,
        )


# ══════════════════════════════════════════════════════════════════════════
# Consistency under asynchronous index maintenance
# ══════════════════════════════════════════════════════════════════════════

def save_doc(s, f, coll, obj) -> dict:
    return send(s, f, {"type": "SAVE", "databaseName": DB, "collectionName": coll, "object": obj})


def setup_consistency(s, f):
    for coll in (CONS, CONS_SORT, CONS_COUNT, CONS_JOIN, CONS_LEFT, CONS_CREATE):
        send(s, f, {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
    # JOIN left side: a single row that shares the key every remote row will be saved with.
    save_doc(s, f, CONS_LEFT, {"_id": "left_shared", "joinKey": "shared"})
    # Build the indexes on the (empty) collections, so every subsequent write exercises the
    # committed-but-not-yet-indexed path.
    send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": CONS, "fieldName": "status"})
    send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": CONS_SORT, "fieldName": "score"})
    send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": CONS_JOIN, "fieldName": "joinKey"})
    wait_for_indexes(s, f, [(CONS, "status"), (CONS_SORT, "score"), (CONS_JOIN, "joinKey")])


def filter_status(s, f, value):
    r = agg(s, f, CONS, [{"type": "FILTER",
                          "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": value}}])
    return sorted(d.get("_id") for d in (r.get("results") or []))


def probe_filter_no_false_negative(s, f):
    # A just-saved matching document must be returned immediately, even before it is indexed.
    bad = 0
    for i in range(CONS_REPEATS):
        value = f"fn_val_{i}"
        save_doc(s, f, CONS, {"_id": f"fn_{i}", "status": value})
        if filter_status(s, f, value) != [f"fn_{i}"]:
            bad += 1
    check_true("FILTER never misses a just-written match (no false negative)", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} immediate queries were stale")


def probe_filter_no_false_positive_on_update(s, f):
    # Re-pointing a document's indexed value must not leave it visible under the old value, and it
    # must be visible under the new value — immediately.
    save_doc(s, f, CONS, {"_id": "upd", "status": "upd_v0"})
    prev = "upd_v0"
    bad = 0
    for i in range(1, CONS_REPEATS + 1):
        cur = f"upd_v{i}"
        save_doc(s, f, CONS, {"_id": "upd", "status": cur})
        if "upd" in filter_status(s, f, prev):
            bad += 1  # stale false positive under the old value
        if filter_status(s, f, cur) != ["upd"]:
            bad += 1  # missing under the new value
        prev = cur
    check_true("FILTER reflects updates immediately (no stale false positive / no false negative)",
               bad == 0, detail=f"{bad} inconsistent immediate queries")


def probe_count_with_filter(s, f):
    # An index-only COUNT with a filter must reflect every committed matching document.
    bad = 0
    for i in range(CONS_REPEATS):
        save_doc(s, f, CONS, {"_id": f"cf_{i}", "status": "countme"})
        r = agg(s, f, CONS, [{"type": "FILTER",
                              "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": "countme"}},
                             {"type": "COUNT"}])
        got = (r.get("results") or [{}])[0].get("count")
        if got != i + 1:
            bad += 1
    check_true("index-only COUNT (with filter) counts committed docs immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} counts were stale")


def probe_whole_collection_count(s, f):
    # The no-filter COUNT comes from the synchronously-maintained PK index, so it is exact at once.
    bad = 0
    for i in range(CONS_REPEATS):
        save_doc(s, f, CONS_COUNT, {"_id": f"wc_{i}", "n": i})
        r = agg(s, f, CONS_COUNT, [{"type": "COUNT"}])
        got = (r.get("results") or [{}])[0].get("count")
        if got != i + 1:
            bad += 1
    check_true("whole-collection COUNT is exact immediately after each save", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} counts were stale")


def probe_distinct_new_value(s, f):
    # A brand-new indexed value must appear in DISTINCT immediately.
    bad = 0
    for i in range(CONS_REPEATS):
        value = f"dv_{i}"
        save_doc(s, f, CONS, {"_id": f"di_{i}", "status": value})
        r = agg(s, f, CONS, DISTINCT_STATUS_STEPS)
        values = {d.get("status") for d in (r.get("results") or [])}
        if value not in values:
            bad += 1
    check_true("DISTINCT includes a just-written new value immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} distinct results were missing the new value")


def probe_group_by(s, f):
    # A just-written document must land in its group immediately.
    bad = 0
    for i in range(CONS_REPEATS):
        value = f"gv_{i}"
        save_doc(s, f, CONS, {"_id": f"gi_{i}", "status": value})
        r = agg(s, f, CONS, [{"type": "GROUP_BY", "fieldName": "status"}])
        sizes = {d.get("status"): len(d.get("group") or []) for d in (r.get("results") or [])}
        if sizes.get(value) != 1:
            bad += 1
    check_true("GROUP_BY places a just-written doc in its group immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} group-by results were stale")


def probe_sort(s, f):
    # SORT must include and correctly order a just-written value immediately.
    bad = 0
    for i in range(CONS_REPEATS):
        save_doc(s, f, CONS_SORT, {"_id": f"s_{i}", "score": i})
        r = agg(s, f, CONS_SORT, [{"type": "SORT", "fieldName": "score", "ascending": True}])
        scores = [d.get("score") for d in (r.get("results") or [])]
        if scores != sorted(scores) or i not in scores or len(scores) != i + 1:
            bad += 1
    check_true("SORT includes and orders a just-written value immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} sort results were stale or misordered")


def probe_join(s, f):
    # A just-written remote document must be matched by an index-backed JOIN immediately.
    bad = 0
    for i in range(CONS_REPEATS):
        save_doc(s, f, CONS_JOIN, {"_id": f"r_{i}", "joinKey": "shared", "label": f"lbl_{i}"})
        r = agg(s, f, CONS_LEFT, [{"type": "JOIN", "joinCollection": CONS_JOIN, "localField": "joinKey",
                                   "remoteField": "joinKey", "asField": "joined"}])
        rows = r.get("results") or []
        joined = rows[0].get("joined") if rows else None
        if joined is None or len(joined) != i + 1:
            bad += 1
    check_true("index-backed JOIN matches a just-written remote doc immediately", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} join results were stale")


def probe_delete_consistency(s, f):
    # Deleting the only doc with a unique value must remove it from index-only COUNT and DISTINCT
    # immediately (not just from FILTER), even before the async index removal runs.
    bad = 0
    for i in range(CONS_REPEATS):
        value = f"del_{i}"
        doc_id = f"del_doc_{i}"
        save_doc(s, f, CONS, {"_id": doc_id, "status": value})
        send(s, f, {"type": "DELETE", "databaseName": DB, "collectionName": CONS, "_id": doc_id})
        # Immediately: the value must be gone from FILTER, COUNT and DISTINCT.
        if filter_status(s, f, value):
            bad += 1
        r = agg(s, f, CONS, [{"type": "FILTER",
                              "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": value}},
                             {"type": "COUNT"}])
        if (r.get("results") or [{}])[0].get("count") != 0:
            bad += 1
        d = agg(s, f, CONS, DISTINCT_STATUS_STEPS)
        if value in {row.get("status") for row in (d.get("results") or [])}:
            bad += 1
    check_true("DELETE removes a doc from FILTER, COUNT and DISTINCT immediately", bad == 0,
               detail=f"{bad} inconsistent immediate queries after delete")


def probe_same_id_rapid_updates_converge(s, f):
    # Hammer one _id with alternating values so the worker pool processes its events concurrently
    # (and possibly out of order). After the background settles, the index must reflect the LAST
    # committed value — not a reordered stale one.
    iterations = max(CONS_REPEATS, 20)
    last_value = None
    for i in range(iterations):
        last_value = f"hammer_{i}"
        save_doc(s, f, CONS, {"_id": "hammer", "status": last_value})
    wait_for_background()
    bad = 0
    if filter_status(s, f, last_value) != ["hammer"]:
        bad += 1
    if filter_status(s, f, "hammer_0"):  # an earlier value must no longer resolve to the doc
        bad += 1
    r = agg(s, f, CONS, [{"type": "FILTER",
                          "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": last_value}},
                         {"type": "COUNT"}])
    if (r.get("results") or [{}])[0].get("count") != 1:
        bad += 1
    check_true("same-id rapid updates converge to the last value after the background settles", bad == 0,
               detail=f"{bad} stale results after hammering one id with {iterations} updates")


def probe_save_delete_converges(s, f):
    # Flood save+delete pairs for distinct ids; after the background settles none of the values may
    # remain in the index (catches a save event applied after its delete event).
    for i in range(CONS_REPEATS):
        value = f"sd_{i}"
        doc_id = f"sd_doc_{i}"
        save_doc(s, f, CONS, {"_id": doc_id, "status": value})
        send(s, f, {"type": "DELETE", "databaseName": DB, "collectionName": CONS, "_id": doc_id})
    wait_for_background()
    bad = sum(1 for i in range(CONS_REPEATS) if filter_status(s, f, f"sd_{i}"))
    check_true("save+delete of the same id converges to absent after the background settles", bad == 0,
               detail=f"{bad}/{CONS_REPEATS} deleted values still present after settling")


def probe_concurrent_save_during_create_index(s, f):
    # A document saved concurrently with CREATE_INDEX must not be lost from the new index. The build
    # holds the collection write lock and registers the field synchronously, so each concurrent save is
    # serialized: it is either captured by the build's whole-collection read or indexed afterwards
    # because the field is already a known index. Without the fix, saves landing in the build window
    # were silently missing from the index forever.
    seed = 40
    for i in range(seed):
        save_doc(s, f, CONS_CREATE, {"_id": f"seed_{i}", "tag": f"t{i % 5}"})

    saved_ids = []
    saved_lock = threading.Lock()
    stop = threading.Event()

    def writer():
        with new_conn() as (ws, wf):
            authenticate(ws, wf, ADMIN_USERNAME, ADMIN_PASSWORD)
            i = 0
            while not stop.is_set():
                doc_id = f"conc_{i}"
                r = save_doc(ws, wf, CONS_CREATE, {"_id": doc_id, "tag": "concurrent"})
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
        send(s, f, {"type": "CREATE_INDEX", "databaseName": DB,
                    "collectionName": CONS_CREATE, "fieldName": "tag"})
        time.sleep(0.05)
    finally:
        stop.set()
        t.join()
    wait_for_background()

    r = agg(s, f, CONS_CREATE, [{"type": "FILTER",
                                 "operator": {"fieldOperatorType": "EQUALS", "field": "tag",
                                              "value": "concurrent"}}])
    found = {row.get("_id") for row in (r.get("results") or [])}
    with saved_lock:
        expected = list(saved_ids)
    missing = [i for i in expected if i not in found]
    check_true("no document saved concurrently with CREATE_INDEX is missing from the index",
               not missing,
               detail=f"{len(missing)} of {len(expected)} concurrently-saved docs missing from the index")


def probe_drop_burst_no_background_errors(s, f):
    # Create, populate and drop many collections (plus the database) in a burst while background
    # events for those collections are still in flight. This stresses the shared admin-collection
    # files (collections/databases) with repeated compaction; with the PK-position fix the survivors
    # stay readable and no NegativeArraySizeException / null-collection error is raised.
    burst_db = "idxagg_drop_burst"
    send(s, f, {"type": "CREATE_DATABASE", "databaseName": burst_db})
    n = max(CONS_REPEATS, 12)
    for i in range(n):
        coll = f"burst_{i}"
        send(s, f, {"type": "CREATE_COLLECTION", "databaseName": burst_db, "collectionName": coll})
        for j in range(5):
            send(s, f, {"type": "SAVE", "databaseName": burst_db, "collectionName": coll,
                        "object": {"_id": f"d{j}", "k": f"v{j}"}})
        send(s, f, {"type": "DROP_COLLECTION", "databaseName": burst_db, "collectionName": coll})
    # A survivor collection that is NOT dropped must remain fully readable afterwards.
    send(s, f, {"type": "CREATE_COLLECTION", "databaseName": burst_db, "collectionName": "keep"})
    for j in range(5):
        send(s, f, {"type": "SAVE", "databaseName": burst_db, "collectionName": "keep",
                    "object": {"_id": f"k{j}", "k": f"v{j}"}})
    wait_for_background()
    r = send(s, f, {"type": "AGGREGATE", "databaseName": burst_db, "collectionName": "keep",
                    "aggregationSteps": [{"type": "COUNT"}]})
    count = (r.get("results") or [{}])[0].get("count")
    send(s, f, {"type": "DROP_DATABASE", "databaseName": burst_db})
    check_true("drop burst keeps survivors intact (no stale-position corruption)", count == 5,
               detail=f"survivor count={count} (expected 5)")


def probe_bulk_update_same_page_no_corruption(s, f):
    # Bulk-insert several docs (they pack onto one page), then BULK_SAVE updating all of them to
    # different, longer values. Each must read back intact afterwards — regression for the
    # multi-same-page bulk-update page-corruption bug.
    coll = "idxagg_bulk_upd"
    send(s, f, {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
    ids = [f"bu_{i}" for i in range(6)]
    send(s, f, {"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll,
                "objects": [{"_id": i, "v": "short"} for i in ids]})
    send(s, f, {"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll,
                "objects": [{"_id": i, "v": f"updated-longer-value-for-{i}"} for i in ids]})
    bad = 0
    for i in ids:
        r = send(s, f, {"type": "FIND_BY_ID", "databaseName": DB, "collectionName": coll, "_id": i})
        if (r.get("object") or {}).get("v") != f"updated-longer-value-for-{i}":
            bad += 1
    send(s, f, {"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": coll})
    check_true("bulk update of multiple same-page docs reads back intact (no page corruption)", bad == 0,
               detail=f"{bad}/{len(ids)} docs corrupted/stale after bulk update")


def probe_convergence(s, f):
    # After the background settles, the document is found via the (now-updated, re-evicted) index.
    save_doc(s, f, CONS, {"_id": "converge", "status": "converged"})
    wait_for_background()
    check_true("index converges after the background settles", filter_status(s, f, "converged") == ["converge"])


def wait_for_background():
    time.sleep(2)


DISTINCT_STATUS_STEPS = [{"type": "DISTINCT", "fieldName": "status"}]


def consistency_suite(s, f):
    section("Consistency under asynchronous index maintenance (pending-write window)")
    setup_consistency(s, f)
    probe_filter_no_false_negative(s, f)
    probe_filter_no_false_positive_on_update(s, f)
    probe_count_with_filter(s, f)
    probe_whole_collection_count(s, f)
    probe_distinct_new_value(s, f)
    probe_group_by(s, f)
    probe_sort(s, f)
    probe_join(s, f)
    probe_delete_consistency(s, f)
    probe_same_id_rapid_updates_converge(s, f)
    probe_save_delete_converges(s, f)
    probe_concurrent_save_during_create_index(s, f)
    probe_drop_burst_no_background_errors(s, f)
    probe_bulk_update_same_page_no_corruption(s, f)
    probe_convergence(s, f)


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    print("\n" + "═" * 60)
    print("  LWNRDB — index-backed aggregation performance test suite")
    print("═" * 60)
    print(f"  Connecting to {HOST}:{PORT}")
    print(f"  Plan: load {NUM_DOCS} docs into {COLL} ({NUM_CATEGORIES} categories, each with an object "
          f"meta + array tags), {LEFT_DOCS} left + {NUM_DOCS} right docs for JOIN.")
    print(f"        Measure GROUP_BY/JOIN/SORT/DISTINCT and object/array element-match FILTER unindexed, "
          f"then create indexes and re-measure.")

    with new_conn() as (s, f):
        r = authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            sys.exit(1)
        teardown_fixtures(s, f)
        print("\nLoading fixtures (this can take a moment)...")
        setup_fixtures(s, f)

    cases = [
        ("DISTINCT on category", COLL, DISTINCT_STEPS, distinct_signature, True),
        ("GROUP_BY on category", COLL, GROUP_BY_STEPS, group_by_signature, False),
        ("SORT on score (+LIMIT 10)", COLL, SORT_STEPS, sort_signature, False),
        ("JOIN against a large remote collection", JOIN_LEFT, JOIN_STEPS, join_signature, True),
        ("FILTER element-match on object field", COLL, FILTER_OBJECT_STEPS, filter_signature, True),
        ("FILTER element-match on array field", COLL, FILTER_ARRAY_STEPS, filter_signature, True),
        ("FILTER IN over a list of objects", COLL, FILTER_OBJECT_IN_STEPS, filter_signature, True),
    ]

    # Phase 1 — measure every case while no index exists (full scan path).
    unindexed = {}
    with new_conn() as (s, f):
        authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        for name, coll, steps, _sig, _strict in cases:
            best, resp = timed_agg(s, f, coll, steps)
            check(f"[unindexed] {name} (OK)", resp, "OK")
            unindexed[name] = (best, resp)

    # Build the indexes, then re-measure.
    with new_conn() as (s, f):
        authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        print("\nCreating indexes and waiting for the background build...")
        create_indexes(s, f)

    indexed = {}
    with new_conn() as (s, f):
        authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        for name, coll, steps, _sig, _strict in cases:
            best, resp = timed_agg(s, f, coll, steps)
            check(f"[indexed] {name} (OK)", resp, "OK")
            indexed[name] = (best, resp)

    for name, coll, steps, sig, strict in cases:
        u_time, u_resp = unindexed[name]
        i_time, i_resp = indexed[name]
        compare(name, u_time, u_resp, i_time, i_resp, sig, strict)

    # Phase 3 — correctness under asynchronous index maintenance (the consistency fixes).
    with new_conn() as (s, f):
        authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        consistency_suite(s, f)

    with new_conn() as (s, f):
        authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown_fixtures(s, f)

    print("\n" + "═" * 60)
    if failures == 0:
        print(f"  \033[92mAll checks passed.\033[0m")
    else:
        print(f"  \033[91m{failures} check(s) FAILED.\033[0m")
    print("═" * 60 + "\n")

    sys.exit(0 if failures == 0 else 1)


if __name__ == "__main__":
    main()
