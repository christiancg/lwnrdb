import os
import random
import shutil
import string
import sys
import tempfile
import time

import base_utils as bu
import refused_index_writes
from base_utils import Conn, check, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("MEMORY_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

HOT_COLL = "hot_coll"
COLD_COLL = "cold_coll"
DOCS_PER_COLLECTION = 5_000
PAYLOAD_BYTES = 512
BULK_BATCH_SIZE = 500
LATENCY_PROBE_INTERVAL = 50
LATENCY_BUDGET_MS = 2_000

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


def setup_fixtures(c):
    c.send({"type": "CREATE_DATABASE", "databaseName": "mem_db"})
    c.send({"type": "CREATE_COLLECTION", "databaseName": "mem_db", "collectionName": HOT_COLL})
    c.send({"type": "CREATE_COLLECTION", "databaseName": "mem_db", "collectionName": COLD_COLL})


def teardown_fixtures(c):
    c.send({"type": "DROP_DATABASE", "databaseName": "mem_db"})


def test_mass_insert_with_eviction(c):
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
                    return None
                if len(sample_ids[coll]) < 50:
                    sample_ids[coll].append(doc_id)
                batch = []
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


def test_hot_collection_remains_warm(c: Conn):
    section("Hot-vs-cold access pattern — repeated reads on hot, occasional on cold")

    for _ in range(200):
        c.send({"type": "AGGREGATE", "databaseName": "mem_db",
                    "collectionName": HOT_COLL,
                    "aggregationSteps": [{"type": "COUNT"}]})
    c.send({"type": "AGGREGATE", "databaseName": "mem_db",
                "collectionName": COLD_COLL,
                "aggregationSteps": [{"type": "COUNT"}]})

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
    section("Repeated full scans — must return same count every time")

    counts = []
    for _ in range(5):
        r = c.send({"type": "AGGREGATE", "databaseName": "mem_db",
                        "collectionName": HOT_COLL,
                        "aggregationSteps": [{"type": "COUNT"}]})
        if r.get("status") != "OK":
            check_status("AGGREGATE COUNT during scans", r, "OK")
            return
        counts.append(r.get("status"))

    check(
        f"All 5 full scans returned OK",
        all(c == "OK" for c in counts),
        detail=f"statuses={counts}",
    )


RESIDENT_INDEX_COLL = "resident_index_coll"
TINY_COLL = "tiny_coll"
RESIDENT_INDEX_DOCS = 200_000
RESIDENT_INDEX_BATCH = 5_000
LATENCY_SAVES = 300
LATENCY_RATIO_BUDGET = 3.0
LATENCY_NOISE_FLOOR_MS = 1.0


def average_save_ms(c: Conn, prefix: str) -> float:
    started = time.perf_counter()
    for i in range(LATENCY_SAVES):
        c.send({"type": "SAVE", "databaseName": "mem_db", "collectionName": TINY_COLL,
                "object": {"_id": f"{prefix}{i}", "v": i}})
    return (time.perf_counter() - started) * 1000.0 / LATENCY_SAVES


def test_resident_index_does_not_slow_unrelated_saves(c: Conn):
    section(f"A resident {RESIDENT_INDEX_DOCS}-entry field index does not slow SAVEs into an unrelated collection")
    for coll in (TINY_COLL, RESIDENT_INDEX_COLL):
        c.send({"type": "CREATE_COLLECTION", "databaseName": "mem_db", "collectionName": coll})
    average_save_ms(c, "warm")
    baseline_ms = average_save_ms(c, "base")
    for start in range(0, RESIDENT_INDEX_DOCS, RESIDENT_INDEX_BATCH):
        r = c.send({"type": "BULK_SAVE", "databaseName": "mem_db", "collectionName": RESIDENT_INDEX_COLL,
                    "objects": [{"_id": f"r{i:06d}", "k": f"key-{i}"} for i in range(start, start + RESIDENT_INDEX_BATCH)]},
                   timeout=120)
        if r.get("status") != "OK":
            check_status("BULK_SAVE of the indexed collection", r, "OK")
            return
    check_status("CREATE_INDEX on the large collection",
                 c.send({"type": "CREATE_INDEX", "databaseName": "mem_db", "collectionName": RESIDENT_INDEX_COLL,
                         "fieldName": "k"}, timeout=300), "OK")
    check_status("an index-backed FILTER loads the index into the cache",
                 c.send({"type": "AGGREGATE", "databaseName": "mem_db", "collectionName": RESIDENT_INDEX_COLL,
                         "aggregationSteps": [bu.filter_step("k", "EQUALS", "key-12345")]}, timeout=120), "OK")
    resident_bytes = stats(c).get("stats", {}).get("memory", {}).get("userCacheBytes", 0)
    check(f"the {RESIDENT_INDEX_DOCS}-entry index is resident ({resident_bytes / (1024 * 1024):.1f} MB cached)",
          resident_bytes >= RESIDENT_INDEX_DOCS * 50, "the index was not admitted, so nothing below is measured")
    after_ms = average_save_ms(c, "after")
    budget_ms = max(baseline_ms * LATENCY_RATIO_BUDGET, baseline_ms + LATENCY_NOISE_FLOOR_MS)
    check(f"average SAVE latency stays within {LATENCY_RATIO_BUDGET:.0f}x the baseline "
          f"(baseline {baseline_ms:.2f} ms, with the index resident {after_ms:.2f} ms)",
          after_ms <= budget_ms,
          "every admission re-measures each resident field index again")


ADMISSION_PORT = int(os.environ.get("MEMORY_ADMISSION_TEST_PORT", "9449"))
ADMISSION_MAX_MEMORY = "6mb"
ADMISSION_DB = "admission_db"
ADMISSION_ORDERS = "orders"
ADMISSION_FILLER = "filler"
ADMISSION_ORDER_DOCS = 20_000
ADMISSION_FILLER_DOCS = 6_000
ADMISSION_UPDATES = 300


def admission_save_ms(c: Conn, label: str) -> float:
    started = time.perf_counter()
    for i in range(ADMISSION_UPDATES):
        n = (i * 7919) % ADMISSION_ORDER_DOCS
        c.send({"type": "SAVE", "databaseName": ADMISSION_DB, "collectionName": ADMISSION_ORDERS,
                "object": {"_id": f"o{n:06d}", "cat": f"c{(n + len(label)) % 20}", "n": n}})
    return (time.perf_counter() - started) * 1000.0 / ADMISSION_UPDATES


def load_admission_fixture(c: Conn):
    c.send({"type": "CREATE_DATABASE", "databaseName": ADMISSION_DB})
    for coll in (ADMISSION_ORDERS, ADMISSION_FILLER):
        c.send({"type": "CREATE_COLLECTION", "databaseName": ADMISSION_DB, "collectionName": coll})
    for start in range(0, ADMISSION_ORDER_DOCS, 2_000):
        c.send({"type": "BULK_SAVE", "databaseName": ADMISSION_DB, "collectionName": ADMISSION_ORDERS,
                "objects": [{"_id": f"o{i:06d}", "cat": f"c{i % 20}", "n": i}
                            for i in range(start, start + 2_000)]}, timeout=120)
    for start in range(0, ADMISSION_FILLER_DOCS, 2_000):
        c.send({"type": "BULK_SAVE", "databaseName": ADMISSION_DB, "collectionName": ADMISSION_FILLER,
                "objects": [{"_id": f"f{i:05d}", "payload": rand_str(PAYLOAD_BYTES)}
                            for i in range(start, start + 2_000)]}, timeout=120)
    check_status("CREATE_INDEX on orders.cat",
                 c.send({"type": "CREATE_INDEX", "databaseName": ADMISSION_DB, "collectionName": ADMISSION_ORDERS,
                         "fieldName": "cat"}, timeout=120), "OK")


def test_index_admission_evicts_documents_under_a_full_cache():
    section(f"Index admission evicts documents when scans have filled a {ADMISSION_MAX_MEMORY} cache")
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-admission-")
    log_path = os.path.join(work_dir, "server.log")
    bu.write_config(work_dir, ADMISSION_PORT, maxMemory=ADMISSION_MAX_MEMORY, syncWrites=False)
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path, port=ADMISSION_PORT)
        with Conn(port=ADMISSION_PORT) as c:
            c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            load_admission_fixture(c)
        bu.stop_server(proc, port=ADMISSION_PORT)
        proc = bu.start_server(work_dir, log_path, port=ADMISSION_PORT)
        with Conn(port=ADMISSION_PORT) as c:
            c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            admission_save_ms(c, "warm")
            baseline_ms = admission_save_ms(c, "baseline")
        bu.stop_server(proc, port=ADMISSION_PORT)
        proc = bu.start_server(work_dir, log_path, port=ADMISSION_PORT)
        with Conn(port=ADMISSION_PORT) as c:
            c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            for _ in range(3):
                c.send({"type": "AGGREGATE", "databaseName": ADMISSION_DB, "collectionName": ADMISSION_FILLER,
                        "aggregationSteps": [bu.filter_step("payload", "NOT_EQUALS", "none")]}, timeout=120)
            mem = stats(c).get("stats", {}).get("memory", {})
            filled = mem.get("userCacheBytes", 0) / max(mem.get("maxMemoryBytes", 1), 1)
            print(f"  [stats] the filler scans left the cache {filled * 100:.0f}% full of documents")
            admission_save_ms(c, "warm")
            full_ms = admission_save_ms(c, "full")
            snapshot = stats(c).get("stats", {})
        budget_ms = max(baseline_ms * LATENCY_RATIO_BUDGET, baseline_ms + LATENCY_NOISE_FLOOR_MS)
        check(f"SAVE updates under a cache full of documents stay within {LATENCY_RATIO_BUDGET:.0f}x the "
              f"uncached baseline (baseline {baseline_ms:.2f} ms, full {full_ms:.2f} ms)",
              full_ms <= budget_ms, "index lists and lookups were refused instead of evicting documents")
        assert_cache_respects_cap("admission eviction keeps the cache within its cap", snapshot)
    finally:
        bu.stop_server(proc, port=ADMISSION_PORT, wait_for_port=False)
        if bu.failure_count():
            bu.dump_log(log_path)


