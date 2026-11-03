import os
import random
import re
import shutil
import string
import subprocess
import sys
import threading
import time

import base_utils as bu
from base_utils import Conn, check, section, warn

HOST = "127.0.0.1"
PORT = int(os.environ.get("MEM_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

DB = "mem_stress"
NUM_COLLECTIONS = int(os.environ.get("MEM_TEST_COLLECTIONS", "8"))
DOCS_PER_COLLECTION = int(os.environ.get("MEM_TEST_DOCS", "10000"))
PAYLOAD_BYTES = int(os.environ.get("MEM_TEST_PAYLOAD_BYTES", "768"))
BULK_BATCH_SIZE = int(os.environ.get("MEM_TEST_BATCH_SIZE", "250"))
INDEXED_FIELDS = ["category", "score"]

CACHE_CAP_TOLERANCE = 1.25
ACCOUNTING_DOCS = int(os.environ.get("MEM_TEST_ACCOUNTING_DOCS", "20000"))
ACCOUNTING_COLL = "accounting"
ACCOUNTING_RATIO_BOUNDS = (0.75, 1.25)
LATENCY_BUDGET_MS = 3_000
SAMPLED_LOAD_DOCS = int(os.environ.get("MEM_TEST_SAMPLED_LOAD_DOCS", "60000"))
SAMPLED_LOAD_COLL = "sampled_load"
SAMPLE_INTERVAL_S = 1.0
DROP_DB = "mem_drops"
DROP_ROUNDS = 3
DROP_COLLECTIONS = int(os.environ.get("MEM_TEST_DROP_COLLECTIONS", "500"))
DROP_GROWTH_LIMIT = 0.02
REFUSED_INDEX_COLL = "refused_index"
REFUSED_INDEX_DOCS = int(os.environ.get("MEM_TEST_REFUSED_INDEX_DOCS", "40000"))
REFUSED_INDEX_MAX_CAP = 64 * 1024 * 1024
DROP_TRACKED_CLASSES = ("java.util.concurrent.locks.ReentrantReadWriteLock",
                        "java.util.concurrent.ConcurrentLinkedQueue", "org.techhouse.cache.UsageCounter")

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)


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
    if os.environ.get("MEM_TEST_SERVER_PID"):
        return int(os.environ["MEM_TEST_SERVER_PID"])
    try:
        out = subprocess.check_output(
            ["pgrep", "-f", "lwnrdb.jar"], text=True).strip()
        return int(out.splitlines()[0])
    except Exception:
        return None


def snapshot(c):
    r = c.send({"type": "GET_DATABASE_STATS"})
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


def live_heap_bytes(pid):
    try:
        out = subprocess.check_output(["jcmd", str(pid), "GC.class_histogram"], text=True, timeout=180)
    except (OSError, subprocess.SubprocessError):
        return None
    total = re.search(r"(?m)^Total\s+\d+\s+(\d+)", out)
    return int(total.group(1)) if total else None


def class_instance_counts(pid, class_names):
    try:
        out = subprocess.check_output(["jcmd", str(pid), "GC.class_histogram"], text=True, timeout=180)
    except (OSError, subprocess.SubprocessError):
        return None
    counts = dict.fromkeys(class_names, 0)
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 4 and parts[3] in counts:
            counts[parts[3]] = int(parts[1])
    return counts


def short_name(class_name):
    return class_name.rsplit(".", 1)[1]


def create_write_and_drop(c: Conn, coll):
    replies = [
        c.send({"type": "CREATE_COLLECTION", "databaseName": DROP_DB, "collectionName": coll}),
        c.send({"type": "SAVE", "databaseName": DROP_DB, "collectionName": coll,
                "object": {"_id": "only", "v": 1}}),
        c.send({"type": "FIND_BY_ID", "databaseName": DROP_DB, "collectionName": coll, "_id": "only"}),
        c.send({"type": "DROP_COLLECTION", "databaseName": DROP_DB, "collectionName": coll}),
    ]
    return all(reply.get("status") == "OK" for reply in replies)


def dropped_collections_release_memory(c: Conn, pid):
    section(f"Dropped collections release memory: {DROP_ROUNDS} rounds of create/write/drop x {DROP_COLLECTIONS}")
    if shutil.which("jcmd") is None:
        warn("jcmd is not on PATH — dropped-collection memory skipped")
        return
    c.send({"type": "DROP_DATABASE", "databaseName": DROP_DB})
    c.send({"type": "CREATE_DATABASE", "databaseName": DROP_DB})
    rounds = []
    for round_number in range(DROP_ROUNDS):
        failed = [i for i in range(DROP_COLLECTIONS) if not create_write_and_drop(c, f"r{round_number}_c{i}")]
        check(f"round {round_number + 1}: {DROP_COLLECTIONS} collections created, written and dropped", not failed,
              detail=f"failed: {failed[:5]}" if failed else "")
        counts = class_instance_counts(pid, DROP_TRACKED_CLASSES)
        if counts is None:
            warn("jcmd could not read the server's class histogram — dropped-collection memory skipped")
            c.send({"type": "DROP_DATABASE", "databaseName": DROP_DB})
            return
        print(f"  after round {round_number + 1}: "
              + ", ".join(f"{short_name(name)}={count}" for name, count in counts.items()))
        rounds.append(counts)
    for name in DROP_TRACKED_CLASSES:
        second, third = rounds[1][name], rounds[2][name]
        check(f"{short_name(name)} instances grow < {DROP_GROWTH_LIMIT:.0%} between rounds 2 and 3",
              third <= second * (1 + DROP_GROWTH_LIMIT), detail=f"{second} -> {third}")
    c.send({"type": "DROP_DATABASE", "databaseName": DROP_DB})


def small_doc(i: int):
    return {
        "_id": f"acc_{i:06d}",
        "v": i,
        "category": random.choice(["alpha", "beta", "gamma", "delta", "epsilon"]),
        "score": random.randint(0, 1000),
        "name": rand_str(12),
        "active": random.random() < 0.5,
        "tags": [rand_str(5) for _ in range(3)],
        "addr": {"city": rand_str(8), "zip": random.randint(1000, 9999)},
    }


def count_query(c, operator):
    return c.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": ACCOUNTING_COLL,
                   "aggregationSteps": [{"type": "FILTER", "operator": operator}, {"type": "COUNT"}]},
                  timeout=120)


