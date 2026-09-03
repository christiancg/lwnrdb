"""End-to-end tests for script admission control (`maxConcurrentScripts` / `scriptQueueWaitMs`).

Like the RUN_SCRIPT and schedules suites this script is **self-contained**: it starts its own
LWNRDB instance on a dedicated port and working directory, because the cap has to be set to a
small, known value for a burst to reach it, and the shared CI server runs with the default 16.
It runs in three phases, each a restart of the same data directory:

  phase 1 — `maxConcurrentScripts=2`, `scriptQueueWaitMs=0`: the cap rejects immediately, so a
            burst larger than the cap produces `503-6` for the surplus;
  phase 2 — `maxConcurrentScripts=2`, `scriptQueueWaitMs=10000`: the same burst is absorbed by
            the wait, so nobody is refused while the ceiling still holds;
  phase 3 — `maxConcurrentScripts=0`: the cap is off and the burst behaves exactly as it did
            before the feature existed. This phase is also the **control** for phase 1 — it is
            what proves the phase-1 ceiling came from the cap and not from a slow runner.

What is covered:

  * the ceiling itself: `scripts.running` is sampled throughout each burst and must never exceed
    the configured capacity in phases 1 and 2, and must exceed it in phase 3;
  * the rejection: shape (`type`/`status`/`errorCode`/`message`), that it applies to
    `RUN_SCRIPT` and `CALL_PROCEDURE` alike, and that every non-rejected caller still gets a
    real result;
  * `GET_DATABASE_STATS`: `scripts.capacity`/`available`/`rejected`/`waited`, including that
    `available` returns to the capacity once the node is quiet (i.e. no permit leaked) after
    successful, failing, timed-out and memory-aborted runs;
  * the wait as the leading indicator: `waited` counts a caller that queued, `rejected` counts
    one that was turned away;
  * that a doomed request never spends a permit (scripts disabled, unknown database, oversized
    source), so a saturated node still answers them with their own error;
  * that a rejection does not poison the connection — the client's retry on the same socket
    succeeds once a permit frees;
  * that non-script operations are unaffected while the pool is exhausted;
  * **the central invariant**: triggers and scheduled procedures are exempt. Both are bounded by
    their own worker pools, and a run refused for want of a permit would be a *dropped* trigger
    rather than a retried one, since its pending-run record is consumed by the transaction that
    applies its effects.

The server lifecycle is managed via a tracked subprocess handle (not pgrep), so stopping it
never touches an unrelated LWNRDB process.
"""

import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time

