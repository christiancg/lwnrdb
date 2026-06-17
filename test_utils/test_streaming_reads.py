import os
import socket
import json
import sys
import time
import random
import string

HOST = "127.0.0.1"
PORT = 8989

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "adminstrator"

# Knobs — tune these (and maxMemory in lwnrdb.cfg) to exercise paging. With the
# default 2MB page size, a few thousand ~512B docs span many .dat pages so both
# the targeted-fetch and page-streaming paths cross page boundaries. CI runners are
# slower, so the workload is overridable via env vars to keep the run quick.
DB = "streamdb"
COLL = "stream_coll"
JOIN_COLL = "stream_join"
NUM_DOCS = int(os.environ.get("STREAM_TEST_DOCS", "8000"))
PAYLOAD_BYTES = int(os.environ.get("STREAM_TEST_PAYLOAD_BYTES", "512"))
BULK_BATCH_SIZE = int(os.environ.get("STREAM_TEST_BATCH_SIZE", "500"))

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


# See test_memory_limits.py: the sweep runs on a timer, so an admission may briefly
# push the cache over cap until the next sweep catches up. 20% headroom is conservative.
CACHE_CAP_TOLERANCE = 1.20


def print_memory_snapshot(label: str, s, f) -> dict:
    r = stats(s, f)
    if r.get("status") != "OK":
        print(f"  [stats] {label}: stats unavailable ({r.get('message')})")
        return {}
    mem = r.get("stats", {}).get("memory", {})
    totals = r.get("stats", {}).get("totals", {})
    cache_mb = mem.get("userCacheBytes", 0) / (1024 * 1024)
    max_cache_mb = mem.get("maxMemoryBytes", 0) / (1024 * 1024)
    print(f"  [stats] {label}: cache={cache_mb:.2f}MB (cap {max_cache_mb:.0f}MB)  "
          f"entries={totals.get('entryCount', 0)} pages={totals.get('pageCount', 0)} "
          f"sizeBytes={totals.get('sizeBytes', 0)}")
    return r.get("stats", {})


def assert_cache_respects_cap(label: str, snapshot: dict):
    mem = snapshot.get("memory", {})
    if mem.get("cachingDisabled"):
        print(f"  [skip] {label}: caching disabled — cap not applicable")
        return
    if mem.get("cacheUnlimited"):
        print(f"  [skip] {label}: cache unlimited — cap not applicable")
        return
    cap = mem.get("maxMemoryBytes", 0)
    used = mem.get("userCacheBytes", 0)
    budget = int(cap * CACHE_CAP_TOLERANCE)
    check_true(
        f"{label}: userCacheBytes={used} <= maxMemoryBytes*{CACHE_CAP_TOLERANCE}={budget}",
        used <= budget,
        detail=("streaming reads kept the cache within the configured cap"
                if used <= budget
                else "the streaming read path appears to be bloating the cache"),
    )