def accounting_accuracy(c: Conn, pid):
    section(f"Accounting accuracy: {ACCOUNTING_DOCS} small docs, userCacheBytes vs live heap")
    if shutil.which("jcmd") is None:
        warn("jcmd is not on PATH — accounting accuracy skipped")
        return
    baseline_heap = live_heap_bytes(pid)
    if baseline_heap is None:
        warn("jcmd could not read the server's class histogram — accounting accuracy skipped")
        return
    baseline_cache = snapshot(c).get("memory", {}).get("userCacheBytes", 0)

    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": ACCOUNTING_COLL})
    loaded = True
    for start in range(0, ACCOUNTING_DOCS, BULK_BATCH_SIZE):
        batch = [small_doc(i) for i in range(start, min(start + BULK_BATCH_SIZE, ACCOUNTING_DOCS))]
        r = c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": ACCOUNTING_COLL,
                    "objects": batch}, timeout=120)
        loaded = loaded and r.get("status") == "OK"
    check(f"{ACCOUNTING_DOCS} small docs loaded into {ACCOUNTING_COLL}", loaded)
    for fld in INDEXED_FIELDS:
        r = c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": ACCOUNTING_COLL,
                    "fieldName": fld}, timeout=120)
        check(f"CREATE_INDEX {ACCOUNTING_COLL}.{fld}", r.get("status") == "OK",
              detail=str(r) if r.get("status") != "OK" else "")
    time.sleep(3)
    warmed = all(count_query(c, operator).get("status") == "OK" for operator in (
        {"fieldOperatorType": "GREATER_THAN_EQUALS", "field": "v", "value": 0},
        {"fieldOperatorType": "EQUALS", "field": "category", "value": "alpha"},
        {"fieldOperatorType": "GREATER_THAN", "field": "score", "value": 500}))
    check("full scan and indexed queries warm the accounting collection", warmed)
    time.sleep(3)

    cache_delta = snapshot(c).get("memory", {}).get("userCacheBytes", 0) - baseline_cache
    loaded_heap = live_heap_bytes(pid)
    if loaded_heap is None:
        warn("jcmd could not read the loaded server's class histogram — accounting accuracy skipped")
        return
    heap_delta = loaded_heap - baseline_heap
    ratio = cache_delta / heap_delta if heap_delta > 0 else float("inf")
    low, high = ACCOUNTING_RATIO_BOUNDS
    check(f"userCacheBytes tracks live heap: cache delta={cache_delta/1024/1024:.1f}MB "
          f"live heap delta={heap_delta/1024/1024:.1f}MB ratio={ratio:.2f} within [{low}, {high}]",
          low <= ratio <= high,
          detail="the cache's modelled heap matches what the documents and indexes really hold"
          if low <= ratio <= high else "THE CACHE ACCOUNTING DOES NOT TRACK REAL HEAP")
    c.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": ACCOUNTING_COLL})


