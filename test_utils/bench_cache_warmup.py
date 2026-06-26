"""Measure cold vs warm aggregation latency on the same collection.

Inserts a fixed dataset, then runs the same FILTER+COUNT query repeatedly
against the collection. The first call hits disk (cold); subsequent calls
should hit the in-memory cache (warm).

Run with the server already up and admin credentials known.
"""

import json
import os
import random
import signal
import socket
import statistics
import string
import subprocess
import sys
import time

HOST = "127.0.0.1"
PORT = 8989
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "bench_db"
COLL = "items"
DOCS = int(os.environ.get("BENCH_DOCS", "20000"))
PAYLOAD_BYTES = int(os.environ.get("BENCH_PAYLOAD_BYTES", "512"))
WARM_RUNS = int(os.environ.get("BENCH_WARM_RUNS", "30"))
# Warm (cached) calls must beat the cold (disk) call. Allow a small tolerance so
# noisy CI timing doesn't flake the assertion while still catching a real regression.
SPEED_TOLERANCE = float(os.environ.get("BENCH_SPEED_TOLERANCE", "1.0"))

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

failures = 0


def section(title):
    print(f"\n{'─' * 60}")
    print(f"  {title}")
    print(f"{'─' * 60}")


def check_true(label, ok, detail=""):
    global failures
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    if detail:
        print(f"         {detail}")
    if not ok:
        failures += 1


def stat(label, detail):
    print(f"  [stats] {label}: {detail}")


def send(s, f, payload):
    s.settimeout(60.0)
    s.sendall((json.dumps(payload) + "\n").encode())
    raw = f.readline().decode().strip()
    if not raw:
        return {"status": "ERROR"}
    return json.loads(raw)


def rand_str(n):
    return "".join(random.choices(string.ascii_letters + string.digits, k=n))


def connect():
    s = socket.socket()
    s.connect((HOST, PORT))
    f = s.makefile("rb")
    send(s, f, {"type": "AUTHENTICATE",
                "username": ADMIN_USERNAME, "password": ADMIN_PASSWORD})
    return s, f


