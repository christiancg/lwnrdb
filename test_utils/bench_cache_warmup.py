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
ADMIN_PASSWORD = "adminstrator"

DB = "benchdb"
COLL = "items"
DOCS = int(os.environ.get("BENCH_DOCS", "20000"))
PAYLOAD_BYTES = int(os.environ.get("BENCH_PAYLOAD_BYTES", "512"))
WARM_RUNS = int(os.environ.get("BENCH_WARM_RUNS", "30"))


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
            send(s, f, {"type": "BULK_SAVE", "databaseName": DB,
                        "collectionName": COLL, "objects": batch})
            batch = []
    if batch:
        send(s, f, {"type": "BULK_SAVE", "databaseName": DB,
                    "collectionName": COLL, "objects": batch})


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
    if r.get("status") != "OK":
        print(f"  ERROR response: {r}")
        sys.exit(1)
    return dt_ms


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
        print(f"--- server log tail ---\n{tail}\n--- end ---", file=sys.stderr)
    except OSError:
        pass
    raise RuntimeError("server did not come up in time")


def main():
    s, f = connect()

    print(f"Setting up {DOCS} docs in {DB}.{COLL} ...")
    setup(s, f)
    cb_before = cache_bytes(s, f)
    print(f"  cache after insert: {cb_before/1024/1024:.2f}MB")
    s.close()

    # Restart server to clear the in-memory cache so the "cold" call really is cold.
    print("Restarting server to clear cache ...")
    kill_server()
    start_server()
    s, f = connect()
    cb_restart = cache_bytes(s, f)
    print(f"  cache after restart: {cb_restart/1024/1024:.2f}MB (data is on disk)")

    # === Cold call ===
    print("\nCold call (first FILTER on this collection — reads from disk):")
    cold_ms = time_query(s, f, "alpha")
    cb_after_cold = cache_bytes(s, f)
    print(f"  latency: {cold_ms:.2f} ms")
    print(f"  cache after:  {cb_after_cold/1024/1024:.2f}MB  "
          f"(delta {(cb_after_cold-cb_restart)/1024/1024:+.2f}MB)")

    # === Warm calls ===
    print(f"\nWarm calls ({WARM_RUNS} repetitions of the same query):")
    warm_samples = []
    for _ in range(WARM_RUNS):
        warm_samples.append(time_query(s, f, "alpha"))
    print(f"  min={min(warm_samples):.2f} ms  "
          f"median={statistics.median(warm_samples):.2f} ms  "
          f"mean={statistics.mean(warm_samples):.2f} ms  "
          f"max={max(warm_samples):.2f} ms")

    # === Different value, still warm (same collection) ===
    print(f"\nWarm calls on different filter value (still same collection):")
    diff_samples = []
    for v in ["beta", "gamma", "delta", "epsilon", "alpha"] * 6:
        diff_samples.append(time_query(s, f, v))
    print(f"  min={min(diff_samples):.2f} ms  "
          f"median={statistics.median(diff_samples):.2f} ms  "
          f"mean={statistics.mean(diff_samples):.2f} ms  "
          f"max={max(diff_samples):.2f} ms")

    # === Speedup ===
    speedup = cold_ms / statistics.median(warm_samples)
    print(f"\nSpeedup (cold / warm median): {speedup:.1f}x")

    # cleanup
    send(s, f, {"type": "DROP_DATABASE", "databaseName": DB})
    s.close()


if __name__ == "__main__":
    main()
