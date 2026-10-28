import os
import sys
import tempfile
import threading
import time
import random
import string

import base_utils as bu
from base_utils import Conn, check, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("STREAM_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

DB = "stream_db"
COLL = "stream_coll"
JOIN_COLL = "stream_join"
NUM_DOCS = int(os.environ.get("STREAM_TEST_DOCS", "8000"))
PAYLOAD_BYTES = int(os.environ.get("STREAM_TEST_PAYLOAD_BYTES", "512"))
BULK_BATCH_SIZE = int(os.environ.get("STREAM_TEST_BATCH_SIZE", "500"))

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)


def stats(c) -> dict:
    return c.send({"type": "GET_DATABASE_STATS"})


CACHE_CAP_TOLERANCE = 1.20


def print_memory_snapshot(label: str, c) -> dict:
    r = stats(c)
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
    check(
        f"{label}: userCacheBytes={used} <= maxMemoryBytes*{CACHE_CAP_TOLERANCE}={budget}",
        used <= budget,
        detail=("streaming reads kept the cache within the configured cap"
                if used <= budget
                else "the streaming read path appears to be bloating the cache"),
    )


def agg(c, coll, steps) -> dict:
    return c.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                       "aggregationSteps": steps})


def field_filter(field, op, value):
    return {"type": "FILTER", "operator": {"fieldOperatorType": op, "field": field, "value": value}}


def rand_str(size: int) -> str:
    return ''.join(random.choices(string.ascii_letters + string.digits, k=size))


def email_for(v: int) -> str:
    return f"user{v}@example.com"


def setup_fixtures(c: Conn):
    c.send({"type": "CREATE_DATABASE", "databaseName": DB})
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL})
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": JOIN_COLL})

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
            r = c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL, "objects": batch})
            if r.get("status") != "OK":
                check_status("BULK_SAVE during fixture load", r, "OK")
                return
            batch = []
    if batch:
        c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL, "objects": batch})

    c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": JOIN_COLL, "objects": [
        {"_id": "active", "status": "active", "label": "Active users"},
        {"_id": "inactive", "status": "inactive", "label": "Inactive users"},
    ]})

    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "status"})
    c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": "email"})
    time.sleep(3)


def teardown_fixtures(c):
    c.send({"type": "DROP_DATABASE", "databaseName": DB})


def test_indexed_filter_correctness(c):
    section("Indexed FILTER (targeted fetch) — status EQUALS 'active'")
    expected_active = sum(1 for v in range(NUM_DOCS) if v % 2 == 0)
    r = agg(c, COLL, [field_filter("status", "EQUALS", "active")])
    check_status("AGGREGATE FILTER status==active (OK)", r, "OK")
    got = len(r.get("results") or [])
    check(
        f"Returned all active docs (got {got}, expected {expected_active})",
        got == expected_active,
        detail="targeted fetch via the status index returned the correct rows",
    )


def test_indexed_email_exact(c):
    section("Indexed FILTER (targeted fetch) — single exact email")
    target_v = NUM_DOCS // 2
    r = agg(c, COLL, [field_filter("email", "EQUALS", email_for(target_v))])
    check_status("AGGREGATE FILTER email==<known> (OK)", r, "OK")
    results = r.get("results") or []
    check(
        f"Exactly one doc returned for unique email (got {len(results)})",
        len(results) == 1 and results[0].get("_id") == f"doc_{target_v:06d}",
        detail=f"resolved id={results[0].get('_id') if results else None}",
    )


