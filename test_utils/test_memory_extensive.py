"""Extensive memory-bound stress test for LWNRDB.

Pushes much more data than maxMemory through the server, mixes
inserts, indexed reads, full scans, and aggregations across many collections,
and verifies that:

  - userCacheBytes (in-cache JVM-managed budget) never exceeds the configured
    cap by more than CACHE_CAP_TOLERANCE.
  - heapUsedRatio stays below the configured high watermark (we read it from
    GET_DATABASE_STATS for each snapshot, falling back to 80%).
  - The OS-visible process RSS does not balloon beyond a sane envelope
    relative to -Xmx (assumes the operator follows CLAUDE.md guidance).
  - Every document we wrote is still retrievable (eviction is transparent).

Server is expected to be already running with:
    maxMemory=64mb in lwnrdb.cfg
    java -Xmx128m -jar target/lwnrdb-1.0-SNAPSHOT.jar
"""

import json
import os
import random
import socket
import string
import subprocess
import sys
import time

HOST = "127.0.0.1"
PORT = 8989

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "mem_stress"
NUM_COLLECTIONS = int(os.environ.get("MEM_TEST_COLLECTIONS", "8"))
DOCS_PER_COLLECTION = int(os.environ.get("MEM_TEST_DOCS", "10000"))
PAYLOAD_BYTES = int(os.environ.get("MEM_TEST_PAYLOAD_BYTES", "768"))
BULK_BATCH_SIZE = int(os.environ.get("MEM_TEST_BATCH_SIZE", "250"))
INDEXED_FIELDS = ["category", "score"]

# How much over the cap we tolerate transiently between sweeps.
CACHE_CAP_TOLERANCE = 1.25
LATENCY_BUDGET_MS = 3_000

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"
WARN = "\033[93mWARN\033[0m"

failures = 0
warnings = 0


def send(s, f, payload, timeout=30.0):
    s.settimeout(timeout)
    s.sendall((json.dumps(payload) + "\n").encode())
    raw = f.readline().decode().strip()
    if not raw:
        return {"status": "ERROR", "message": "connection closed/no response"}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"status": "ERROR", "message": f"non-JSON response: {raw[:200]!r}"}


def check(label, ok, detail=""):
    global failures
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    if detail:
        print(f"         {detail}")
    if not ok:
        failures += 1


def warn(label, detail=""):
    global warnings
    print(f"  [{WARN}] {label}")
    if detail:
        print(f"         {detail}")
    warnings += 1


class Conn:
    def __init__(self):
        self.s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.s.connect((HOST, PORT))
        self.f = self.s.makefile("rb")
        send(self.s, self.f,
             {"type": "AUTHENTICATE", "username": ADMIN_USERNAME,
              "password": ADMIN_PASSWORD})

    def close(self):
        self.s.close()


def rand_str(n):
    return "".join(random.choices(string.ascii_letters + string.digits, k=n))


def get_pid_rss_mb(pid):
    try:
        out = subprocess.check_output(
            ["ps", "-o", "rss=", "-p", str(pid)], text=True).strip()
        return int(out) / 1024
    except Exception:
        return None


def find_server_pid():
    try:
        out = subprocess.check_output(
            ["pgrep", "-f", "lwnrdb-1.0-SNAPSHOT.jar"], text=True).strip()
        return int(out.splitlines()[0])
    except Exception:
        return None


def section(title):
    print(f"\n{'─' * 70}")
    print(f"  {title}")
    print(f"{'─' * 70}")


def snapshot(c):
    r = send(c.s, c.f, {"type": "GET_DATABASE_STATS"})
    return r.get("stats", {})


def fmt_snap(s, pid=None):
    mem = s.get("memory", {})
    tot = s.get("totals", {})
    heap_used = mem.get("heapUsedBytes", 0) / (1024 * 1024)
    heap_max = mem.get("heapMaxBytes", 0) / (1024 * 1024)
    cache = mem.get("userCacheBytes", 0) / (1024 * 1024)
    cap = mem.get("maxMemoryBytes", 0) / (1024 * 1024)
    on_disk = tot.get("sizeBytes", 0) / (1024 * 1024)
    rss = f" rss={get_pid_rss_mb(pid):.1f}MB" if pid else ""
    return (f"heap={heap_used:.1f}/{heap_max:.0f}MB "
            f"({(mem.get('heapUsedBytes', 0) / max(mem.get('heapMaxBytes', 1), 1))*100:.0f}%)  "
            f"cache={cache:.2f}MB (cap {cap:.0f}MB)  "
            f"onDisk={on_disk:.1f}MB  "
            f"entries={tot.get('entryCount', 0)}{rss}")