def sample_cache_every_second(stop, samples):
    sampler = Conn()
    sampler.authenticate()
    try:
        while not stop.is_set():
            samples.append(snapshot(sampler).get("memory", {}).get("userCacheBytes", 0))
            stop.wait(SAMPLE_INTERVAL_S)
    finally:
        sampler.close()


def cache_stays_bounded_during_bulk_load(c: Conn, cap_bytes):
    section(f"Bulk load of {SAMPLED_LOAD_DOCS} indexed docs, userCacheBytes sampled every second")
    if cap_bytes <= 0:
        warn("cap is 0 or unlimited — sampled bulk load skipped")
        return
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": SAMPLED_LOAD_COLL})
    for fld in INDEXED_FIELDS:
        c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": SAMPLED_LOAD_COLL,
                "fieldName": fld}, timeout=60)
    samples = []
    stop = threading.Event()
    sampler = threading.Thread(target=sample_cache_every_second, args=(stop, samples), daemon=True)
    sampler.start()
    loaded = True
    try:
        for start in range(0, SAMPLED_LOAD_DOCS, BULK_BATCH_SIZE):
            batch = [dict(small_doc(i), _id=f"load_{i:07d}")
                     for i in range(start, min(start + BULK_BATCH_SIZE, SAMPLED_LOAD_DOCS))]
            r = c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": SAMPLED_LOAD_COLL,
                        "objects": batch}, timeout=120)
            loaded = loaded and r.get("status") == "OK"
    finally:
        stop.set()
        sampler.join(timeout=30)
    check(f"{SAMPLED_LOAD_DOCS} docs loaded into {SAMPLED_LOAD_COLL}", loaded)
    budget = cap_bytes * CACHE_CAP_TOLERANCE
    after_first = samples[1:]
    peak = max(after_first, default=0)
    print(f"  {len(samples)} samples, peak after the first {peak/1024/1024:.2f}MB, "
          f"cap {cap_bytes/1024/1024:.2f}MB")
    if not after_first:
        warn("the bulk load finished before a second sample — raise MEM_TEST_SAMPLED_LOAD_DOCS")
    over = [round(s / 1024 / 1024, 2) for s in after_first if s > budget]
    check(f"userCacheBytes stays <= cap*{CACHE_CAP_TOLERANCE}={budget/1024/1024:.2f}MB during the load",
          not over,
          detail="writes wake the sweep as soon as the cache is over its cap" if not over
          else f"samples over the bound (MB): {over}")
    c.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": SAMPLED_LOAD_COLL})


DISPLACE_COLL = "displace"
DISPLACE_DOCS = int(os.environ.get("MEM_TEST_DISPLACE_DOCS", "11000"))
DISPLACE_HOT_PAD = 48
DISPLACE_HOT_QUERIES = 40
REFUSED_PK_COLL = "refused_pk"
REFUSED_PK_DOCS = int(os.environ.get("MEM_TEST_REFUSED_PK_DOCS", "60000"))


def cache_bytes(c):
    return snapshot(c).get("memory", {}).get("userCacheBytes", 0)


def bulk_load(c, coll, docs):
    loaded = True
    for start in range(0, len(docs), BULK_BATCH_SIZE):
        r = c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll,
                    "objects": docs[start:start + BULK_BATCH_SIZE]}, timeout=120)
        loaded = loaded and r.get("status") == "OK"
    return loaded


def indexed_ids(c, coll, steps, scan=False, analyze=False):
    pipeline = ([{"type": "SKIP", "skip": 0}] if scan else []) + steps
    r = c.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll, "aggregationSteps": pipeline,
                "analyze": analyze}, timeout=120)
    return sorted(row.get("_id") for row in r.get("results") or []), r


