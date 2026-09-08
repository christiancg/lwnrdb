"""End-to-end tests for scheduled procedures.

Like the RUN_SCRIPT and procedures/triggers suites this script is **self-contained**: it
starts its own LWNRDB instance on a dedicated port and working directory, because the
feature needs both `scriptsEnabled=true` and `schedulesEnabled=true`, which the shared CI
server does not have. It runs in two phases:

  phase 1 — scripts and schedules enabled, with a one-second tick so an interval schedule
            is observable inside a test rather than only in theory;
  phase 2 — the same data directory restarted with `schedulesEnabled=false`, proving the
            master switch refuses all three operations and stops anything from firing.

What is covered:

  * an interval schedule fires repeatedly against a live counter document, and deleting it
    stops the firing;
  * a cron schedule for the next minute fires, and fires exactly once within that minute;
  * LIST_SCHEDULES round-trips the definition and reports a nextRunAt in the future;
  * validation: a malformed cron and both/neither of cron and intervalMs answer 400-16, an
    unknown procedure answers 404-8, and the per-database cap answers 400-17;
  * referential integrity: deleting a procedure a schedule still references is refused;
  * permissions: a non-MANAGE user may not install a schedule but a READ user may list;
  * definer rights: the job writes a collection its installer owns;
  * storage placement: the record lands in {db}/.schedules/{name}.json, nothing under admin/;
  * cascade delete: DROP_DATABASE removes the schedules and the scheduler stops firing them;
  * stats: GET_DATABASE_STATS reports the schedule counters;
  * the schedulesEnabled master switch.

The server lifecycle is managed via a tracked subprocess handle (not pgrep), so stopping it
never touches an unrelated LWNRDB process.
"""

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone

HOST = "127.0.0.1"
PORT = int(os.environ.get("SCHEDULE_TEST_PORT", "8995"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "sched_test_db"
COLL = "counters"
USER_PASSWORD = "password123"
MANAGER = "sched_manager"
READER = "sched_reader"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

TICK_MS = 200
SCHEDULE_TIMEOUT_MS = 10_000
MAX_PER_DATABASE = 3

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
    def __init__(self):
        self.s = socket.create_connection((HOST, PORT), timeout=60)
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

    def save_procedure(self, name, script, db=DB, **extra) -> dict:
        payload = {"type": "SAVE_PROCEDURE", "databaseName": db, "name": name, "script": script}
        payload.update(extra)
        return self.send(payload)

    def delete_procedure(self, name, db=DB) -> dict:
        return self.send({"type": "DELETE_PROCEDURE", "databaseName": db, "name": name})

    def save_schedule(self, name, procedure, db=DB, **extra) -> dict:
        payload = {"type": "SAVE_SCHEDULE", "databaseName": db, "name": name, "procedureName": procedure}
        payload.update(extra)
        return self.send(payload)

    def delete_schedule(self, name, db=DB) -> dict:
        return self.send({"type": "DELETE_SCHEDULE", "databaseName": db, "name": name})

    def list_schedules(self, db=DB) -> dict:
        return self.send({"type": "LIST_SCHEDULES", "databaseName": db})

    def find(self, doc_id, coll=COLL, db=DB) -> dict:
        return self.send({"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": doc_id})

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


def user_conn(username: str) -> Conn:
    conn = Conn()
    conn.authenticate(username, USER_PASSWORD)
    return conn


def counter_value(conn: Conn, doc_id: str) -> int:
    response = conn.find(doc_id)
    if response.get("status") != "OK":
        return 0
    return int(response.get("object", {}).get("n", 0))


def await_counter(conn: Conn, doc_id: str, at_least: int, timeout=30.0) -> int:
    """Scheduled runs are asynchronous, so poll rather than sleep a fixed interval."""
    deadline = time.time() + timeout
    latest = 0
    while time.time() < deadline:
        latest = counter_value(conn, doc_id)
        if latest >= at_least:
            return latest
        time.sleep(0.2)
    return latest


# ── server lifecycle ─────────────────────────────────────────────────────────

def write_config(work_dir: str, scripts_enabled: bool, schedules_enabled: bool):
    cfg = (
        f"port={PORT}\n"
        "filePath=db\n"
        "logPath=logs\n"
        f"defaultAdminUsername={ADMIN_USERNAME}\n"
        f"defaultAdminPassword={ADMIN_PASSWORD}\n"
        f"scriptsEnabled={'true' if scripts_enabled else 'false'}\n"
        "scriptInstructionBudget=2000000\n"
        "scriptTimeoutMs=5000\n"
        "scriptMaxDepth=64\n"
        "scriptMaxSourceBytes=8192\n"
        "scriptMaxLogLines=50\n"
        "scriptMaxLogLineChars=500\n"
        "scriptMaxMemoryBytes=4194304\n"
        "scriptMaxResultBytes=65536\n"
        "scriptTextImportEnabled=false\n"
        "scriptTimeZone=UTC\n"
        "scriptLocale=en-US\n"
        "procedureCacheSize=16\n"
        "triggersEnabled=false\n"
        f"schedulesEnabled={'true' if schedules_enabled else 'false'}\n"
        "scheduleThreads=2\n"
        "scheduleQueueSize=100\n"
        f"scheduleTickMs={TICK_MS}\n"
        "scheduleRefreshMs=1000\n"
        f"scheduleTimeoutMs={SCHEDULE_TIMEOUT_MS}\n"
        f"scheduleMaxPerDatabase={MAX_PER_DATABASE}\n"
        "scheduleCacheMaxBytes=8Mb\n"
        "scriptRunHistoryEnabled=true\n"
        "scriptRunHistoryKinds=CALL_PROCEDURE,TRIGGER,SCHEDULE\n"
        "scriptRunHistoryRetentionMs=604800000\n"
        "scriptRunHistoryIncludeLogs=false\n"
        "scriptRunHistoryMaxErrorChars=2000\n"
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


def read_log(log_path: str) -> str:
    try:
        with open(log_path, "rb") as fp:
            return fp.read().decode(errors="replace")
    except OSError:
        return ""


def dump_log(log_path: str):
    try:
        with open(log_path, "rb") as fp:
            tail = fp.read()[-4000:].decode(errors="replace")
        print(f"--- server log tail ---\n{tail}\n--- end ---", file=sys.stderr)
    except OSError:
        pass


# ── fixtures ─────────────────────────────────────────────────────────────────

# The counter procedure is idempotent in shape but not in effect: each run reads the
# document and writes it back one higher, which is exactly what makes "did it fire, and how
# often" observable from the client side.
COUNTER_SOURCE = (
    "import db from 'db'; import args from 'args';"
    " const id = args.id;"
    " const existing = db.findById(db.name, '" + COLL + "', id);"
    " const n = existing ? existing.n + 1 : 1;"
    " db.save(db.name, '" + COLL + "', { _id: id, n: n });"
    " return n;"
)


def setup_data(conn: Conn):
    section("Setup")
    conn.send({"type": "DROP_DATABASE", "databaseName": DB})
    check_status("create database", conn.send({"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
    check_status("create collection",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL}), "OK")
    check_status("store the counter procedure", conn.save_procedure("counter", COUNTER_SOURCE), "OK")

    check_status("create the MANAGE-level user", conn.send({
        "type": "CREATE_USER", "username": MANAGER, "password": USER_PASSWORD, "admin": False,
        "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "MANAGE"}}), "OK")
    check_status("create the read-only user", conn.send({
        "type": "CREATE_USER", "username": READER, "password": USER_PASSWORD, "admin": False,
        "databasePermissions": {DB: "READ"}}), "OK")


def clear_schedules(conn: Conn):
    for schedule in conn.list_schedules().get("schedules", []):
        conn.delete_schedule(schedule["name"])


# ── tests ────────────────────────────────────────────────────────────────────

def test_interval_schedule(conn: Conn):
    section("Interval schedule")
    check_status("save an interval schedule", conn.save_schedule(
        "ticker", "counter", intervalMs=500, args={"id": "interval"}), "OK")

    first = await_counter(conn, "interval", 1)
    check("the schedule fired", first >= 1, f"counter never advanced (last value {first})")
    second = await_counter(conn, "interval", first + 1)
    check("the schedule keeps firing", second > first, f"counter stuck at {first}")

    check_status("delete the schedule", conn.delete_schedule("ticker"), "OK")
    settled = counter_value(conn, "interval")
    time.sleep(2.0)
    after = counter_value(conn, "interval")
    check("deleting the schedule stops the firing", after <= settled + 1,
          f"counter advanced from {settled} to {after} after the delete")


def test_cron_schedule(conn: Conn):
    section("Cron schedule")
    # A cron for the minute after next, so the test never races the current minute rolling over.
    target = (datetime.now(timezone.utc) + timedelta(minutes=1)).replace(second=0, microsecond=0)
    cron = f"{target.minute} {target.hour} * * *"
    check_status("save a cron schedule for the next minute",
                 conn.save_schedule("onceAMinute", "counter", cron=cron, args={"id": "cron"}), "OK")

    wait = max(5.0, (target - datetime.now(timezone.utc)).total_seconds() + 20.0)
    value = await_counter(conn, "cron", 1, timeout=wait)
    check("the cron schedule fired", value >= 1, f"counter never advanced (last value {value})")
    # Still within the same minute: a cron schedule must fire once per matching minute, not per tick.
    time.sleep(3.0)
    check("it fired exactly once in that minute", counter_value(conn, "cron") == value,
          f"counter advanced past {value} within the same minute")
    conn.delete_schedule("onceAMinute")


def test_listing(conn: Conn):
    section("LIST_SCHEDULES")
    check_status("save a schedule to list", conn.save_schedule(
        "listed", "counter", cron="0 3 * * *", args={"id": "listed"}, timeoutMs=60000,
        description="daily rollup"), "OK")
    response = conn.list_schedules()
    check_status("list schedules", response, "OK")
    listed = [s for s in response.get("schedules", []) if s["name"] == "listed"]
    check("the schedule is listed", len(listed) == 1, f"got {response.get('schedules')}")
    if listed:
        entry = listed[0]
        check("the definition round-trips", entry.get("procedureName") == "counter"
              and entry.get("cron") == "0 3 * * *" and entry.get("timeoutMs") == 60000
              and entry.get("description") == "daily rollup" and entry.get("definer") == ADMIN_USERNAME,
              f"got {entry}")
        check("the listing omits args", "args" not in entry, f"got {entry}")
        check("nextRunAt is in the future", entry.get("nextRunAt", 0) > time.time() * 1000,
              f"nextRunAt={entry.get('nextRunAt')}")
    check_status("re-saving bumps the version",
                 conn.save_schedule("listed", "counter", cron="0 4 * * *"), "OK")
    check("the version is now 2", conn.save_schedule("listed", "counter", cron="0 5 * * *").get("version") == 3,
          "a third save must report version 3")
    conn.delete_schedule("listed")


def test_validation(conn: Conn):
    section("Validation")
    check_code("a malformed cron is refused", conn.save_schedule("bad", "counter", cron="not a cron"),
               "ERROR", "400-16")
    check_code("both cron and intervalMs are refused",
               conn.save_schedule("bad", "counter", cron="0 3 * * *", intervalMs=1000), "ERROR", "400-16")
    check_code("neither cron nor intervalMs is refused", conn.save_schedule("bad", "counter"), "ERROR", "400-16")
    check_code("an unknown procedure is refused", conn.save_schedule("bad", "nosuchproc", intervalMs=1000),
               "NOT_FOUND", "404-8")
    check_code("an unknown database is refused",
               conn.save_schedule("bad", "counter", db="no_such_db", intervalMs=1000), "NOT_FOUND", "404-4")
    check_code("an invalid schedule name is refused", conn.save_schedule("x", "counter", intervalMs=1000),
               "ERROR", "400-1")
    check_status("deleting an absent schedule is idempotent", conn.delete_schedule("neverExisted"), "OK")

    section("Per-database cap")
    clear_schedules(conn)
    for i in range(MAX_PER_DATABASE):
        check_status(f"save schedule {i + 1} of the cap",
                     conn.save_schedule(f"capped{i}", "counter", cron="0 3 * * *"), "OK")
    check_code("one past the cap is refused", conn.save_schedule("capped99", "counter", cron="0 3 * * *"),
               "ERROR", "400-17")
    check_status("editing an existing schedule is still allowed",
                 conn.save_schedule("capped0", "counter", cron="0 4 * * *"), "OK")
    clear_schedules(conn)


def test_failure_logs_a_stack(conn: Conn, log_path: str):
    section("A failing scheduled run names where it failed")
    check_status("install a procedure that throws two frames deep",
                 conn.save_procedure("scheduled_explodes",
                                     "function inner() {\n"
                                     "  throw new Error('schedule blew up');\n"
                                     "}\n"
                                     "function outer() {\n"
                                     "  inner();\n"
                                     "}\n"
                                     "outer();"), "OK")
    before = schedule_failures(conn)
    check_status("schedule it every 500ms",
                 conn.save_schedule("exploder", "scheduled_explodes", intervalMs=500), "OK")
    deadline = time.time() + 20.0
    while schedule_failures(conn) <= before and time.time() < deadline:
        time.sleep(0.2)
    check("the failure is counted", schedule_failures(conn) > before, f"failures did not increase from {before}")

    # Nobody is waiting on a response, so the server log is where the trace has to appear
    log = read_log(log_path)
    line = next((entry for entry in log.splitlines()
                 if "SCHEDULE name=exploder" in entry and "schedule blew up" in entry), "")
    check("the scheduled run's failure line carries a stack", "stack=[" in line, f"line={line!r}")
    check("the stack names the throwing function", "inner (" in line, f"line={line!r}")

    check_status("delete the schedule", conn.delete_schedule("exploder"), "OK")
    check_status("delete the procedure", conn.delete_procedure("scheduled_explodes"), "OK")


def history_rows(conn: Conn, **filters) -> list:
    response = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": "script_runs",
                          "aggregationSteps": [{"type": "SORT", "fieldName": "startedAt", "ascending": False}]})
    rows = response.get("results") or []
    for field, value in filters.items():
        rows = [row for row in rows if row.get(field) == value]
    return rows


def await_history(conn: Conn, timeout: float = 20.0, **filters) -> list:
    deadline = time.time() + timeout
    rows = history_rows(conn, **filters)
    while not rows and time.time() < deadline:
        time.sleep(0.3)
        rows = history_rows(conn, **filters)
    return rows


def test_run_history(conn: Conn):
    section("Run history")
    check_status("install a procedure worth recording",
                 conn.save_procedure("historied_job",
                                     "let total = 0;\n"
                                     "for (let i = 0; i < 20; i++) total += i;\n"
                                     "return total;"), "OK")
    check_status("schedule it every 500ms",
                 conn.save_schedule("historied", "historied_job", intervalMs=500), "OK")

    rows = await_history(conn, kind="SCHEDULE", name="historied")
    check("a fired schedule is recorded", bool(rows), f"rows={rows!r}")
    if rows:
        row = rows[0]
        check("the row reports the outcome", row.get("outcome") == "ok", f"row={row!r}")
        check("the row names the procedure behind the schedule",
              row.get("procedure") == "historied_job", f"row={row!r}")
        check("the row carries metrics",
              (row.get("metrics") or {}).get("instructions", 0) > 0, f"row={row!r}")
        check("the row names the definer", row.get("username") == ADMIN_USERNAME, f"row={row!r}")

    check_status("delete the schedule", conn.delete_schedule("historied"), "OK")

    # A schedule that could not run at all is the outcome no other surface reports. Its procedure cannot
    # be deleted while a schedule references it, so it is disabled instead - the state the dispatcher
    # treats the same way.
    check_status("schedule the procedure again",
                 conn.save_schedule("orphaned", "historied_job", intervalMs=500), "OK")
    check_status("then disable the procedure",
                 conn.save_procedure("historied_job", "return 1;", enabled=False), "OK")

    skipped = await_history(conn, kind="SCHEDULE", name="orphaned", outcome="skipped")
    check("a schedule that never ran is recorded as skipped", bool(skipped), f"rows={skipped!r}")
    if skipped:
        check("with the reason", "missing or disabled" in (skipped[0].get("errorMessage") or ""),
              f"row={skipped[0]!r}")

    check_status("delete the orphaned schedule", conn.delete_schedule("orphaned"), "OK")
    check_status("delete its procedure", conn.delete_procedure("historied_job"), "OK")


def test_import_failure(conn: Conn):
    section("A scheduled procedure whose import cannot be resolved")
    # Installing the procedure passes the save-time import check, so a schedule ends up broken only
    # when the library is deleted from under it afterwards.
    check_status("install a library",
                 conn.save_procedure("lib_step", "export function step(n) { return n + 1; }"), "OK")
    check_status("install a procedure that imports it",
                 conn.save_procedure("counter_via_lib",
                                     "import db from 'db'; import args from 'args';"
                                     " import { step } from 'procedures/lib_step';"
                                     " const id = args.id;"
                                     " const existing = db.findById(db.name, '" + COLL + "', id);"
                                     " const n = existing ? step(existing.n) : 1;"
                                     " db.save(db.name, '" + COLL + "', { _id: id, n: n });"
                                     " return n;"), "OK")
    check_status("schedule it every 500ms",
                 conn.save_schedule("importer", "counter_via_lib", intervalMs=500,
                                    args={"id": "import-fail"}), "OK")
    fired = await_counter(conn, "import-fail", 1)
    check("it fires while the library is present", fired >= 1, f"counter never advanced (last {fired})")

    before_failed = schedule_failures(conn)
    check_status("delete the library it imports", conn.delete_procedure("lib_step"), "OK")
    settled = counter_value(conn, "import-fail")
    time.sleep(2.5)
    after = counter_value(conn, "import-fail")
    check("the schedule stops producing results once the import is unresolvable",
          after <= settled + 1, f"counter advanced from {settled} to {after} after the library was deleted")
    check("the failures are counted in the stats", schedule_failures(conn) > before_failed,
          f"failures did not increase from {before_failed}")

    check_status("delete the schedule", conn.delete_schedule("importer"), "OK")
    check_status("delete the broken procedure", conn.delete_procedure("counter_via_lib"), "OK")


def schedule_failures(conn: Conn) -> int:
    stats = conn.send({"type": "GET_DATABASE_STATS"}).get("stats", {}).get("schedules", {})
    return stats.get("failed", 0)


def test_referential_integrity(conn: Conn):
    section("Referential integrity")
    check_status("save a schedule referencing the procedure",
                 conn.save_schedule("holder", "counter", cron="0 3 * * *"), "OK")
    check_code("deleting the referenced procedure is refused", conn.delete_procedure("counter"), "ERROR", "400-16")
    check_status("delete the schedule", conn.delete_schedule("holder"), "OK")
    check_status("now the procedure may be deleted", conn.delete_procedure("counter"), "OK")
    check_status("restore the procedure", conn.save_procedure("counter", COUNTER_SOURCE), "OK")


def test_permissions():
    section("Permissions")
    with user_conn(MANAGER) as manager:
        check_status("a MANAGE user may install a schedule",
                     manager.save_schedule("byManager", "counter", cron="0 3 * * *"), "OK")
        check_status("a MANAGE user may list", manager.list_schedules(), "OK")
        check_status("a MANAGE user may delete", manager.delete_schedule("byManager"), "OK")
    with user_conn(READER) as reader:
        check_code("a read-only user may not install a schedule",
                   reader.save_schedule("byReader", "counter", cron="0 3 * * *"), "FORBIDDEN", "403-1")
        check_code("a read-only user may not delete a schedule",
                   reader.delete_schedule("byReader"), "FORBIDDEN", "403-1")
        check_status("a read-only user may list", reader.list_schedules(), "OK")
    with Conn() as anonymous:
        check_code("an unauthenticated client may not list", anonymous.list_schedules(),
                   "UNAUTHENTICATED", "401-1")


def test_storage_placement(conn: Conn, work_dir: str):
    section("Storage placement")
    check_status("save a schedule to place", conn.save_schedule("placed", "counter", cron="0 3 * * *"), "OK")
    path = os.path.join(work_dir, "db", DB, ".schedules", "placed.json")
    check("the schedule lives with its database", os.path.isfile(path), f"expected a file at {path}")
    if os.path.isfile(path):
        with open(path) as fp:
            stored = json.loads(fp.read())
        check("the stored record carries its definer", stored.get("definer") == ADMIN_USERNAME, f"got {stored}")
    admin_dir = os.path.join(work_dir, "db", "admin")
    check("nothing new appears under admin/", "schedules" not in os.listdir(admin_dir),
          f"admin/ contains {os.listdir(admin_dir)}")
    conn.delete_schedule("placed")


def test_stats(conn: Conn):
    section("Stats")
    response = conn.send({"type": "GET_DATABASE_STATS"})
    check_status("get stats", response, "OK")
    schedules = response.get("stats", {}).get("schedules", {})
    check("the stats report the schedule counters",
          schedules.get("enabled") is True and "fired" in schedules and "failed" in schedules
          and "skipped" in schedules and "dropped" in schedules and "queued" in schedules
          and "registered" in schedules, f"got {schedules}")
    check("something has fired by now", schedules.get("fired", 0) >= 1, f"got {schedules}")


def test_cascade_delete(conn: Conn, work_dir: str):
    section("Cascade delete")
    check_status("save a schedule that keeps firing",
                 conn.save_schedule("doomed", "counter", intervalMs=500, args={"id": "doomed"}), "OK")
    check("the doomed schedule fired", await_counter(conn, "doomed", 1) >= 1, "counter never advanced")
    check_status("drop the database", conn.send({"type": "DROP_DATABASE", "databaseName": DB}), "OK")
    check("the schedules folder is gone",
          not os.path.isdir(os.path.join(work_dir, "db", DB, ".schedules")),
          "the .schedules folder survived the drop")
    # Nothing must keep firing against a database that no longer exists.
    time.sleep(2.0)
    check_code("the dropped database no longer answers LIST_SCHEDULES", conn.list_schedules(),
               "NOT_FOUND", "404-4")


def test_switch_off(conn: Conn):
    section("schedulesEnabled=false")
    check_code("SAVE_SCHEDULE is refused", conn.save_schedule("off", "counter", cron="0 3 * * *"),
               "FORBIDDEN", "403-2")
    check_code("DELETE_SCHEDULE is refused", conn.delete_schedule("off"), "FORBIDDEN", "403-2")
    check_code("LIST_SCHEDULES is refused", conn.list_schedules(), "FORBIDDEN", "403-2")
    response = conn.send({"type": "GET_DATABASE_STATS"})
    check("the stats report the feature as disabled",
          response.get("stats", {}).get("schedules", {}).get("enabled") is False,
          f"got {response.get('stats', {}).get('schedules')}")


# ── main ─────────────────────────────────────────────────────────────────────

def main() -> int:
    global failures
    jar = os.path.join(REPO_ROOT, JAR)
    if not os.path.isfile(jar):
        print(f"jar not found at {jar}; run `mvn clean package -DskipTests` first", file=sys.stderr)
        return 1
    if port_open():
        print(f"port {PORT} is already in use", file=sys.stderr)
        return 1

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-schedule-test-")
    log_path = os.path.join(work_dir, "server.out")
    proc = None
    try:
        print(f"work dir: {work_dir}")
        write_config(work_dir, scripts_enabled=True, schedules_enabled=True)
        proc = start_server(work_dir, log_path)

        with admin_conn() as conn:
            setup_data(conn)
            test_interval_schedule(conn)
            test_cron_schedule(conn)
            test_listing(conn)
            test_validation(conn)
            test_import_failure(conn)
            test_failure_logs_a_stack(conn, log_path)
            test_run_history(conn)
            test_referential_integrity(conn)
        test_permissions()
        with admin_conn() as conn:
            test_storage_placement(conn, work_dir)
            test_stats(conn)
            test_cascade_delete(conn, work_dir)

        # Phase 2: same data directory, the master switch off
        stop_server(proc)
        proc = None
        write_config(work_dir, scripts_enabled=True, schedules_enabled=False)
        proc = start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_switch_off(conn)
    except Exception as exc:  # noqa: BLE001 - the suite reports rather than propagates
        print(f"\nunexpected failure: {exc}", file=sys.stderr)
        dump_log(log_path)
        failures += 1
    finally:
        stop_server(proc)

    print(f"\n{'═' * 70}")
    if failures:
        print(f"  {failures} check(s) failed")
        dump_log(log_path)
    else:
        print("  all checks passed")
    print(f"{'═' * 70}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