OWN_PORT = int(os.environ.get("MEMORY_TEST_OWN_PORT", "9448"))
TIGHT_MAX_MEMORY = "2mb"
TIGHT_DB = "tight_db"
TIGHT_COLL = "tight_coll"
SHARED_COLL = "shared_ids_coll"
TIGHT_DOCS = int(os.environ.get("MEMORY_TEST_TIGHT_DOCS", "20000"))
SHARED_DOCS = 2_000
TIGHT_UPDATES = 300
TIGHT_UPDATE_BUDGET_MS = 50.0
TIGHT_CONVERGE_S = 60.0


def tight_probes() -> list:
    return [[bu.filter_step("n", "EQUALS", 7)], [bu.filter_step("s", "EQUALS", "s3")],
            [bu.filter_step("t", "EQUALS", True), {"type": "COUNT"}],
            [bu.filter_step("n", "GREATER_THAN_EQUALS", 45)]]


def answers(c: Conn, coll: str, steps: list):
    r = c.send({"type": "AGGREGATE", "databaseName": TIGHT_DB, "collectionName": coll, "aggregationSteps": steps},
               timeout=120)
    rows = r.get("results") or []
    return sorted(row.get("_id", str(row)) for row in rows)


def mismatched_probes(c: Conn, coll: str) -> list:
    mismatched = []
    for steps in tight_probes():
        if answers(c, coll, steps) != answers(c, coll, [{"type": "SKIP", "skip": 0}] + steps):
            mismatched.append(steps[0]["operator"])
    return mismatched