def test_indexed_in_filter(c):
    section("Indexed FILTER — email IN [three known]")
    vs = sorted({NUM_DOCS // 10, NUM_DOCS // 2, NUM_DOCS - 1})
    r = agg(c, COLL, [field_filter("email", "IN", [email_for(v) for v in vs])])
    check_status("AGGREGATE FILTER email IN [...] (OK)", r, "OK")
    ids = {d.get("_id") for d in (r.get("results") or [])}
    expected = {f"doc_{v:06d}" for v in vs}
    check(
        f"Exactly the three requested docs returned (got {len(ids)})",
        ids == expected,
        detail=f"ids={sorted(ids)}",
    )


def test_full_scan_filter_correctness(c):
    section("Full-scan FILTER (page streaming) — unindexed v GREATER_THAN mid")
    mid = NUM_DOCS // 2
    expected = sum(1 for v in range(NUM_DOCS) if v > mid)
    r = agg(c, COLL, [field_filter("v", "GREATER_THAN", mid)])
    check_status("AGGREGATE FILTER v>mid on unindexed field (OK)", r, "OK")
    got = len(r.get("results") or [])
    check(
        f"Full scan returned correct tail (got {got}, expected {expected})",
        got == expected,
        detail="page-by-page streaming scan produced the same result as the oracle",
    )


def test_streamable_steps_bounded(c):
    section("Streamable steps stay memory-bounded — indexed FILTER + LIMIT 10")
    before = print_memory_snapshot("before bounded query", c)
    r = agg(c, COLL, [field_filter("status", "EQUALS", "active"), {"type": "LIMIT", "limit": 10}])
    check_status("AGGREGATE FILTER+LIMIT (OK)", r, "OK")
    got = len(r.get("results") or [])
    check(f"LIMIT honored (got {got} <= 10)", got <= 10)
    after = print_memory_snapshot("after bounded query", c)
    assert_cache_respects_cap("bounded query keeps cache within cap", after)
    _ = before


def test_targeted_fetch_does_not_bloat_cache(c):
    section("Targeted fetch does not load the whole collection into cache")
    snap = print_memory_snapshot("before point query", c)
    on_disk = snap.get("totals", {}).get("sizeBytes", 0)
    mem = snap.get("memory", {})
    if mem.get("cachingDisabled") or mem.get("cacheUnlimited"):
        print("  [skip] caching disabled/unlimited — cache-growth assertion not applicable")
        return
    before_cache = mem.get("userCacheBytes", 0)
    agg(c, COLL, [field_filter("email", "EQUALS", email_for(NUM_DOCS // 3))])
    after = print_memory_snapshot("after point query", c)
    after_cache = after.get("memory", {}).get("userCacheBytes", 0)
    growth = after_cache - before_cache
    check(
        f"Cache grew by {growth}B, far less than full collection ({on_disk}B)",
        on_disk == 0 or growth < on_disk,
        detail="an index hit fetches only matched entries, not the whole collection",
    )


def test_large_collection_under_tight_memory(c):
    section("Full scan over a multi-page collection completes (no OOM)")
    snap = print_memory_snapshot("before tight-memory scan", c)
    on_disk = snap.get("totals", {}).get("sizeBytes", 0)
    cap = snap.get("memory", {}).get("maxMemoryBytes", 0)
    if cap > 0 and on_disk <= cap:
        print(f"  [note] on-disk size ({on_disk}B) <= cap ({cap}B): lower maxMemory in "
              f"lwnrdb.cfg (and -Xmx) to actually stress the streaming path.")
    r = agg(c, COLL, [field_filter("v", "GREATER_THAN_EQUALS", 0)])
    check_status("Full scan of entire collection completes (OK)", r, "OK")
    got = len(r.get("results") or [])
    check(
        f"Full scan returned every doc (got {got}, expected {NUM_DOCS})",
        got == NUM_DOCS,
        detail="streamed page-by-page instead of materializing the whole collection at once",
    )


def test_blocking_steps_still_work(c):
    section("Blocking steps parity — SORT / GROUP_BY / DISTINCT / JOIN")

    r_sort = agg(c, COLL, [field_filter("status", "EQUALS", "active"),
                              {"type": "SORT", "fieldName": "v", "ascending": True},
                              {"type": "LIMIT", "limit": 5}])
    check_status("SORT after FILTER (OK)", r_sort, "OK")
    sorted_vs = [d.get("v") for d in (r_sort.get("results") or [])]
    check(f"SORT ascending honored {sorted_vs}", sorted_vs == sorted(sorted_vs))

    r_distinct = agg(c, COLL, [{"type": "DISTINCT", "fieldName": "status"}])
    check_status("DISTINCT on status (OK)", r_distinct, "OK")
    distinct_vals = {d.get("status") for d in (r_distinct.get("results") or [])}
    check(f"DISTINCT status == {{active, inactive}} (got {distinct_vals})",
               distinct_vals == {"active", "inactive"})

    r_group = agg(c, COLL, [{"type": "GROUP_BY", "fieldName": "status"}])
    check_status("GROUP_BY status (OK)", r_group, "OK")
    check("GROUP_BY produced two groups",
               len(r_group.get("results") or []) == 2)

    r_join = agg(c, COLL, [field_filter("email", "EQUALS", email_for(0)),
                              {"type": "JOIN", "joinCollection": JOIN_COLL, "localField": "status",
                               "remoteField": "status", "asField": "statusInfo"}])
    check_status("JOIN against small collection (OK)", r_join, "OK")
    joined = r_join.get("results") or []
    check("JOIN attached statusInfo to the matched doc",
               bool(joined) and joined[0].get("statusInfo") is not None,
               detail=f"statusInfo={joined[0].get('statusInfo') if joined else None}")


SKIP_PAGE = 500


def best_time(c, steps, rounds=5):
    best = None
    for _ in range(rounds):
        started = time.perf_counter()
        agg(c, COLL, steps)
        elapsed = time.perf_counter() - started
        best = elapsed if best is None else min(best, elapsed)
    return best


def test_skip_pages_tile_the_scan_and_stay_flat(c):
    section("SKIP + LIMIT pages tile the whole scan across page files, and the last page costs like the first")
    paged = []
    for skip in range(0, NUM_DOCS + SKIP_PAGE, SKIP_PAGE):
        r = agg(c, COLL, [{"type": "SKIP", "skip": skip}, {"type": "LIMIT", "limit": SKIP_PAGE}])
        paged += [d.get("_id") for d in (r.get("results") or [])]
    whole = [d.get("_id") for d in (agg(c, COLL, [{"type": "SKIP", "skip": 0}]).get("results") or [])]
    check(f"the pages concatenate to the whole scan ({len(paged)} of {len(whole)} rows)", paged == whole)
    check("no document is repeated or missed", len(set(paged)) == NUM_DOCS == len(paged),
          detail=f"distinct={len(set(paged))} rows={len(paged)}")
    first = best_time(c, [{"type": "SKIP", "skip": 0}, {"type": "LIMIT", "limit": 100}])
    last = best_time(c, [{"type": "SKIP", "skip": NUM_DOCS - 100}, {"type": "LIMIT", "limit": 100}])
    check(f"the last page ({last * 1000:.1f} ms) costs at most 3x the first ({first * 1000:.1f} ms)",
          last <= max(3 * first, 0.05))


TIGHT_PORT = int(os.environ.get("STREAM_TIGHT_TEST_PORT", "9450"))
TIGHT_DOCS = 5_000
TIGHT_WRITES = 400


def dirty_find(c: Conn, id_: str) -> dict:
    return c.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": id_, "dirtyRead": True})


def test_dirty_find_by_id_under_tight_memory():
    section("Dirty FIND_BY_ID answers present and absent ids while the pk index is not resident")
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-stream-tight-")
    log_path = os.path.join(work_dir, "server.log")
    bu.write_config(work_dir, TIGHT_PORT, maxMemory="64kb")
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path, port=TIGHT_PORT)
        with Conn(port=TIGHT_PORT) as c:
            c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            c.send({"type": "CREATE_DATABASE", "databaseName": DB})
            c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL})
            for start in range(0, TIGHT_DOCS, 1_000):
                check_status(f"bulk {start}", c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL,
                                                      "objects": [{"_id": f"t{i:05d}", "v": i}
                                                                  for i in range(start, start + 1_000)]},
                                                     timeout=120), "OK")
        stop = threading.Event()
        written = []

        def writer():
            with Conn(port=TIGHT_PORT) as w:
                w.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
                i = 0
                while not stop.is_set() and i < TIGHT_WRITES:
                    j = (i * 7 + 3) % TIGHT_DOCS
                    w.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                            "object": {"_id": f"t{j:05d}", "v": j}})
                    w.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                            "object": {"_id": f"new{i:05d}", "v": -i}})
                    written.append(i)
                    i += 1

        thread = threading.Thread(target=writer)
        thread.start()
        wrong = []
        with Conn(port=TIGHT_PORT) as c:
            c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            for i in range(0, TIGHT_DOCS, 7):
                found = dirty_find(c, f"t{i:05d}")
                if found.get("status") != "OK" or (found.get("object") or {}).get("v") != i:
                    wrong.append((i, found.get("status"), found.get("errorCode")))
                absent = dirty_find(c, f"absent{i}")
                if absent.get("errorCode") != "404-2":
                    wrong.append((f"absent{i}", absent.get("status"), absent.get("errorCode")))
            stop.set()
            thread.join(60)
            last = written[-1] if written else None
            if last is not None:
                fresh = dirty_find(c, f"new{last:05d}")
                check("a write acknowledged before the read is visible to it",
                      fresh.get("status") == "OK", f"resp={fresh}")
            mem = stats(c).get("stats", {}).get("memory", {})
        check(f"every dirty point read answered right under concurrent writes ({len(wrong)} wrong)", not wrong,
              f"first wrong: {wrong[:3]}")
        check(f"the cache stays within its cap ({mem.get('userCacheBytes')} of {mem.get('maxMemoryBytes')} bytes)",
              mem.get("userCacheBytes", 0) <= mem.get("maxMemoryBytes", 0) * CACHE_CAP_TOLERANCE)
    finally:
        bu.stop_server(proc, port=TIGHT_PORT, wait_for_port=False)
        if bu.failure_count():
            bu.dump_log(log_path)