def test_hot_index_displaces_cold_index(c: Conn, cap_bytes):
    section(f"A hot field index displaces a colder one ({DISPLACE_DOCS} docs, two unique-valued indexes)")
    if cap_bytes <= 0:
        warn("cap is 0 or unlimited — same-tier displacement skipped")
        return
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": DISPLACE_COLL})
    docs = [{"_id": f"dp_{i:06d}", "cold": f"c-{i:06d}", "hot": f"h-{i:06d}-" + "x" * DISPLACE_HOT_PAD}
            for i in range(DISPLACE_DOCS)]
    check(f"{DISPLACE_DOCS} docs loaded into {DISPLACE_COLL}", bulk_load(c, DISPLACE_COLL, docs))
    for fld in ("cold", "hot"):
        r = c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": DISPLACE_COLL,
                    "fieldName": fld}, timeout=120)
        check(f"CREATE_INDEX {DISPLACE_COLL}.{fld}", r.get("status") == "OK", detail=str(r))
    time.sleep(2)
    for i in range(3):
        indexed_ids(c, DISPLACE_COLL, [bu.filter_step("cold", "EQUALS", f"c-{i:06d}")])
    time.sleep(1)
    with_cold = cache_bytes(c)
    answered = True
    for _ in range(DISPLACE_HOT_QUERIES):
        n = random.randrange(DISPLACE_DOCS)
        ids, _ = indexed_ids(c, DISPLACE_COLL, [bu.filter_step("hot", "EQUALS", docs[n]["hot"])])
        answered = answered and ids == [docs[n]["_id"]]
        time.sleep(0.12)
    time.sleep(1)
    with_hot = cache_bytes(c)
    value = docs[7]["hot"]
    scanned, _ = indexed_ids(c, DISPLACE_COLL, [bu.filter_step("hot", "EQUALS", value)], scan=True)
    indexed, _ = indexed_ids(c, DISPLACE_COLL, [bu.filter_step("hot", "EQUALS", value)])
    check("every hot EQUALS answered its one document, and the index matches the scan",
          answered and scanned == indexed == [docs[7]["_id"]])
    print(f"  cache with the cold index {with_cold/1024/1024:.2f}MB, after the hot queries "
          f"{with_hot/1024/1024:.2f}MB, cap {cap_bytes/1024/1024:.2f}MB")
    before_drop = cache_bytes(c)
    r = c.send({"type": "DROP_INDEX", "databaseName": DB, "collectionName": DISPLACE_COLL, "fieldName": "hot"})
    freed = before_drop - cache_bytes(c)
    resident = r.get("status") == "OK" and freed >= DISPLACE_DOCS * DISPLACE_HOT_PAD
    check(f"dropping the hot index frees its resident list ({freed/1024:.0f}KB >= "
          f"{DISPLACE_DOCS * DISPLACE_HOT_PAD // 1024}KB): it replaced the colder one", resident,
          detail="a field index used far more than a resident one displaces it" if resident
          else f"THE HOT INDEX NEVER BECAME RESIDENT ({r})")
    check(f"userCacheBytes stays within the cap ({with_hot/1024/1024:.2f}MB)",
          with_hot <= cap_bytes * CACHE_CAP_TOLERANCE)
    c.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": DISPLACE_COLL})