HOST = "127.0.0.1"
PORT = int(os.environ.get("ADMISSION_TEST_PORT", "8997"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "admission_test_db"
COLL = "docs"
AUDIT_COLL = "audit"
TICKS_COLL = "ticks"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The cap under test. Deliberately tiny so a burst of CALLERS reaches it in one round.
CAPACITY = 2
CALLERS = 6
# Long enough that the whole burst is still queued when the last caller arrives, short enough
# that phase 1 does not take noticeably longer than the burst itself.
SLOW_SCRIPT_MS = 600
LONG_WAIT_MS = 10_000

TIMEOUT_MS = 5_000
MAX_MEMORY_BYTES = 4 * 1024 * 1024
MAX_SOURCE_BYTES = 8 * 1024

SLOW_SCRIPT = f"export default new Promise(r => setTimeout(() => r(1), {SLOW_SCRIPT_MS}));"

failures = 0


# ── reporting helpers (mirrors the other suites) ─────────────────────────────

def section(title: str):
    print(f"\n{'─' * 70}")
    print(f"  {title}")
    print(f"{'─' * 70}")


def check(label: str, ok: bool, detail: str = ""):
    global failures
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    if detail and not ok:
        print(f"         {detail}")
    if not ok:
        failures += 1


def check_status(label: str, response: dict, expected_status: str):
    check(label, response.get("status") == expected_status,
          f"expected status={expected_status} got={response.get('status')} "
          f"code={response.get('errorCode')} msg={response.get('message')!r}")


def check_code(label: str, response: dict, expected_status: str, expected_code: str):
    ok = response.get("status") == expected_status and response.get("errorCode") == expected_code
    check(label, ok,
          f"expected {expected_status}/{expected_code} got {response.get('status')}/"
          f"{response.get('errorCode')} msg={response.get('message')!r}")


def check_result(label: str, response: dict, expected):
    ok = response.get("status") == "OK" and response.get("result") == expected
    check(label, ok,
          f"expected result={expected!r} got={response.get('result')!r} "
          f"status={response.get('status')} msg={response.get('message')!r}")


# ── connection / protocol ────────────────────────────────────────────────────

class Conn:
    def __init__(self):
        self.s = socket.create_connection((HOST, PORT), timeout=120)
        self.f = self.s.makefile("rb")

    def send(self, payload: dict) -> dict:
        try:
            self.s.sendall((json.dumps(payload) + "\n").encode())
        except (BrokenPipeError, OSError) as e:
            return {"status": "ERROR", "message": f"send failed: {e}"}
        raw = self.f.readline().decode().strip()
        if not raw:
            return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"status": "ERROR", "message": raw}

    def authenticate(self, username=ADMIN_USERNAME, password=ADMIN_PASSWORD) -> dict:
        return self.send({"type": "AUTHENTICATE", "username": username, "password": password})

    def run(self, script: str, args=None, db=DB) -> dict:
        payload = {"type": "RUN_SCRIPT", "databaseName": db, "script": script}
        if args is not None:
            payload["args"] = args
        return self.send(payload)

    def call(self, name: str, args=None, db=DB) -> dict:
        payload = {"type": "CALL_PROCEDURE", "databaseName": db, "procedureName": name}
        if args is not None:
            payload["args"] = args
        return self.send(payload)

    def save_procedure(self, name: str, script: str, db=DB, **extra) -> dict:
        payload = {"type": "SAVE_PROCEDURE", "databaseName": db, "name": name, "script": script}
        payload.update(extra)
        return self.send(payload)

    def save_trigger(self, name: str, procedure: str, events, coll=COLL, db=DB, **extra) -> dict:
        payload = {"type": "SAVE_TRIGGER", "databaseName": db, "collectionName": coll,
                   "name": name, "events": events, "procedureName": procedure}
        payload.update(extra)
        return self.send(payload)

    def save_schedule(self, name: str, procedure: str, db=DB, **extra) -> dict:
        payload = {"type": "SAVE_SCHEDULE", "databaseName": db, "name": name, "procedureName": procedure}
        payload.update(extra)
        return self.send(payload)

    def delete_schedule(self, name: str, db=DB) -> dict:
        return self.send({"type": "DELETE_SCHEDULE", "databaseName": db, "name": name})

    def save_doc(self, doc, coll=COLL, db=DB) -> dict:
        return self.send({"type": "SAVE", "databaseName": db, "collectionName": coll, "object": doc})

    def find(self, doc_id, coll=COLL, db=DB) -> dict:
        return self.send({"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": doc_id})

    def script_stats(self) -> dict:
        response = self.send({"type": "GET_DATABASE_STATS"})
        return response.get("stats", {}).get("scripts", {})

    def close(self):
        try:
            self.s.close()
        except OSError:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def admin_conn() -> Conn:
    conn = Conn()
    conn.authenticate()
    return conn


# ── the burst ────────────────────────────────────────────────────────────────

class Burst:
    """The result of firing CALLERS concurrent script runs, each on its own connection.

    `peak_running` is the highest `scripts.running` a separate sampling connection observed while
    the burst was in flight. That number, not the response mix, is what actually demonstrates the
    ceiling: responses alone cannot distinguish "the cap held" from "the runner was slow".
    """

    def __init__(self, responses: list, peak_running: int):
        self.responses = responses
        self.peak_running = peak_running
        self.ok = [r for r in responses if r.get("status") == "OK"]
        self.rejected = [r for r in responses if r.get("errorCode") == "503-6"]
        self.other = [r for r in responses if r not in self.ok and r not in self.rejected]


def fire_burst(request: dict, callers: int = CALLERS) -> Burst:
    """Authenticate every caller first, then release them together so the burst really overlaps.

    Connecting and authenticating inside the timed window would stagger the arrivals enough that
    a cap of 2 could be satisfied sequentially without ever being contended.
    """
    responses = []
    responses_lock = threading.Lock()
    ready = threading.Barrier(callers + 1)
    stop_sampling = threading.Event()
    peak = [0]

    def caller():
        with Conn() as conn:
            conn.authenticate()
            ready.wait()
            response = conn.send(request)
        with responses_lock:
            responses.append(response)

    def sampler():
        # `wait` rather than a bare loop: an undelayed poll would flood the server with stats
        # requests and perturb the very concurrency it is measuring. A 5ms interval still takes
        # ~100 samples across a SLOW_SCRIPT_MS run, and an overshoot lasts a whole run.
        with admin_conn() as conn:
            ready.wait()
            while not stop_sampling.wait(0.005):
                running = conn.script_stats().get("running")
                if isinstance(running, int):
                    peak[0] = max(peak[0], running)

    threads = [threading.Thread(target=caller, name=f"caller-{i}") for i in range(callers)]
    sampling = threading.Thread(target=sampler, name="sampler")
    sampling.start()
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(120)
    stop_sampling.set()
    sampling.join(120)
    return Burst(responses, peak[0])


def await_available(conn: Conn, expected: int, timeout=30.0) -> int:
    """A permit is released as the response is written, so a quiet node settles almost at once."""
    deadline = time.time() + timeout
    latest = -1
    while time.time() < deadline:
        latest = conn.script_stats().get("available", -1)
        if latest == expected:
            return latest
        time.sleep(0.1)
    return latest


def await_doc(conn: Conn, doc_id: str, coll: str, timeout=30.0):
    """Triggers and schedules are asynchronous, so poll rather than sleep a fixed interval."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        response = conn.find(doc_id, coll=coll)
        if response.get("status") == "OK":
            return response.get("object")
        time.sleep(0.2)
    return None


# ── server lifecycle ─────────────────────────────────────────────────────────

def write_config(work_dir: str, capacity: int, queue_wait_ms: int):
    cfg = (
        f"port={PORT}\n"
        "filePath=db\n"
        "logPath=logs\n"
        f"defaultAdminUsername={ADMIN_USERNAME}\n"
        f"defaultAdminPassword={ADMIN_PASSWORD}\n"
        "scriptsEnabled=true\n"
        f"maxConcurrentScripts={capacity}\n"
        f"scriptQueueWaitMs={queue_wait_ms}\n"
        f"scriptTimeoutMs={TIMEOUT_MS}\n"
        f"scriptMaxMemoryBytes={MAX_MEMORY_BYTES}\n"
        f"scriptMaxSourceBytes={MAX_SOURCE_BYTES}\n"
        "scriptInstructionBudget=10000000\n"
        "scriptMaxDepth=200\n"
        "triggersEnabled=true\n"
        "triggerThreads=2\n"
        "schedulesEnabled=true\n"
        "scheduleThreads=2\n"
        "scheduleTickMs=200\n"
        "scriptTimeZone=UTC\n"
        "scriptLocale=en-US\n"
    )
    with open(os.path.join(work_dir, "lwnrdb.cfg"), "w") as fp:
        fp.write(cfg)


def port_open() -> bool:
    try:
        with socket.create_connection((HOST, PORT), timeout=0.5):
            return True
    except OSError:
        return False


def start_server(work_dir: str, log_path: str):
    jar = os.path.join(REPO_ROOT, JAR)
    log = open(log_path, "ab")
    proc = subprocess.Popen(["java", "-Xmx512m", "-jar", jar], stdout=log, stderr=log, cwd=work_dir)
    deadline = time.time() + 60.0
    while time.time() < deadline:
        if port_open():
            time.sleep(0.5)
            return proc
        if proc.poll() is not None:
            break
        time.sleep(0.2)
    dump_log(log_path)
    proc.kill()
    raise RuntimeError("server did not come up in time")


def stop_server(proc):
    if proc is None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()
    deadline = time.time() + 30.0
    while time.time() < deadline and port_open():
        time.sleep(0.2)


def dump_log(log_path: str):
    try:
        with open(log_path, "rb") as fp:
            tail = fp.read()[-4000:].decode(errors="replace")
        print(f"--- server log tail ---\n{tail}\n--- end ---", file=sys.stderr)
    except OSError:
        pass


# ── fixtures ─────────────────────────────────────────────────────────────────

def setup_data(conn: Conn):
    section("Setup")
    conn.send({"type": "DROP_DATABASE", "databaseName": DB})
    check_status("create database", conn.send({"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
    for coll in (COLL, AUDIT_COLL, TICKS_COLL):
        check_status(f"create collection {coll}",
                     conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    check_status("store the slow procedure",
                 conn.save_procedure("slow", f"return await new Promise("
                                             f"r => setTimeout(() => r(7), {SLOW_SCRIPT_MS}));"), "OK")
    check_status("store the quick procedure", conn.save_procedure("quick", "return 7;"), "OK")
    check_status("store the audit procedure", conn.save_procedure(
        "audit",
        f"import db from 'db'; import args from 'args';"
        f" db.save(db.name, '{AUDIT_COLL}', {{ _id: args.id, fired: true }}); return 'ok';"), "OK")
    check_status("store the tick procedure", conn.save_procedure(
        "tick",
        f"import db from 'db'; import args from 'args';"
        f" db.save(db.name, '{TICKS_COLL}', {{ _id: args.id, fired: true }}); return 'ok';"), "OK")


# ══════════════════════════════════════════════════════════════════════════
# Phase 1 — the cap rejects immediately (scriptQueueWaitMs=0)
# ══════════════════════════════════════════════════════════════════════════

def test_burst_is_capped(conn: Conn):
    section(f"A burst of {CALLERS} with capacity {CAPACITY} and no wait")
    burst = fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT})

    check("every caller got a response", len(burst.responses) == CALLERS,
          f"expected {CALLERS} responses, got {len(burst.responses)}")
    check("every response is either OK or the capacity rejection", not burst.other,
          f"unexpected responses: {burst.other}")
    check(f"at most the capacity succeeded", 1 <= len(burst.ok) <= CAPACITY,
          f"{len(burst.ok)} callers succeeded with capacity {CAPACITY}")
    check("the surplus was rejected", len(burst.rejected) == CALLERS - len(burst.ok),
          f"{len(burst.ok)} ok + {len(burst.rejected)} rejected != {CALLERS}")
    check("every successful run returned its value",
          all(r.get("result") == 1 for r in burst.ok),
          f"results: {[r.get('result') for r in burst.ok]}")

    # The actual ceiling, not just the response mix.
    check(f"no more than {CAPACITY} interpreters ran at once",
          0 < burst.peak_running <= CAPACITY,
          f"scripts.running peaked at {burst.peak_running}")

    rejection = burst.rejected[0] if burst.rejected else {}
    check("the rejection names the operation", rejection.get("type") == "RUN_SCRIPT", f"got {rejection!r}")
    check("… with ERROR status", rejection.get("status") == "ERROR", f"got {rejection.get('status')!r}")
    check("… and a message", bool(rejection.get("message")), f"got {rejection.get('message')!r}")

    stats = conn.script_stats()
    check("stats report the configured capacity", stats.get("capacity") == CAPACITY, f"got {stats!r}")
    check("stats counted the rejections", stats.get("rejected") == len(burst.rejected),
          f"rejected={stats.get('rejected')} but {len(burst.rejected)} callers were refused")
    check("nobody queued, because the wait is zero", stats.get("waited") == 0, f"got {stats!r}")
    check("every permit came back once the node was quiet",
          await_available(conn, CAPACITY) == CAPACITY, f"available={conn.script_stats().get('available')}")


def test_call_procedure_is_capped(conn: Conn):
    section("CALL_PROCEDURE is capped by the same pool")
    burst = fire_burst({"type": "CALL_PROCEDURE", "databaseName": DB, "procedureName": "slow"})
    check("every response is either OK or the capacity rejection", not burst.other,
          f"unexpected responses: {burst.other}")
    check(f"at most the capacity succeeded", 1 <= len(burst.ok) <= CAPACITY,
          f"{len(burst.ok)} callers succeeded")
    check("the surplus was rejected", len(burst.rejected) == CALLERS - len(burst.ok))
    rejection = burst.rejected[0] if burst.rejected else {}
    check("the rejection names CALL_PROCEDURE", rejection.get("type") == "CALL_PROCEDURE", f"got {rejection!r}")
    check("every permit came back", await_available(conn, CAPACITY) == CAPACITY)


def test_rejection_leaves_the_connection_usable(conn: Conn):
    section("A rejection is an ordinary error response")
    # Hold the pool with CAPACITY slow runs on their own connections, then ask on a fresh one.
    holders = []
    started = threading.Barrier(CAPACITY + 1)

    def holder():
        with Conn() as held:
            held.authenticate()
            started.wait()
            held.run(f"export default new Promise(r => setTimeout(() => r(1), {SLOW_SCRIPT_MS * 3}));")

    for _ in range(CAPACITY):
        thread = threading.Thread(target=holder)
        thread.start()
        holders.append(thread)
    started.wait()
    time.sleep(0.3)

    with Conn() as client:
        client.authenticate()
        refused = client.run("return 1;")
        check_code("a caller is refused while the pool is exhausted", refused, "ERROR", "503-6")
        check_status("… non-script operations still work on that connection",
                     client.send({"type": "LIST_COLLECTIONS", "databaseName": DB}), "OK")
        for thread in holders:
            thread.join(120)
        await_available(conn, CAPACITY)
        # The point: no reconnect was needed. A client forced to reconnect after every 503-6
        # would turn a capacity blip into a connection storm.
        check_result("… and the retry on the same connection succeeds", client.run("return 41 + 1;"), 42)


def test_doomed_requests_do_not_spend_a_permit(conn: Conn):
    section("A request that was never going to run does not consume a permit")
    before = conn.script_stats()
    check("the pool starts full", before.get("available") == CAPACITY, f"got {before!r}")

    check_code("an unknown database is answered on its own terms",
               conn.run("return 1;", db="no_such_db"), "NOT_FOUND", "404-4")
    check_code("an oversized source too", conn.run("x".rjust(MAX_SOURCE_BYTES + 1, "/") + "\nreturn 1;"),
               "ERROR", "400-10")

    after = conn.script_stats()
    check("neither spent a permit", after.get("available") == CAPACITY, f"got {after!r}")
    check("neither counted as a capacity rejection",
          after.get("rejected") == before.get("rejected"),
          f"rejected went from {before.get('rejected')} to {after.get('rejected')}")


def test_permits_survive_failing_runs(conn: Conn):
    section("A permit is released however the run ends")
    check_code("a throwing script", conn.run("throw new Error('nope');"), "ERROR", "400-9")
    check("… released its permit", await_available(conn, CAPACITY) == CAPACITY)
    # A timer, not a busy loop: a spin would trip the instruction budget (400-11) long before the
    # wall clock, and it is the deadline path being tested here.
    check_code("a script that exceeds the deadline",
               conn.run(f"export default new Promise(r => setTimeout(() => r(1), {TIMEOUT_MS * 4}));"),
               "ERROR", "408-1")
    check("… released its permit", await_available(conn, CAPACITY) == CAPACITY)
    check_code("a script that exhausts its allocation budget",
               conn.run("return 'x'.repeat(100000000);"), "ERROR", "400-12")
    check("… released its permit", await_available(conn, CAPACITY) == CAPACITY)
    check_result("and the node still runs scripts afterwards", conn.run("return 'alive';"), "alive")


def test_triggers_are_exempt(conn: Conn):
    section("Triggers are exempt from the cap")
    check_status("install a trigger", conn.save_trigger("auditor", "audit", ["CREATED", "UPDATED"]), "OK")
    # Cumulative for the life of the process, so this section asserts on the delta.
    rejected_before = conn.script_stats().get("rejected")

    holders = []
    started = threading.Barrier(CAPACITY + 1)

    def holder():
        with Conn() as held:
            held.authenticate()
            started.wait()
            held.run(f"export default new Promise(r => setTimeout(() => r(1), {SLOW_SCRIPT_MS * 4}));")

    for _ in range(CAPACITY):
        thread = threading.Thread(target=holder)
        thread.start()
        holders.append(thread)
    started.wait()
    time.sleep(0.3)

    with Conn() as writer:
        writer.authenticate()
        exhausted = writer.script_stats().get("available")
        check("the script pool is exhausted", exhausted == 0, f"available={exhausted}")
        check_status("a write still commits", writer.save_doc({"_id": "trigger-under-saturation", "v": 1}), "OK")

    # The invariant: a permit must never be able to drop a trigger, because the pending-run record
    # is consumed by the transaction that applies its effects — nothing would replay it.
    row = await_doc(conn, "trigger-under-saturation", AUDIT_COLL)
    check("the trigger ran while the cap was saturated", row is not None,
          "the audit row never appeared, so the trigger was dropped by the script cap")

    for thread in holders:
        thread.join(120)
    stats = conn.script_stats()
    check("no trigger run was counted as a capacity rejection",
          stats.get("rejected") == rejected_before,
          f"rejected went from {rejected_before} to {stats.get('rejected')}")
    check_status("remove the trigger",
                 conn.send({"type": "DELETE_TRIGGER", "databaseName": DB,
                            "collectionName": COLL, "name": "auditor"}), "OK")


def test_schedules_are_exempt(conn: Conn):
    section("Scheduled procedures are exempt from the cap")
    check_status("install a schedule",
                 conn.save_schedule("ticker", "tick", intervalMs=500, args={"id": "schedule-under-saturation"}), "OK")

    holders = []
    started = threading.Barrier(CAPACITY + 1)

    def holder():
        with Conn() as held:
            held.authenticate()
            started.wait()
            held.run(f"export default new Promise(r => setTimeout(() => r(1), {SLOW_SCRIPT_MS * 6}));")

    for _ in range(CAPACITY):
        thread = threading.Thread(target=holder)
        thread.start()
        holders.append(thread)
    started.wait()
    time.sleep(0.3)

    with Conn() as probe:
        probe.authenticate()
        check("the script pool is exhausted", probe.script_stats().get("available") == 0)

    row = await_doc(conn, "schedule-under-saturation", TICKS_COLL)
    check("the scheduled run fired while the cap was saturated", row is not None,
          "the tick row never appeared, so the scheduled run was dropped by the script cap")

    for thread in holders:
        thread.join(120)
    check_status("remove the schedule", conn.delete_schedule("ticker"), "OK")


# ══════════════════════════════════════════════════════════════════════════
# Phase 2 — the wait absorbs the burst (scriptQueueWaitMs=10000)
# ══════════════════════════════════════════════════════════════════════════

def test_wait_absorbs_the_burst(conn: Conn):
    section(f"The same burst with a {LONG_WAIT_MS}ms wait")
    burst = fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT})

    check("nobody was refused", not burst.rejected, f"{len(burst.rejected)} caller(s) got 503-6")
    check("every caller succeeded", len(burst.ok) == CALLERS,
          f"only {len(burst.ok)} of {CALLERS} succeeded; others: {burst.other}")
    # The cap still bounds concurrency — the wait changes who is refused, not how many run.
    check(f"no more than {CAPACITY} interpreters ran at once",
          0 < burst.peak_running <= CAPACITY, f"scripts.running peaked at {burst.peak_running}")

    stats = conn.script_stats()
    check("stats counted no rejections", stats.get("rejected") == 0, f"got {stats!r}")
    # `waited` is the leading indicator: the cap is becoming the bottleneck before anyone errors.
    check("stats counted the callers that queued", stats.get("waited") >= CALLERS - CAPACITY,
          f"waited={stats.get('waited')}, expected at least {CALLERS - CAPACITY}")
    check("every permit came back", await_available(conn, CAPACITY) == CAPACITY)


def test_wait_may_exceed_the_run_timeout(conn: Conn):
    section("The wait is not bounded by the run timeout")
    # Legal and intended: a caller may wait longer for a permit than a run is allowed to take.
    check(f"the configured wait ({LONG_WAIT_MS}ms) exceeds scriptTimeoutMs ({TIMEOUT_MS}ms)",
          LONG_WAIT_MS > TIMEOUT_MS)
    burst = fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT},
                       callers=CAPACITY + 1)
    check("the caller that had to wait still succeeded", len(burst.ok) == CAPACITY + 1,
          f"only {len(burst.ok)} of {CAPACITY + 1} succeeded; rejected={len(burst.rejected)}")


# ══════════════════════════════════════════════════════════════════════════
# Phase 3 — the cap is off (maxConcurrentScripts=0)
# ══════════════════════════════════════════════════════════════════════════

def test_cap_disabled(conn: Conn):
    section("The cap disabled (maxConcurrentScripts=0)")
    stats_before = conn.script_stats()
    check("stats report no capacity", stats_before.get("capacity") == 0, f"got {stats_before!r}")

    burst = fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT})
    check("every caller succeeded", len(burst.ok) == CALLERS,
          f"only {len(burst.ok)} of {CALLERS} succeeded; rejected={len(burst.rejected)}; others={burst.other}")
    check("nobody was refused", not burst.rejected)

    # The control for phase 1: with the cap off the same burst really does overlap beyond CAPACITY,
    # so the phase-1 ceiling was the cap and not a slow runner serialising the work anyway.
    check(f"more than {CAPACITY} interpreters ran at once", burst.peak_running > CAPACITY,
          f"scripts.running only peaked at {burst.peak_running}; phase 1's ceiling proves nothing "
          f"if the burst cannot exceed it here")

    stats = conn.script_stats()
    check("stats counted no rejections", stats.get("rejected") == 0, f"got {stats!r}")
    check("stats counted no waits", stats.get("waited") == 0, f"got {stats!r}")
    check_result("a procedure still runs", conn.call("quick"), 7)


def cleanup(conn: Conn):
    section("Cleanup")
    check_status(f"drop {DB}", conn.send({"type": "DROP_DATABASE", "databaseName": DB}), "OK")


# ══════════════════════════════════════════════════════════════════════════

def main():
    print("\n" + "═" * 70)
    print("  LWNRDB — script admission control test suite")
    print("═" * 70)

    jar = os.path.join(REPO_ROOT, JAR)
    if not os.path.isfile(jar):
        print(f"\n[ERROR] Jar not found at {jar}. Build it first: mvn package -DskipTests\n")
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-admission-")
    log_path = os.path.join(work_dir, "server.log")
    print(f"  Working dir: {work_dir}")

    proc = None
    try:
        write_config(work_dir, capacity=CAPACITY, queue_wait_ms=0)
        print(f"  Starting server (capacity {CAPACITY}, no wait) on {HOST}:{PORT} ...")
        proc = start_server(work_dir, log_path)

        with admin_conn() as conn:
            setup_data(conn)
            test_burst_is_capped(conn)
            test_call_procedure_is_capped(conn)
            test_rejection_leaves_the_connection_usable(conn)
            test_doomed_requests_do_not_spend_a_permit(conn)
            test_permits_survive_failing_runs(conn)
            test_triggers_are_exempt(conn)
            test_schedules_are_exempt(conn)

        stop_server(proc)
        proc = None

        write_config(work_dir, capacity=CAPACITY, queue_wait_ms=LONG_WAIT_MS)
        print(f"\n  Restarting server (capacity {CAPACITY}, {LONG_WAIT_MS}ms wait) on {HOST}:{PORT} ...")
        proc = start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_wait_absorbs_the_burst(conn)
            test_wait_may_exceed_the_run_timeout(conn)

        stop_server(proc)
        proc = None

        write_config(work_dir, capacity=0, queue_wait_ms=0)
        print(f"\n  Restarting server (cap disabled) on {HOST}:{PORT} ...")
        proc = start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_cap_disabled(conn)
            cleanup(conn)
    finally:
        stop_server(proc)

    print("\n" + "═" * 70)
    if failures == 0:
        print("  \033[92mAll checks passed.\033[0m")
    else:
        print(f"  \033[91m{failures} check(s) FAILED.\033[0m")
        dump_log(log_path)
    print("═" * 70 + "\n")

    sys.exit(0 if failures == 0 else 1)


if __name__ == "__main__":
    main()