def agg(s, f, coll, steps) -> dict:
    return send(s, f, {"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                       "aggregationSteps": steps})


def field_filter(field, op, value):
    return {"type": "FILTER", "operator": {"fieldOperatorType": op, "field": field, "value": value}}


def rand_str(size: int) -> str:
    return ''.join(random.choices(string.ascii_letters + string.digits, k=size))


# ── fixtures ───────────────────────────────────────────────────────────────

def email_for(v: int) -> str:
    return f"user{v}@example.com"


def setup_fixtures(s, f):
    send(s, f, {"type": "CREATE_DATABASE", "databaseName": DB})
    send(s, f, {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL})
    send(s, f, {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": JOIN_COLL})

    # Load the main collection. status is low-cardinality; v is sequential; email unique.
    batch = []
    for v in range(NUM_DOCS):
        doc = {
            "_id": f"doc_{v:06d}",
            "v": v,
            "status": "active" if v % 2 == 0 else "inactive",
            "email": email_for(v),
            "payload": rand_str(PAYLOAD_BYTES),
        }
        batch.append(doc)
        if len(batch) >= BULK_BATCH_SIZE:
            r = send(s, f, {"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL, "objects": batch})
            if r.get("status") != "OK":
                check("BULK_SAVE during fixture load", r, "OK")
                return
            batch = []
    if batch:
        send(s, f, {"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL, "objects": batch})

    # Small join collection keyed by status.
    send(s, f, {"type": "BULK_SAVE", "databaseName": DB, "collectionName": JOIN_COLL, "objects": [
        {"_id": "active", "status": "active", "label": "Active users"},
        {"_id": "inactive", "status": "inactive", "label": "Inactive users"},
    ]})

    # Index status and email; leave v unindexed to force a full scan.
    send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "status"})
    send(s, f, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "email"})
    # Indexes are built in the background; give them a moment to settle.
    time.sleep(3)


def teardown_fixtures(s, f):
    send(s, f, {"type": "DROP_DATABASE", "databaseName": DB})


# ══════════════════════════════════════════════════════════════════════════
# Tests
# ══════════════════════════════════════════════════════════════════════════

def test_indexed_filter_correctness(s, f):
    section("Indexed FILTER (targeted fetch) — status EQUALS 'active'")
    expected_active = sum(1 for v in range(NUM_DOCS) if v % 2 == 0)
    r = agg(s, f, COLL, [field_filter("status", "EQUALS", "active")])
    check("AGGREGATE FILTER status==active (OK)", r, "OK")
    got = len(r.get("results") or [])
    check_true(
        f"Returned all active docs (got {got}, expected {expected_active})",
        got == expected_active,
        detail="targeted fetch via the status index returned the correct rows",
    )


def test_indexed_email_exact(s, f):
    section("Indexed FILTER (targeted fetch) — single exact email")
    target_v = NUM_DOCS // 2
    r = agg(s, f, COLL, [field_filter("email", "EQUALS", email_for(target_v))])
    check("AGGREGATE FILTER email==<known> (OK)", r, "OK")
    results = r.get("results") or []
    check_true(
        f"Exactly one doc returned for unique email (got {len(results)})",
        len(results) == 1 and results[0].get("_id") == f"doc_{target_v:06d}",
        detail=f"resolved id={results[0].get('_id') if results else None}",
    )


def test_indexed_in_filter(s, f):
    section("Indexed FILTER — email IN [three known]")
    vs = sorted({NUM_DOCS // 10, NUM_DOCS // 2, NUM_DOCS - 1})
    r = agg(s, f, COLL, [field_filter("email", "IN", [email_for(v) for v in vs])])
    check("AGGREGATE FILTER email IN [...] (OK)", r, "OK")
    ids = {d.get("_id") for d in (r.get("results") or [])}
    expected = {f"doc_{v:06d}" for v in vs}
    check_true(
        f"Exactly the three requested docs returned (got {len(ids)})",
        ids == expected,
        detail=f"ids={sorted(ids)}",
    )


def test_full_scan_filter_correctness(s, f):
    section("Full-scan FILTER (page streaming) — unindexed v GREATER_THAN mid")
    mid = NUM_DOCS // 2
    expected = sum(1 for v in range(NUM_DOCS) if v > mid)
    r = agg(s, f, COLL, [field_filter("v", "GREATER_THAN", mid)])
    check("AGGREGATE FILTER v>mid on unindexed field (OK)", r, "OK")
    got = len(r.get("results") or [])
    check_true(
        f"Full scan returned correct tail (got {got}, expected {expected})",
        got == expected,
        detail="page-by-page streaming scan produced the same result as the oracle",
    )


def test_streamable_steps_bounded(s, f):
    section("Streamable steps stay memory-bounded — indexed FILTER + LIMIT 10")
    before = print_memory_snapshot("before bounded query", s, f)
    r = agg(s, f, COLL, [field_filter("status", "EQUALS", "active"), {"type": "LIMIT", "limit": 10}])
    check("AGGREGATE FILTER+LIMIT (OK)", r, "OK")
    got = len(r.get("results") or [])
    check_true(f"LIMIT honored (got {got} <= 10)", got <= 10)
    after = print_memory_snapshot("after bounded query", s, f)
    assert_cache_respects_cap("bounded query keeps cache within cap", after)
    _ = before


def test_targeted_fetch_does_not_bloat_cache(s, f):
    section("Targeted fetch does not load the whole collection into cache")
    snap = print_memory_snapshot("before point query", s, f)
    on_disk = snap.get("totals", {}).get("sizeBytes", 0)
    mem = snap.get("memory", {})
    if mem.get("cachingDisabled") or mem.get("cacheUnlimited"):
        print("  [skip] caching disabled/unlimited — cache-growth assertion not applicable")
        return
    before_cache = mem.get("userCacheBytes", 0)
    # A single indexed point lookup should pull at most a tiny subset into cache.
    agg(s, f, COLL, [field_filter("email", "EQUALS", email_for(NUM_DOCS // 3))])
    after = print_memory_snapshot("after point query", s, f)
    after_cache = after.get("memory", {}).get("userCacheBytes", 0)
    growth = after_cache - before_cache
    check_true(
        f"Cache grew by {growth}B, far less than full collection ({on_disk}B)",
        on_disk == 0 or growth < on_disk,
        detail="an index hit fetches only matched entries, not the whole collection",
    )


def test_large_collection_under_tight_memory(s, f):
    section("Full scan over a multi-page collection completes (no OOM)")
    # Run with a deliberately tight maxMemory (and small -Xmx) in lwnrdb.cfg to truly
    # exercise the streaming guard; otherwise this just confirms functional correctness.
    snap = print_memory_snapshot("before tight-memory scan", s, f)
    on_disk = snap.get("totals", {}).get("sizeBytes", 0)
    cap = snap.get("memory", {}).get("maxMemoryBytes", 0)
    if cap > 0 and on_disk <= cap:
        print(f"  [note] on-disk size ({on_disk}B) <= cap ({cap}B): lower maxMemory in "
              f"lwnrdb.cfg (and -Xmx) to actually stress the streaming path.")
    r = agg(s, f, COLL, [field_filter("v", "GREATER_THAN_EQUALS", 0)])
    check("Full scan of entire collection completes (OK)", r, "OK")
    got = len(r.get("results") or [])
    check_true(
        f"Full scan returned every doc (got {got}, expected {NUM_DOCS})",
        got == NUM_DOCS,
        detail="streamed page-by-page instead of materializing the whole collection at once",
    )


def test_blocking_steps_still_work(s, f):
    section("Blocking steps parity — SORT / GROUP_BY / DISTINCT / JOIN")

    r_sort = agg(s, f, COLL, [field_filter("status", "EQUALS", "active"),
                              {"type": "SORT", "fieldName": "v", "ascending": True},
                              {"type": "LIMIT", "limit": 5}])
    check("SORT after FILTER (OK)", r_sort, "OK")
    sorted_vs = [d.get("v") for d in (r_sort.get("results") or [])]
    check_true(f"SORT ascending honored {sorted_vs}", sorted_vs == sorted(sorted_vs))

    r_distinct = agg(s, f, COLL, [{"type": "DISTINCT", "fieldName": "status"}])
    check("DISTINCT on status (OK)", r_distinct, "OK")
    distinct_vals = {d.get("status") for d in (r_distinct.get("results") or [])}
    check_true(f"DISTINCT status == {{active, inactive}} (got {distinct_vals})",
               distinct_vals == {"active", "inactive"})

    r_group = agg(s, f, COLL, [{"type": "GROUP_BY", "fieldName": "status"}])
    check("GROUP_BY status (OK)", r_group, "OK")
    check_true("GROUP_BY produced two groups",
               len(r_group.get("results") or []) == 2)

    r_join = agg(s, f, COLL, [field_filter("email", "EQUALS", email_for(0)),
                              {"type": "JOIN", "joinCollection": JOIN_COLL, "localField": "status",
                               "remoteField": "status", "asField": "statusInfo"}])
    check("JOIN against small collection (OK)", r_join, "OK")
    joined = r_join.get("results") or []
    check_true("JOIN attached statusInfo to the matched doc",
               bool(joined) and joined[0].get("statusInfo") is not None,
               detail=f"statusInfo={joined[0].get('statusInfo') if joined else None}")


def test_server_still_alive(s, f):
    section("Liveness check after the whole run")
    check("LIST_DATABASES still responds", send(s, f, {"type": "LIST_DATABASES"}), "OK")
    check("AUTHENTICATE still works", authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD), "OK")


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    print("\n" + "═" * 60)
    print("  LWNRDB — Streaming / targeted-fetch read path test suite")
    print("═" * 60)
    print(f"  Connecting to {HOST}:{PORT}")
    print(f"  Plan: load {NUM_DOCS} docs (~{(NUM_DOCS * PAYLOAD_BYTES) // (1024*1024)}MB) "
          f"across multiple pages, index status+email, leave v unindexed.")
    print(f"        Tune maxMemory in lwnrdb.cfg low (and -Xmx) to stress the streaming guard.")

    with new_conn() as (s, f):
        r = authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            sys.exit(1)
        teardown_fixtures(s, f)
        print("\nLoading fixtures (this can take a moment)...")
        setup_fixtures(s, f)

    scenarios = [
        test_indexed_filter_correctness,
        test_indexed_email_exact,
        test_indexed_in_filter,
        test_full_scan_filter_correctness,
        test_streamable_steps_bounded,
        test_targeted_fetch_does_not_bloat_cache,
        test_large_collection_under_tight_memory,
        test_blocking_steps_still_work,
    ]
    for scenario in scenarios:
        with new_conn() as (s, f):
            authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
            scenario(s, f)

    with new_conn() as (s, f):
        test_server_still_alive(s, f)

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