def setup(s, f):
    send(s, f, {"type": "DROP_DATABASE", "databaseName": DB})
    send(s, f, {"type": "CREATE_DATABASE", "databaseName": DB})
    send(s, f, {"type": "CREATE_COLLECTION",
                "databaseName": DB, "collectionName": COLL})
    categories = ["alpha", "beta", "gamma", "delta", "epsilon"]
    ok = True

    def flush(b):
        r = send(s, f, {"type": "BULK_SAVE", "databaseName": DB,
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


def cache_bytes(s, f):
    r = send(s, f, {"type": "GET_DATABASE_STATS"})
    return r.get("stats", {}).get("memory", {}).get("userCacheBytes", 0)


def evict_collection_cache(s, f):
    """Drop the collection's cached document map by dropping and re-creating.
    Cheaper alternative: restart the server. Here we use DROP_COLLECTION + recreate +
    re-insert, but that's expensive. Instead, we re-run setup() to reset state."""
    setup(s, f)


def run_query(s, f, value):
    return send(s, f, {
        "type": "AGGREGATE", "databaseName": DB, "collectionName": COLL,
        "aggregationSteps": [
            {"type": "FILTER",
             "operator": {"fieldOperatorType": "EQUALS",
                          "field": "category", "value": value}},
            {"type": "COUNT"},
        ],
    })


def time_query(s, f, value):
    t0 = time.perf_counter()
    r = run_query(s, f, value)
    dt_ms = (time.perf_counter() - t0) * 1000.0
    return dt_ms, r.get("status") == "OK"


JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"


SERVER_LOG = "/tmp/lwnrdb-bench.log"


def _port_open():
    try:
        with socket.create_connection((HOST, PORT), timeout=0.5):
            return True
    except OSError:
        return False


def kill_server():
    pids = []
    try:
        out = subprocess.check_output(
            ["pgrep", "-f", "lwnrdb-1.0-SNAPSHOT.jar"], text=True).strip()
        pids = [int(p) for p in out.splitlines() if p.strip()]
    except subprocess.CalledProcessError:
        return
    for pid in pids:
        os.kill(pid, signal.SIGTERM)
    # Wait for the port to actually close (the JVM holds the listening socket
    # until shutdown completes; on CI runners this can take several seconds).
    deadline = time.time() + 30.0
    while time.time() < deadline and _port_open():
        time.sleep(0.2)
    # Escalate to SIGKILL if anything is still alive.
    for pid in pids:
        try:
            os.kill(pid, 0)
            os.kill(pid, signal.SIGKILL)
        except OSError:
            pass
    while time.time() < deadline and _port_open():
        time.sleep(0.2)


def start_server():
    log = open(SERVER_LOG, "ab")
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    subprocess.Popen(["java", "-Xmx1g", "-jar", JAR],
                     stdout=log, stderr=log, cwd=repo_root)
    deadline = time.time() + 60.0
    while time.time() < deadline:
        if _port_open():
            return
        time.sleep(0.2)
    # Surface server log on failure so CI is debuggable.
    try:
        with open(SERVER_LOG, "rb") as fp:
            tail = fp.read()[-4000:].decode(errors="replace")
        print(f"  --- server log tail ---\n{tail}\n  --- end ---", file=sys.stderr)
    except OSError:
        pass
    raise RuntimeError("server did not come up in time")


def timed_runs(s, f, values):
    """Run each query, returning (latency samples, all-OK flag)."""
    samples, all_ok = [], True
    for v in values:
        dt_ms, ok = time_query(s, f, v)
        samples.append(dt_ms)
        all_ok = all_ok and ok
    return samples, all_ok


def main():
    print("\n" + "═" * 60)
    print("  LWNRDB — Cache warmup (cold vs warm) benchmark suite")
    print("═" * 60)
    print(f"  Connecting to {HOST}:{PORT}")
    print(f"  Plan: load {DOCS} docs (~{(DOCS * PAYLOAD_BYTES) // (1024*1024)}MB) "
          f"in {DB}.{COLL}, restart to drop the cache, then compare the cold")
    print(f"        disk read against {WARM_RUNS} warm (cached) calls.")

    s, f = connect()

    section(f"Load dataset ({DOCS} docs in {DB}.{COLL})")
    loaded = setup(s, f)
    cb_before = cache_bytes(s, f)
    check_true(f"dataset of {DOCS} docs loaded", loaded)
    stat("cache after insert", f"{cb_before/1024/1024:.2f}MB")
    s.close()

    # Let async background workers finish writing admin metadata to disk before
    # the abrupt SIGTERM below — otherwise we corrupt admin pages and the next
    # boot has to salvage them. CI runners are slower than dev hardware so
    # err on the side of waiting longer.
    time.sleep(8)

    # Restart server to clear the in-memory cache so the "cold" call really is cold.
    section("Restart server to clear the cache")
    kill_server()
    start_server()
    s, f = connect()
    cb_restart = cache_bytes(s, f)
    stat("cache after restart", f"{cb_restart/1024/1024:.2f}MB (data is on disk)")

    section("Cold call (first FILTER on this collection — reads from disk)")
    cold_ms, cold_ok = time_query(s, f, "alpha")
    cb_after_cold = cache_bytes(s, f)
    check_true("cold query returns OK", cold_ok)
    stat("latency", f"{cold_ms:.2f} ms")
    stat("cache after", f"{cb_after_cold/1024/1024:.2f}MB  "
         f"(delta {(cb_after_cold-cb_restart)/1024/1024:+.2f}MB)")

    section(f"Warm calls ({WARM_RUNS} repetitions of the same query)")
    warm_samples, warm_ok = timed_runs(s, f, ["alpha"] * WARM_RUNS)
    warm_median = statistics.median(warm_samples)
    check_true("all warm queries return OK", warm_ok)
    stat("timing", f"min={min(warm_samples):.2f} ms  "
         f"median={warm_median:.2f} ms  "
         f"mean={statistics.mean(warm_samples):.2f} ms  "
         f"max={max(warm_samples):.2f} ms")

    section("Warm calls on different filter values (still same collection)")
    diff_samples, diff_ok = timed_runs(
        s, f, ["beta", "gamma", "delta", "epsilon", "alpha"] * 6)
    check_true("all warm queries return OK", diff_ok)
    stat("timing", f"min={min(diff_samples):.2f} ms  "
         f"median={statistics.median(diff_samples):.2f} ms  "
         f"mean={statistics.mean(diff_samples):.2f} ms  "
         f"max={max(diff_samples):.2f} ms")

    section("Speedup (warm cache vs cold disk read)")
    speedup = cold_ms / warm_median if warm_median else float("inf")
    check_true(
        f"warm median is faster than the cold call (within {SPEED_TOLERANCE:.2f}x)",
        warm_median <= cold_ms * SPEED_TOLERANCE,
        detail=f"cold={cold_ms:.2f} ms  warm median={warm_median:.2f} ms  speedup={speedup:.1f}x")

    # cleanup
    send(s, f, {"type": "DROP_DATABASE", "databaseName": DB})
    s.close()

    print("\n" + "═" * 60)
    if failures == 0:
        print(f"  \033[92mAll checks passed.\033[0m")
    else:
        print(f"  \033[91m{failures} check(s) FAILED.\033[0m")
    print("═" * 60 + "\n")

    sys.exit(0 if failures == 0 else 1)


if __name__ == "__main__":
    main()