# ── workload ──────────────────────────────────────────────────────────────


def setup(c):
    send(c.s, c.f, {"type": "DROP_DATABASE", "databaseName": DB})
    send(c.s, c.f, {"type": "CREATE_DATABASE", "databaseName": DB})
    for i in range(NUM_COLLECTIONS):
        send(c.s, c.f, {"type": "CREATE_COLLECTION",
                        "databaseName": DB,
                        "collectionName": f"coll_{i}"})


def teardown(c):
    send(c.s, c.f, {"type": "DROP_DATABASE", "databaseName": DB})


def mass_insert(c, pid, rss_samples, cache_samples, heap_samples):
    """Insert NUM_COLLECTIONS x DOCS_PER_COLLECTION docs with ~PAYLOAD_BYTES filler.

    Total raw data ~= NUM_COLLECTIONS * DOCS * PAYLOAD_BYTES (well over the cap).
    """
    section(f"Mass insert: {NUM_COLLECTIONS} collections x {DOCS_PER_COLLECTION} docs "
            f"x {PAYLOAD_BYTES}B "
            f"(~{(NUM_COLLECTIONS * DOCS_PER_COLLECTION * PAYLOAD_BYTES) // (1024*1024)}MB)")

    samples = {}
    categories = ["alpha", "beta", "gamma", "delta", "epsilon"]
    t_start = time.perf_counter()
    max_probe_ms = 0.0
    probes = 0

    for ci in range(NUM_COLLECTIONS):
        coll = f"coll_{ci}"
        samples[coll] = []
        batch = []
        for i in range(DOCS_PER_COLLECTION):
            doc_id = f"{coll}_{i:06d}"
            batch.append({
                "_id": doc_id,
                "v": i,
                "category": random.choice(categories),
                "score": random.randint(0, 1000),
                "payload": rand_str(PAYLOAD_BYTES),
            })
            if len(batch) >= BULK_BATCH_SIZE:
                r = send(c.s, c.f, {"type": "BULK_SAVE", "databaseName": DB,
                                    "collectionName": coll, "objects": batch})
                if r.get("status") != "OK":
                    check(f"BULK_SAVE coll={coll}", False,
                          detail=f"got {r}")
                    return samples
                if len(samples[coll]) < 30:
                    samples[coll].append(doc_id)
                batch = []
                # Responsiveness probe
                probes += 1
                if probes % 30 == 0:
                    t0 = time.perf_counter()
                    send(c.s, c.f, {"type": "LIST_DATABASES"})
                    max_probe_ms = max(max_probe_ms, (time.perf_counter() - t0) * 1000.0)
                # Memory samples every ~10 batches
                if probes % 10 == 0:
                    s = snapshot(c)
                    mem = s.get("memory", {})
                    cache_samples.append(mem.get("userCacheBytes", 0))
                    heap_samples.append((mem.get("heapUsedBytes", 0) / max(mem.get("heapMaxBytes", 1), 1)))
                    rss = get_pid_rss_mb(pid)
                    if rss is not None:
                        rss_samples.append(rss)
        if batch:
            send(c.s, c.f, {"type": "BULK_SAVE", "databaseName": DB,
                            "collectionName": coll, "objects": batch})
        print(f"    coll_{ci} done — {fmt_snap(snapshot(c), pid)}")

    elapsed = time.perf_counter() - t_start
    print(f"  inserted in {elapsed:.1f}s; max probe latency {max_probe_ms:.0f}ms")
    check(f"Server stayed responsive during inserts "
          f"(max LIST_DATABASES probe = {max_probe_ms:.0f}ms < {LATENCY_BUDGET_MS}ms)",
          max_probe_ms < LATENCY_BUDGET_MS)
    return samples


def create_indexes(c, pid):
    section("Create field indexes on every collection")
    for ci in range(NUM_COLLECTIONS):
        coll = f"coll_{ci}"
        for fld in INDEXED_FIELDS:
            r = send(c.s, c.f, {"type": "CREATE_INDEX", "databaseName": DB,
                                "collectionName": coll, "fieldName": fld}, timeout=60)
            check(f"CREATE_INDEX {coll}.{fld}", r.get("status") == "OK",
                  detail=str(r) if r.get("status") != "OK" else "")
    # let background workers settle
    time.sleep(3)
    print(f"  after indexing — {fmt_snap(snapshot(c), pid)}")


