import os
import random
import signal
import statistics
import string
import subprocess
import time

import base_utils as bu
from base_utils import Conn, check, section

HOST = "127.0.0.1"
PORT = 8989
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

DB = "bench_db"
COLL = "items"
DOCS = int(os.environ.get("BENCH_DOCS", "20000"))
PAYLOAD_BYTES = int(os.environ.get("BENCH_PAYLOAD_BYTES", "512"))
WARM_RUNS = int(os.environ.get("BENCH_WARM_RUNS", "30"))
SPEED_TOLERANCE = float(os.environ.get("BENCH_SPEED_TOLERANCE", "1.0"))

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)


def stat(label, detail):
    print(f"  [stats] {label}: {detail}")


def rand_str(n):
    return "".join(random.choices(string.ascii_letters + string.digits, k=n))


def connect():
    c = Conn()
    c.authenticate()
    return c


def setup(c: Conn):
    c.send({"type": "DROP_DATABASE", "databaseName": DB})
    c.send({"type": "CREATE_DATABASE", "databaseName": DB})
    c.send({"type": "CREATE_COLLECTION",
                "databaseName": DB, "collectionName": COLL})
    categories = ["alpha", "beta", "gamma", "delta", "epsilon"]
    ok = True

    def flush(b):
        r = c.send({"type": "BULK_SAVE", "databaseName": DB,
                        "collectionName": COLL, "objects": b})
        return r.get("status") == "OK"

    batch = []
    for i in range(DOCS):
        batch.append({
            "_id": f"id_{i:06d}",
            "v": i,
            "category": random.choice(categories),
            "score": random.randint(0, 1000),
            "payload": rand_str(PAYLOAD_BYTES),
        })
        if len(batch) >= 500:
            ok = flush(batch) and ok
            batch = []
    if batch:
        ok = flush(batch) and ok
    return ok


def cache_bytes(c):
    r = c.send({"type": "GET_DATABASE_STATS"})
    return r.get("stats", {}).get("memory", {}).get("userCacheBytes", 0)


def evict_collection_cache(c):
    setup(c)


def run_query(c, value):
    return c.send({
        "type": "AGGREGATE", "databaseName": DB, "collectionName": COLL,
        "aggregationSteps": [
            {"type": "FILTER",
             "operator": {"fieldOperatorType": "EQUALS",
                          "field": "category", "value": value}},
            {"type": "COUNT"},
        ],
    })


def time_query(c: Conn, value):
    t0 = time.perf_counter()
    r = run_query(c, value)
    dt_ms = (time.perf_counter() - t0) * 1000.0
    return dt_ms, r.get("status") == "OK"


JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"


SERVER_LOG = "/tmp/lwnrdb-bench.log"


def kill_server():
    try:
        out = subprocess.check_output(
            ["pgrep", "-f", "lwnrdb-1.0-SNAPSHOT.jar"], text=True).strip()
        pids = [int(p) for p in out.splitlines() if p.strip()]
    except subprocess.CalledProcessError:
        return
    for pid in pids:
        os.kill(pid, signal.SIGTERM)
    deadline = time.time() + 30.0
    while time.time() < deadline and bu.port_open():
        time.sleep(0.2)
    for pid in pids:
        try:
            os.kill(pid, 0)
            os.kill(pid, signal.SIGKILL)
        except OSError:
            pass
    while time.time() < deadline and bu.port_open():
        time.sleep(0.2)


def timed_runs(c: Conn, values):
    samples, all_ok = [], True
    for v in values:
        dt_ms, ok = time_query(c, v)
        samples.append(dt_ms)
        all_ok = all_ok and ok
    return samples, all_ok


def main():
    bu.banner("Cache warmup (cold vs warm) benchmark suite", HOST, PORT)
    print(f"  Plan: load {DOCS} docs (~{(DOCS * PAYLOAD_BYTES) // (1024*1024)}MB) "
          f"in {DB}.{COLL}, restart to drop the cache, then compare the cold")
    print(f"        disk read against {WARM_RUNS} warm (cached) calls.")

    c = connect()

    section(f"Load dataset ({DOCS} docs in {DB}.{COLL})")
    loaded = setup(c)
    cb_before = cache_bytes(c)
    check(f"dataset of {DOCS} docs loaded", loaded)
    stat("cache after insert", f"{cb_before/1024/1024:.2f}MB")
    c.close()

    time.sleep(8)

    section("Restart server to clear the cache")
    kill_server()
    bu.start_server(bu.REPO_ROOT, SERVER_LOG, xmx="1g")
    c = connect()
    cb_restart = cache_bytes(c)
    stat("cache after restart", f"{cb_restart/1024/1024:.2f}MB (data is on disk)")

    section("Cold call (first FILTER on this collection — reads from disk)")
    cold_ms, cold_ok = time_query(c, "alpha")
    cb_after_cold = cache_bytes(c)
    check("cold query returns OK", cold_ok)
    stat("latency", f"{cold_ms:.2f} ms")
    stat("cache after", f"{cb_after_cold/1024/1024:.2f}MB  "
         f"(delta {(cb_after_cold-cb_restart)/1024/1024:+.2f}MB)")

    section(f"Warm calls ({WARM_RUNS} repetitions of the same query)")
    warm_samples, warm_ok = timed_runs(c, ["alpha"] * WARM_RUNS)
    warm_median = statistics.median(warm_samples)
    check("all warm queries return OK", warm_ok)
    stat("timing", f"min={min(warm_samples):.2f} ms  "
         f"median={warm_median:.2f} ms  "
         f"mean={statistics.mean(warm_samples):.2f} ms  "
         f"max={max(warm_samples):.2f} ms")

    section("Warm calls on different filter values (still same collection)")
    diff_samples, diff_ok = timed_runs(
        c, ["beta", "gamma", "delta", "epsilon", "alpha"] * 6)
    check("all warm queries return OK", diff_ok)
    stat("timing", f"min={min(diff_samples):.2f} ms  "
         f"median={statistics.median(diff_samples):.2f} ms  "
         f"mean={statistics.mean(diff_samples):.2f} ms  "
         f"max={max(diff_samples):.2f} ms")

    section("Speedup (warm cache vs cold disk read)")
    speedup = cold_ms / warm_median if warm_median else float("inf")
    check(
        f"warm median is faster than the cold call (within {SPEED_TOLERANCE:.2f}x)",
        warm_median <= cold_ms * SPEED_TOLERANCE,
        detail=f"cold={cold_ms:.2f} ms  warm median={warm_median:.2f} ms  speedup={speedup:.1f}x")

    c.send({"type": "DROP_DATABASE", "databaseName": DB})
    c.close()

    bu.summary()


if __name__ == "__main__":
    main()