def test_refused_pk_filter_limit_point_scans(c: Conn, cap_bytes):
    section(f"Indexed FILTER + LIMIT on a collection whose pk does not fit ({REFUSED_PK_DOCS} docs)")
    if cap_bytes <= 0:
        warn("cap is 0 or unlimited — refused pk FILTER + LIMIT skipped")
        return
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REFUSED_PK_COLL})
    docs = [{"_id": f"rp_{i:07d}", "category": f"cat{i % 50}", "n": i} for i in range(REFUSED_PK_DOCS)]
    check(f"{REFUSED_PK_DOCS} docs loaded into {REFUSED_PK_COLL}", bulk_load(c, REFUSED_PK_COLL, docs))
    for fld in ("category", "n"):
        r = c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REFUSED_PK_COLL,
                    "fieldName": fld}, timeout=120)
        check(f"CREATE_INDEX {REFUSED_PK_COLL}.{fld}", r.get("status") == "OK", detail=str(r))
    time.sleep(2)
    steps = [bu.filter_step("category", "EQUALS", "cat7"), {"type": "LIMIT", "limit": 5}]
    everything = set(indexed_ids(c, REFUSED_PK_COLL, [bu.filter_step("category", "EQUALS", "cat7")],
                                 scan=True)[0])
    timings = []
    answers = True
    scanned = None
    for _ in range(20):
        started = time.perf_counter()
        ids, response = indexed_ids(c, REFUSED_PK_COLL, steps, analyze=True)
        timings.append((time.perf_counter() - started) * 1000)
        answers = answers and len(set(ids)) == 5 and set(ids) <= everything
        scanned = (response.get("analyzeResult") or {}).get("documentsScanned")
    timings.sort()
    check(f"every FILTER + LIMIT 5 answers 5 distinct matching rows ({len(everything)} match)", answers)
    check(f"it reads only the rows it returns (documentsScanned={scanned})", scanned is not None and scanned <= 5)
    print(f"  FILTER + LIMIT 5 p50={timings[len(timings) // 2]:.1f}ms max={timings[-1]:.1f}ms "
          f"with a refused pk")
    top, response = indexed_ids(c, REFUSED_PK_COLL, [bu.filter_step("n", "GREATER_THAN", 1000),
                                                     {"type": "SORT", "fieldName": "n", "ascending": True},
                                                     {"type": "LIMIT", "limit": 5}], analyze=True)
    check("range + SORT + LIMIT 5 answers the five smallest matches in order",
          [row.get("_id") for row in response.get("results") or []] == [f"rp_{i:07d}" for i in range(1001, 1006)],
          detail=str(top))
    check(f"userCacheBytes stays within the cap ({cache_bytes(c)/1024/1024:.2f}MB)",
          cache_bytes(c) <= cap_bytes * CACHE_CAP_TOLERANCE)
    c.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": REFUSED_PK_COLL})


def refused_aggregate(c, steps, analyze=False):
    request = {"type": "AGGREGATE", "databaseName": DB, "collectionName": REFUSED_INDEX_COLL,
               "aggregationSteps": steps}
    if analyze:
        request["analyze"] = True
    return c.send(request, timeout=120)


def refused_email(i: int) -> str:
    return f"user{i:06d}@example.com"


def test_refused_index_answers_from_file(c: Conn, cap_bytes):
    section(f"Refused index: {REFUSED_INDEX_DOCS} unique emails and numbers that cannot fit the cache, "
            "answered from the index file and compared with the scan")
    if cap_bytes <= 0 or cap_bytes > REFUSED_INDEX_MAX_CAP:
        warn("cap is unlimited, disabled or larger than 64MB — the refused-index case needs a small cap; skipped")
        return
    c.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REFUSED_INDEX_COLL})
    loaded = True
    for start in range(0, REFUSED_INDEX_DOCS, 1000):
        batch = [{"_id": f"ref_{i:06d}", "email": refused_email(i), "n": i, "bucket": i % 50}
                 for i in range(start, min(start + 1000, REFUSED_INDEX_DOCS))]
        r = c.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": REFUSED_INDEX_COLL,
                    "objects": batch}, timeout=120)
        loaded = loaded and r.get("status") == "OK"
    check(f"{REFUSED_INDEX_DOCS} docs loaded into {REFUSED_INDEX_COLL}", loaded)
    for fld in ("email", "n"):
        r = c.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": REFUSED_INDEX_COLL,
                    "fieldName": fld}, timeout=120)
        check(f"CREATE_INDEX {REFUSED_INDEX_COLL}.{fld}", r.get("status") == "OK", detail=str(r))
    check("the sweep brings the bulk load's resident pk rows back under the cap before the queries",
          bu.wait_until(lambda: cache_bytes(c) <= cap_bytes, timeout_s=15.0),
          detail=f"{cache_bytes(c)} bytes against a {cap_bytes} byte cap")
    last = REFUSED_INDEX_DOCS - 1
    probes = [
        ("EQUALS email", bu.filter_step("email", "EQUALS", refused_email(123)), 1),
        ("EQUALS email in another case", bu.filter_step("email", "EQUALS", refused_email(456).upper()), 1),
        ("EQUALS an absent email", bu.filter_step("email", "EQUALS", "nobody@example.com"), 0),
        ("IN three emails", bu.filter_step("email", "IN", [refused_email(1), refused_email(last),
                                                          refused_email(7).upper()]), 3),
        ("EQUALS n", bu.filter_step("n", "EQUALS", 777), 1),
        ("n >= last - 24", bu.filter_step("n", "GREATER_THAN_EQUALS", last - 24), 25),
        ("n < 10", bu.filter_step("n", "SMALLER_THAN", 10), 10),
        ("NOT_IN all but one email", bu.filter_step("email", "NOT_IN", [refused_email(2)]), REFUSED_INDEX_DOCS - 1),
    ]
    for label, step, expected in probes:
        refused_aggregate(c, [step], analyze=True)
        indexed = refused_aggregate(c, [step], analyze=True)
        scanned = refused_aggregate(c, [{"type": "SKIP", "skip": 0}, step])
        check(f"{label}: index answer == scan answer ({expected} rows)",
              indexed.get("status") in ("OK", "NO_RESULTS") and bu.ids_of(indexed) == bu.ids_of(scanned)
              and len(bu.ids_of(indexed)) == expected,
              detail=f"index {len(bu.ids_of(indexed))} rows, scan {len(bu.ids_of(scanned))} rows")
        analysis = indexed.get("analyzeResult") or {}
        if expected <= 25:
            check(f"{label}: served by the index, reading only the {expected} matching documents",
                  analysis.get("documentsScanned") == expected and step["operator"]["field"] in (
                      analysis.get("indexesUsed") or []), detail=str(analysis))
        counted = refused_aggregate(c, [step, {"type": "COUNT"}])
        check(f"{label}: index-only COUNT == {expected}", bu.count_of(counted) == expected, detail=str(counted))
    limited = refused_aggregate(c, [bu.filter_step("email", "EQUALS", refused_email(99)), {"type": "LIMIT", "limit": 1}],
                                analyze=True)
    check("[FILTER email, LIMIT 1] answers its one row from the index",
          bu.ids_of(limited) == ["ref_000099"]
          and (limited.get("analyzeResult") or {}).get("documentsScanned") == 1, detail=str(limited.get("analyzeResult")))
    used = snapshot(c).get("memory", {}).get("userCacheBytes", 0)
    check(f"userCacheBytes stays within cap*{CACHE_CAP_TOLERANCE} after the refused-index queries",
          used <= cap_bytes * CACHE_CAP_TOLERANCE, detail=f"{used} bytes against a {cap_bytes} byte cap")
    c.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": REFUSED_INDEX_COLL})