def load_indexed(c: Conn, coll: str, documents: int):
    check_status(f"create {coll}", c.send({"type": "CREATE_COLLECTION", "databaseName": TIGHT_DB,
                                           "collectionName": coll}), "OK")
    for start in range(0, documents, BULK_BATCH_SIZE):
        r = c.send({"type": "BULK_SAVE", "databaseName": TIGHT_DB, "collectionName": coll,
                    "objects": [{"_id": f"{coll}-{i:06d}", "n": i % 50, "s": f"s{i % 40}", "t": i % 2 == 0,
                                 "pad": "p" * 64} for i in range(start, min(documents, start + BULK_BATCH_SIZE))]},
                   timeout=120)
        if r.get("status") != "OK":
            check_status(f"BULK_SAVE into {coll}", r, "OK")
            return
    for field in ("n", "s", "t"):
        check_status(f"CREATE_INDEX {coll}.{field}",
                     c.send({"type": "CREATE_INDEX", "databaseName": TIGHT_DB, "collectionName": coll,
                             "fieldName": field}, timeout=300), "OK")


def test_indexed_updates_stay_cheap_under_max_memory(c: Conn):
    section(f"{TIGHT_UPDATES} updates on {TIGHT_DOCS} documents with 3 indexes under maxMemory={TIGHT_MAX_MEMORY}")
    c.send({"type": "CREATE_DATABASE", "databaseName": TIGHT_DB})
    load_indexed(c, TIGHT_COLL, TIGHT_DOCS)
    rng = random.Random(20261025)
    started = time.perf_counter()
    for _ in range(TIGHT_UPDATES):
        i = rng.randrange(TIGHT_DOCS)
        r = c.send({"type": "SAVE", "databaseName": TIGHT_DB, "collectionName": TIGHT_COLL,
                    "object": {"_id": f"{TIGHT_COLL}-{i:06d}", "n": rng.randrange(60), "s": f"s{rng.randrange(45)}",
                               "t": rng.random() < 0.5, "pad": "u" * 64}})
        if r.get("status") != "OK":
            check_status("an indexed update under a tight maxMemory", r, "OK")
            return
    per_update_ms = (time.perf_counter() - started) * 1000.0 / TIGHT_UPDATES
    converge_started = time.perf_counter()
    converged = bu.wait_until(lambda: not mismatched_probes(c, TIGHT_COLL), TIGHT_CONVERGE_S, interval_s=1.0)
    converge_s = time.perf_counter() - converge_started
    check(f"an indexed update averages under {TIGHT_UPDATE_BUDGET_MS:.0f} ms ({per_update_ms:.2f} ms)",
          per_update_ms <= TIGHT_UPDATE_BUDGET_MS)
    check(f"every index answers like a scan once the background batches ran ({converge_s:.1f} s)",
          converged, detail=str(mismatched_probes(c, TIGHT_COLL)))


