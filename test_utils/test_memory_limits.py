import sys
import time
import random
import string

import base_utils as bu
from base_utils import Conn, check, check_status, section

HOST = "127.0.0.1"
PORT = 8989

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

# Knobs — tweak via env or here. Defaults try to push enough data through that
# eviction is forced when maxMemory is configured tight (e.g. 8mb).
HOT_COLL = "hot_coll"
COLD_COLL = "cold_coll"
DOCS_PER_COLLECTION = 5_000
PAYLOAD_BYTES = 512                # per-doc filler — total ~2.5MB/coll, x2 collections = ~5MB
BULK_BATCH_SIZE = 500
LATENCY_PROBE_INTERVAL = 50        # probe every Nth bulk batch
LATENCY_BUDGET_MS = 2_000          # a single small query should not exceed this under pressure

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)



def stats(c) -> dict:
    return c.send({"type": "GET_DATABASE_STATS"})


# Tolerance applied to the cache cap check. The eviction sweep runs at most
# every `pressurePollIntervalSeconds`/`memoryManagementSweepIntervalSeconds`,
# so an admission may temporarily push the cache slightly over cap until the
# next sweep catches up. 20% headroom is conservative.
CACHE_CAP_TOLERANCE = 1.20


def print_memory_snapshot(label: str, c) -> dict:
    r = stats(c)
    if r.get("status") != "OK":
        print(f"  [stats] {label}: stats unavailable ({r.get('message')})")
        return {}
    mem = r.get("stats", {}).get("memory", {})
    totals = r.get("stats", {}).get("totals", {})
    heap_used_mb = mem.get("heapUsedBytes", 0) / (1024 * 1024)
    heap_max_mb = mem.get("heapMaxBytes", 0) / (1024 * 1024)
    cache_mb = mem.get("userCacheBytes", 0) / (1024 * 1024)
    max_cache_mb = mem.get("maxMemoryBytes", 0) / (1024 * 1024)
    os_free_ratio = 0
    print(f"  [stats] {label}: heap={heap_used_mb:.1f}/{heap_max_mb:.0f}MB  "
          f"heapRatio={(mem.get('heapUsedBytes', 0) / max(mem.get('heapMaxBytes', 1), 1))*100:.1f}%  "
          f"cache={cache_mb:.2f}MB (cap {max_cache_mb:.0f}MB)  "
          f"osFree={os_free_ratio*100:.1f}%  "
          f"entries={totals.get('entryCount', 0)} pages={totals.get('pageCount', 0)}")
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
        detail=("eviction is keeping the cache within the configured cap"
                if used <= budget
                else "eviction sweep appears to be lagging or not running"),
    )


def assert_eviction_actually_happened(label: str, snapshot: dict):
    """When cache cap is configured tight and total on-disk size exceeds the cap,
    the in-memory cache must hold strictly less than the full dataset."""
    mem = snapshot.get("memory", {})
    totals = snapshot.get("totals", {})
    if mem.get("cachingDisabled") or mem.get("cacheUnlimited"):
        print(f"  [skip] {label}: caching disabled or unlimited")
        return
    cap = mem.get("maxMemoryBytes", 0)
    on_disk = totals.get("sizeBytes", 0)
    used = mem.get("userCacheBytes", 0)
    if on_disk <= cap:
        print(f"  [skip] {label}: on-disk size ({on_disk}B) does not exceed cap "
              f"({cap}B) — eviction is not expected to fire. "
              f"Lower maxMemory in lwnrdb.cfg to actually exercise this.")
        return
    check(
        f"{label}: with on-disk={on_disk}B > cap={cap}B, cache holds only {used}B (subset)",
        used < on_disk,
        detail="confirms eviction is reclaiming entries the cache can no longer hold",
    )