def setup(c):
    c.send({"type": "DROP_DATABASE", "databaseName": DB})
    c.send({"type": "CREATE_DATABASE", "databaseName": DB})
    for i in range(NUM_COLLECTIONS):
        c.send({"type": "CREATE_COLLECTION",
                        "databaseName": DB,
                        "collectionName": f"coll_{i}"})


def teardown(c):
    c.send({"type": "DROP_DATABASE", "databaseName": DB})


def mass_insert(c: Conn, pid, rss_samples, cache_samples, heap_samples):
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
                r = c.send({"type": "BULK_SAVE", "databaseName": DB,
                                    "collectionName": coll, "objects": batch})
                if r.get("status") != "OK":
                    check(f"BULK_SAVE coll={coll}", False,
                          detail=f"got {r}")
                    return samples
                if len(samples[coll]) < 30:
                    samples[coll].append(doc_id)
                batch = []
                probes += 1
                if probes % 30 == 0:
                    t0 = time.perf_counter()
                    c.send({"type": "LIST_DATABASES"})
                    max_probe_ms = max(max_probe_ms, (time.perf_counter() - t0) * 1000.0)
                if probes % 10 == 0:
                    s = snapshot(c)
                    mem = s.get("memory", {})
                    cache_samples.append(mem.get("userCacheBytes", 0))
                    heap_samples.append((mem.get("heapUsedBytes", 0) / max(mem.get("heapMaxBytes", 1), 1)))
                    rss = get_pid_rss_mb(pid)
                    if rss is not None:
                        rss_samples.append(rss)
        if batch:
            c.send({"type": "BULK_SAVE", "databaseName": DB,
                            "collectionName": coll, "objects": batch})
        print(f"    coll_{ci} done — {fmt_snap(snapshot(c), pid)}")

    elapsed = time.perf_counter() - t_start
    print(f"  inserted in {elapsed:.1f}s; max probe latency {max_probe_ms:.0f}ms")
    check(f"Server stayed responsive during inserts "
          f"(max LIST_DATABASES probe = {max_probe_ms:.0f}ms < {LATENCY_BUDGET_MS}ms)",
          max_probe_ms < LATENCY_BUDGET_MS)
    return samples


