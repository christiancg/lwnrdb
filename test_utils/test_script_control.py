"""End-to-end tests for script run visibility and cancellation (LIST_SCRIPTS / CANCEL_SCRIPT).

Like the RUN_SCRIPT and schedules suites this script is **self-contained**: it starts its
own LWNRDB instance on a dedicated port and working directory, because the feature needs
scripts, triggers and schedules all enabled plus a deliberately *generous* script timeout
— the opposite of the run-script suite's tight sandbox. A short deadline would make every
cancellation indistinguishable from a timeout, so `scriptTimeoutMs` here is far longer
than any test's patience and a run only ever ends because something stopped it.

It runs in two phases:

  phase 1 — everything enabled, exercising the whole feature;
  phase 2 — the same data directory restarted, proving a trigger run cancelled in phase 1
            is *not* replayed by startup recovery. That is the one place the exactly-once
            trigger guarantee is deliberately waived, so it is pinned across a real
            restart rather than by a unit test alone.

What is covered:

  * LIST_SCRIPTS: empty at rest, one row per live run, and the shape of a row
    (runId/node/kind/database/name/username/ageMs) for each of the four run kinds —
    RUN_SCRIPT, CALL_PROCEDURE, TRIGGER and SCHEDULE;
  * CANCEL_SCRIPT: the run stops, the cancelled caller receives 408-2 with the same runId
    the listing showed, and the listing empties again;
  * the answers that are deliberately not errors: an unknown runId and an already-finished
    run both report cancelled:false with status OK;
  * cancellation semantics through the wire: it is not catchable by the script's own
    try/catch, its finally does not run, a run parked on a long timer is stopped promptly
    (the bounded event-loop park), and an open db.transaction is rolled back;
  * validation and authorization: a missing/blank/non-UUID runId is 400-1, and both
    operations are admin-only — a database owner holding a script grant is still refused;
  * the runId on the RUN_SCRIPT and CALL_PROCEDURE responses, so a caller can name its own
    run without listing first;
  * GET_DATABASE_STATS: scripts.running tracks live concurrency and scripts.cancelled
    counts cancellations.

The cluster-wide half of the feature — a run being listed and cancelled from a node that
is not executing it — lives in test_clustering.py, which owns the multi-node topology.

The server lifecycle is managed via a tracked subprocess handle (not pgrep), so stopping
it never touches an unrelated LWNRDB process.
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
from collections.abc import Callable
from typing import Optional

HOST = "127.0.0.1"
PORT = int(os.environ.get("SCRIPT_CONTROL_TEST_PORT", "8998"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "script_control_db"
COLL = "docs"
OUT_COLL = "sideeffects"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Long enough that no test's own patience can be mistaken for the sandbox giving up: every
# run in this suite ends because it was cancelled, finished, or the test failed.
TIMEOUT_MS = 600_000
# How long a "slow" script asks to sleep for. Well past the point any assertion waits.
SLOW_MS = 300_000
SLOW_SCRIPT = f"export default new Promise(r => setTimeout(() => r(1), {SLOW_MS}));"
# A busy loop instead of a parked timer, for the cases that must not depend on the event loop.
BUSY_SCRIPT = "while (true) { }"

# Far past what any run here can spend before it is cancelled: like the deadline, the budget
# must never be the reason a run ended. (The key rejects -1, so this is the "unlimited" form.)
INSTRUCTION_BUDGET = 1_000_000_000_000
TICK_MS = 200

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


# ── connection / protocol ────────────────────────────────────────────────────

class Conn:
    def __init__(self, timeout=60):
        self.s = socket.create_connection((HOST, PORT), timeout=timeout)
        self.f = self.s.makefile("rb")

    def send(self, payload: dict) -> dict:
        try:
            self.s.sendall((json.dumps(payload) + "\n").encode())
        except (BrokenPipeError, OSError) as e:
            return {"status": "ERROR", "message": f"send failed: {e}"}
        try:
            raw = self.f.readline().decode().strip()
        except (OSError, ConnectionError) as e:
            return {"status": "ERROR", "message": f"read failed: {e}"}
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

    def list_scripts(self) -> dict:
        return self.send({"type": "LIST_SCRIPTS"})

    def cancel(self, run_id) -> dict:
        payload = {"type": "CANCEL_SCRIPT"}
        if run_id is not None:
            payload["runId"] = run_id
        return self.send(payload)

    def save_procedure(self, name: str, script: str, db=DB, **extra) -> dict:
        payload = {"type": "SAVE_PROCEDURE", "databaseName": db, "name": name, "script": script}
        payload.update(extra)
        return self.send(payload)

    def stats(self) -> dict:
        response = self.send({"type": "GET_DATABASE_STATS"})
        return ((response.get("stats") or {}).get("scripts") or {})

    def trigger_stats(self) -> dict:
        response = self.send({"type": "GET_DATABASE_STATS"})
        return ((response.get("stats") or {}).get("triggers") or {})

    def close(self):
        try:
            self.s.close()
        except OSError:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def admin_conn(timeout=60) -> Conn:
    conn = Conn(timeout=timeout)
    r = conn.authenticate()
    if r.get("status") != "OK":
        conn.close()
        raise RuntimeError(f"admin auth failed: {r}")
    return conn


# ── run-in-the-background helper ─────────────────────────────────────────────

class BackgroundRun:
    """Starts a script on its own connection and thread, so the test can look it up and
    cancel it while it is still executing. The response is captured for assertions."""

    def __init__(self, start: Callable[[Conn], dict]):
        self.response: Optional[dict] = None
        self._thread = threading.Thread(target=self._run, args=(start,), daemon=True)
        self._thread.start()

    def _run(self, start: Callable[[Conn], dict]):
        conn = admin_conn(timeout=SLOW_MS / 1000 + 60)
        try:
            self.response = start(conn)
        finally:
            conn.close()

    def join(self, timeout_s: float = 60.0) -> bool:
        self._thread.join(timeout_s)
        return not self._thread.is_alive()


def wait_until(predicate: Callable[[], object], timeout_s: float = 30.0, interval_s: float = 0.1):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval_s)
    return predicate()


def await_one_run(conn: Conn, timeout_s: float = 30.0) -> Optional[dict]:
    """The single row LIST_SCRIPTS reports, once it appears. Polling rather than sleeping,
    because how long a run takes to register is a scheduling detail, not a contract."""
    def _one():
        response = conn.list_scripts()
        if response.get("status") != "OK":
            return None
        rows = response.get("scripts") or []
        return rows[0] if len(rows) == 1 else None
    return wait_until(_one, timeout_s)


def await_empty_listing(conn: Conn, timeout_s: float = 60.0) -> bool:
    return bool(wait_until(lambda: not (conn.list_scripts().get("scripts") or []), timeout_s))


def check_row_shape(label: str, row: Optional[dict], kind: str, name: Optional[str],
                    username: str = ADMIN_USERNAME):
    if not row:
        check(label, False, "no run was listed")
        return
    ok = (isinstance(row.get("runId"), str) and row.get("kind") == kind
          and row.get("database") == DB and row.get("name") == name
          and row.get("username") == username and isinstance(row.get("ageMs"), int)
          and row.get("ageMs") >= 0 and isinstance(row.get("node"), str))
    check(label, ok, f"got {row!r}")


# ── server lifecycle ─────────────────────────────────────────────────────────

def write_config(work_dir: str):
    cfg = (
        f"port={PORT}\n"
        "filePath=db\n"
        "logPath=logs\n"
        "maxMemory=256mb\n"
        f"defaultAdminUsername={ADMIN_USERNAME}\n"
        f"defaultAdminPassword={ADMIN_PASSWORD}\n"
        "scriptsEnabled=true\n"
        f"scriptInstructionBudget={INSTRUCTION_BUDGET}\n"
        f"scriptTimeoutMs={TIMEOUT_MS}\n"
        "scriptMaxDepth=64\n"
        "scriptMaxSourceBytes=8192\n"
        "scriptMaxLogLines=50\n"
        "scriptMaxLogLineChars=500\n"
        "scriptMaxMemoryBytes=16777216\n"
        "scriptMaxResultBytes=65536\n"
        "scriptTextImportEnabled=false\n"
        "scriptTimeZone=UTC\n"
        "scriptLocale=en-US\n"
        "procedureCacheSize=16\n"
        "triggersEnabled=true\n"
        "triggerThreads=2\n"
        "triggerQueueSize=1000\n"
        "triggerMaxDepth=3\n"
        # A trigger must be stoppable rather than time out, for the same reason the script
        # deadline is long.
        f"triggerTimeoutMs={TIMEOUT_MS}\n"
        "triggerRunLogEnabled=true\n"
        "schedulesEnabled=true\n"
        f"scheduleTickMs={TICK_MS}\n"
        "scheduleRefreshMs=2000\n"
        f"scheduleTimeoutMs={TIMEOUT_MS}\n"
        # Room for several overlapping runs: some cases hold two at once.
        "maxConcurrentScripts=8\n"
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


def read_log(log_path: str) -> str:
    try:
        with open(log_path, "rb") as fp:
            return fp.read().decode(errors="replace")
    except OSError:
        return ""


# ── setup ────────────────────────────────────────────────────────────────────

def setup_data(conn: Conn):
    section("Setup")
    check_status("CREATE_DATABASE", conn.send({"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
    for coll in (COLL, OUT_COLL):
        check_status(f"CREATE_COLLECTION {coll}",
                     conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    check_status("SAVE_PROCEDURE for a slow procedure", conn.save_procedure("slow", SLOW_SCRIPT), "OK")


# ── tests ────────────────────────────────────────────────────────────────────

def test_listing_is_empty_at_rest(conn: Conn):
    section("LIST_SCRIPTS — nothing running")
    response = conn.list_scripts()
    check_status("LIST_SCRIPTS succeeds", response, "OK")
    check("no runs are reported", (response.get("scripts") or []) == [],
          f"got {response.get('scripts')!r}")
    check("LIST_SCRIPTS is not itself a run", conn.stats().get("running") == 0,
          f"running={conn.stats().get('running')!r}")


def test_cancels_a_running_script(conn: Conn):
    section("CANCEL_SCRIPT — an ad-hoc RUN_SCRIPT")
    cancelled_before = conn.stats().get("cancelled", 0)
    background = BackgroundRun(lambda c: c.run(SLOW_SCRIPT))
    row = await_one_run(conn)
    check_row_shape("the run is listed with its kind, database and user", row, "RUN_SCRIPT", None)
    check("the run is counted as running", conn.stats().get("running") == 1,
          f"running={conn.stats().get('running')!r}")

    response = conn.cancel(row["runId"]) if row else {}
    check_status("CANCEL_SCRIPT succeeds", response, "OK")
    check("CANCEL_SCRIPT reports the run as cancelled", response.get("cancelled") is True,
          f"got {response!r}")

    check("the cancelled run returns to its caller", background.join(60.0))
    check_code("the caller receives 408-2", background.response or {}, "ERROR", "408-2")
    check("the caller's runId matches the listed run",
          (background.response or {}).get("runId") == (row or {}).get("runId"),
          f"listed={(row or {}).get('runId')!r} response={(background.response or {}).get('runId')!r}")
    check("the run leaves the listing", await_empty_listing(conn))
    check("the cancellation is counted", conn.stats().get("cancelled", 0) == cancelled_before + 1,
          f"before={cancelled_before} after={conn.stats().get('cancelled')!r}")
    check("the node is idle again", conn.stats().get("running") == 0,
          f"running={conn.stats().get('running')!r}")


def test_cancels_a_procedure_call(conn: Conn):
    section("CANCEL_SCRIPT — a CALL_PROCEDURE")
    background = BackgroundRun(lambda c: c.call("slow"))
    row = await_one_run(conn)
    check_row_shape("the call is listed with its procedure name", row, "CALL_PROCEDURE", "slow")

    check("CANCEL_SCRIPT reports the call as cancelled",
          conn.cancel(row["runId"]).get("cancelled") is True if row else False)
    check("the cancelled call returns to its caller", background.join(60.0))
    check_code("the caller receives 408-2", background.response or {}, "ERROR", "408-2")
    check("CALL_PROCEDURE also returns a runId",
          (background.response or {}).get("runId") == (row or {}).get("runId"),
          f"got {(background.response or {}).get('runId')!r}")
    check("the call leaves the listing", await_empty_listing(conn))


def test_cancels_a_trigger_run(conn: Conn):
    """The trigger case is also the exactly-once waiver: phase 2 asserts this run is not
    replayed after a restart, so the document that fired it is written here on purpose."""
    section("CANCEL_SCRIPT — a trigger run")
    # Both events: a wire SAVE of a fresh _id is classified UPDATED, so watching CREATED alone
    # would install a trigger that never fires.
    check_status("SAVE_TRIGGER on the slow procedure",
                 conn.send({"type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": COLL,
                            "name": "slowTrigger", "events": ["CREATED", "UPDATED"],
                            "procedureName": "slow"}), "OK")
    check_status("SAVE fires the trigger",
                 conn.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                            "object": {"_id": "fires-trigger", "v": 1}}), "OK")

    row = await_one_run(conn)
    # A trigger runs with its definer's authority, so that — not the writer — is the user reported.
    check_row_shape("the trigger run is listed with its trigger name", row, "TRIGGER", "slowTrigger")
    check("CANCEL_SCRIPT reports the trigger run as cancelled",
          conn.cancel(row["runId"]).get("cancelled") is True if row else False)
    check("the trigger run leaves the listing", await_empty_listing(conn))
    # The record that would replay this run must be gone, not merely unused: that is what makes
    # phase 2's "nothing was re-queued" an assertion about consumption rather than about timing.
    consumed = wait_until(lambda: conn.trigger_stats().get("pendingRuns") == 0, 30.0)
    check("the cancelled run's pending record was consumed", bool(consumed),
          f"triggers={conn.trigger_stats()!r}")

    check_status("DELETE_TRIGGER so nothing else fires it",
                 conn.send({"type": "DELETE_TRIGGER", "databaseName": DB,
                            "collectionName": COLL, "name": "slowTrigger"}), "OK")


def test_cancels_a_scheduled_run(conn: Conn):
    section("CANCEL_SCRIPT — a scheduled run")
    check_status("SAVE_SCHEDULE on the slow procedure",
                 conn.send({"type": "SAVE_SCHEDULE", "databaseName": DB, "name": "slowJob",
                            "procedureName": "slow", "intervalMs": 500}), "OK")
    row = await_one_run(conn)
    check_row_shape("the scheduled run is listed with its schedule name", row, "SCHEDULE", "slowJob")

    # Deleted before the cancel: the schedule fires every 500ms, so leaving it installed would
    # start a fresh run and make "the listing empties" a race rather than an assertion.
    check_status("DELETE_SCHEDULE stops it recurring",
                 conn.send({"type": "DELETE_SCHEDULE", "databaseName": DB, "name": "slowJob"}), "OK")
    check("CANCEL_SCRIPT reports the scheduled run as cancelled",
          conn.cancel(row["runId"]).get("cancelled") is True if row else False)
    check("the scheduled run leaves the listing", await_empty_listing(conn))


def test_cancellation_is_not_catchable(conn: Conn):
    section("Cancellation is not catchable and skips finally")
    script = ("import db from \"db\";\n"
              "try {\n"
              f"  {BUSY_SCRIPT}\n"
              "} catch (e) {\n"
              "  return \"caught\";\n"
              "} finally {\n"
              "  db.save(db.name, \"" + OUT_COLL + "\", { _id: \"finally-ran\", ok: true });\n"
              "}\n")
    background = BackgroundRun(lambda c: c.run(script))
    row = await_one_run(conn)
    check("the run is listed", bool(row))
    check("CANCEL_SCRIPT reports it as cancelled",
          conn.cancel(row["runId"]).get("cancelled") is True if row else False)
    check("the run returns to its caller", background.join(60.0))

    response = background.response or {}
    check_code("the script's own catch did not intercept it", response, "ERROR", "408-2")
    check("the result is not the caught value", response.get("result") is None,
          f"result={response.get('result')!r}")
    found = conn.send({"type": "FIND_BY_ID", "databaseName": DB,
                       "collectionName": OUT_COLL, "_id": "finally-ran"})
    check("the finally block did not run", found.get("status") != "OK",
          f"the finalizer's write is present: {found!r}")


def test_a_parked_run_is_cancelled_promptly(conn: Conn):
    """The bounded event-loop park: before it, a run parked on a long timer would not notice
    its cancellation until the timer came due."""
    section("A run parked on a long timer is cancelled promptly")
    background = BackgroundRun(lambda c: c.run(SLOW_SCRIPT))
    row = await_one_run(conn)
    check("the parked run is listed", bool(row))

    started = time.time()
    check("CANCEL_SCRIPT reports it as cancelled",
          conn.cancel(row["runId"]).get("cancelled") is True if row else False)
    returned = background.join(30.0)
    elapsed = time.time() - started
    check("the parked run returned", returned)
    # Generous against a loaded runner while still far below the SLOW_MS the timer asked for.
    check("it returned promptly rather than waiting out its timer", returned and elapsed < 20.0,
          f"took {elapsed:.1f}s for a timer of {SLOW_MS / 1000:.0f}s")
    check_code("the caller receives 408-2", background.response or {}, "ERROR", "408-2")


def test_cancelling_a_transactional_script(conn: Conn):
    section("A cancelled db.transaction is rolled back")
    script = ("import db from \"db\";\n"
              "db.transaction(() => {\n"
              "  db.save(db.name, \"" + OUT_COLL + "\", { _id: \"tx-cancelled\", ok: true });\n"
              f"  {BUSY_SCRIPT}\n"
              "});\n")
    background = BackgroundRun(lambda c: c.run(script))
    row = await_one_run(conn)
    check("the transactional run is listed", bool(row))
    check("CANCEL_SCRIPT reports it as cancelled",
          conn.cancel(row["runId"]).get("cancelled") is True if row else False)
    check("the run returns to its caller", background.join(60.0))
    check_code("the caller receives 408-2", background.response or {}, "ERROR", "408-2")

    found = conn.send({"type": "FIND_BY_ID", "databaseName": DB,
                       "collectionName": OUT_COLL, "_id": "tx-cancelled"})
    check("the buffered write was rolled back", found.get("status") != "OK", f"got {found!r}")
    # A stranded collection lock would make the next write on that collection hang or fail;
    # this is what proves the rollback released it.
    check_status("the collection is writable again",
                 conn.send({"type": "SAVE", "databaseName": DB, "collectionName": OUT_COLL,
                            "object": {"_id": "after-rollback", "ok": True}}), "OK")


def test_reports_several_concurrent_runs(conn: Conn):
    section("LIST_SCRIPTS — several runs at once")
    first = BackgroundRun(lambda c: c.run(SLOW_SCRIPT))
    second = BackgroundRun(lambda c: c.call("slow"))

    def _two():
        rows = conn.list_scripts().get("scripts") or []
        return rows if len(rows) == 2 else None

    rows = wait_until(_two, 30.0)
    check("both runs are listed", bool(rows), f"got {conn.list_scripts().get('scripts')!r}")
    check("running counts both", conn.stats().get("running") == 2,
          f"running={conn.stats().get('running')!r}")
    if rows:
        check("each run has its own id", rows[0]["runId"] != rows[1]["runId"])
        check("the two kinds are distinguished",
              {r["kind"] for r in rows} == {"RUN_SCRIPT", "CALL_PROCEDURE"},
              f"kinds={[r['kind'] for r in rows]!r}")
        # Cancelling one must not touch the other: the id is the unit of cancellation.
        target = next(r for r in rows if r["kind"] == "RUN_SCRIPT")
        other = next(r for r in rows if r["kind"] == "CALL_PROCEDURE")
        check("cancelling one of them succeeds", conn.cancel(target["runId"]).get("cancelled") is True)
        check("that run returned", first.join(60.0))
        check_code("and it is the one that got 408-2", first.response or {}, "ERROR", "408-2")
        remaining = conn.list_scripts().get("scripts") or []
        check("the other run is still listed",
              [r["runId"] for r in remaining] == [other["runId"]], f"got {remaining!r}")
        check("cancelling the other one succeeds", conn.cancel(other["runId"]).get("cancelled") is True)
    check("the second run returned", second.join(60.0))
    check("the listing empties", await_empty_listing(conn))


def test_cancel_answers_that_are_not_errors(conn: Conn):
    section("CANCEL_SCRIPT — the answers that are not errors")
    unknown = conn.cancel("00000000-0000-0000-0000-000000000000")
    check_status("an unknown runId is OK", unknown, "OK")
    check("an unknown runId reports cancelled:false", unknown.get("cancelled") is False,
          f"got {unknown!r}")

    finished = conn.run("return 1;")
    check_status("a quick run succeeds", finished, "OK")
    check("a completed run returns a runId", isinstance(finished.get("runId"), str),
          f"got {finished.get('runId')!r}")
    already = conn.cancel(finished.get("runId"))
    check_status("cancelling a finished run is OK", already, "OK")
    check("cancelling a finished run reports cancelled:false", already.get("cancelled") is False,
          f"got {already!r}")


def test_request_validation(conn: Conn):
    section("CANCEL_SCRIPT — validation")
    check_code("a missing runId is refused", conn.cancel(None), "ERROR", "400-1")
    check_code("a blank runId is refused", conn.cancel("   "), "ERROR", "400-1")
    check_code("a non-UUID runId is refused", conn.cancel("not-a-uuid"), "ERROR", "400-1")
    check_code("an id-shaped but non-UUID runId is refused", conn.cancel("run-1"), "ERROR", "400-1")


def test_permissions(conn: Conn):
    section("Both operations are admin-only")
    # The widest non-admin authority there is for scripts: owns the database and may run them.
    check_status("CREATE_USER for a database owner with a script grant",
                 conn.send({"type": "CREATE_USER", "username": "script_owner",
                            "password": "script_owner1234", "admin": False,
                            "globalPermissions": [], "databasePermissions": {DB: "READ_WRITE"},
                            "collectionPermissions": {}, "scriptPermissions": {DB: "MANAGE"}}), "OK")
    check_status("SET_DATABASE_OWNERS makes them the owner",
                 conn.send({"type": "SET_DATABASE_OWNERS", "databaseName": DB,
                            "owners": ["script_owner"]}), "OK")

    with Conn() as owner:
        check_status("the owner authenticates",
                     owner.authenticate("script_owner", "script_owner1234"), "OK")
        check_status("the owner may still run a script", owner.run("return 1;"), "OK")
        check_code("but LIST_SCRIPTS is refused", owner.list_scripts(), "FORBIDDEN", "403-1")
        check_code("and CANCEL_SCRIPT is refused",
                   owner.cancel("00000000-0000-0000-0000-000000000000"), "FORBIDDEN", "403-1")

    with Conn() as anonymous:
        check_code("LIST_SCRIPTS needs authentication", anonymous.list_scripts(),
                   "UNAUTHENTICATED", "401-1")
    with Conn() as anonymous:
        check_code("CANCEL_SCRIPT needs authentication",
                   anonymous.cancel("00000000-0000-0000-0000-000000000000"),
                   "UNAUTHENTICATED", "401-1")


def test_the_run_id_is_logged(conn: Conn, log_path: str):
    section("The runId reaches the server log")
    response = conn.run("return 1;")
    check_status("a run for the log line", response, "OK")
    run_id = response.get("runId")
    found = wait_until(lambda: f"runId={run_id}" in read_log(log_path), 15.0)
    check("the run's log line names it", bool(found), f"runId={run_id!r} not found in the log")


def test_cancelled_trigger_run_is_not_replayed(conn: Conn):
    """Phase 2. Trigger execution is otherwise exactly-once: a run whose pending record
    survives is re-queued at startup. A cancellation consumes that record instead, so the
    restart must fire nothing — the documented waiver."""
    section("Phase 2 — a cancelled trigger run is not replayed after a restart")
    check("nothing is running after the restart", (conn.list_scripts().get("scripts") or []) == [],
          f"got {conn.list_scripts().get('scripts')!r}")
    stats = conn.stats()
    check("the cancellation counter starts fresh on the new process", stats.get("cancelled") == 0,
          f"cancelled={stats.get('cancelled')!r}")
    check("no pending trigger run survived the restart",
          conn.trigger_stats().get("pendingRuns") == 0, f"triggers={conn.trigger_stats()!r}")
    # If the cancelled run had been left pending, recovery would have re-queued it and the slow
    # procedure would be executing again by now.
    still_quiet = wait_until(lambda: (conn.list_scripts().get("scripts") or []) != [], 5.0)
    check("startup recovery did not re-queue the cancelled trigger run", not still_quiet,
          f"a run appeared after the restart: {conn.list_scripts().get('scripts')!r}")


def cleanup(conn: Conn):
    section("Cleanup")
    check_status("DROP_DATABASE", conn.send({"type": "DROP_DATABASE", "databaseName": DB}), "OK")
    check_status("DELETE_USER", conn.send({"type": "DELETE_USER", "username": "script_owner"}), "OK")


# ── main ─────────────────────────────────────────────────────────────────────

def main():
    print("\n" + "═" * 70)
    print("  LWNRDB — script run visibility and cancellation e2e tests")
    print("═" * 70)

    jar = os.path.join(REPO_ROOT, JAR)
    if not os.path.isfile(jar):
        print(f"\n[ERROR] Jar not found at {jar}. Build it first: mvn package -DskipTests\n")
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-scriptcontrol-")
    log_path = os.path.join(work_dir, "server.log")
    print(f"  Working dir: {work_dir}")

    proc = None
    try:
        write_config(work_dir)
        print(f"  Starting server on {HOST}:{PORT} ...")
        proc = start_server(work_dir, log_path)

        with admin_conn() as conn:
            setup_data(conn)
            test_listing_is_empty_at_rest(conn)
            test_cancels_a_running_script(conn)
            test_cancels_a_procedure_call(conn)
            test_cancels_a_trigger_run(conn)
            test_cancels_a_scheduled_run(conn)
            test_cancellation_is_not_catchable(conn)
            test_a_parked_run_is_cancelled_promptly(conn)
            test_cancelling_a_transactional_script(conn)
            test_reports_several_concurrent_runs(conn)
            test_cancel_answers_that_are_not_errors(conn)
            test_request_validation(conn)
            test_the_run_id_is_logged(conn, log_path)
            test_permissions(conn)

        stop_server(proc)
        proc = None

        print(f"\n  Restarting server on {HOST}:{PORT} (trigger-recovery phase) ...")
        proc = start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_cancelled_trigger_run_is_not_replayed(conn)
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