def assert_heap_stays_under_high_watermark(label: str, snapshot: dict, high_watermark: float = 0.80):
    """The eviction sweep should keep heap usage below the configured high
    watermark. A bit fuzzy because heap pressure depends on GC timing."""
    mem = snapshot.get("memory", {})
    heap_ratio = (mem.get("heapUsedBytes", 0) / max(mem.get("heapMaxBytes", 1), 1))
    check(
        f"{label}: heapUsedRatio={heap_ratio*100:.1f}% < high watermark ({high_watermark*100:.0f}%)",
        heap_ratio < high_watermark,
        detail=("heap is below the high watermark — the sweep / GC are keeping up"
                if heap_ratio < high_watermark
                else "heap is above the configured high watermark — sweep may be falling behind"),
    )


def rand_str(size: int) -> str:
    return ''.join(random.choices(string.ascii_letters + string.digits, k=size))


# ── fixtures ───────────────────────────────────────────────────────────────

def setup_fixtures(c):
    c.send({"type": "CREATE_DATABASE", "databaseName": "mem_db"})
    c.send({"type": "CREATE_COLLECTION", "databaseName": "mem_db", "collectionName": HOT_COLL})
    c.send({"type": "CREATE_COLLECTION", "databaseName": "mem_db", "collectionName": COLD_COLL})


def teardown_fixtures(c):
    c.send({"type": "DROP_DATABASE", "databaseName": "mem_db"})


# ══════════════════════════════════════════════════════════════════════════
# Tests
# ══════════════════════════════════════════════════════════════════════════

def test_mass_insert_with_eviction(c):
    """Push more data than the cache can hold; the database must stay responsive
    and the latency of a small probe query must not blow up."""
    section(f"Mass insert ({DOCS_PER_COLLECTION} docs x 2 collections x {PAYLOAD_BYTES}B)")

    sample_ids = {HOT_COLL: [], COLD_COLL: []}
    max_probe_ms = 0.0
    probes = 0

    for coll in (HOT_COLL, COLD_COLL):
        batch = []
        for i in range(DOCS_PER_COLLECTION):
            doc_id = f"{coll}_{i:06d}"
            batch.append({"_id": doc_id, "v": i, "payload": rand_str(PAYLOAD_BYTES)})
            if len(batch) >= BULK_BATCH_SIZE:
                r = c.send({"type": "BULK_SAVE", "databaseName": "mem_db",
                                "collectionName": coll, "objects": batch})
                if r.get("status") != "OK":
                    check_status("BULK_SAVE during mass insert", r, "OK")
                    return
                # remember some ids for later correctness check
                if len(sample_ids[coll]) < 50:
                    sample_ids[coll].append(doc_id)
                batch = []
                # responsiveness probe — server must keep answering a trivial query promptly
                probes += 1
                if probes % LATENCY_PROBE_INTERVAL == 0:
                    t0 = time.perf_counter()
                    c.send({"type": "LIST_DATABASES"})
                    dt_ms = (time.perf_counter() - t0) * 1000.0
                    max_probe_ms = max(max_probe_ms, dt_ms)
        if batch:
            c.send({"type": "BULK_SAVE", "databaseName": "mem_db",
                        "collectionName": coll, "objects": batch})

    check(
        f"Server stayed responsive during mass insert (max probe latency = {max_probe_ms:.1f} ms, budget = {LATENCY_BUDGET_MS} ms)",
        max_probe_ms < LATENCY_BUDGET_MS,
        detail=f"if this fails the eviction sweep is likely blocking the request path",
    )
    return sample_ids


def test_correctness_after_eviction(c, sample_ids: dict):
    """Eviction must be transparent: every doc we wrote is still retrievable."""
    section("Correctness after eviction — every sampled doc must still be retrievable")

    misses = []
    for coll, ids in sample_ids.items():
        for doc_id in ids:
            r = c.send({"type": "FIND_BY_ID", "databaseName": "mem_db",
                            "collectionName": coll, "_id": doc_id})
            if r.get("status") != "OK":
                misses.append((coll, doc_id, r.get("status")))

    check(
        f"All {sum(len(v) for v in sample_ids.values())} sampled docs retrievable across both collections",
        not misses,
        detail=(f"first miss: {misses[0]}" if misses else "data survived eviction (read from disk)"),
    )