def create_indexes(c: Conn, pid):
    section("Create field indexes on every collection")
    for ci in range(NUM_COLLECTIONS):
        coll = f"coll_{ci}"
        for fld in INDEXED_FIELDS:
            r = c.send({"type": "CREATE_INDEX", "databaseName": DB,
                                "collectionName": coll, "fieldName": fld}, timeout=60)
            check(f"CREATE_INDEX {coll}.{fld}", r.get("status") == "OK",
                  detail=str(r) if r.get("status") != "OK" else "")
    time.sleep(3)
    print(f"  after indexing — {fmt_snap(snapshot(c), pid)}")


def mixed_workload(c: Conn, pid, rss_samples, cache_samples, heap_samples):
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
            steps = [{"type": "FILTER",
                      "operator": {"fieldOperatorType": "EQUALS",
                                   "field": "category",
                                   "value": random.choice(categories)}},
                     {"type": "COUNT"}]
            r = c.send({"type": "AGGREGATE", "databaseName": DB,
                                "collectionName": coll,
                                "aggregationSteps": steps})
            if r.get("status") != "OK":
                warn(f"AGGREGATE filter on {coll}.category", str(r))
        elif choice < 0.7:
            lo = random.randint(0, 500)
            steps = [{"type": "FILTER",
                      "operator": {"fieldOperatorType": "GREATER_THAN",
                                   "field": "score", "value": lo}},
                     {"type": "LIMIT", "limit": 50}]
            r = c.send({"type": "AGGREGATE", "databaseName": DB,
                                "collectionName": coll,
                                "aggregationSteps": steps})
            if r.get("status") != "OK":
                warn(f"AGGREGATE range on {coll}.score", str(r))
        elif choice < 0.9:
            c.send({"type": "AGGREGATE", "databaseName": DB,
                            "collectionName": coll,
                            "aggregationSteps": [{"type": "COUNT"}]})
        else:
            doc_id = f"{coll}_{random.randint(0, DOCS_PER_COLLECTION - 1):06d}"
            c.send({"type": "FIND_BY_ID", "databaseName": DB,
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
            r = c.send({"type": "FIND_BY_ID", "databaseName": DB,
                                "collectionName": coll, "_id": doc_id})
            total += 1
            if r.get("status") != "OK":
                misses.append((coll, doc_id, r.get("status"), r.get("message")))
    check(f"All {total} sampled docs retrievable after eviction",
          not misses,
          detail=(f"first miss: {misses[0]}" if misses else
                  "data survived eviction (read from disk)"))


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


def assert_rss_bounded(label, max_rss_mb: float, xmx_mb):
    envelope = xmx_mb * 2.0
    check(f"{label}: max RSS {max_rss_mb:.1f}MB <= envelope {envelope:.0f}MB "
          f"(-Xmx={xmx_mb}MB)",
          max_rss_mb <= envelope,
          detail=("OS-visible process size respects the configured budget"
                  if max_rss_mb <= envelope else
                  "PROCESS SIZE BLEW PAST -Xmx ENVELOPE — investigate "
                  "native leaks / direct buffers"))


def main():
    bu.banner("Extensive memory-bound stress test suite", HOST, PORT)

    pid = find_server_pid()
    print(f"  server pid={pid}  initial rss={get_pid_rss_mb(pid)}MB")
    if pid is None:
        print("  [ERROR] could not find lwnrdb server PID — is it running?")
        sys.exit(1)

    c = Conn()
    c.authenticate()
    teardown(c)
    setup(c)
    print(f"  after setup — {fmt_snap(snapshot(c), pid)}")

    s = snapshot(c)
    cap_bytes = s.get("memory", {}).get("maxMemoryBytes", 0)
    print(f"  configured cap = {cap_bytes/1024/1024:.0f}MB")

    test_hot_index_displaces_cold_index(c, cap_bytes)
    test_refused_pk_filter_limit_point_scans(c, cap_bytes)
    accounting_accuracy(c, pid)
    cache_stays_bounded_during_bulk_load(c, cap_bytes)
    test_refused_index_answers_from_file(c, cap_bytes)
    dropped_collections_release_memory(c, pid)

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

    bu.summary(suffix=f"  ({bu.warning_count()} warnings)")


if __name__ == "__main__":
    main()
