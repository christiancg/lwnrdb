import os
import socket
import json
import sys
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