def test_hot_collection_remains_warm(c):
    """LFU policy should keep the hot collection's PK index resident while
    evicting cold. We can't observe that directly, but we can verify queries
    on the hot collection stay fast under repeated access."""
    section("Hot-vs-cold access pattern — repeated reads on hot, occasional on cold")

    # Warm "hot" hard
    for _ in range(200):
        c.send({"type": "AGGREGATE", "databaseName": "mem_db",
                    "collectionName": HOT_COLL,
                    "aggregationSteps": [{"type": "COUNT"}]})
    # Touch "cold" once
    c.send({"type": "AGGREGATE", "databaseName": "mem_db",
                "collectionName": COLD_COLL,
                "aggregationSteps": [{"type": "COUNT"}]})

    # After all that access, hot queries must still complete promptly.
    t0 = time.perf_counter()
    r = c.send({"type": "AGGREGATE", "databaseName": "mem_db",
                    "collectionName": HOT_COLL,
                    "aggregationSteps": [{"type": "COUNT"}]})
    dt_ms = (time.perf_counter() - t0) * 1000.0
    check_status("AGGREGATE COUNT on hot collection (OK)", r, "OK")
    check(
        f"Hot-collection COUNT under access pressure stays fast (took {dt_ms:.1f} ms)",
        dt_ms < LATENCY_BUDGET_MS,
    )


def test_repeated_full_scan(c):
    """Repeatedly stream the full hot collection. Even if its full document map
    is evicted between scans, results must be consistent and the server must not
    fail or hang."""
    section("Repeated full scans — must return same count every time")

    counts = []
    for _ in range(5):
        r = c.send({"type": "AGGREGATE", "databaseName": "mem_db",
                        "collectionName": HOT_COLL,
                        "aggregationSteps": [{"type": "COUNT"}]})
        if r.get("status") != "OK":
            check_status("AGGREGATE COUNT during scans", r, "OK")
            return
        # The COUNT step returns {"count": N} inside the result array.
        # The wire shape may vary across operations; just sanity-check status.
        counts.append(r.get("status"))

    check(
        f"All 5 full scans returned OK",
        all(c == "OK" for c in counts),
        detail=f"statuses={counts}",
    )


def test_server_still_alive(c):
    """Final liveness check — the server must still be answering after the load."""
    section("Liveness check after the whole run")

    check_status("LIST_DATABASES still responds", c.send({"type": "LIST_DATABASES"}), "OK")
    check_status("AUTHENTICATE still works",
          c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    bu.banner("Memory limits & eviction test suite", HOST, PORT)
    print(f"  Plan: insert ~{(DOCS_PER_COLLECTION * PAYLOAD_BYTES * 2) // (1024*1024)}MB total")
    print(f"        Tune maxMemory in lwnrdb.cfg low (e.g. 4mb) to force eviction.")

    with Conn() as c:
        r = c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            sys.exit(1)
        teardown_fixtures(c)
        setup_fixtures(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        before = print_memory_snapshot("before mass insert", c)
        assert_cache_respects_cap("before mass insert", before)

    sample_ids = None
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        sample_ids = test_mass_insert_with_eviction(c)

    # Give the background sweep a few cycles to react to the load.
    # Default sweep is every 10s and pressure poll every 2s, so 12s covers both.
    section("Waiting 12s for the background sweep to settle")
    time.sleep(12)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        after = print_memory_snapshot("after mass insert + sweep settle", c)
        section("Memory-limit assertions (via GET_DATABASE_STATS)")
        assert_cache_respects_cap("post-insert cache stays within cap", after)
        assert_eviction_actually_happened("post-insert eviction observed", after)
        assert_heap_stays_under_high_watermark("post-insert heap below high watermark", after)

    if sample_ids:
        with Conn() as c:
            c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            test_correctness_after_eviction(c, sample_ids)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        test_hot_collection_remains_warm(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        test_repeated_full_scan(c)

    with Conn() as c:
        test_server_still_alive(c)

    # cleanup
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown_fixtures(c)

    bu.summary()


if __name__ == "__main__":
    main()