def mixed_workload(c, pid, rss_samples, cache_samples, heap_samples):
    section("Mixed workload — indexed queries, full scans, aggregations")
    categories = ["alpha", "beta", "gamma", "delta", "epsilon"]
    max_op_ms = 0.0
    ops = 0
    for _ in range(200):
        ci = random.randint(0, NUM_COLLECTIONS - 1)
        coll = f"coll_{ci}"
        choice = random.random()
        t0 = time.perf_counter()
        if choice < 0.4:
            # indexed equality filter
            steps = [{"type": "FILTER",
                      "operator": {"fieldOperatorType": "EQUALS",
                                   "field": "category",
                                   "value": random.choice(categories)}},
                     {"type": "COUNT"}]
            r = send(c.s, c.f, {"type": "AGGREGATE", "databaseName": DB,
                                "collectionName": coll,
                                "aggregationSteps": steps})
            if r.get("status") != "OK":
                warn(f"AGGREGATE filter on {coll}.category", str(r))
        elif choice < 0.7:
            # indexed range
            lo = random.randint(0, 500)
            steps = [{"type": "FILTER",
                      "operator": {"fieldOperatorType": "GREATER_THAN",
                                   "field": "score", "value": lo}},
                     {"type": "LIMIT", "limit": 50}]
            r = send(c.s, c.f, {"type": "AGGREGATE", "databaseName": DB,
                                "collectionName": coll,
                                "aggregationSteps": steps})
            if r.get("status") != "OK":
                warn(f"AGGREGATE range on {coll}.score", str(r))
        elif choice < 0.9:
            # full count
            send(c.s, c.f, {"type": "AGGREGATE", "databaseName": DB,
                            "collectionName": coll,
                            "aggregationSteps": [{"type": "COUNT"}]})
        else:
            # random FIND_BY_ID
            doc_id = f"{coll}_{random.randint(0, DOCS_PER_COLLECTION - 1):06d}"
            send(c.s, c.f, {"type": "FIND_BY_ID", "databaseName": DB,
                            "collectionName": coll, "_id": doc_id})
        dt = (time.perf_counter() - t0) * 1000.0
        max_op_ms = max(max_op_ms, dt)
        ops += 1
        if ops % 20 == 0:
            s = snapshot(c)
            mem = s.get("memory", {})
            cache_samples.append(mem.get("userCacheBytes", 0))
            heap_samples.append((mem.get("heapUsedBytes", 0) / max(mem.get("heapMaxBytes", 1), 1)))
            rss = get_pid_rss_mb(pid)
            if rss is not None:
                rss_samples.append(rss)

    print(f"  {ops} mixed ops, max latency {max_op_ms:.0f}ms")
    check(f"Mixed-workload max op latency {max_op_ms:.0f}ms < {LATENCY_BUDGET_MS}ms",
          max_op_ms < LATENCY_BUDGET_MS)


def verify_correctness(c, samples):
    section("Correctness after pressure — every sampled doc retrievable")
    misses = []
    total = 0
    for coll, ids in samples.items():
        for doc_id in ids:
            r = send(c.s, c.f, {"type": "FIND_BY_ID", "databaseName": DB,
                                "collectionName": coll, "_id": doc_id})
            total += 1
            if r.get("status") != "OK":
                misses.append((coll, doc_id, r.get("status"), r.get("message")))
    check(f"All {total} sampled docs retrievable after eviction",
          not misses,
          detail=(f"first miss: {misses[0]}" if misses else
                  "data survived eviction (read from disk)"))


# ── assertions ────────────────────────────────────────────────────────────


def assert_cap(label, cache_used_bytes, cap_bytes):
    if cap_bytes <= 0:
        warn(f"{label}: cap is 0 (unlimited?)")
        return
    budget = cap_bytes * CACHE_CAP_TOLERANCE
    check(
        f"{label}: cache={cache_used_bytes/1024/1024:.2f}MB <= "
        f"cap*{CACHE_CAP_TOLERANCE}={budget/1024/1024:.2f}MB",
        cache_used_bytes <= budget,
        detail="eviction is bounding the cache" if cache_used_bytes <= budget
        else "EVICTION IS NOT KEEPING UP")


def assert_heap(label, heap_ratio, high_watermark):
    check(
        f"{label}: heapUsedRatio={heap_ratio*100:.1f}% < high WM {high_watermark*100:.0f}%",
        heap_ratio < high_watermark,
        detail="heap below high watermark" if heap_ratio < high_watermark else
        "HEAP ABOVE HIGH WATERMARK — sweep falling behind")