def test_server_still_alive(c):
    section("Liveness check after the whole run")
    check_status("AUTHENTICATE still works", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    check_status("LIST_DATABASES still responds", c.send({"type": "LIST_DATABASES"}), "OK")


def main():
    bu.banner("Streaming / targeted-fetch read path test suite", HOST, PORT)
    print(f"  Plan: load {NUM_DOCS} docs (~{(NUM_DOCS * PAYLOAD_BYTES) // (1024*1024)}MB) "
          f"across multiple pages, index status+email, leave v unindexed.")
    print(f"        Tune maxMemory in lwnrdb.cfg low (and -Xmx) to stress the streaming guard.")

    with Conn() as c:
        r = c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            sys.exit(1)
        teardown_fixtures(c)
        print("\nLoading fixtures (this can take a moment)...")
        setup_fixtures(c)

    scenarios = [
        test_indexed_filter_correctness,
        test_indexed_email_exact,
        test_indexed_in_filter,
        test_full_scan_filter_correctness,
        test_streamable_steps_bounded,
        test_targeted_fetch_does_not_bloat_cache,
        test_large_collection_under_tight_memory,
        test_blocking_steps_still_work,
        test_skip_pages_tile_the_scan_and_stay_flat,
    ]
    for scenario in scenarios:
        with Conn() as c:
            c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            scenario(c)

    test_dirty_find_by_id_under_tight_memory()

    with Conn() as c:
        test_server_still_alive(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown_fixtures(c)

    bu.summary()


if __name__ == "__main__":
    main()