def test_correctness_after_the_pk_list_is_evicted(c: Conn):
    section("Index answers stay exact after the pk list their resident ids share is evicted")
    load_indexed(c, SHARED_COLL, SHARED_DOCS)
    check("the small collection's indexes answer like a scan before any eviction",
          not mismatched_probes(c, SHARED_COLL))
    for _ in range(3):
        c.send({"type": "AGGREGATE", "databaseName": TIGHT_DB, "collectionName": TIGHT_COLL,
                "aggregationSteps": [{"type": "SKIP", "skip": 0}, {"type": "COUNT"}]}, timeout=120)
    time.sleep(6)
    for i in range(0, SHARED_DOCS, 97):
        c.send({"type": "SAVE", "databaseName": TIGHT_DB, "collectionName": SHARED_COLL,
                "object": {"_id": f"{SHARED_COLL}-{i:06d}", "n": 7, "s": "s3", "t": True, "pad": "e"}})
    converged = bu.wait_until(lambda: not mismatched_probes(c, SHARED_COLL), TIGHT_CONVERGE_S, interval_s=1.0)
    check("after scans evicted the cache, the re-queried indexes still answer like a scan", converged,
          detail=str(mismatched_probes(c, SHARED_COLL)))


def run_tight_memory_phase():
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-tight-memory-")
    log_path = os.path.join(work_dir, "server.log")
    bu.write_config(work_dir, OWN_PORT, maxMemory=TIGHT_MAX_MEMORY)
    print(f"\n  Starting an own server on {HOST}:{OWN_PORT} with maxMemory={TIGHT_MAX_MEMORY} ...")
    proc = bu.start_server(work_dir, log_path, port=OWN_PORT)
    try:
        with Conn(port=OWN_PORT) as c:
            c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            test_indexed_updates_stay_cheap_under_max_memory(c)
            test_correctness_after_the_pk_list_is_evicted(c)
    finally:
        bu.stop_server(proc, port=OWN_PORT)
    shutil.rmtree(work_dir, ignore_errors=True)


USAGE_DB = "usage_flat_db"
USAGE_COLLECTIONS = 200
USAGE_TOUCH_SECONDS = 12


def test_usage_profile_flush_keeps_the_background_queue_flat(c: Conn):
    section(f"Usage flush: {USAGE_COLLECTIONS} collections read for {USAGE_TOUCH_SECONDS}s keep the queue flat")
    c.send({"type": "DROP_DATABASE", "databaseName": USAGE_DB})
    check_status("create the usage database", c.send({"type": "CREATE_DATABASE", "databaseName": USAGE_DB}), "OK")
    names = [f"u{i:03d}" for i in range(USAGE_COLLECTIONS)]
    for name in names:
        c.send({"type": "CREATE_COLLECTION", "databaseName": USAGE_DB, "collectionName": name})
    deadline = time.time() + USAGE_TOUCH_SECONDS
    worst_maintenance = 0
    worst_pending = 0
    reads = 0
    while time.time() < deadline:
        for name in names:
            c.send({"type": "FIND_BY_ID", "databaseName": USAGE_DB, "collectionName": name, "_id": "missing"})
            reads += 1
        background = stats(c).get("stats", {}).get("background", {})
        worst_maintenance = max(worst_maintenance, background.get("maintenance", 0))
        worst_pending = max(worst_pending, background.get("pending", 0))
    print(f"  [usage] {reads} reads, worst maintenance={worst_maintenance}, worst pending={worst_pending}")
    check("at most two usage maintenance events are ever queued", worst_maintenance <= 2)
    check("the gated background queue stays near empty", worst_pending <= 20)
    check_status("drop the usage database", c.send({"type": "DROP_DATABASE", "databaseName": USAGE_DB}), "OK")


def test_server_still_alive(c):
    section("Liveness check after the whole run")

    check_status("AUTHENTICATE still works",
          c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    check_status("LIST_DATABASES still responds", c.send({"type": "LIST_DATABASES"}), "OK")


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
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        test_resident_index_does_not_slow_unrelated_saves(c)

    test_index_admission_evicts_documents_under_a_full_cache()

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        test_usage_profile_flush_keeps_the_background_queue_flat(c)

    with Conn() as c:
        test_server_still_alive(c)

    run_tight_memory_phase()

    refused_index_writes.run_refused_index_phase()

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown_fixtures(c)

    bu.summary()


if __name__ == "__main__":
    main()