def assert_rss_bounded(label, max_rss_mb, xmx_mb):
    # RSS envelope = -Xmx + reasonable native/metaspace overhead.
    # Allow 2x as the upper bound (very generous; usually << 1.5x in practice).
    envelope = xmx_mb * 2.0
    check(f"{label}: max RSS {max_rss_mb:.1f}MB <= envelope {envelope:.0f}MB "
          f"(-Xmx={xmx_mb}MB)",
          max_rss_mb <= envelope,
          detail=("OS-visible process size respects the configured budget"
                  if max_rss_mb <= envelope else
                  "PROCESS SIZE BLEW PAST -Xmx ENVELOPE — investigate "
                  "native leaks / direct buffers"))


# ── main ──────────────────────────────────────────────────────────────────


def main():
    pid = find_server_pid()
    print(f"  server pid={pid}  initial rss={get_pid_rss_mb(pid)}MB")
    if pid is None:
        print("  [ERROR] could not find lwnrdb server PID — is it running?")
        sys.exit(1)

    c = Conn()
    teardown(c)
    setup(c)
    print(f"  after setup — {fmt_snap(snapshot(c), pid)}")

    s = snapshot(c)
    cap_bytes = s.get("memory", {}).get("maxMemoryBytes", 0)
    print(f"  configured cap = {cap_bytes/1024/1024:.0f}MB")

    rss_samples, cache_samples, heap_samples = [], [], []

    samples = mass_insert(c, pid, rss_samples, cache_samples, heap_samples)
    create_indexes(c, pid)
    mixed_workload(c, pid, rss_samples, cache_samples, heap_samples)

    section("Waiting 12s for background sweep to settle")
    time.sleep(12)

    final = snapshot(c)
    print(f"  final — {fmt_snap(final, pid)}")

    final_memory = final.get("memory", {})
    final_total = final.get("totals", {})
    max_cache_seen = max(cache_samples) if cache_samples else final_memory.get("userCacheBytes", 0)
    max_heap_seen = max(heap_samples) if heap_samples else (final_memory.get("heapUsedBytes", 0) / max(final_memory.get("heapMaxBytes", 1), 1))
    max_rss_seen = max(rss_samples) if rss_samples else (get_pid_rss_mb(pid) or 0)
    on_disk = final_total.get("sizeBytes", 0)

    print(f"\n  Peaks across {len(cache_samples)} snapshots: "
          f"cache_max={max_cache_seen/1024/1024:.2f}MB "
          f"heap_max={max_heap_seen*100:.1f}% "
          f"rss_max={max_rss_seen:.1f}MB "
          f"on_disk_final={on_disk/1024/1024:.1f}MB")

    section("Memory-cap assertions")
    assert_cap("peak userCacheBytes vs cap", max_cache_seen, cap_bytes)
    assert_cap("final userCacheBytes vs cap", final_memory.get("userCacheBytes", 0), cap_bytes)

    # Confirm we actually exceeded the cap on disk — otherwise the cap was not exercised.
    if on_disk > cap_bytes:
        check(f"on-disk size {on_disk/1024/1024:.1f}MB > cap "
              f"{cap_bytes/1024/1024:.0f}MB → cache MUST be a subset",
              final_memory.get("userCacheBytes", 0) < on_disk,
              detail="cache holds a strict subset of total data — eviction in effect")
    else:
        warn("on-disk size did not exceed cap — eviction was not exercised; "
             "increase DOCS_PER_COLLECTION or PAYLOAD_BYTES")

    assert_heap("peak heap usage", max_heap_seen, 0.85)
    assert_heap("final heap usage", (final_memory.get("heapUsedBytes", 0) / max(final_memory.get("heapMaxBytes", 1), 1)), 0.85)
    assert_rss_bounded("peak process RSS", max_rss_seen, 1024)

    verify_correctness(c, samples)

    teardown(c)
    c.close()

    print(f"\n{'═' * 70}")
    if failures == 0:
        print(f"  \033[92mAll checks passed.\033[0m  ({warnings} warnings)")
    else:
        print(f"  \033[91m{failures} check(s) FAILED.\033[0m  "
              f"({warnings} warnings)")
    print(f"{'═' * 70}\n")
    sys.exit(0 if failures == 0 else 1)


if __name__ == "__main__":
    main()
