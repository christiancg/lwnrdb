import os
import tempfile
import threading
import time
from collections.abc import Callable
from typing import Optional

import base_utils as bu
from base_utils import check, check_code, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("SCRIPT_CONTROL_TEST_PORT", "8998"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "script_control_db"
COLL = "docs"
OUT_COLL = "sideeffects"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)

TIMEOUT_MS = 600_000
SLOW_MS = 300_000
SLOW_SCRIPT = f"export default new Promise(r => setTimeout(() => r(1), {SLOW_MS}));"
BUSY_SCRIPT = "while (true) { }"

INSTRUCTION_BUDGET = 1_000_000_000_000
TICK_MS = 200


class Conn(bu.Conn):
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

def admin_conn(timeout=60) -> Conn:
    conn = Conn(timeout=timeout)
    r = conn.authenticate()
    if r.get("status") != "OK":
        conn.close()
        raise RuntimeError(f"admin auth failed: {r}")
    return conn


class BackgroundRun:

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


def await_one_run(conn: Conn, timeout_s: float = 30.0) -> Optional[dict]:
    def _one():
        response = conn.list_scripts()
        if response.get("status") != "OK":
            return None
        rows = response.get("scripts") or []
        return rows[0] if len(rows) == 1 else None
    return bu.wait_until(_one, timeout_s, 0.1) or None


def await_empty_listing(conn: Conn, timeout_s: float = 60.0) -> bool:
    return bool(bu.wait_until(lambda: not (conn.list_scripts().get("scripts") or []), timeout_s, 0.1))


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


def write_config(work_dir: str):
    bu.write_config(work_dir, PORT, maxMemory="256mb", scriptsEnabled=True,
                    scriptInstructionBudget=INSTRUCTION_BUDGET, scriptTimeoutMs=TIMEOUT_MS,
                    scriptMaxDepth=64, scriptMaxSourceBytes=8192, scriptMaxLogLines=50,
                    scriptMaxLogLineChars=500, scriptMaxMemoryBytes=16777216,
                    scriptMaxResultBytes=65536, scriptTextImportEnabled=False, scriptTimeZone="UTC",
                    scriptLocale="en-US", procedureCacheSize=16, triggersEnabled=True, triggerThreads=2,
                    triggerQueueSize=1000, triggerMaxDepth=3, triggerTimeoutMs=TIMEOUT_MS,
                    triggerRunLogEnabled=True, schedulesEnabled=True, scheduleTickMs=TICK_MS,
                    scheduleRefreshMs=2000, scheduleTimeoutMs=TIMEOUT_MS, maxConcurrentScripts=8)


def setup_data(conn: Conn):
    section("Setup")
    check_status("CREATE_DATABASE", conn.send({"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
    for coll in (COLL, OUT_COLL):
        check_status(f"CREATE_COLLECTION {coll}",
                     conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    check_status("SAVE_PROCEDURE for a slow procedure", conn.save_procedure("slow", SLOW_SCRIPT), "OK")


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
    section("CANCEL_SCRIPT — a trigger run")
    check_status("SAVE_TRIGGER on the slow procedure",
                 conn.send({"type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": COLL,
                            "name": "slowTrigger", "events": ["CREATED", "UPDATED"],
                            "procedureName": "slow"}), "OK")
    check_status("SAVE fires the trigger",
                 conn.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                            "object": {"_id": "fires-trigger", "v": 1}}), "OK")

    row = await_one_run(conn)
    check_row_shape("the trigger run is listed with its trigger name", row, "TRIGGER", "slowTrigger")
    check("CANCEL_SCRIPT reports the trigger run as cancelled",
          conn.cancel(row["runId"]).get("cancelled") is True if row else False)
    check("the trigger run leaves the listing", await_empty_listing(conn))
    consumed = bu.wait_until(lambda: conn.trigger_stats().get("pendingRuns") == 0, 30.0, 0.1)
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
    check("a cancelled run carries no stack", not response.get("stack"),
          f"stack={response.get('stack')!r}")


def test_a_parked_run_is_cancelled_promptly(conn: Conn):
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

    rows = bu.wait_until(_two, 30.0, 0.1)
    check("both runs are listed", bool(rows), f"got {conn.list_scripts().get('scripts')!r}")
    check("running counts both", conn.stats().get("running") == 2,
          f"running={conn.stats().get('running')!r}")
    if rows:
        check("each run has its own id", rows[0]["runId"] != rows[1]["runId"])
        check("the two kinds are distinguished",
              {r["kind"] for r in rows} == {"RUN_SCRIPT", "CALL_PROCEDURE"},
              f"kinds={[r['kind'] for r in rows]!r}")
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
    found = bu.wait_until(lambda: f"runId={run_id}" in bu.read_log(log_path), 15.0, 0.1)
    check("the run's log line names it", bool(found), f"runId={run_id!r} not found in the log")


def test_cancelled_trigger_run_is_not_replayed(conn: Conn):
    section("Phase 2 — a cancelled trigger run is not replayed after a restart")
    check("nothing is running after the restart", (conn.list_scripts().get("scripts") or []) == [],
          f"got {conn.list_scripts().get('scripts')!r}")
    stats = conn.stats()
    check("the cancellation counter starts fresh on the new process", stats.get("cancelled") == 0,
          f"cancelled={stats.get('cancelled')!r}")
    check("no pending trigger run survived the restart",
          conn.trigger_stats().get("pendingRuns") == 0, f"triggers={conn.trigger_stats()!r}")
    still_quiet = bu.wait_until(lambda: (conn.list_scripts().get("scripts") or []) != [], 5.0, 0.1)
    check("startup recovery did not re-queue the cancelled trigger run", not still_quiet,
          f"a run appeared after the restart: {conn.list_scripts().get('scripts')!r}")


def cleanup(conn: Conn):
    section("Cleanup")
    check_status("DROP_DATABASE", conn.send({"type": "DROP_DATABASE", "databaseName": DB}), "OK")
    check_status("DELETE_USER", conn.send({"type": "DELETE_USER", "username": "script_owner"}), "OK")


def main():
    bu.banner("script run visibility and cancellation e2e tests")

    bu.require_jar()

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-scriptcontrol-")
    log_path = os.path.join(work_dir, "server.log")
    print(f"  Working dir: {work_dir}")

    proc = None
    try:
        write_config(work_dir)
        print(f"  Starting server on {HOST}:{PORT} ...")
        proc = bu.start_server(work_dir, log_path)

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

        bu.stop_server(proc)
        proc = None

        print(f"\n  Restarting server on {HOST}:{PORT} (trigger-recovery phase) ...")
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_cancelled_trigger_run_is_not_replayed(conn)
            cleanup(conn)
    finally:
        bu.stop_server(proc)

    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
