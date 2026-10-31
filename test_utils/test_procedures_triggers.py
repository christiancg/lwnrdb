import os
import sys
import tempfile
import threading
import time

import base_utils as bu
from base_utils import check, check_code, check_result, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("PROC_TEST_PORT", "8996"))
HISTORY_CAP = 50
CLUSTER_PORT = int(os.environ.get("PROC_TEST_CLUSTER_PORT", "9996"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

DB = "proc_test_db"
OTHER_DB = "proc_other_db"
COLL = "orders"
AUDIT = "audit"
UNREADABLE_COLL = "broken_triggers"
CORRUPT_COLL = "corrupt_triggers"
RECOVERED_COLL = "tx_recovered"
FENCED_COLL = "tx_fenced"
RETRY_COLL = "retry_shutdown"
BATCH_DELETE_COLL = "batch_deletes"
TWIN_FAILING_COLL = "twin_failing"
TWIN_AUDITED_COLL = "twin_audited"
CAP_COLL = "trigger_cap"
REVOKE_COLL = "revoke_source"
DROPPED_RUNS_COLL = "dropped_runs"
FIRST_RUN_COLL = "first_run"
FIRST_RUN_BLOCK_COLL = "first_run_block"
GROUPED_COLL = "grouped_hooked"
GROUPED_AUDIT = "grouped_audit"
GROUPED_WRITERS = 16
GROUPED_SAVES_EACH = 10
BULK_TRIGGERED_COLL = "bulk_triggered"
BULK_AUDIT = "bulk_audit"
BULK_TRIGGERED_DOCS = 500
TX_TRIGGERED_COLL = "tx_triggered"
TX_AUDIT = "tx_audit"
TX_TRIGGERED_OPS = 200
BUSY_TRIGGERED_COLL = "busy_triggered"
BUSY_AUDIT = "busy_audit"
BUSY_TRIGGERED_DOCS = 300
BUSY_CALLERS = 3

RUNNER = "proc_runner"
MANAGER = "proc_manager"
MANAGER_TWO = "proc_manager_two"
PROC_OWNER = "proc_owner"
WRITER = "proc_writer"
LEGACY = "proc_legacy"
REVOKED_DEFINER = "proc_revoked_definer"
USER_PASSWORD = "password123"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)

INSTRUCTION_BUDGET = 2_000_000
TIMEOUT_MS = 5_000
MAX_DEPTH = 64
MAX_SOURCE_BYTES = 8 * 1024
MAX_MEMORY_BYTES = 4 * 1024 * 1024
MAX_RESULT_BYTES = 64 * 1024
TRIGGER_MAX_DEPTH = 2
TRIGGER_TIMEOUT_MS = 4_000
BEFORE_HOOK_BUDGET = 200_000
BEFORE_HOOK_TIMEOUT_MS = 500

SHUTDOWN_BUDGET_MS = 15_000
TRIGGER_MAX_PER_COLLECTION = 12
LONG_RETRY_BACKOFF_MS = 60_000
SHUTDOWN_CEILING_SECONDS = 7.0


class Conn(bu.Conn):
    def save_procedure(self, name, script, db=DB, **extra) -> dict:
        payload = {"type": "SAVE_PROCEDURE", "databaseName": db, "name": name, "script": script}
        payload.update(extra)
        return self.send(payload)

    def call(self, name, args=None, db=DB) -> dict:
        payload = {"type": "CALL_PROCEDURE", "databaseName": db, "procedureName": name}
        if args is not None:
            payload["args"] = args
        return self.send(payload)

    def save_trigger(self, name, events, procedure, coll=COLL, db=DB, **extra) -> dict:
        payload = {"type": "SAVE_TRIGGER", "databaseName": db, "collectionName": coll,
                   "name": name, "events": events, "procedureName": procedure}
        payload.update(extra)
        return self.send(payload)

    def test_trigger(self, name, event, document, coll=COLL, db=DB) -> dict:
        return self.send({"type": "TEST_TRIGGER", "databaseName": db, "collectionName": coll,
                          "name": name, "event": event, "document": document})

    def save_doc(self, doc, coll=COLL, db=DB) -> dict:
        return self.send({"type": "SAVE", "databaseName": db, "collectionName": coll, "object": doc})

    def find(self, doc_id, coll=COLL, db=DB) -> dict:
        return self.send({"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": doc_id})


admin_conn = Conn.authenticated


def user_conn(username: str) -> Conn:
    return Conn.authenticated(username, USER_PASSWORD)


def await_latest(fetch, settled, timeout_s: float, interval_s: float):
    latest = [fetch()]

    def _refetched():
        latest[0] = fetch()
        return settled(latest[0])

    if not settled(latest[0]):
        bu.wait_until(_refetched, timeout_s, interval_s)
    return latest[0]


def await_doc(conn: Conn, doc_id: str, coll=AUDIT, timeout=15.0):
    return await_latest(lambda: conn.find(doc_id, coll=coll), lambda r: r.get("status") == "OK", timeout, 0.1)


def await_absent(conn: Conn, doc_id: str, coll=AUDIT, settle=2.0):
    time.sleep(settle)
    return conn.find(doc_id, coll=coll)


def write_config(work_dir: str, scripts_enabled: bool, triggers_enabled: bool,
                 history_enabled: bool = True, history_kinds: str = "CALL_PROCEDURE,TRIGGER,SCHEDULE",
                 history_retention_ms: int = 604800000, max_attempts: int = 1, retry_backoff_ms: int = 0,
                 retry_max_backoff_ms: int = 2000, shutdown_timeout_ms: int = SHUTDOWN_BUDGET_MS,
                 clustered: bool = False, **extra):
    cluster = {"clusterEnabled": True, "clusterPort": CLUSTER_PORT, "clusterBindAddress": "127.0.0.1",
               "clusterAdvertisedAddress": "127.0.0.1", "clusterSeeds": "", "clusterExpectedSize": 2,
               "clusterSecret": "proc-test-cluster-secret-of-32-plus-chars"} if clustered else {}
    bu.write_config(work_dir, PORT,
                    scriptsEnabled=scripts_enabled,
                    scriptInstructionBudget=INSTRUCTION_BUDGET,
                    scriptTimeoutMs=TIMEOUT_MS,
                    scriptMaxDepth=MAX_DEPTH,
                    scriptMaxSourceBytes=MAX_SOURCE_BYTES,
                    scriptMaxLogLines=50,
                    scriptMaxLogLineChars=500,
                    scriptMaxMemoryBytes=MAX_MEMORY_BYTES,
                    scriptMaxResultBytes=MAX_RESULT_BYTES,
                    scriptTextImportEnabled=False,
                    scriptTimeZone="UTC",
                    scriptLocale="en-US",
                    procedureCacheSize=16,
                    triggersEnabled=triggers_enabled,
                    triggerThreads=2,
                    triggerQueueSize=1000,
                    triggerMaxPerCollection=TRIGGER_MAX_PER_COLLECTION,
                    triggerMaxDepth=TRIGGER_MAX_DEPTH,
                    triggerTimeoutMs=TRIGGER_TIMEOUT_MS,
                    beforeHookInstructionBudget=BEFORE_HOOK_BUDGET,
                    beforeHookTimeoutMs=BEFORE_HOOK_TIMEOUT_MS,
                    scriptRunHistoryEnabled=history_enabled,
                    scriptRunHistoryKinds=history_kinds,
                    scriptRunHistoryRetentionMs=history_retention_ms,
                    scriptRunHistoryIncludeLogs=False,
                    scriptRunHistoryMaxErrorChars=2000,
                    triggerMaxAttempts=max_attempts,
                    triggerRetryBackoffMs=retry_backoff_ms,
                    triggerRetryMaxBackoffMs=retry_max_backoff_ms,
                    triggerDeadLetterRetentionMs=604800000,
                    shutdownTimeoutMs=shutdown_timeout_ms,
                    **cluster, **extra)


def setup_data(conn: Conn):
    section("Setup")
    for db in (DB, OTHER_DB):
        conn.send({"type": "DROP_DATABASE", "databaseName": db})
        check_status(f"CREATE_DATABASE {db}", conn.send({"type": "CREATE_DATABASE", "databaseName": db}), "OK")
    for coll in (COLL, AUDIT):
        check_status(f"create collection {coll}",
                     conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    check_status("create collection in the other database",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": OTHER_DB, "collectionName": COLL}), "OK")

    check_status("create the RUN-level user", conn.send({
        "type": "CREATE_USER", "username": RUNNER, "password": USER_PASSWORD, "admin": False,
        "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "RUN"}}), "OK")
    check_status("create the MANAGE-level user", conn.send({
        "type": "CREATE_USER", "username": MANAGER, "password": USER_PASSWORD, "admin": False,
        "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "MANAGE"}}), "OK")
    check_status("create the writer with no audit access", conn.send({
        "type": "CREATE_USER", "username": WRITER, "password": USER_PASSWORD, "admin": False,
        "collectionPermissions": {f"{DB}|{COLL}": "READ_WRITE"}}), "OK")
    check_status("create a user with a legacy boolean grant", conn.send({
        "type": "CREATE_USER", "username": LEGACY, "password": USER_PASSWORD, "admin": False,
        "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: True}}), "OK")
    for username in (MANAGER_TWO, PROC_OWNER):
        check_status(f"create the MANAGE-level user {username}", conn.send({
            "type": "CREATE_USER", "username": username, "password": USER_PASSWORD, "admin": False,
            "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "MANAGE"}}), "OK")
    check_status("make the owner user a database owner", conn.send({
        "type": "SET_DATABASE_OWNERS", "databaseName": DB, "owners": [ADMIN_USERNAME, PROC_OWNER]}), "OK")


def test_procedure_lifecycle(conn: Conn):
    section("Procedure lifecycle")
    check_status("save a procedure", conn.save_procedure("answer", "return 42;"), "OK")
    saved = conn.save_procedure("versioned", "return 1;")
    check("first save is version 1", saved.get("version") == 1, f"got {saved.get('version')}")
    check_result("call it", conn.call("answer"), 42)

    listed = conn.send({"type": "LIST_PROCEDURES", "databaseName": DB})
    names = sorted(p.get("name") for p in listed.get("procedures", []))
    check("list reports both procedures", names == ["answer", "versioned"], f"got {names}")
    check("list omits the source by default",
          all("source" not in p for p in listed.get("procedures", [])),
          f"got {listed.get('procedures')}")

    with_source = conn.send({"type": "LIST_PROCEDURES", "databaseName": DB, "includeSource": True})
    sources = {p.get("name"): p.get("source") for p in with_source.get("procedures", [])}
    check("includeSource returns the body", sources.get("answer") == "return 42;", f"got {sources}")

    bumped = conn.save_procedure("versioned", "return 2;")
    check("re-save bumps the version", bumped.get("version") == 2, f"got {bumped.get('version')}")
    check_result("the new body runs", conn.call("versioned"), 2)

    check_status("args reach the procedure",
                 conn.save_procedure("greet", "import args from 'args'; return 'hi ' + args.name;"), "OK")
    check_result("call with args", conn.call("greet", {"name": "ada"}), "hi ada")

    check_status("a procedure may write through db", conn.save_procedure(
        "writer", "import db from 'db'; import args from 'args';"
                  "db.save(db.name, '" + COLL + "', { _id: args.id, via: 'procedure' }); return 'saved';"), "OK")
    check_result("call the writing procedure", conn.call("writer", {"id": "written-by-proc"}), "saved")
    found = conn.find("written-by-proc")
    check("the document is there", found.get("status") == "OK", f"got {found}")

    check_status("delete a procedure",
                 conn.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "answer"}), "OK")
    check_code("calling a deleted procedure is not found", conn.call("answer"), "NOT_FOUND", "404-8")
    check_status("deleting again is idempotent",
                 conn.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "answer"}), "OK")

    check_status("a disabled procedure saves",
                 conn.save_procedure("switched-off", "return 1;", enabled=False), "OK")
    check_code("but is not callable", conn.call("switched-off"), "NOT_FOUND", "404-8")

    first = conn.save_procedure("guarded", "return 1;")
    check_code("a stale ifVersion conflicts",
               conn.save_procedure("guarded", "return 2;", ifVersion=99), "ERROR", "409-8")
    check_status("a matching ifVersion succeeds",
                 conn.save_procedure("guarded", "return 2;", ifVersion=first.get("version")), "OK")


def test_procedure_name_case_collision(conn: Conn):
    section("Procedure name case collisions")
    check_status("save MyProc", conn.save_procedure("MyProc", "return 1;"), "OK")
    check_code("saving myproc beside it -> 409-11", conn.save_procedure("myproc", "return 2;"), "ERROR", "409-11")
    resaved = conn.save_procedure("MyProc", "return 3;")
    check_status("re-saving MyProc still updates it", resaved, "OK")
    check("re-saving MyProc bumped its version", (resaved.get("version") or 0) > 1,
          detail=str(resaved)[:160])
    check_status("a trigger references MyProc", conn.save_trigger("case_guard", ["CREATED"], "MyProc"), "OK")
    conn.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "myproc"})
    listed = conn.send({"type": "LIST_PROCEDURES", "databaseName": DB})
    names = [p.get("name") for p in listed.get("procedures", [])]
    check("deleting myproc leaves the referenced MyProc in place", "MyProc" in names, f"got {names}")
    check_result("MyProc is still callable", conn.call("MyProc"), 3)
    check_code("myproc is not a name for MyProc", conn.call("myproc"), "NOT_FOUND", "404-8")
    refused = conn.save_trigger("case_alias", ["CREATED"], "myproc")
    check("a trigger cannot name myproc", refused.get("status") != "OK", detail=str(refused)[:160])
    conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL, "name": "case_guard"})
    conn.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "MyProc"})


def test_procedure_validation(conn: Conn):
    section("Procedure validation")
    check_code("unknown database", conn.save_procedure("valid_name", "return 1;", db="no_such_db"), "NOT_FOUND", "404-4")
    check_code("reserved database", conn.save_procedure("valid_name", "return 1;", db="admin"), "ERROR", "400-1")
    check_code("a name that is too short", conn.save_procedure("ab", "return 1;"), "ERROR", "400-1")
    check_code("a name with a path separator", conn.save_procedure("a/b", "return 1;"), "ERROR", "400-1")
    check_code("a name with a dot", conn.save_procedure("a.b", "return 1;"), "ERROR", "400-1")
    check_code("a blank script", conn.save_procedure("blank", "   "), "ERROR", "400-1")
    check_code("oversized source",
               conn.save_procedure("huge", "// " + ("x" * (MAX_SOURCE_BYTES + 10))), "ERROR", "400-10")

    broken = conn.save_procedure("broken", "return (;")
    check_code("unparseable source is refused at save time", broken, "ERROR", "400-13")
    check("the parse error names a position", "line" in (broken.get("message") or ""),
          f"got {broken.get('message')!r}")
    check_code("and it was not stored", conn.call("broken"), "NOT_FOUND", "404-8")


def test_permissions():
    section("Permissions and delegation")
    with user_conn(RUNNER) as runner:
        check_result("a RUN user may call", runner.call("greet", {"name": "run"}), "hi run")
        check_code("a RUN user may not install",
                   runner.save_procedure("sneaky", "return 1;"), "FORBIDDEN", "403-1")
        check_code("a RUN user may not install a trigger",
                   runner.save_trigger("sneaky", ["CREATED"], "greet"), "FORBIDDEN", "403-1")

    with user_conn(MANAGER) as manager:
        check_status("a MANAGE user may install", manager.save_procedure("by_manager", "return 'ok';"), "OK")
        check_result("and call", manager.call("by_manager"), "ok")
        check_status("a MANAGE user may delete what it installed",
                     manager.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "by_manager"}), "OK")
        check_code("MANAGE on one database confers nothing on another",
                   manager.save_procedure("elsewhere", "return 1;", db=OTHER_DB), "FORBIDDEN", "403-1")

    with user_conn(LEGACY) as legacy:
        check_result("a legacy boolean grant still calls", legacy.call("greet", {"name": "legacy"}), "hi legacy")
        check_code("but does not confer install rights",
                   legacy.save_procedure("sneaky", "return 1;"), "FORBIDDEN", "403-1")

    with user_conn(WRITER) as writer:
        check_code("a user with no script grant may not call", writer.call("greet"), "FORBIDDEN", "403-1")

    with Conn() as anon:
        check_code("unauthenticated call", anon.call("greet"), "UNAUTHENTICATED", "401-1")
        check_code("unauthenticated save", anon.save_procedure("valid_name", "return 1;"), "UNAUTHENTICATED", "401-1")

    with admin_conn() as admin:
        check_status("promote the RUN user to MANAGE", admin.send({
            "type": "CHANGE_PERMISSIONS", "username": RUNNER, "admin": False,
            "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "MANAGE"}}), "OK")
    with user_conn(RUNNER) as promoted:
        check_status("the promoted user may now install",
                     promoted.save_procedure("after_promotion", "return 1;"), "OK")
    with admin_conn() as admin:
        check_status("demote back to RUN", admin.send({
            "type": "CHANGE_PERMISSIONS", "username": RUNNER, "admin": False,
            "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "RUN"}}), "OK")
    with user_conn(RUNNER) as demoted:
        check_code("and may not install again",
                   demoted.save_procedure("after_demotion", "return 1;"), "FORBIDDEN", "403-1")
    check_code("an invalid level is rejected loudly", admin_conn().send({
        "type": "CHANGE_PERMISSIONS", "username": RUNNER, "admin": False,
        "scriptPermissions": {DB: "MANAGER"}}), "ERROR", "400-1")


def test_storage_placement(work_dir: str):
    section("Storage placement")
    db_root = os.path.join(work_dir, "db")
    procedures = os.path.join(db_root, DB, ".procedures")
    check("procedures live in a folder beside the collections", os.path.isdir(procedures), procedures)
    check("one file per procedure", os.path.isfile(os.path.join(procedures, "greet.json")),
          str(os.listdir(procedures)) if os.path.isdir(procedures) else "no folder")
    triggers = os.path.join(db_root, DB, COLL, f"{COLL}-triggers.json")
    check("triggers live in the collection folder beside its schema", os.path.isfile(triggers), triggers)
    admin_children = sorted(os.listdir(os.path.join(db_root, "admin")))
    check("nothing new under admin/",
          "procedures" not in admin_children and "triggers" not in admin_children,
          f"admin/ contains {admin_children}")


GUARDED_SOURCE = (
    "import db from 'db'; import args from 'args';"
    "db.save(db.name, '" + AUDIT + "', { _id: 'guarded-' + args.id }); return 'guarded';"
)

AUDIT_PROCEDURE = (
    "import db from 'db'; import args from 'args';"
    "db.save(db.name, '" + AUDIT + "', { _id: args.event + '-' + args.id,"
    " event: args.event, by: args.actingUser, definer: args.definer, depth: args.depth });"
    "return 'audited';"
)


def test_triggers(conn: Conn):
    section("Triggers")
    check_status("install the audit procedure", conn.save_procedure("auditor", AUDIT_PROCEDURE), "OK")
    installed = conn.save_trigger("audit_writes", ["CREATED", "UPDATED", "DELETED"], "auditor")
    check_status("install the trigger", installed, "OK")
    check("the definer is recorded", installed.get("definer") == ADMIN_USERNAME, f"got {installed}")

    check_status("write a document", conn.save_doc({"_id": "t1", "n": 1}), "OK")
    row = await_doc(conn, "CREATED-t1")
    check("a first write with an explicit _id audits as CREATED", row.get("status") == "OK", f"got {row}")
    if row.get("status") == "OK":
        check("the row names the writer", row["object"].get("by") == ADMIN_USERNAME, f"got {row['object']}")
        check("and the definer", row["object"].get("definer") == ADMIN_USERNAME, f"got {row['object']}")

    check_status("overwrite the same document", conn.save_doc({"_id": "t1", "n": 2}), "OK")
    updated = await_doc(conn, "UPDATED-t1")
    check("overwriting the same _id audits as UPDATED", updated.get("status") == "OK", f"got {updated}")

    check_status("delete a document",
                 conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "t1"}), "OK")
    deleted_row = await_doc(conn, "DELETED-t1")
    check("a DELETED trigger fires too", deleted_row.get("status") == "OK", f"got {deleted_row}")

    listed = conn.send({"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": COLL})
    check("list reports the trigger",
          any(t.get("name") == "audit_writes" for t in listed.get("triggers", [])), f"got {listed}")

    check_status("narrow the trigger to CREATED only",
                 conn.save_trigger("audit_writes", ["CREATED"], "auditor"), "OK")
    check_status("write again", conn.save_doc({"_id": "t2", "n": 2}), "OK")
    absent = await_absent(conn, "UPDATED-t2")
    check("an UPDATED write fires nothing now", absent.get("status") != "OK", f"got {absent}")

    check_status("disable the trigger",
                 conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "auditor", enabled=False), "OK")
    check_status("write while disabled", conn.save_doc({"_id": "t3", "n": 3}), "OK")
    disabled = await_absent(conn, "UPDATED-t3")
    check("a disabled trigger fires nothing", disabled.get("status") != "OK", f"got {disabled}")
    check_status("re-enable it", conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "auditor"), "OK")


def test_a_transaction_reports_only_its_net_effect(conn: Conn):
    section("A transaction that creates and deletes the same id")
    check_status("install a trigger watching creates and deletes",
                 conn.save_trigger("audit_tx_net", ["CREATED", "DELETED"], "auditor"), "OK")

    check_status("start a transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_status("create a new document in it", conn.save_doc({"_id": "txnet1", "n": 1}), "OK")
    check_status("delete it in the same transaction",
                 conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "txnet1"}), "OK")
    check_status("commit", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    check("no DELETED fires for a document nobody outside the transaction saw",
          await_absent(conn, "DELETED-txnet1").get("status") != "OK")
    check("and no CREATED either", conn.find("CREATED-txnet1", coll=AUDIT).get("status") != "OK")

    check_status("write a document outside any transaction", conn.save_doc({"_id": "txnet2", "n": 1}), "OK")
    check("its creation is audited", await_doc(conn, "CREATED-txnet2").get("status") == "OK")
    check_status("start another transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_status("update the existing document", conn.save_doc({"_id": "txnet2", "n": 2}), "OK")
    check_status("then delete it",
                 conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "txnet2"}), "OK")
    check_status("commit", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    check("deleting a document that existed before the transaction still fires DELETED",
          await_doc(conn, "DELETED-txnet2").get("status") == "OK")

    conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL, "name": "audit_tx_net"})


def install_the_trigger_whose_file_will_break(conn: Conn):
    section("The collection whose trigger file phase 2 makes unreadable")
    check_status("create the collection", conn.send(
        {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": UNREADABLE_COLL}), "OK")
    check_status("install a trigger on it",
                 conn.save_trigger("audit_unreadable", ["CREATED", "UPDATED"], "auditor", coll=UNREADABLE_COLL), "OK")
    check_status("write a document", conn.save_doc({"_id": "ur-seed", "n": 1}, coll=UNREADABLE_COLL), "OK")
    check("the trigger really fires while its file is intact",
          await_doc(conn, "CREATED-ur-seed").get("status") == "OK",
          "the fixture never fired, so what phase 2 asserts about it would prove nothing")

    check_status("create the collection whose trigger file phase 2 corrupts", conn.send(
        {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": CORRUPT_COLL}), "OK")
    check_status("install a trigger on it",
                 conn.save_trigger("audit_corrupt", ["CREATED", "UPDATED"], "auditor", coll=CORRUPT_COLL), "OK")
    check_status("write a document", conn.save_doc({"_id": "co-seed", "n": 1}, coll=CORRUPT_COLL), "OK")
    check("the trigger really fires while its file is intact",
          await_doc(conn, "CREATED-co-seed").get("status") == "OK",
          "the fixture never fired, so what phase 2 asserts about it would prove nothing")


BIG_RESULT_PROCEDURE = (
    "import db from 'db'; import args from 'args';"
    "db.save(db.name, '" + AUDIT + "', { _id: 'big-' + (args.id ?? 'called'),"
    " event: args.event ?? 'CALL', by: args.actingUser ?? 'caller' });"
    "return new Array(5000).fill('0123456789');"
)


def test_result_cap(conn: Conn):
    section("Result cap (shared by RUN_SCRIPT and CALL_PROCEDURE)")
    check_status("install a procedure returning an oversized value",
                 conn.save_procedure("bigresult", BIG_RESULT_PROCEDURE), "OK")
    check_code("calling it reports the shared 400-15", conn.call("bigresult", {"id": "call"}), "ERROR", "400-15")
    check("the write it made still committed", conn.find("big-call", coll=AUDIT).get("status") == "OK")

    check_status("point a trigger at the same procedure",
                 conn.save_trigger("big_result_trigger", ["CREATED", "UPDATED"], "bigresult"), "OK")
    check_status("write a document", conn.save_doc({"_id": "cap1", "n": 1}), "OK")
    row = await_doc(conn, "big-cap1")
    check("the trigger completed despite its oversized result", row.get("status") == "OK", f"got {row}")
    check_status("remove the trigger again",
                 conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL,
                            "name": "big_result_trigger"}), "OK")


def test_trigger_validation(conn: Conn):
    section("Trigger validation")
    check_code("unknown collection",
               conn.save_trigger("valid_name", ["CREATED"], "auditor", coll="no_such_coll"), "NOT_FOUND", "404-4")
    check_code("unknown event", conn.save_trigger("valid_name", ["EXPLODED"], "auditor"), "ERROR", "400-14")
    check_code("no events", conn.save_trigger("valid_name", [], "auditor"), "ERROR", "400-14")
    check_code("unknown mode",
               conn.save_trigger("valid_name", ["CREATED"], "auditor", mode="sometimes"), "ERROR", "400-14")
    check_code("missing procedure", conn.save_trigger("valid_name", ["CREATED"], "no_such_procedure"),
               "NOT_FOUND", "404-8")
    check_code("a procedure a trigger still references cannot be deleted",
               conn.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "auditor"}), "ERROR", "400-14")


def test_definer_rights():
    section("Definer rights")
    with user_conn(WRITER) as writer:
        check_code("the writer indeed cannot write the audit collection directly",
                   writer.save_doc({"_id": "direct", "x": 1}, coll=AUDIT), "FORBIDDEN", "403-1")
        check_status("but may write the source collection", writer.save_doc({"_id": "by-writer", "n": 1}), "OK")
    with admin_conn() as conn:
        row = await_doc(conn, "CREATED-by-writer")
        check("the audit row still appears", row.get("status") == "OK",
              f"definer rights must let the trigger write where the writer cannot: {row}")
        if row.get("status") == "OK":
            check("it records the writer as the actor", row["object"].get("by") == WRITER, f"got {row['object']}")
            check("and the installer as the definer",
                  row["object"].get("definer") == ADMIN_USERNAME, f"got {row['object']}")


def stored_procedure(conn: Conn, name: str) -> dict:
    listed = conn.send({"type": "LIST_PROCEDURES", "databaseName": DB, "includeSource": True})
    return next((p for p in listed.get("procedures") or [] if p.get("name") == name), {})


def check_bound(label: str, response: dict):
    check_code(label, response, "FORBIDDEN", "403-4")


def check_the_direct_binding_is_protected(manager: Conn, intruder: Conn):
    check_status("MANAGER installs the guarded procedure", manager.save_procedure("guarded", GUARDED_SOURCE), "OK")
    check_status("and binds a trigger to it",
                 manager.save_trigger("guard_trigger", ["CREATED"], "guarded"), "OK")
    before = stored_procedure(manager, "guarded")
    check_bound("another MANAGE user may not overwrite it", intruder.save_procedure("guarded", "return 'pwned';"))
    check_bound("nor disable it", intruder.save_procedure("guarded", GUARDED_SOURCE, enabled=False))
    check_bound("nor delete it",
                intruder.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "guarded"}))
    after = stored_procedure(manager, "guarded")
    check("the source and version are unchanged",
          (after.get("source"), after.get("version"), after.get("enabled"))
          == (before.get("source"), before.get("version"), True), f"before {before} after {after}")
    check_status("a write still fires the original procedure", manager.save_doc({"_id": "guarded-doc"}), "OK")
    row = await_doc(manager, "guarded-guarded-doc")
    check("the bound trigger ran the original source", row.get("status") == "OK", f"got {row}")


def check_the_privileged_may_overwrite(manager: Conn):
    with user_conn(PROC_OWNER) as owner, admin_conn() as admin:
        check_status("the definer may overwrite it", manager.save_procedure("guarded", GUARDED_SOURCE), "OK")
        check_status("a database owner may", owner.save_procedure("guarded", GUARDED_SOURCE), "OK")
        check_status("an admin may", admin.save_procedure("guarded", GUARDED_SOURCE), "OK")
    check_status("drop the guard trigger", manager.send(
        {"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL, "name": "guard_trigger"}), "OK")


def check_schedule_and_hook_bindings(manager: Conn, intruder: Conn):
    check_status("MANAGER installs a scheduled procedure", manager.save_procedure("sched_guarded", "return 1;"), "OK")
    check_status("and schedules it hourly", manager.send(
        {"type": "SAVE_SCHEDULE", "databaseName": DB, "name": "guard_schedule", "procedureName": "sched_guarded",
         "intervalMs": 3_600_000}), "OK")
    check_bound("a schedule binding protects its procedure", intruder.save_procedure("sched_guarded", "return 2;"))
    check_status("drop the schedule", manager.send(
        {"type": "DELETE_SCHEDULE", "databaseName": DB, "name": "guard_schedule"}), "OK")

    check_status("MANAGER installs a before hook",
                 install_hook(manager, "guard_hook", "guarded_hook", "return null;", ["CREATED"]), "OK")
    check_bound("a before hook binding protects its procedure", intruder.save_procedure("guarded_hook", "return 1;"))
    drop_hook(manager, "guard_hook")


def check_import_bindings(manager: Conn, intruder: Conn):
    check_status("MANAGER installs a library", manager.save_procedure("guard_lib", "export const v = 'lib';"), "OK")
    check_status("and an entry importing it", manager.save_procedure(
        "guard_entry", "import { v } from 'procedures/guard_lib'; return v;"), "OK")
    check_status("binds a trigger to the entry", manager.save_trigger("guard_import", ["CREATED"], "guard_entry"), "OK")
    check_bound("the imported library is protected too", intruder.save_procedure("guard_lib", "export const v = 1;"))
    check_status("drop the import trigger", manager.send(
        {"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL, "name": "guard_import"}), "OK")

    check_status("MANAGER installs a procedure importing by a computed name", manager.save_procedure(
        "guard_dyn", "const n = 'x'; try { await import('procedures/' + n); } catch (e) {} return 1;"), "OK")
    check_status("and binds a trigger to it", manager.save_trigger("guard_dyn_trigger", ["CREATED"], "guard_dyn"), "OK")
    check_bound("so any procedure name counts as reached",
                intruder.save_procedure("guard_unrelated", "return 1;"))
    check_status("drop the computed-import trigger", manager.send(
        {"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL, "name": "guard_dyn_trigger"}), "OK")
    check_status("with nothing bound the same save is free",
                 intruder.save_procedure("guard_unrelated", "return 1;"), "OK")


def test_a_procedure_bound_by_another_definer_is_protected():
    section("A procedure bound by another definer is protected")
    with user_conn(MANAGER) as manager, user_conn(MANAGER_TWO) as intruder:
        check_the_direct_binding_is_protected(manager, intruder)
        check_the_privileged_may_overwrite(manager)
        check_schedule_and_hook_bindings(manager, intruder)
        check_import_bindings(manager, intruder)
        check_status("MANAGER installs an unbound procedure", manager.save_procedure("guard_free", "return 1;"), "OK")
        check_status("another MANAGE user may overwrite an unbound one",
                     intruder.save_procedure("guard_free", "return 2;"), "OK")


def test_a_manager_cannot_change_a_trigger_defined_by_someone_else():
    section("A MANAGE user cannot replace or delete a trigger another user defined")
    delete_foreign = {"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL, "name": "foreign_owned"}
    with user_conn(MANAGER) as manager, user_conn(MANAGER_TWO) as intruder:
        check_status("MANAGER installs a trigger", manager.save_trigger("foreign_owned", ["CREATED"], "greet"), "OK")
        check_code("another MANAGE user may not replace it",
                   intruder.save_trigger("foreign_owned", ["DELETED"], "greet"), "FORBIDDEN", "403-5")
        check_code("nor delete it", intruder.send(delete_foreign), "FORBIDDEN", "403-5")
        listed = manager.send({"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": COLL})
        stored = next((t for t in (listed.get("triggers") or []) if t.get("name") == "foreign_owned"), {})
        check("the trigger is unchanged", stored.get("events") == ["CREATED"], f"got {stored}")
        check_status("its definer may delete it", manager.send(delete_foreign), "OK")


def test_a_client_cannot_forge_a_definer():
    section("A client cannot forge the coordinator's stamp")
    forged = {"stampedVersion": 7, "stampedDefiner": ADMIN_USERNAME, "stampedUpdatedBy": ADMIN_USERNAME,
              "stampedUpdatedAt": 1, "stampedCreatedAt": 1}
    with user_conn(MANAGER) as manager:
        check_status("a MANAGE user installs a trigger carrying a forged stamp",
                     manager.save_trigger("forged_stamp", ["CREATED"], "greet", **forged), "OK")
        with admin_conn() as admin:
            listed = admin.send({"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": COLL})
            stored = next((t for t in (listed.get("triggers") or []) if t.get("name") == "forged_stamp"), {})
            check("the definer is the caller, not the forged admin", stored.get("definer") == MANAGER,
                  f"got {stored}")
            check("so is updatedBy", stored.get("updatedBy") == MANAGER, f"got {stored}")
            check("and the version was assigned by the server", stored.get("version") == 1, f"got {stored}")
        check_status("clean up the trigger", manager.send(
            {"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL, "name": "forged_stamp"}), "OK")
        check_code("a forged stamp does not skip the import check",
                   manager.save_procedure("forged_import", "import { x } from 'procedures/not_there'; return x;",
                                          stampedVersion=5), "ERROR", "400-18")


def test_procedure_source_requires_manage():
    section("Procedure source is readable only with script MANAGE")
    show = "import { secret } from 'procedures/lib_hidden'; return [String(secret), secret(1)].join('|');"
    with admin_conn() as admin:
        check_status("install a library", admin.save_procedure(
            "lib_hidden", "export function secret(x) { return x + 1; }"), "OK")
    with user_conn(RUNNER) as runner:
        check_code("a READ/RUN user asking for the source is refused (403-6)",
                   runner.send({"type": "LIST_PROCEDURES", "databaseName": DB, "includeSource": True}),
                   "FORBIDDEN", "403-6")
        summary = runner.send({"type": "LIST_PROCEDURES", "databaseName": DB})
        check_status("the summary still lists for that user", summary, "OK")
        check("the summary carries no source",
              all("source" not in p for p in (summary.get("procedures") or [])), str(summary)[:300])
        check_result("an imported function prints as a native stub for a RUN-only user",
                     runner.send({"type": "RUN_SCRIPT", "databaseName": DB, "script": show}),
                     "function secret() { [native code] }|2")
    with user_conn(MANAGER) as manager:
        listed = manager.send({"type": "LIST_PROCEDURES", "databaseName": DB, "includeSource": True})
        sources = {p.get("name"): p.get("source") for p in (listed.get("procedures") or [])}
        check("a MANAGE user lists the source",
              sources.get("lib_hidden") == "export function secret(x) { return x + 1; }", f"got {sources}")
        check_result("and prints the imported function's source",
                     manager.send({"type": "RUN_SCRIPT", "databaseName": DB, "script": show}),
                     "function secret(x) { return x + 1; }|2")
    with admin_conn() as admin:
        check_status("drop the library", admin.send({"type": "DELETE_PROCEDURE", "databaseName": DB,
                                                     "name": "lib_hidden"}), "OK")


def test_procedure_imports(conn: Conn):
    section("Shared code between procedures (procedures/<name>)")

    check_status("install a library of helpers",
                 conn.save_procedure("lib_fmt",
                                     "export function label(id) { return 'order:' + id; }\n"
                                     "export const VERSION = 2;"), "OK")

    check_status("install a procedure that imports the library",
                 conn.save_procedure("labeller",
                                     "import { label, VERSION } from 'procedures/lib_fmt';\n"
                                     "import args from 'args';\n"
                                     "return { text: label(args.id), v: VERSION };"), "OK")
    response = conn.call("labeller", {"id": "o9"})
    check_status("CALL_PROCEDURE resolves the import", response, "OK")
    check("the composed result is correct", response.get("result") == {"text": "order:o9", "v": 2},
          f"got {response.get('result')!r}")

    check_status("install a library that itself imports another",
                 conn.save_procedure("lib_wrap",
                                     "import { label } from 'procedures/lib_fmt';\n"
                                     "export function shout(id) { return label(id).toUpperCase(); }"), "OK")
    check_status("install a procedure two levels up",
                 conn.save_procedure("shouter",
                                     "import { shout } from 'procedures/lib_wrap';\n"
                                     "import args from 'args';\n"
                                     "return shout(args.id);"), "OK")
    check("a library may import a library", conn.call("shouter", {"id": "o1"}).get("result") == "ORDER:O1",
          f"got {conn.call('shouter', {'id': 'o1'}).get('result')!r}")

    listed = conn.send({"type": "LIST_PROCEDURES", "databaseName": DB})
    names = [p.get("name") for p in (listed.get("procedures") or [])]
    check("a library is listed like any other procedure", "lib_fmt" in names, f"got {names}")

    check_status("delete the library", conn.send({"type": "DELETE_PROCEDURE", "databaseName": DB,
                                                  "name": "lib_fmt"}), "OK")
    failed = conn.call("labeller", {"id": "o9"})
    check("deleting the library breaks its importers at the next call",
          failed.get("errorCode") == "400-9" and "Cannot find module 'procedures/lib_fmt'" in (
              failed.get("message") or ""),
          f"got {failed.get('errorCode')} {failed.get('message')!r}")
    check_status("reinstalling the library fixes them again",
                 conn.save_procedure("lib_fmt", "export function label(id) { return 'order:' + id; }\n"
                                                "export const VERSION = 3;"), "OK")
    check("the importer picks up the new version",
          conn.call("labeller", {"id": "o9"}).get("result") == {"text": "order:o9", "v": 3},
          f"got {conn.call('labeller', {'id': 'o9'}).get('result')!r}")


def test_trigger_imports(conn: Conn):
    section("A trigger's procedure may import a library")
    check_status("install a library the trigger will use",
                 conn.save_procedure("lib_audit",
                                     "export function describe(event, id, actor) {\n"
                                     "    return event + ' on ' + id + ' by ' + actor;\n"
                                     "}"), "OK")
    check_status("install a trigger procedure that imports it",
                 conn.save_procedure("audit_via_lib",
                                     "import db from 'db';\n"
                                     "import args from 'args';\n"
                                     "import { describe } from 'procedures/lib_audit';\n"
                                     "db.save(db.name, '" + AUDIT + "', { _id: 'lib-' + args.id,\n"
                                     "    note: describe(args.event, args.id, args.actingUser),\n"
                                     "    definer: args.definer });\n"
                                     "return 'ok';"), "OK")
    check_status("point the trigger at it",
                 conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "audit_via_lib"), "OK")

    with user_conn(WRITER) as writer:
        check_status("the writer saves a document", writer.save_doc({"_id": "imp1", "n": 1}), "OK")
    row = await_doc(conn, "lib-imp1")
    check("the trigger's import resolved and it wrote the audit row", row.get("status") == "OK", f"got {row}")
    if row.get("status") == "OK":
        check("the library computed the note",
              row["object"].get("note") == f"CREATED on imp1 by {WRITER}", f"got {row['object']}")
        check("definer rights survive the import",
              row["object"].get("definer") == ADMIN_USERNAME, f"got {row['object']}")

    check_status("restore the original audit trigger",
                 conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "auditor"), "OK")


def test_trigger_import_failure(conn: Conn):
    section("A trigger whose import cannot be resolved")
    check_status("install a library",
                 conn.save_procedure("lib_gone", "export function tag(id) { return 'tagged-' + id; }"), "OK")
    check_status("install a trigger procedure that imports it",
                 conn.save_procedure("audit_needs_lib",
                                     "import db from 'db';\n"
                                     "import args from 'args';\n"
                                     "import { tag } from 'procedures/lib_gone';\n"
                                     "db.save(db.name, '" + AUDIT + "', { _id: 'gone-' + args.id,\n"
                                     "    note: tag(args.id) });\n"
                                     "return 'ok';"), "OK")
    check_status("point the trigger at it",
                 conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "audit_needs_lib"), "OK")
    check_status("a write fires it while the library is present", conn.save_doc({"_id": "ok1", "n": 1}), "OK")
    check("the trigger wrote its row", await_doc(conn, "gone-ok1").get("status") == "OK")

    check_status("delete the library the trigger's procedure imports",
                 conn.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "lib_gone"}), "OK")
    before = trigger_failures(conn)
    check_status("a write still succeeds with the trigger broken", conn.save_doc({"_id": "ok2", "n": 2}), "OK")
    missing = await_doc(conn, "gone-ok2", timeout=5.0)
    check("the trigger produced nothing", missing.get("status") != "OK",
          f"the run should have failed on the missing import, but a row appeared: {missing}")
    check("the failure is counted in the stats", trigger_failures(conn) > before,
          f"failures did not increase from {before}")

    check_status("restore the original audit trigger",
                 conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "auditor"), "OK")


def test_trigger_failure_logs_a_stack(conn: Conn, log_path: str):
    section("A failing trigger names where it failed")
    check_status("install a procedure that throws two frames deep",
                 conn.save_procedure("audit_explodes",
                                     "function inner() {\n"
                                     "  throw new Error('trigger blew up');\n"
                                     "}\n"
                                     "function outer() {\n"
                                     "  inner();\n"
                                     "}\n"
                                     "outer();"), "OK")
    check_status("point the trigger at it",
                 conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "audit_explodes"), "OK")
    before = trigger_failures(conn)
    check_status("a write still succeeds with the trigger throwing",
                 conn.save_doc({"_id": "stacked1", "n": 1}), "OK")
    bu.wait_until(lambda: trigger_failures(conn) > before, 15.0, 0.2)
    check("the failure is counted", trigger_failures(conn) > before, f"failures did not increase from {before}")

    log = bu.read_log(log_path)
    line = next((entry for entry in log.splitlines()
                 if "TRIGGER name=audit_writes" in entry and "trigger blew up" in entry), "")
    check("the trigger's failure line carries a stack", "stack=[" in line, f"line={line!r}")
    check("the stack names the throwing function", "inner (" in line, f"line={line!r}")

    check_status("restore the original audit trigger",
                 conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "auditor"), "OK")


def trigger_failures(conn: Conn) -> int:
    stats = conn.send({"type": "GET_DATABASE_STATS"}).get("stats", {}).get("triggers", {})
    return stats.get("failed", 0)


def test_batch_mode(conn: Conn):
    section("Batch mode")
    check_status("install a batch counter", conn.save_procedure("batch_counter",
                 "import db from 'db'; import args from 'args';"
                 "db.save(db.name, '" + AUDIT + "', { _id: 'batch-' + args.documents.length,"
                 " count: args.documents.length }); return 'ok';"), "OK")
    check_status("switch the trigger to batch mode",
                 conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "batch_counter", mode="batch"), "OK")
    docs = [{"_id": f"b{i}", "n": i} for i in range(4)]
    check_status("bulk save four documents",
                 conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL, "objects": docs}), "OK")
    row = await_doc(conn, "batch-4")
    check("batch mode fired once with the whole batch", row.get("status") == "OK", f"got {row}")


def test_batch_deleted_trigger_fires_once_per_transaction(conn: Conn):
    section("A batch DELETED trigger fires once for a transaction's deletes")
    check_status("create a collection for it", conn.send(
        {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": BATCH_DELETE_COLL}), "OK")
    check_status("install a batch delete counter", conn.save_procedure("batch_delete_counter",
                 "import db from 'db'; import args from 'args';"
                 "db.save(db.name, '" + AUDIT + "', { _id: 'batchdel-' + args.documents.length,"
                 " count: args.documents.length }); return 'ok';"), "OK")
    check_status("watch its deletes in batch mode",
                 conn.save_trigger("batch_deletes_watch", ["DELETED"], "batch_delete_counter",
                                   coll=BATCH_DELETE_COLL, mode="batch"), "OK")
    for doc_id in ("bd1", "bd2", "bd3"):
        check_status(f"write {doc_id}", conn.save_doc({"_id": doc_id, "n": 1}, coll=BATCH_DELETE_COLL), "OK")

    check_status("start a transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    for doc_id in ("bd1", "bd2", "bd3"):
        check_status(f"delete {doc_id} in it", conn.send(
            {"type": "DELETE", "databaseName": DB, "collectionName": BATCH_DELETE_COLL, "_id": doc_id}), "OK")
    check_status("commit", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")

    row = await_doc(conn, "batchdel-3")
    check("the batch run received all three deleted documents", row.get("status") == "OK", f"got {row}")
    check("and no single-document run fired", await_absent(conn, "batchdel-1").get("status") != "OK")
    conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": BATCH_DELETE_COLL,
               "name": "batch_deletes_watch"})


def test_cascade(conn: Conn):
    section("Cascade control")
    check_status("install a self-writing procedure", conn.save_procedure("cascader",
                 "import db from 'db'; import args from 'args';"
                 "db.save(db.name, '" + COLL + "', { _id: 'gen-' + args.depth, depth: args.depth });"
                 "db.save(db.name, '" + AUDIT + "', { _id: 'cascade-' + args.depth, depth: args.depth });"
                 "return 'ok';"), "OK")

    check_status("install it with cascade off",
                 conn.save_trigger("cascade_test", ["CREATED", "UPDATED"], "cascader"), "OK")
    check_status("seed a write", conn.save_doc({"_id": "cascade-seed", "n": 1}), "OK")
    first = await_doc(conn, "cascade-0")
    check("the trigger fired once", first.get("status") == "OK", f"got {first}")
    second = await_absent(conn, "cascade-1")
    check("and did not cascade with allowCascade off", second.get("status") != "OK", f"got {second}")

    check_status("turn cascade on",
                 conn.save_trigger("cascade_test", ["CREATED", "UPDATED"], "cascader", allowCascade=True), "OK")
    check_status("seed another write", conn.save_doc({"_id": "cascade-seed-2", "n": 1}), "OK")
    depth_one = await_doc(conn, "cascade-1")
    check("it cascaded one level", depth_one.get("status") == "OK", f"got {depth_one}")
    beyond = await_absent(conn, f"cascade-{TRIGGER_MAX_DEPTH + 1}", settle=4.0)
    check(f"and terminated at triggerMaxDepth={TRIGGER_MAX_DEPTH}", beyond.get("status") != "OK", f"got {beyond}")
    check_status("remove the cascading trigger",
                 conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL,
                            "name": "cascade_test"}), "OK")


def install_hook(conn: Conn, name, procedure, source, events, coll=COLL):
    check_status(f"install the {procedure} procedure", conn.save_procedure(procedure, source), "OK")
    return conn.save_trigger(name, events, procedure, coll=coll, timing="before")


def drop_hook(conn: Conn, name, coll=COLL):
    conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": coll, "name": name})


def test_before_hooks(conn: Conn):
    section("Before hooks - the happy path")
    check_status("install a normalizing before hook",
                 install_hook(conn, "normalize", "normalizer",
                              "export default (doc) => ({ ...doc, total: doc.qty * doc.price });",
                              ["CREATED", "UPDATED"]), "OK")
    listed = conn.send({"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": COLL})
    row = next((x for x in listed.get("triggers", []) if x.get("name") == "normalize"), None)
    check("the row reports its timing", row is not None and row.get("timing") == "before", f"got {row}")

    saved = conn.save_doc({"_id": "h1", "qty": 2, "price": 10})
    check_status("the write succeeds", saved, "OK")
    stored = conn.find("h1")
    check("the stored document carries the computed field",
          (stored.get("object") or {}).get("total") == 20, f"got {stored}")

    check_status("a hook returning nothing accepts unchanged",
                 install_hook(conn, "noop_hook", "noophook", "export default (doc) => { };", ["CREATED", "UPDATED"]),
                 "OK")
    drop_hook(conn, "normalize")
    check_status("write again", conn.save_doc({"_id": "h2", "qty": 3, "price": 4}), "OK")
    unchanged = conn.find("h2")
    check("the document is untouched", "total" not in (unchanged.get("object") or {}), f"got {unchanged}")
    drop_hook(conn, "noop_hook")


CUSTOM_SPELLINGS = {"loc": "#geo(45,-122)", "at": "#datetime(2024-01-01T10:00:00)",
                    "time": "#time(10:00:00)", "vec": "#vector(1,2)"}


def test_before_hook_preserves_custom_spelling(conn: Conn):
    section("Before hooks - a custom value the hook never touched keeps its spelling")
    check_status("install a hook that rebuilds the document",
                 install_hook(conn, "spread_hook", "spreader", "export default (doc) => ({ ...doc, touched: true });",
                              ["CREATED", "UPDATED"]), "OK")
    check_status("write custom values through the hook", conn.save_doc({"_id": "spell1", **CUSTOM_SPELLINGS}), "OK")
    stored = conn.find("spell1").get("object") or {}
    check("the hook ran", stored.get("touched") is True, f"got {stored}")
    for field, spelling in CUSTOM_SPELLINGS.items():
        check(f"the {field} value is stored exactly as written", stored.get(field) == spelling,
              f"expected {spelling!r}, got {stored.get(field)!r}")
    distinct = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": COLL, "aggregationSteps": [
        {"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "_id", "value": "spell1"}},
        {"type": "DISTINCT", "fieldName": "loc"}]})
    check("DISTINCT yields the written spelling",
          [row.get("loc") for row in distinct.get("results") or []] == [CUSTOM_SPELLINGS["loc"]], f"got {distinct}")
    drop_hook(conn, "spread_hook")


def test_a_before_hook_getter_is_stored_like_db_save_stores_it(conn: Conn):
    section("Before hooks - a getter on the replacement is read, as db.save reads it")
    check_status("install a hook whose replacement computes a field through a getter",
                 install_hook(conn, "getter_hook", "gettered",
                              "export default (doc) => ({ ...doc, get total() { return doc.qty * doc.price; } });",
                              ["CREATED", "UPDATED"]), "OK")
    check_status("write through the hook", conn.save_doc({"_id": "getter1", "qty": 2, "price": 10}), "OK")
    hooked = conn.find("getter1").get("object") or {}
    check("the hook's getter value is stored", hooked.get("total") == 20, f"got {hooked}")
    drop_hook(conn, "getter_hook")
    check_status("install a procedure that saves the same shape with db.save",
                 conn.save_procedure("getter_saver", "import db from 'db';"
                                     "const doc = { _id: 'getter2', qty: 2, price: 10 };"
                                     "db.save(db.name, '" + AUDIT + "', { ...doc, get total() { return doc.qty * doc.price; } });"
                                     "return 'saved';"), "OK")
    check_status("run it", conn.call("getter_saver"), "OK")
    saved = conn.find("getter2", coll=AUDIT).get("object") or {}
    check("db.save stores the same getter value", saved.get("total") == hooked.get("total"),
          f"hook stored {hooked.get('total')!r}, db.save stored {saved.get('total')!r}")


def test_before_hook_veto(conn: Conn):
    section("Before hooks - veto")
    check_status("install a vetoing hook",
                 install_hook(conn, "require_customer", "requirecustomer",
                              "export default (doc) => { if (!doc.customerId) {"
                              " throw new Error('customerId is required'); } };",
                              ["CREATED", "UPDATED"]), "OK")
    rejected = conn.save_doc({"_id": "v1", "qty": 1})
    check_code("a non-compliant write is refused", rejected, "ERROR", "400-21")
    check("the message carries the thrown text", "customerId is required" in (rejected.get("message") or ""),
          f"got {rejected}")
    check("and nothing was written", conn.find("v1").get("status") != "OK")
    check_status("a compliant write still succeeds",
                 conn.save_doc({"_id": "v2", "qty": 1, "customerId": "c1"}), "OK")
    check("it is stored", conn.find("v2").get("status") == "OK")
    drop_hook(conn, "require_customer")


def test_before_hook_contract(conn: Conn):
    section("Before hooks - the return contract")
    cases = [
        ("returns a number", "export default (doc) => 42;"),
        ("returns a string", "export default (doc) => 'nope';"),
        ("returns an array", "export default (doc) => [1, 2];"),
        ("returns false", "export default (doc) => false;"),
        ("changes _id", "export default (doc) => ({ ...doc, _id: 'somewhere-else' });"),
    ]
    for index, (label, source) in enumerate(cases):
        check_status(f"install a hook that {label}",
                     install_hook(conn, "contract", f"contract{index}", source, ["CREATED", "UPDATED"]), "OK")
        check_code(f"a hook that {label} refuses the write",
                   conn.save_doc({"_id": f"c{index}", "qty": 1}), "ERROR", "400-21")
        check(f"and nothing was written for {label}", conn.find(f"c{index}").get("status") != "OK")
    drop_hook(conn, "contract")

    check_status("seed a document to delete", conn.save_doc({"_id": "cd1", "qty": 1}), "OK")
    check_status("install a DELETED hook that returns a document",
                 install_hook(conn, "del_returns", "delreturns", "export default (doc) => ({ ...doc });",
                              ["DELETED"]), "OK")
    refused = conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "cd1"})
    check_code("returning a document on DELETED is refused", refused, "ERROR", "400-21")
    check("the document survives", conn.find("cd1").get("status") == "OK")
    drop_hook(conn, "del_returns")


def test_before_hook_sees_the_same_id_in_and_out_of_a_transaction(conn: Conn):
    section("Before hooks - an id-less write hands the hook its generated _id either way")
    check_status("install a hook that records the _id it saw",
                 install_hook(conn, "id_seer", "idseer", "export default (doc) => ({ ...doc, seenId: doc._id });",
                              ["CREATED"]), "OK")
    saved = conn.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL, "object": {"qty": 1}})
    check_status("an id-less save outside a transaction succeeds", saved, "OK")
    stored = conn.find(saved.get("_id")).get("object") or {}
    check("the hook saw the _id the document is stored under", stored.get("seenId") == saved.get("_id"),
          f"saved={saved.get('_id')!r} seen={stored.get('seenId')!r}")
    drop_hook(conn, "id_seer")

    check_status("install a hook that returns a fresh document with no _id",
                 install_hook(conn, "id_dropper", "iddropper", "export default () => ({ fresh: true });",
                              ["CREATED"]), "OK")
    check_code("outside a transaction the hook's dropped _id is refused",
               conn.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL, "object": {"qty": 1}}),
               "ERROR", "400-21")
    check_status("start a transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_code("inside a transaction the same hook is refused the same way",
               conn.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL, "object": {"qty": 1}}),
               "ERROR", "400-21")
    check_status("roll back", conn.send({"type": "ROLLBACK_TRANSACTION"}), "OK")
    drop_hook(conn, "id_dropper")


def test_before_hook_schema_recheck(conn: Conn):
    section("Before hooks - a replacement is re-validated")
    schema = {"type": "object", "properties": {"qty": {"type": "number"}}, "required": ["qty"],
              "additionalProperties": False}
    check_status("install a schema", conn.send(
        {"type": "SAVE_SCHEMA", "databaseName": DB, "collectionName": "hooked", "schema": schema}), "OK")         if False else None
    check_status("create a schema-guarded collection",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": "guarded"}), "OK")
    check_status("save the schema", conn.send(
        {"type": "SAVE_SCHEMA", "databaseName": DB, "collectionName": "guarded", "schema": schema}), "OK")
    check_status("install a hook that adds a forbidden field",
                 install_hook(conn, "sneak", "sneaky", "export default (doc) => ({ ...doc, extra: 1 });",
                              ["CREATED", "UPDATED"], coll="guarded"), "OK")
    rejected = conn.save_doc({"_id": "g1", "qty": 1}, coll="guarded")
    check_code("a replacement failing the schema is refused", rejected, "ERROR", "400-21")
    check("and nothing was written", conn.find("g1", coll="guarded").get("status") != "OK")
    drop_hook(conn, "sneak", coll="guarded")


DEEP_REPLACEMENT_HOOK = ("export default (doc) => { let o = {}; for (let i = 0; i < 200; i++) { o = { a: o }; }"
                         " return { ...doc, deep: o }; };")


def test_before_hook_replacement_depth(conn: Conn):
    section("Before hooks - a replacement is held to the request nesting cap")
    check_status("install a hook that nests its replacement 200 levels deep",
                 install_hook(conn, "nester", "nesting", DEEP_REPLACEMENT_HOOK, ["CREATED", "UPDATED"]), "OK")
    check_code("a plain save is refused", conn.save_doc({"_id": "deep1", "qty": 1}), "ERROR", "400-21")
    check("and nothing was written", conn.find("deep1").get("status") != "OK")
    check_code("a bulk save is refused", conn.send(
        {"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL,
         "objects": [{"_id": "deep2", "qty": 1}, {"_id": "deep3", "qty": 2}]}), "ERROR", "400-21")
    check("and neither document was written",
          conn.find("deep2").get("status") != "OK" and conn.find("deep3").get("status") != "OK")
    check_status("start a transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_code("inside a transaction the save is refused the same way", conn.send(
        {"type": "SAVE", "databaseName": DB, "collectionName": COLL, "object": {"_id": "deep4", "qty": 1}}),
               "ERROR", "400-21")
    check_status("roll back", conn.send({"type": "ROLLBACK_TRANSACTION"}), "OK")
    check("and nothing was written", conn.find("deep4").get("status") != "OK")
    drop_hook(conn, "nester")


def test_before_hook_on_delete(conn: Conn):
    section("Before hooks - delete")
    check_status("seed a locked document", conn.save_doc({"_id": "d1", "qty": 1, "locked": True}), "OK")
    check_status("seed an unlocked one", conn.save_doc({"_id": "d2", "qty": 1, "locked": False}), "OK")
    check_status("install a delete guard",
                 install_hook(conn, "guard_delete", "guarddelete",
                              "export default (doc) => { if (doc.locked) { throw new Error('locked'); } };",
                              ["DELETED"]), "OK")
    refused = conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "d1"})
    check_code("deleting a locked document is refused", refused, "ERROR", "400-21")
    check("the locked document survives", conn.find("d1").get("status") == "OK")
    check_status("deleting an unlocked one succeeds",
                 conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "d2"}), "OK")
    check("it is gone", conn.find("d2").get("status") != "OK")
    drop_hook(conn, "guard_delete")


def test_before_hook_bulk_save(conn: Conn):
    section("Before hooks - bulk save")
    check_status("install a counting hook",
                 install_hook(conn, "bulk_calc", "bulkcalc",
                              "let opened = 0; opened++;"
                              " export default (doc) => ({ ...doc, total: doc.qty * 2, opened: opened });",
                              ["CREATED", "UPDATED"]), "OK")
    docs = [{"_id": f"bk{i}", "qty": i} for i in range(10)]
    check_status("bulk save ten documents", conn.send(
        {"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL, "objects": docs}), "OK")
    first = (conn.find("bk1").get("object") or {})
    last = (conn.find("bk9").get("object") or {})
    check("every document carries the computed field", first.get("total") == 2 and last.get("total") == 18,
          f"got {first} / {last}")
    check("the module body evaluated once for the whole batch",
          first.get("opened") == 1 and last.get("opened") == 1, f"got {first} / {last}")
    drop_hook(conn, "bulk_calc")

    check_status("install a hook that vetoes the fourth document",
                 install_hook(conn, "bulk_veto", "bulkveto",
                              "export default (doc) => { if (doc.qty === 3) { throw new Error('no'); } };",
                              ["CREATED", "UPDATED"]), "OK")
    batch = [{"_id": f"bv{i}", "qty": i} for i in range(10)]
    check_code("the whole request is refused", conn.send(
        {"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL, "objects": batch}), "ERROR", "400-21")
    stored = [i for i in range(10) if conn.find(f"bv{i}").get("status") == "OK"]
    check("not one document from the batch was stored", stored == [], f"stored {stored}")
    drop_hook(conn, "bulk_veto")

    check_status("seed the document a colliding _id would redirect onto",
                 conn.save_doc({"_id": "BB", "qty": 1, "owner": "untouched"}), "OK")
    check_status("seed the document the client is updating", conn.save_doc({"_id": "Aa", "qty": 1}), "OK")
    check_status("install a hook that swaps _id for one sharing its hash code",
                 install_hook(conn, "bulk_collide", "bulkcollide",
                              "export default (doc) => ({ ...doc, _id: doc._id === 'Aa' ? 'BB' : doc._id,"
                              " owner: 'hook' });",
                              ["CREATED", "UPDATED"]), "OK")
    check_code("a bulk entry whose _id the hook changed is refused", conn.send(
        {"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL, "objects": [{"_id": "Aa", "qty": 2}]}),
        "ERROR", "400-21")
    check_code("the same swap is refused on a single save", conn.save_doc({"_id": "Aa", "qty": 3}), "ERROR",
               "400-21")
    victim = conn.find("BB").get("object") or {}
    check("the unrelated document is unchanged", victim.get("owner") == "untouched", f"got {victim}")
    drop_hook(conn, "bulk_collide")


COUNTED_COLL = "bulk_counted"
COUNTING_HOOK = "let calls = 0; export default (doc) => ({ ...doc, call: ++calls });"


def calls_of(conn: Conn, ids, coll=COLL):
    return [(conn.find(doc_id, coll=coll).get("object") or {}).get("call") for doc_id in ids]


def test_before_hook_bulk_save_in_a_transaction(conn: Conn):
    section("Before hooks - a bulk save shares one context inside a transaction and a trigger too")
    check_status("install a hook counting its calls",
                 install_hook(conn, "bulk_count", "bulkcount", COUNTING_HOOK, ["CREATED", "UPDATED"]), "OK")
    check_status("bulk save three documents outside a transaction", conn.send(
        {"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL,
         "objects": [{"_id": f"bo{i}", "qty": i} for i in range(3)]}), "OK")
    check("outside a transaction the calls are numbered 1..3",
          calls_of(conn, ["bo0", "bo1", "bo2"]) == [1, 2, 3], f"got {calls_of(conn, ['bo0', 'bo1', 'bo2'])}")

    check_status("start a transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_status("bulk save three documents inside it", conn.send(
        {"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL,
         "objects": [{"_id": f"bt{i}", "qty": i} for i in range(3)]}), "OK")
    check_status("commit", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    check("inside a transaction the calls are numbered 1..3 as well",
          calls_of(conn, ["bt0", "bt1", "bt2"]) == [1, 2, 3], f"got {calls_of(conn, ['bt0', 'bt1', 'bt2'])}")
    drop_hook(conn, "bulk_count")

    check_status("create the collection a trigger bulk-saves into",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COUNTED_COLL}), "OK")
    check_status("install the counting hook on it",
                 install_hook(conn, "bulk_count_target", "bulkcounttarget", COUNTING_HOOK, ["CREATED", "UPDATED"],
                              coll=COUNTED_COLL), "OK")
    fan_out = ("import db from 'db'; import args from 'args';"
               " if (args.id === 'fanout') { db.bulkSave(db.name, '" + COUNTED_COLL + "',"
               " [{ _id: 'tg0' }, { _id: 'tg1' }, { _id: 'tg2' }]); }"
               " return 'ok';")
    check_status("install the fan-out procedure", conn.save_procedure("fanout", fan_out), "OK")
    check_status("install a trigger running it", conn.save_trigger("fan_out", ["CREATED"], "fanout"), "OK")
    check_status("write the document that fires it", conn.save_doc({"_id": "fanout", "qty": 1}), "OK")
    landed = await_doc(conn, "tg2", coll=COUNTED_COLL)
    check("the trigger's bulk save landed", landed.get("status") == "OK", f"got {landed}")
    check("a bulk save from a trigger numbers its calls 1..3 too",
          calls_of(conn, ["tg0", "tg1", "tg2"], coll=COUNTED_COLL) == [1, 2, 3],
          f"got {calls_of(conn, ['tg0', 'tg1', 'tg2'], coll=COUNTED_COLL)}")
    drop_hook(conn, "fan_out")
    drop_hook(conn, "bulk_count_target", coll=COUNTED_COLL)


def test_before_hook_chaining(conn: Conn):
    section("Before hooks - chaining in name order")
    check_status("install the first hook",
                 install_hook(conn, "a_stamp", "astamp", "export default (doc) => ({ ...doc, trail: 'a' });",
                              ["CREATED", "UPDATED"]), "OK")
    check_status("install the second",
                 install_hook(conn, "b_double", "bdouble",
                              "export default (doc) => ({ ...doc, trail: doc.trail + 'b' });",
                              ["CREATED", "UPDATED"]), "OK")
    check_status("write", conn.save_doc({"_id": "ch1", "qty": 1}), "OK")
    check("the second hook saw the first hook's output",
          (conn.find("ch1").get("object") or {}).get("trail") == "ab", f"got {conn.find('ch1')}")
    drop_hook(conn, "a_stamp")
    drop_hook(conn, "b_double")

    check_status("install them again with the order flipped",
                 install_hook(conn, "a_double", "adouble",
                              "export default (doc) => ({ ...doc, trail: (doc.trail || '') + 'b' });",
                              ["CREATED", "UPDATED"]), "OK")
    check_status("and the stamper second",
                 install_hook(conn, "b_stamp", "bstamp", "export default (doc) => ({ ...doc, trail: 'a' });",
                              ["CREATED", "UPDATED"]), "OK")
    check_status("write again", conn.save_doc({"_id": "ch2", "qty": 1}), "OK")
    check("name order decided the chain, not install order",
          (conn.find("ch2").get("object") or {}).get("trail") == "a", f"got {conn.find('ch2')}")
    drop_hook(conn, "a_double")
    drop_hook(conn, "b_stamp")


def test_before_hook_sandbox(conn: Conn):
    section("Before hooks - fail-closed")
    check_status("install a hook that never returns",
                 install_hook(conn, "spin", "spinner", "export default (doc) => { while (true) { } };",
                              ["CREATED", "UPDATED"]), "OK")
    started = time.time()
    timed_out = conn.save_doc({"_id": "to1", "qty": 1})
    elapsed = time.time() - started
    check("a hook that never finishes refuses the write",
          timed_out.get("status") == "ERROR" and timed_out.get("errorCode") in ("408-1", "400-11"),
          f"got {timed_out}")
    check("and nothing was written", conn.find("to1").get("status") != "OK")
    check("it gave up near the configured budget", elapsed < 10, f"took {elapsed:.1f}s")
    drop_hook(conn, "spin")


def test_before_hook_no_db(conn: Conn):
    section("Before hooks - the capability boundary")
    check_status("install a hook that reaches for db",
                 install_hook(conn, "needs_db", "needsdb",
                              "import db from 'db'; export default (doc) => doc;", ["CREATED", "UPDATED"]), "OK")
    refused = conn.save_doc({"_id": "nd1", "qty": 1})
    check_code("a hook with no db binding refuses the write", refused, "ERROR", "400-21")
    check("the message says database access is unavailable",
          "Database access is not available" in (refused.get("message") or ""), f"got {refused}")
    drop_hook(conn, "needs_db")

    check_status("install a shared library procedure",
                 conn.save_procedure("hooklib", "export function tag(d) { return { ...d, via: 'lib' }; }"), "OK")
    check_status("install a hook that imports it",
                 install_hook(conn, "uses_lib", "useslib",
                              "import { tag } from 'procedures/hooklib';"
                              " export default (doc) => tag(doc);", ["CREATED", "UPDATED"]), "OK")
    check_status("the write succeeds", conn.save_doc({"_id": "nd2", "qty": 1}), "OK")
    check("the imported library ran", (conn.find("nd2").get("object") or {}).get("via") == "lib",
          f"got {conn.find('nd2')}")
    drop_hook(conn, "uses_lib")


def test_a_before_hook_does_not_change_the_scripts_suppressed_error(conn: Conn):
    section("Before hooks - a hook's realm never leaks into the script that triggered it")
    check_status("install a pass-through before hook",
                 install_hook(conn, "realm_hook", "realmhook", "export default (doc) => doc;", ["CREATED", "UPDATED"]),
                 "OK")
    script = ("import db from 'db';"
              f" db.save('{DB}', '{COLL}', {{ _id: 'realm1', qty: 1 }});"
              " try { using a = { [Symbol.dispose]() { throw new Error('a'); } },"
              " b = { [Symbol.dispose]() { throw new Error('b'); } }; }"
              " catch (e) { return e instanceof SuppressedError; }"
              " return 'no error';")
    check_result("the script's SuppressedError is still its own realm's",
                 conn.send({"type": "RUN_SCRIPT", "databaseName": DB, "script": script}), True)
    drop_hook(conn, "realm_hook")


def test_before_hook_in_transaction(conn: Conn):
    section("Before hooks - inside a transaction")
    check_status("install a normalizing hook",
                 install_hook(conn, "tx_calc", "txcalc",
                              "export default (doc) => ({ ...doc, total: doc.qty * 5 });",
                              ["CREATED", "UPDATED"]), "OK")
    check_status("start a transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_status("buffer a save", conn.save_doc({"_id": "tx1", "qty": 3}), "OK")
    check_status("commit", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    check("the committed document is the hook's output",
          (conn.find("tx1").get("object") or {}).get("total") == 15, f"got {conn.find('tx1')}")
    drop_hook(conn, "tx_calc")

    check_status("install a vetoing hook",
                 install_hook(conn, "tx_veto", "txveto", "export default (doc) => { throw new Error('nope'); };",
                              ["CREATED", "UPDATED"]), "OK")
    check_status("start another transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_code("the buffered save is refused at buffer time", conn.save_doc({"_id": "tx2", "qty": 1}),
               "ERROR", "400-21")
    check_status("commit the empty transaction", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    check("nothing was written", conn.find("tx2").get("status") != "OK")
    drop_hook(conn, "tx_veto")


def test_before_hook_that_shrinks_an_oversized_document(conn: Conn):
    section("Before hooks - a hook that shrinks an oversized document is accepted in and out of a transaction")
    check_status("install a blob-stripping hook",
                 install_hook(conn, "strip_blob", "stripblob",
                              "export default (doc) => { const { blob, ...rest } = doc; return rest; };",
                              ["CREATED", "UPDATED"]), "OK")
    blob = "x" * (1024 * 1024 + 100)
    check_status("an oversized standalone save is accepted once the hook shrinks it",
                 conn.save_doc({"_id": "shrunk_standalone", "blob": blob}), "OK")
    check_status("start a transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_status("the same save is accepted at buffer time inside a transaction",
                 conn.save_doc({"_id": "shrunk_buffered", "blob": blob}), "OK")
    check_status("commit", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    for doc_id in ("shrunk_standalone", "shrunk_buffered"):
        stored = conn.find(doc_id).get("object") or {}
        check(f"{doc_id} was stored without the blob", stored.get("_id") == doc_id and "blob" not in stored,
              f"got keys {sorted(stored)}")
    drop_hook(conn, "strip_blob")
    check_status("start a transaction without the hook", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_code("with no hook the oversized document is still refused", conn.save_doc({"_id": "too_big", "blob": blob}),
               "ERROR", "400-2")
    check_status("roll back", conn.send({"type": "ROLLBACK_TRANSACTION"}), "OK")


def test_before_hook_validation(conn: Conn):
    section("Before hooks - validation")
    check_status("install a procedure to point at", conn.save_procedure("hookproc", "export default (d) => d;"), "OK")
    check_code("an unknown timing is refused",
               conn.save_trigger("bad_timing", ["CREATED"], "hookproc", timing="sideways"), "ERROR", "400-14")
    check_code("batch mode on a before trigger is refused",
               conn.save_trigger("bad_batch", ["CREATED"], "hookproc", timing="before", mode="batch"),
               "ERROR", "400-14")
    check_code("allowCascade on a before trigger is refused",
               conn.save_trigger("bad_cascade", ["CREATED"], "hookproc", timing="before", allowCascade=True),
               "ERROR", "400-14")
    check_code("a before trigger naming a missing procedure is refused",
               conn.save_trigger("bad_proc", ["CREATED"], "no_such_procedure", timing="before"), "NOT_FOUND", "404-8")


def test_before_hook_definer_is_not_enforced(conn: Conn):
    section("Before hooks - the definer divergence")
    check_status("create a throwaway installer", conn.send({
        "type": "CREATE_USER", "username": "hook_installer", "password": USER_PASSWORD, "admin": False,
        "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "MANAGE"}}), "OK")
    with user_conn("hook_installer") as installer:
        check_status("they install a before hook",
                     install_hook(installer, "by_installer", "byinstaller",
                                  "export default (doc) => ({ ...doc, stamped: true });",
                                  ["CREATED", "UPDATED"]), "OK")
    check_status("delete the installer",
                 conn.send({"type": "DELETE_USER", "username": "hook_installer"}), "OK")
    check_status("a write still succeeds", conn.save_doc({"_id": "df1", "qty": 1}), "OK")
    check("the hook still ran despite the definer being gone",
          (conn.find("df1").get("object") or {}).get("stamped") is True, f"got {conn.find('df1')}")
    drop_hook(conn, "by_installer")

    check_status("leave a vetoing hook installed for phase 2",
                 install_hook(conn, "off_hook", "offhook",
                              "export default (doc) => { throw new Error('always refuses'); };",
                              ["CREATED", "UPDATED"]), "OK")
    check_code("it refuses while the switches are on", conn.save_doc({"_id": "off0", "qty": 1}), "ERROR", "400-21")


def test_dry_run(conn: Conn):
    section("TEST_TRIGGER - the dry run")
    check_status("install a hook that logs and computes",
                 install_hook(conn, "dry", "dryrun",
                              "export default (doc) => { console.log('checking ' + doc._id);"
                              " if (doc.qty < 0) { throw new Error('qty must not be negative'); }"
                              " if (doc.qty === 0) { return; }"
                              " return { ...doc, total: doc.qty * 7 }; };",
                              ["CREATED", "UPDATED"]), "OK")

    replaced = conn.test_trigger("dry", "CREATED", {"_id": "dr1", "qty": 2})
    check_status("a replacing verdict is OK", replaced, "OK")
    check("it reports replace", replaced.get("decision") == "replace", f"got {replaced}")
    check("and returns the resulting document", (replaced.get("document") or {}).get("total") == 14,
          f"got {replaced}")
    check("it captures the console output", replaced.get("logs") == ["checking dr1"], f"got {replaced}")

    accepted = conn.test_trigger("dry", "CREATED", {"_id": "dr2", "qty": 0})
    check("an accepting verdict reports accept", accepted.get("decision") == "accept", f"got {accepted}")
    check("and echoes the document unchanged", (accepted.get("document") or {}).get("_id") == "dr2",
          f"got {accepted}")

    rejected = conn.test_trigger("dry", "CREATED", {"_id": "dr3", "qty": -1})
    check_status("a rejecting verdict is still an OK response", rejected, "OK")
    check("it reports reject", rejected.get("decision") == "reject", f"got {rejected}")
    check("with the thrown message", "qty must not be negative" in (rejected.get("reason") or ""),
          f"got {rejected}")
    check("and a stack naming where it broke", bool(rejected.get("stack")), f"got {rejected}")

    check("nothing was written", conn.find("dr1").get("status") != "OK")

    live = conn.save_doc({"_id": "dr4", "qty": 1})
    check_status("the live write succeeds", live, "OK")
    check("the live path returns no logs", "logs" not in live, f"got {live}")

    check_code("an unknown trigger is not found", conn.test_trigger("no_such", "CREATED", {"_id": "x"}),
               "NOT_FOUND", "404-9")
    check_code("an unknown event is refused", conn.test_trigger("dry", "EXPLODED", {"_id": "x"}), "ERROR", "400-14")
    check_code("an event the trigger does not watch is refused",
               conn.test_trigger("dry", "DELETED", {"_id": "x"}), "ERROR", "400-14")

    check_status("install an after trigger", conn.save_trigger("after_only", ["CREATED"], "dryrun"), "OK")
    check_code("an after trigger cannot be tested", conn.test_trigger("after_only", "CREATED", {"_id": "x"}),
               "ERROR", "400-14")
    drop_hook(conn, "after_only")

    with user_conn(RUNNER) as runner:
        check_code("a RUN-level user may not test a trigger",
                   runner.test_trigger("dry", "CREATED", {"_id": "x"}), "FORBIDDEN", "403-1")
    with user_conn(MANAGER) as manager:
        check_status("a MANAGE-level user may",
                     manager.test_trigger("dry", "CREATED", {"_id": "dr5", "qty": 1}), "OK")
    drop_hook(conn, "dry")


def history_rows(conn: Conn, **filters) -> list:
    response = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": "script_runs",
                          "aggregationSteps": [{"type": "SORT", "fieldName": "startedAt", "ascending": False}]})
    rows = response.get("results") or []
    for field, value in filters.items():
        rows = [row for row in rows if row.get(field) == value]
    return rows


def await_history(conn: Conn, timeout: float = 15.0, **filters) -> list:
    return await_latest(lambda: history_rows(conn, **filters), bool, timeout, 0.2)


def await_history_rows(conn: Conn, name: str, at_least: int) -> list:
    return await_latest(lambda: history_rows(conn, kind="TRIGGER", name=name),
                        lambda rows: len(rows) >= at_least, 30.0, 0.3)


def test_run_history(conn: Conn):
    section("Run history")
    check_status("install a procedure worth recording",
                 conn.save_procedure("historied",
                                     "import args from 'args';\n"
                                     "let total = args.n || 0;\n"
                                     "for (let i = 0; i < 50; i++) total += i;\n"
                                     "return total;"), "OK")
    check_status("call it", conn.send({"type": "CALL_PROCEDURE", "databaseName": DB,
                                       "procedureName": "historied", "args": {"n": 41}}), "OK")

    rows = await_history(conn, kind="CALL_PROCEDURE", name="historied")
    check("a CALL_PROCEDURE row is recorded", len(rows) == 1, f"rows={rows!r}")
    if rows:
        row = rows[0]
        check("the row reports the outcome", row.get("outcome") == "ok", f"row={row!r}")
        check("the row names the caller", row.get("username") == ADMIN_USERNAME, f"row={row!r}")
        check("the row carries metrics",
              (row.get("metrics") or {}).get("instructions", 0) > 0, f"row={row!r}")
        check("the row records a duration", row.get("durationMs") is not None, f"row={row!r}")
        check("logs are omitted by default", row.get("logs") == [], f"row={row!r}")

    check_status("install an auditing trigger",
                 conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "auditor"), "OK")
    check_status("write a document so it fires", conn.save_doc({"_id": "histdoc1", "n": 1}), "OK")
    trigger_rows = await_history(conn, kind="TRIGGER", name="audit_writes")
    check("a TRIGGER row is recorded", len(trigger_rows) >= 1, f"rows={trigger_rows!r}")
    if trigger_rows:
        row = trigger_rows[0]
        check("the trigger row names the collection", row.get("collection") == COLL, f"row={row!r}")
        check("the trigger row names the event", row.get("event") in ("CREATED", "UPDATED"), f"row={row!r}")
        check("the trigger row names the definer", row.get("username") == ADMIN_USERNAME, f"row={row!r}")

    history_before = len(history_rows(conn))
    check_status("install a trigger on the history collection itself",
                 conn.save_trigger("history_watcher", ["CREATED"], "auditor", coll="script_runs"), "ERROR")
    check("a trigger cannot even be installed on script_runs",
          len(history_rows(conn)) >= history_before, "the listing should be unaffected")

    check_code("a client may not write into script_runs",
               conn.send({"type": "SAVE", "databaseName": DB, "collectionName": "script_runs",
                          "object": {"_id": "forged", "outcome": "ok"}}), "ERROR", "400-1")


def test_run_history_failures(conn: Conn):
    section("Run history for failures")
    check_status("install a procedure that throws",
                 conn.save_procedure("history_boom", "throw new RangeError('history boom');"), "OK")
    conn.send({"type": "CALL_PROCEDURE", "databaseName": DB, "procedureName": "history_boom"})

    rows = await_history(conn, kind="CALL_PROCEDURE", name="history_boom")
    check("a failed call is recorded", len(rows) == 1, f"rows={rows!r}")
    if rows:
        row = rows[0]
        check("the row reports the error outcome", row.get("outcome") == "error", f"row={row!r}")
        check("the row names the error", row.get("errorName") == "RangeError", f"row={row!r}")
        check("the row carries the message", "history boom" in (row.get("errorMessage") or ""), f"row={row!r}")
        check("the row carries a stack", bool(row.get("stack")), f"row={row!r}")

    check_status("install a procedure whose message is shaped like a custom type",
                 conn.save_procedure("history_shaped", "throw new Error('#tag(fix)');"), "OK")
    conn.send({"type": "CALL_PROCEDURE", "databaseName": DB, "procedureName": "history_shaped"})
    shaped = await_history(conn, kind="CALL_PROCEDURE", name="history_shaped")
    check("the custom-shaped failure is recorded", len(shaped) == 1, f"rows={shaped!r}")
    if shaped:
        check("its message is stored escaped", shaped[0].get("errorMessage") == "\\#tag(fix)",
              f"row={shaped[0]!r}")

    check_status("install a trigger owned by a user about to be deleted",
                 conn.send({"type": "CREATE_USER", "username": "ghostdefiner", "password": "password123",
                            "admin": True, "globalPermissions": [], "databasePermissions": {},
                            "collectionPermissions": {}}), "OK")
    with user_conn("ghostdefiner") as ghost:
        check_status("the ghost installs a trigger",
                     ghost.save_trigger("ghost_trigger", ["CREATED", "UPDATED"], "auditor"), "OK")
    check_status("delete the definer",
                 conn.send({"type": "DELETE_USER", "username": "ghostdefiner"}), "OK")
    ghost = trigger_entry(conn, COLL, "ghost_trigger")
    check("deleting the definer disables its trigger", ghost.get("enabled") is False, f"got {ghost}")
    check("with the reason", "no longer exists" in str(ghost.get("disabledReason")), f"got {ghost}")
    check_status("write a document", conn.save_doc({"_id": "ghostdoc", "n": 1}), "OK")
    check("the disabled trigger records no run",
          not await_latest(lambda: history_rows(conn, kind="TRIGGER", name="ghost_trigger"), bool, 2.0, 0.2),
          f"rows={history_rows(conn, kind='TRIGGER', name='ghost_trigger')!r}")
    check_status("remove the ghost trigger",
                 conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL,
                            "name": "ghost_trigger"}), "OK")


def test_a_custom_shaped_history_row_survives_the_restart(conn: Conn):
    section("A custom-shaped run history row is still readable after a restart")
    rows = history_rows(conn, kind="CALL_PROCEDURE", name="history_shaped")
    check("the row is still listed after the restart", len(rows) == 1, f"rows={rows!r}")
    if rows:
        check("and reads back as the same plain string", rows[0].get("errorMessage") == "\\#tag(fix)",
              f"row={rows[0]!r}")


def fenced_collection_folder(work_dir: str) -> str:
    return os.path.join(work_dir, "db", DB, FENCED_COLL)


def arrange_a_commit_interrupted_past_its_commit_point(conn: Conn, work_dir: str):
    section("A commit that cannot finish applying, then a kill with no drain")
    for coll in (RECOVERED_COLL, FENCED_COLL):
        check_status(f"create {coll}", conn.send(
            {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    check_status("install an after trigger on the recovered collection",
                 conn.save_trigger("audit_recovered", ["CREATED", "UPDATED"], "auditor", coll=RECOVERED_COLL), "OK")

    check_status("start a warm-up transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_status("buffer a save", conn.save_doc({"_id": "tx-warmup", "n": 1}, coll=RECOVERED_COLL), "OK")
    check_status("commit it", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    check("an ordinary transactional commit fires the trigger",
          await_doc(conn, "CREATED-tx-warmup").get("status") == "OK",
          "the fixture never fired, so the recovered commit below would prove nothing")

    check_status("start the transaction that will not finish", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_status("buffer the audited save", conn.save_doc({"_id": "tx-recovered", "n": 1}, coll=RECOVERED_COLL), "OK")
    check_status("buffer a save into a second collection",
                 conn.save_doc({"_id": "tx-blocker", "n": 1}, coll=FENCED_COLL), "OK")
    os.chmod(fenced_collection_folder(work_dir), 0o000)
    check_code("the commit fences past its commit point",
               conn.send({"type": "COMMIT_TRANSACTION"}), "ERROR", "500-33")


def test_a_recovered_commit_fires_its_triggers(conn: Conn):
    section("A commit finished by startup recovery fires its after triggers")
    check("recovery applied the interrupted commit",
          conn.find("tx-recovered", coll=RECOVERED_COLL).get("status") == "OK",
          f"got {conn.find('tx-recovered', coll=RECOVERED_COLL)}")
    check("including the op that could not apply before the kill",
          conn.find("tx-blocker", coll=FENCED_COLL).get("status") == "OK",
          f"got {conn.find('tx-blocker', coll=FENCED_COLL)}")

    row = await_doc(conn, "CREATED-tx-recovered", timeout=30.0)
    check("the recovered commit fired the after trigger the online commit would have",
          row.get("status") == "OK", f"got {row}")
    check("and reported the insert the original commit decided on, not an update",
          conn.find("UPDATED-tx-recovered", coll=AUDIT).get("status") != "OK",
          "the recovered commit re-derived CREATED/UPDATED from disk, where the document already existed")
    if row.get("status") == "OK":
        check("the row names the user whose transaction it was",
              row["object"].get("by") == ADMIN_USERNAME, f"got {row['object']}")


def test_an_unreadable_trigger_file_is_not_an_empty_one(conn: Conn, work_dir: str):
    section("A trigger file that cannot be read is not 'no triggers'")
    trigger_file = os.path.join(work_dir, "db", DB, UNREADABLE_COLL, f"{UNREADABLE_COLL}-triggers.json")
    check("the trigger file survived the restart", os.path.isfile(trigger_file), trigger_file)
    with open(trigger_file, "rb") as fp:
        original = fp.read()
    check("and still names the trigger installed before it", b"audit_unreadable" in original, f"got {original!r}")

    os.chmod(trigger_file, 0o000)
    try:
        refused = conn.save_doc({"_id": "ur1", "n": 1}, coll=UNREADABLE_COLL)
        check_code("a write is refused rather than quietly firing no triggers", refused, "ERROR", "500-7")
        check("and nothing was written", conn.find("ur1", coll=UNREADABLE_COLL).get("status") != "OK")
        check_code("saving a trigger is refused",
                   conn.save_trigger("audit_second", ["CREATED"], "auditor", coll=UNREADABLE_COLL), "ERROR", "500-29")
        check_code("deleting one is refused", conn.send(
            {"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": UNREADABLE_COLL,
             "name": "audit_unreadable"}), "ERROR", "500-30")
    finally:
        os.chmod(trigger_file, 0o644)

    with open(trigger_file, "rb") as fp:
        after = fp.read()
    check("neither DDL call erased the definitions it could not read", after == original,
          f"the file changed while it was unreadable: {after!r}")
    listed = conn.send({"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": UNREADABLE_COLL})
    check("the trigger is still installed once the file can be read again",
          any(t.get("name") == "audit_unreadable" for t in (listed.get("triggers") or [])), f"got {listed}")
    check_status("and a write fires it again", conn.save_doc({"_id": "ur2", "n": 2}, coll=UNREADABLE_COLL), "OK")
    check("the audit row is back", await_doc(conn, "CREATED-ur2").get("status") == "OK")


def test_a_corrupt_trigger_file_is_not_an_empty_one(conn: Conn, work_dir: str):
    section("A trigger file that cannot be parsed is not 'no triggers' either")
    trigger_file = os.path.join(work_dir, "db", DB, CORRUPT_COLL, f"{CORRUPT_COLL}-triggers.json")
    check("the trigger file survived the restart", os.path.isfile(trigger_file), trigger_file)
    with open(trigger_file, "rb") as fp:
        original = fp.read()
    check("and still names the trigger installed before it", b"audit_corrupt" in original, f"got {original!r}")

    with open(trigger_file, "wb") as fp:
        fp.write(b'{"triggers":[{"name":"audit_corrupt","events":["NOT_AN_EVENT"]}]}')
    try:
        refused = conn.save_doc({"_id": "co1", "n": 1}, coll=CORRUPT_COLL)
        check_code("a write is refused rather than quietly firing no triggers", refused, "ERROR", "500-7")
        check("and nothing was written", conn.find("co1", coll=CORRUPT_COLL).get("status") != "OK")
        check_code("listing the triggers answers an error instead of dropping the connection", conn.send(
            {"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": CORRUPT_COLL}), "ERROR", "500-8")
        check_code("saving a trigger is refused",
                   conn.save_trigger("audit_second", ["CREATED"], "auditor", coll=CORRUPT_COLL), "ERROR", "500-29")
        check_code("deleting one is refused", conn.send(
            {"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": CORRUPT_COLL,
             "name": "audit_corrupt"}), "ERROR", "500-30")
        with open(trigger_file, "rb") as fp:
            while_corrupt = fp.read()
        check("neither DDL call erased the definitions it could not parse",
              b"NOT_AN_EVENT" in while_corrupt,
              f"the file was rewritten from a list that could not be read: {while_corrupt!r}")
    finally:
        with open(trigger_file, "wb") as fp:
            fp.write(original)

    listed = conn.send({"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": CORRUPT_COLL})
    check("the trigger is still installed once the file parses again",
          any(t.get("name") == "audit_corrupt" for t in (listed.get("triggers") or [])), f"got {listed}")
    check_status("and a write fires it again", conn.save_doc({"_id": "co2", "n": 2}, coll=CORRUPT_COLL), "OK")
    check("the audit row is back", await_doc(conn, "CREATED-co2").get("status") == "OK")


def test_an_unparseable_definition_is_answered_not_dropped(conn: Conn, work_dir: str):
    section("An unparseable definition is answered with an error, and the connection survives")
    procedures = os.path.join(work_dir, "db", DB, ".procedures")
    torn = os.path.join(procedures, "torn_definition.json")
    with open(torn, "wb") as fp:
        fp.write(b'{"name":"torn_definition","source"')
    try:
        check_code("LIST_PROCEDURES answers", conn.send({"type": "LIST_PROCEDURES", "databaseName": DB}),
                   "ERROR", "500-8")
        check_code("CALL_PROCEDURE answers", conn.call("torn_definition"), "ERROR", "500-8")
        check_status("and the same connection still serves the next request", conn.send(
            {"type": "LIST_DATABASES"}), "OK")
    finally:
        os.remove(torn)
    check_status("LIST_PROCEDURES works again once the file is gone", conn.send(
        {"type": "LIST_PROCEDURES", "databaseName": DB}), "OK")


def test_a_deleted_definition_stops_being_served(conn: Conn):
    section("A deleted procedure or schema stops applying immediately")
    coll = "definition_cache_coll"
    check_status("create the collection",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")

    check_status("save a procedure", conn.save_procedure("ghost_proc", "export default () => 1;"), "OK")
    check_status("the procedure runs", conn.call("ghost_proc"), "OK")
    check_status("delete the procedure",
                 conn.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "ghost_proc"}), "OK")
    after = conn.call("ghost_proc")
    check("a deleted procedure stops being callable at once", after.get("status") != "OK",
          f"got {after.get('status')}/{after.get('errorCode')}")

    schema = {"type": "object", "properties": {"n": {"type": "string"}}, "required": ["n"]}
    check_status("save a schema", conn.send({"type": "SAVE_SCHEMA", "databaseName": DB,
                                             "collectionName": coll, "schema": schema}), "OK")
    rejected = conn.save_doc({"_id": "s1", "n": 7}, coll=coll)
    check("the schema rejects a non-conforming write", rejected.get("status") != "OK",
          f"got {rejected.get('status')}/{rejected.get('errorCode')}")

    check_status("delete the schema", conn.send({"type": "DELETE_SCHEMA", "databaseName": DB,
                                                 "collectionName": coll}), "OK")
    check_status("a deleted schema stops rejecting writes at once",
                 conn.save_doc({"_id": "s2", "n": 7}, coll=coll), "OK")


def retries_scheduled(conn: Conn) -> int:
    return conn.send({"type": "GET_DATABASE_STATS"}).get("stats", {}).get("triggers", {}).get("retried", 0)


def arrange_a_pending_trigger_retry(conn: Conn):
    section("A trigger retry still waiting out its backoff")
    check_status("create a collection for it", conn.send(
        {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": RETRY_COLL}), "OK")
    check_status("install a procedure that always throws",
                 conn.save_procedure("shutdown_boom", "throw new Error('shutdown boom');"), "OK")
    check_status("point a trigger at it",
                 conn.save_trigger("shutdown_retry", ["CREATED", "UPDATED"], "shutdown_boom", coll=RETRY_COLL), "OK")

    before = retries_scheduled(conn)
    check_status("write a document so it fires", conn.save_doc({"_id": "sr1", "n": 1}, coll=RETRY_COLL), "OK")
    bu.wait_until(lambda: retries_scheduled(conn) > before, 30.0, 0.2)
    check("the failed run scheduled a retry", retries_scheduled(conn) > before,
          f"retried stayed at {before}, so nothing is pending and the timing below would prove nothing")
    pending = [run for run in trigger_runs(conn, "PENDING") if run.get("trigger") == "shutdown_retry"]
    check(f"and the run is still pending, {LONG_RETRY_BACKOFF_MS}ms from its next attempt",
          len(pending) == 1, f"runs={pending!r}")


def test_shutdown_does_not_wait_out_its_budget(proc):
    section("Shutdown with a retry still pending")
    started = time.time()
    bu.stop_server(proc)
    elapsed = time.time() - started
    check("the server stopped well inside its shutdown budget", elapsed < SHUTDOWN_CEILING_SECONDS,
          f"took {elapsed:.1f}s of a {SHUTDOWN_BUDGET_MS}ms budget with a retry pending; a drain that waits"
          " for a retry that cannot come back takes the whole budget and abandons the background queue")


def test_a_run_left_pending_standalone_is_replayed_once_clustered(conn: Conn):
    section("A run left pending before clusterEnabled was switched on")
    dead = await_dead_letters(conn, "shutdown_retry")
    check("startup recovery replayed it until it was dead-lettered", len(dead) == 1,
          f"runs={trigger_runs(conn)!r} - a run stamped with the standalone node id that the clustered node"
          " skips stays pending at its first attempt forever, and its write never gets its trigger")
    if dead:
        check("every configured attempt was spent", dead[0].get("attempts") == 3, f"entry={dead[0]!r}")


def test_concurrent_hooked_and_triggered_saves_fire_once_each(conn: Conn):
    section(f"{GROUPED_WRITERS} writers save concurrently through a before hook and an after trigger")
    for coll in (GROUPED_COLL, GROUPED_AUDIT):
        check_status(f"create {coll}", conn.send({"type": "CREATE_COLLECTION", "databaseName": DB,
                                                  "collectionName": coll}), "OK")
    check_status("install the pass-through hook procedure",
                 conn.save_procedure("grouped_hook", "export default (doc) => undefined;"), "OK")
    check_status("install the hook", conn.save_trigger("grouped_hook", ["CREATED", "UPDATED"], "grouped_hook",
                                                       coll=GROUPED_COLL, timing="before"), "OK")
    check_status("install the audit procedure", conn.save_procedure(
        "grouped_auditor", "import db from 'db'; import args from 'args';"
                           " db.save(db.name, '" + GROUPED_AUDIT + "', { doc: args.id, event: args.event });"
                           " return 1;"), "OK")
    check_status("install the after trigger", conn.save_trigger("grouped_audit", ["CREATED", "UPDATED"],
                                                                "grouped_auditor", coll=GROUPED_COLL), "OK")
    answered = []
    lock = threading.Lock()

    def writer(number: int):
        with admin_conn() as own:
            for i in range(GROUPED_SAVES_EACH):
                response = own.save_doc({"_id": f"w{number}-{i}", "n": i}, coll=GROUPED_COLL)
                with lock:
                    answered.append(response.get("status"))

    threads = [threading.Thread(target=writer, args=(number,)) for number in range(GROUPED_WRITERS)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(60)
    writes = GROUPED_WRITERS * GROUPED_SAVES_EACH
    check("every concurrent save was acknowledged", answered.count("OK") == writes,
          f"{answered.count('OK')} of {writes}")

    def audited():
        return conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": GROUPED_AUDIT,
                          "aggregationSteps": [{"type": "COUNT"}]})

    bu.wait_until(lambda: bu.count_of(audited()) == writes, timeout_s=60, interval_s=0.25)
    time.sleep(1.0)
    check("every write fired its trigger exactly once", bu.count_of(audited()) == writes,
          f"{bu.count_of(audited())} audit rows for {writes} writes")
    docs = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": GROUPED_AUDIT,
                      "aggregationSteps": [{"type": "DISTINCT", "fieldName": "doc"}, {"type": "COUNT"}]})
    check("one audit row per written document", bu.count_of(docs) == writes, f"got {docs}")
    bu.wait_until(lambda: not [run for run in trigger_runs(conn) if run.get("trigger") == "grouped_audit"],
                  timeout_s=30, interval_s=0.25)
    left = [run for run in trigger_runs(conn) if run.get("trigger") == "grouped_audit"]
    check("no trigger run is left behind", not left, f"left {left!r}")
    for name in ("grouped_audit", "grouped_hook"):
        conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": GROUPED_COLL, "name": name})


def trigger_runs(conn: Conn, status: str = None) -> list:
    payload = {"type": "LIST_TRIGGER_RUNS"}
    if status:
        payload["status"] = status
    return conn.send(payload).get("runs") or []


def dead_letters_for(conn: Conn, trigger_name: str) -> list:
    return [run for run in trigger_runs(conn, "DEAD") if run.get("trigger") == trigger_name]


def await_dead_letters(conn: Conn, trigger_name: str) -> list:
    return await_latest(lambda: dead_letters_for(conn, trigger_name), bool, 30.0, 0.3)


def arrange_failing_trigger(conn: Conn, trigger: str, procedure: str, procedure_label: str, source: str,
                            events: list, doc_id: str, coll=COLL):
    drop_hook(conn, "off_hook")
    check_status(procedure_label, conn.save_procedure(procedure, source), "OK")
    check_status("point a trigger at it", conn.save_trigger(trigger, events, procedure, coll=coll), "OK")
    check_status("write a document so it fires", conn.save_doc({"_id": doc_id, "n": 1}, coll=coll), "OK")


def remove_failing_trigger(conn: Conn, trigger: str, label: str, coll=COLL):
    check_status(label, conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": coll,
                                   "name": trigger}), "OK")
    check_status("restore the vetoing hook for the next phase",
                 install_hook(conn, "off_hook", "offhook",
                              "export default (doc) => { throw new Error('always refuses'); };",
                              ["CREATED", "UPDATED"]), "OK")


def test_retry_and_dead_letters(conn: Conn):
    section("Retry and dead letters")
    arrange_failing_trigger(conn, "retrying", "retry_boom", "install a trigger whose procedure always throws",
                            "throw new Error('retry boom');", ["CREATED", "UPDATED"], "retrydoc")

    dead = await_dead_letters(conn, "retrying")
    check("the exhausted run is dead-lettered", len(dead) == 1, f"runs={dead!r}")

    attempts = await_history_rows(conn, "retrying", 2)
    check("every attempt is recorded", len(attempts) >= 2, f"rows={attempts!r}")
    check("the attempts are numbered",
          sorted({row.get("attempt") for row in attempts}) == [1, 2], f"rows={attempts!r}")
    check("the last attempt is recorded as a dead letter",
          any(row.get("outcome") == "dead_letter" for row in attempts), f"rows={attempts!r}")

    if dead:
        entry = dead[0]
        check("the dead letter names the trigger", entry.get("trigger") == "retrying", f"entry={entry!r}")
        check("it carries the last error", "retry boom" in (entry.get("lastError") or ""), f"entry={entry!r}")
        check("it counts its attempts", entry.get("attempts") == 2, f"entry={entry!r}")
        check("it names the node holding it", bool(entry.get("node")), f"entry={entry!r}")

        check_status("fix the procedure",
                     conn.save_procedure("retry_boom",
                                         "import db from 'db';\n"
                                         "db.save(db.name, '" + AUDIT + "', { _id: 'replayed', ok: true });\n"
                                         "return 'fixed';"), "OK")
        check_status("replay the dead letter",
                     conn.send({"type": "RESOLVE_TRIGGER_RUN", "runId": entry.get("runId"),
                                "decision": "replay"}), "OK")
        check("the replayed run applied its effects",
              await_doc(conn, "replayed") is not None, "the replay never wrote its document")
        check("and its dead letter is gone", not dead_letters_for(conn, "retrying"),
              f"runs={dead_letters_for(conn, 'retrying')!r}")

    remove_failing_trigger(conn, "retrying", "remove the retrying trigger")


def test_same_named_triggers_in_one_transaction_dead_letter_their_own_run(conn: Conn):
    section("Same-named triggers on two collections keep their own run in one transaction")
    for coll in (TWIN_FAILING_COLL, TWIN_AUDITED_COLL):
        check_status(f"create {coll}", conn.send(
            {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    check_status("install a procedure that always throws",
                 conn.save_procedure("twin_boom", "throw new Error('twin boom');"), "OK")
    check_status("install a procedure that audits",
                 conn.save_procedure("twin_ok", "import db from 'db';"
                                     "db.save(db.name, '" + AUDIT + "', { _id: 'twin-ok' }); return 'ok';"), "OK")
    check_status("name the failing collection's trigger twin",
                 conn.save_trigger("twin", ["CREATED"], "twin_boom", coll=TWIN_FAILING_COLL), "OK")
    check_status("and the audited collection's trigger twin too",
                 conn.save_trigger("twin", ["CREATED"], "twin_ok", coll=TWIN_AUDITED_COLL), "OK")

    check_status("start a transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    for coll in (TWIN_FAILING_COLL, TWIN_AUDITED_COLL):
        check_status(f"write the same id into {coll}", conn.save_doc({"_id": "tw1", "n": 1}, coll=coll), "OK")
    check_status("commit", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")

    check("the audited collection's trigger ran", await_doc(conn, "twin-ok").get("status") == "OK")
    dead = await_dead_letters(conn, "twin")
    check("exactly one twin run is dead-lettered", len(dead) == 1, f"runs={dead!r}")
    check("and it is the failing collection's run",
          [run.get("collection") for run in dead] == [TWIN_FAILING_COLL], f"runs={dead!r}")
    for coll in (TWIN_FAILING_COLL, TWIN_AUDITED_COLL):
        conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": coll, "name": "twin"})


def test_a_host_call_with_too_few_arguments_dead_letters(conn: Conn):
    section("A trigger whose script calls db with too few arguments is dead-lettered")
    arrange_failing_trigger(conn, "arity", "arity_boom",
                            "install a trigger whose procedure omits db.save's database argument",
                            "import db from 'db';\n"
                            "db.save('" + AUDIT + "', { _id: 'never' });", ["CREATED", "UPDATED"], "aritydoc")

    dead = await_dead_letters(conn, "arity")
    check("the run is dead-lettered rather than left pending", len(dead) == 1, f"runs={dead!r}")
    if dead:
        entry = dead[0]
        check("it carries the TypeError that killed it", "TypeError" in (entry.get("lastError") or ""),
              f"entry={entry!r}")
        check("it counts its attempts", (entry.get("attempts") or 0) >= 1, f"entry={entry!r}")
        check_status("and it can be discarded like any other dead letter",
                     conn.send({"type": "RESOLVE_TRIGGER_RUN", "runId": entry.get("runId"),
                                "decision": "discard"}), "OK")
    check("no pending run is left behind",
          not [run for run in trigger_runs(conn, "PENDING") if run.get("trigger") == "arity"],
          f"runs={trigger_runs(conn, 'PENDING')!r}")

    remove_failing_trigger(conn, "arity", "remove the arity trigger")


def test_a_retried_trigger_sees_its_original_fired_at(conn: Conn):
    section("A retried trigger sees the firedAt of its first attempt")
    arrange_failing_trigger(conn, "fired_at", "fired_boom",
                            "install a procedure that reports its firedAt in the error it throws",
                            "import args from 'args';\n"
                            "throw new Error('fired at ' + args.firedAt);", ["CREATED", "UPDATED"], "fireddoc")

    rows = await_history_rows(conn, "fired_at", 2)
    check("both attempts are recorded", len(rows) >= 2, f"rows={rows!r}")
    messages = {row.get("errorMessage") for row in rows}
    check("every attempt saw the same firedAt", len(messages) == 1 and None not in messages,
          f"messages={messages!r}; a retry used to be stamped with the time it was retried")

    for entry in dead_letters_for(conn, "fired_at"):
        conn.send({"type": "RESOLVE_TRIGGER_RUN", "runId": entry.get("runId"), "decision": "discard"})
    remove_failing_trigger(conn, "fired_at", "remove the fired_at trigger")


def test_a_dead_letter_with_a_custom_shaped_error_stays_readable(conn: Conn):
    section("A dead letter whose error looks like a custom type stays readable")
    arrange_failing_trigger(conn, "shaped", "shaped_boom",
                            "install a trigger whose procedure throws an error named like a custom type",
                            "throw Object.assign(new Error('x)'), { name: '#abc(' });", ["CREATED", "UPDATED"],
                            "shapeddoc")

    dead = await_dead_letters(conn, "shaped")
    check("the run is dead-lettered and still listed", len(dead) == 1, f"runs={dead!r}")
    if dead:
        entry = dead[0]
        check("its error is stored as plain text", entry.get("lastError") == "\\#abc(: x)", f"entry={entry!r}")
        check_status("listing every run still works",
                     conn.send({"type": "LIST_TRIGGER_RUNS"}), "OK")
        check_status("listing pending runs still works",
                     conn.send({"type": "LIST_TRIGGER_RUNS", "status": "PENDING"}), "OK")
        check_status("and it can be discarded like any other dead letter",
                     conn.send({"type": "RESOLVE_TRIGGER_RUN", "runId": entry.get("runId"),
                                "decision": "discard"}), "OK")
    check("its dead letter is gone", not dead_letters_for(conn, "shaped"),
          f"runs={dead_letters_for(conn, 'shaped')!r}")

    remove_failing_trigger(conn, "shaped", "remove the shaped trigger")


def test_a_post_commit_error_applies_its_effects_once(conn: Conn):
    section("An error raised after the commit is not retried")
    arrange_failing_trigger(conn, "postcommitting", "post_commit",
                            "install a trigger that commits and then overruns its budget",
                            "import db from 'db';\n"
                            "const current = db.findById(db.name, '" + AUDIT + "', 'postcommit');\n"
                            "const runs = current === null ? 0 : current.runs;\n"
                            "db.save(db.name, '" + AUDIT + "', { _id: 'postcommit', runs: runs + 1 });\n"
                            "setInterval(() => { for (let i = 0; i < 20000; i++) { } }, 0);\n",
                            ["CREATED"], "postcommitdoc")
    applied = await_doc(conn, "postcommit")
    check("the committed effects landed", applied.get("status") == "OK", f"got {applied!r}")

    time.sleep(5.0)
    final = conn.find("postcommit", coll=AUDIT)
    runs = (final.get("object") or {}).get("runs")
    check("the effects were applied exactly once", runs == 1, f"runs={runs!r}")

    remove_failing_trigger(conn, "postcommitting", "remove the trigger")


def test_a_replayed_dead_letter_gets_a_full_budget(conn: Conn):
    section("A replayed dead letter starts its attempts over")
    arrange_failing_trigger(conn, "replaying", "replay_boom", "install a trigger whose procedure always throws",
                            "throw new Error('replay boom');", ["CREATED"], "replaydoc")

    dead = await_dead_letters(conn, "replaying")
    check("the run is dead-lettered after its two attempts", len(dead) == 1, f"runs={dead!r}")

    if dead:
        before = len(history_rows(conn, kind="TRIGGER", name="replaying"))
        check_status("replay it without repairing the procedure",
                     conn.send({"type": "RESOLVE_TRIGGER_RUN", "runId": dead[0].get("runId"),
                                "decision": "replay"}), "OK")

        rows = await_history_rows(conn, "replaying", before + 2)
        check("the replay is tried twice, not dead-lettered on its first failure",
              len(rows) >= before + 2, f"rows={len(rows)} before={before}")

    remove_failing_trigger(conn, "replaying", "remove the replaying trigger")


def test_trigger_run_operations(conn: Conn):
    section("LIST_TRIGGER_RUNS / RESOLVE_TRIGGER_RUN")
    check_status("listing works", conn.send({"type": "LIST_TRIGGER_RUNS"}), "OK")
    check_status("an explicit status filter is accepted",
                 conn.send({"type": "LIST_TRIGGER_RUNS", "status": "PENDING"}), "OK")
    check_code("an unknown status is rejected",
               conn.send({"type": "LIST_TRIGGER_RUNS", "status": "sideways"}), "ERROR", "400-1")

    unknown = conn.send({"type": "RESOLVE_TRIGGER_RUN", "runId": "no-such-run", "decision": "discard"})
    check_status("an unknown runId answers OK", unknown, "OK")
    check("… and reports it was not resolved", unknown.get("resolved") is False, f"got {unknown}")

    check_code("a blank runId is rejected",
               conn.send({"type": "RESOLVE_TRIGGER_RUN", "runId": "  ", "decision": "discard"}), "ERROR", "400-1")
    check_code("an unknown decision is rejected",
               conn.send({"type": "RESOLVE_TRIGGER_RUN", "runId": "abc", "decision": "maybe"}), "ERROR", "400-1")

    with user_conn(MANAGER) as manager:
        check_code("a script manager may not list trigger runs",
                   manager.send({"type": "LIST_TRIGGER_RUNS"}), "FORBIDDEN", "403-1")
        check_code("nor resolve one",
                   manager.send({"type": "RESOLVE_TRIGGER_RUN", "runId": "abc", "decision": "discard"}),
                   "FORBIDDEN", "403-1")


def test_stats(conn: Conn):
    section("Statistics")
    stats = conn.send({"type": "GET_DATABASE_STATS"})
    triggers = (stats.get("stats") or {}).get("triggers") or {}
    check("stats report the trigger counters", "fired" in triggers and "dropped" in triggers, f"got {stats}")
    check("triggers are reported as enabled", triggers.get("enabled") is True, f"got {triggers}")
    check("and at least one has fired", (triggers.get("fired") or 0) > 0, f"got {triggers}")
    check("retries and dead letters are counted",
          "retried" in triggers and "deadLettered" in triggers, f"got {triggers}")
    check("before hooks are counted too",
          (triggers.get("beforeReplaced") or 0) > 0 and (triggers.get("beforeRejected") or 0) > 0,
          f"got {triggers}")


def node_stats(conn: Conn) -> dict:
    return conn.send({"type": "GET_DATABASE_STATS"}).get("stats") or {}


def history_count(conn: Conn, name: str) -> int:
    response = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": "script_runs",
                          "aggregationSteps": [bu.filter_step("name", "EQUALS", name), {"type": "COUNT"}]})
    return bu.count_of(response) or 0


def test_a_burst_of_runs_lands_in_history(conn: Conn):
    section("A burst of procedure calls lands in history in batches")
    check_status("install the burst procedure", conn.save_procedure("burst", "return 1;"), "OK")
    recorded_before = node_stats(conn).get("scripts", {}).get("historyRecorded", 0)
    for i in range(500):
        conn.call("burst")
    landed = await_latest(lambda: history_count(conn, "burst"), lambda count: count >= 500, 30.0, 0.3)
    check("every one of the 500 runs has a history row", landed == 500, f"rows={landed}")
    scripts = node_stats(conn).get("scripts", {})
    check("historyRecorded grew by the burst", scripts.get("historyRecorded", 0) - recorded_before >= 500,
          f"scripts={scripts}")
    check("historyQueued is reported", "historyQueued" in scripts, f"scripts={scripts}")


def install_counting_trigger(conn: Conn, coll: str, audit: str, name: str):
    for target in (coll, audit):
        check_status(f"create {target}", conn.send(
            {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": target}), "OK")
    check_status(f"install the {name} procedure", conn.save_procedure(
        name, "import db from 'db'; import args from 'args';"
              " db.save(db.name, '" + audit + "', { doc: args.id, event: args.event }); return 1;"), "OK")
    check_status(f"install the {name} document trigger",
                 conn.save_trigger(name, ["CREATED", "UPDATED"], name, coll=coll, mode="document"), "OK")


def audit_rows(conn: Conn, audit: str) -> int:
    return bu.count_of(conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": audit,
                                  "aggregationSteps": [{"type": "COUNT"}]})) or 0


def audited_documents(conn: Conn, audit: str) -> int:
    return bu.count_of(conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": audit,
                                  "aggregationSteps": [{"type": "DISTINCT", "fieldName": "doc"},
                                                       {"type": "COUNT"}]})) or 0


def runs_of(conn: Conn, trigger: str) -> list:
    return [run for run in trigger_runs(conn) if run.get("trigger") == trigger]


def check_fired_exactly_once(conn: Conn, audit: str, trigger: str, expected: int, timeout_s: float = 90.0):
    bu.wait_until(lambda: audit_rows(conn, audit) >= expected and not runs_of(conn, trigger),
                  timeout_s=timeout_s, interval_s=0.25)
    time.sleep(1.0)
    check(f"{expected} runs fired, none twice", audit_rows(conn, audit) == expected,
          f"{audit_rows(conn, audit)} audit rows for {expected} documents")
    check("one run per document", audited_documents(conn, audit) == expected,
          f"{audited_documents(conn, audit)} distinct documents audited")
    check("LIST_TRIGGER_RUNS drained", not runs_of(conn, trigger), f"left {runs_of(conn, trigger)!r}")
    dead = [run for run in trigger_runs(conn, "DEAD") if run.get("trigger") == trigger]
    check("no run was dead-lettered", not dead, f"dead {dead!r}")


def bulk_of(prefix: str, count: int, coll: str) -> dict:
    return {"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll,
            "objects": [{"_id": f"{prefix}{i}", "n": i} for i in range(count)]}


def test_a_triggered_bulk_save_fires_one_run_per_document(conn: Conn):
    section(f"A BULK_SAVE of {BULK_TRIGGERED_DOCS} documents into a document-mode trigger")
    install_counting_trigger(conn, BULK_TRIGGERED_COLL, BULK_AUDIT, "bulk_auditor")
    started = time.monotonic()
    check_status("the bulk save is acknowledged",
                 conn.send(bulk_of("b", BULK_TRIGGERED_DOCS, BULK_TRIGGERED_COLL)), "OK")
    elapsed = time.monotonic() - started
    check("staging its runs did not cost one forced write per document", elapsed < 10.0, f"took {elapsed:.1f}s")
    check_fired_exactly_once(conn, BULK_AUDIT, "bulk_auditor", BULK_TRIGGERED_DOCS)


def test_triggers_drain_while_their_definer_runs_procedures(conn: Conn):
    section(f"Triggers drain while their definer runs {BUSY_CALLERS} procedures concurrently")
    install_counting_trigger(conn, BUSY_TRIGGERED_COLL, BUSY_AUDIT, "busy_auditor")
    check_status("install the busy procedure", conn.save_procedure(
        "busy_loop", "let s = 0; for (let i = 0; i < 50000; i++) { s += i; } return s;"), "OK")
    stop = threading.Event()
    calls = []
    lock = threading.Lock()

    def caller():
        with admin_conn() as own:
            while not stop.is_set():
                status = own.call("busy_loop").get("status")
                with lock:
                    calls.append(status)

    callers = [threading.Thread(target=caller) for _ in range(BUSY_CALLERS)]
    for thread in callers:
        thread.start()
    try:
        time.sleep(0.5)
        check_status("the bulk save is acknowledged",
                     conn.send(bulk_of("busy", BUSY_TRIGGERED_DOCS, BUSY_TRIGGERED_COLL)), "OK")
        check_fired_exactly_once(conn, BUSY_AUDIT, "busy_auditor", BUSY_TRIGGERED_DOCS)
    finally:
        stop.set()
        for thread in callers:
            thread.join(60)
    check("the definer's procedures kept running meanwhile", calls.count("OK") > 0, f"{len(calls)} calls")


def arrange_a_triggered_commit_cut_by_a_kill(conn: Conn):
    section(f"A transaction commit with {TX_TRIGGERED_OPS} triggered ops, then a kill mid-drain")
    install_counting_trigger(conn, TX_TRIGGERED_COLL, TX_AUDIT, "tx_auditor")
    check_status("start the transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    for i in range(TX_TRIGGERED_OPS):
        response = conn.save_doc({"_id": f"t{i}", "n": i}, coll=TX_TRIGGERED_COLL)
        if response.get("status") != "OK":
            check_status(f"buffer save t{i}", response, "OK")
            break
    check_status("the commit is acknowledged", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    print(f"  {len(runs_of(conn, 'tx_auditor'))} of {TX_TRIGGERED_OPS} runs still pending at the kill")


def test_a_triggered_commit_fires_exactly_once_across_a_restart(conn: Conn):
    section(f"The {TX_TRIGGERED_OPS} runs of the killed commit each fire exactly once")
    check_fired_exactly_once(conn, TX_AUDIT, "tx_auditor", TX_TRIGGERED_OPS)


def test_a_read_only_trigger_consumes_its_run(conn: Conn):
    section("A trigger that writes nothing consumes its run")
    pending_before = node_stats(conn).get("triggers", {}).get("pendingRuns")
    check_status("install a read-only procedure", conn.save_procedure("only_reads", "return 'looked';"), "OK")
    check_status("install a read-only trigger", conn.save_trigger("read_only_watch", ["CREATED"], "only_reads"), "OK")
    check_status("write a document so it fires", conn.save_doc({"_id": "read-only-fired", "n": 1}), "OK")
    rows = await_history(conn, kind="TRIGGER", name="read_only_watch")
    check("the read-only run is recorded ok", bool(rows) and rows[0].get("outcome") == "ok", f"rows={rows!r}")
    pending_after = await_latest(lambda: node_stats(conn).get("triggers", {}).get("pendingRuns"),
                                 lambda count: count == pending_before, 10.0, 0.2)
    check("pendingRuns is back to its baseline", pending_after == pending_before,
          f"before={pending_before} after={pending_after}")
    check_status("remove the read-only trigger", conn.send({"type": "DELETE_TRIGGER", "databaseName": DB,
                                                            "collectionName": COLL, "name": "read_only_watch"}),
                 "OK")


def test_pending_runs_count_the_dead_letters(conn: Conn):
    section("pendingRuns counts the trigger-run records without reading them")
    listed = len(trigger_runs(conn))
    pending = node_stats(conn).get("triggers", {}).get("pendingRuns")
    check("pendingRuns equals the listed runs", pending == listed, f"pendingRuns={pending} listed={listed}")
    background = node_stats(conn).get("background", {})
    check("the background block reports pending and maintenance",
          "pending" in background and "maintenance" in background, f"background={background}")


def test_the_history_size_cap_keeps_the_newest_rows(conn: Conn):
    section("scriptRunHistoryMaxRows keeps the newest rows")
    check_status("install the capped procedure", conn.save_procedure("capped", "return 2;"), "OK")
    for i in range(80):
        conn.call("capped")
    await_latest(lambda: history_count(conn, "capped"), lambda count: count >= 80, 30.0, 0.3)
    total = await_latest(lambda: len(history_rows(conn)), lambda count: count <= HISTORY_CAP, 15.0, 0.5)
    check(f"the sweep trims history to {HISTORY_CAP} rows", total <= HISTORY_CAP, f"rows={total}")
    newest = history_rows(conn)
    check("the rows kept are the newest", bool(newest) and newest[0].get("name") == "capped", f"rows={newest[:1]!r}")


def test_retention_empties_history(conn: Conn):
    section("A short retention empties history")
    remaining = await_latest(lambda: len(history_rows(conn)), lambda count: count == 0, 20.0, 0.5)
    check("every stale row is swept", remaining == 0, f"rows={remaining}")


def test_triggers_per_collection_are_capped(conn: Conn):
    section("A collection holds at most triggerMaxPerCollection triggers")
    check_status("create a collection to fill",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": CAP_COLL}), "OK")
    check_status("store a no-op procedure", conn.save_procedure("cap_noop", "return 1;"), "OK")
    saved = [conn.save_trigger(f"cap{i}", ["CREATED"], "cap_noop", coll=CAP_COLL).get("status")
             for i in range(TRIGGER_MAX_PER_COLLECTION)]
    check("every trigger up to the cap is saved", saved == ["OK"] * TRIGGER_MAX_PER_COLLECTION, f"statuses {saved}")
    check_code("one more -> 400-32", conn.save_trigger("capOver", ["CREATED"], "cap_noop", coll=CAP_COLL),
               "ERROR", "400-32")
    check_status("replacing an existing trigger is never refused",
                 conn.save_trigger("cap0", ["UPDATED"], "cap_noop", coll=CAP_COLL), "OK")
    check_status("drop the filled collection",
                 conn.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": CAP_COLL}), "OK")


def trigger_entry(conn: Conn, coll: str, name: str) -> dict:
    listed = conn.send({"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": coll})
    return next((entry for entry in listed.get("triggers") or [] if entry.get("name") == name), {})


def set_revoked_definer_grants(admin: Conn, source_access: bool) -> dict:
    database_permissions = {DB: "READ_WRITE"} if source_access else {}
    return admin.send({"type": "CHANGE_PERMISSIONS", "username": REVOKED_DEFINER,
                       "databasePermissions": database_permissions,
                       "collectionPermissions": {f"{DB}|{AUDIT}": "READ_WRITE"},
                       "scriptPermissions": {DB: "MANAGE"}})


def test_a_trigger_whose_definer_lost_collection_read_is_disabled():
    section("A trigger whose definer loses READ on its collection is skipped and disabled")
    with admin_conn() as admin:
        check_status("create the source collection",
                     admin.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": REVOKE_COLL}),
                     "OK")
        check_status("create a definer with MANAGE", admin.send({
            "type": "CREATE_USER", "username": REVOKED_DEFINER, "password": USER_PASSWORD, "admin": False,
            "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "MANAGE"}}), "OK")
        with user_conn(REVOKED_DEFINER) as definer:
            check_status("the definer stores an auditing procedure", definer.save_procedure(
                "revocable_audit", "import db from 'db'; import args from 'args';"
                " db.save(db.name, '" + AUDIT + "', { _id: 'revocable-' + args.id }); return 1;"), "OK")
            check_status("and binds a trigger to the source collection",
                         definer.save_trigger("revocable", ["CREATED"], "revocable_audit", coll=REVOKE_COLL), "OK")
        check_status("a write fires it", admin.save_doc({"_id": "r1"}, coll=REVOKE_COLL), "OK")
        check("while the definer holds READ the trigger runs", await_doc(admin, "revocable-r1").get("status") == "OK",
              "the audit row never appeared")
        check_status("narrow the definer to the audit collection only", set_revoked_definer_grants(admin, False),
                     "OK")
        check("the grant change itself disables the trigger, before any write fires it",
              trigger_entry(admin, REVOKE_COLL, "revocable").get("enabled") is False,
              f"got {trigger_entry(admin, REVOKE_COLL, 'revocable')}")
        check_status("another write", admin.save_doc({"_id": "r2"}, coll=REVOKE_COLL), "OK")
        disabled = bu.wait_until(lambda: trigger_entry(admin, REVOKE_COLL, "revocable").get("enabled") is False,
                                 10.0, 0.2)
        stored = trigger_entry(admin, REVOKE_COLL, "revocable")
        check("the trigger is disabled", disabled, f"got {stored}")
        check("with the reason recorded", f"READ on collection '{REVOKE_COLL}'" in str(stored.get("disabledReason")),
              f"got {stored}")
        check("the definer never received the document",
              await_absent(admin, "revocable-r2").get("status") == "NOT_FOUND", "the audit row for r2 was written")
        check_status("give the grant back", set_revoked_definer_grants(admin, True), "OK")
        check_status("a write after the re-grant", admin.save_doc({"_id": "r3"}, coll=REVOKE_COLL), "OK")
        check("re-granting alone does not revive the trigger",
              await_absent(admin, "revocable-r3").get("status") == "NOT_FOUND", "the audit row for r3 was written")
        check_status("the admin re-saves the trigger",
                     admin.save_trigger("revocable", ["CREATED"], "revocable_audit", coll=REVOKE_COLL), "OK")
        check("which re-enables it and clears the reason",
              trigger_entry(admin, REVOKE_COLL, "revocable").get("enabled") is True
              and "disabledReason" not in trigger_entry(admin, REVOKE_COLL, "revocable"),
              f"got {trigger_entry(admin, REVOKE_COLL, 'revocable')}")
        check_status("a write after the re-save", admin.save_doc({"_id": "r4"}, coll=REVOKE_COLL), "OK")
        check("fires again", await_doc(admin, "revocable-r4").get("status") == "OK", "the audit row never appeared")
        admin.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": REVOKE_COLL})
        admin.send({"type": "DELETE_USER", "username": REVOKED_DEFINER})


def occupy_both_trigger_workers(conn: Conn, tag: str):
    for n in (1, 2):
        check_status(f"occupy trigger worker {n}", conn.save_doc({"_id": f"{tag}-{n}"}, coll=FIRST_RUN_BLOCK_COLL),
                     "OK")
    time.sleep(0.3)


def test_a_first_run_delivers_the_document_as_it_is_when_it_runs(conn: Conn):
    section("A trigger's first run reads the document when it runs, not when it fired")
    for coll in (FIRST_RUN_COLL, FIRST_RUN_BLOCK_COLL):
        check_status(f"create {coll}",
                     conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    check_status("a procedure that holds its trigger worker", conn.save_procedure(
        "first_run_blocker", "return new Promise(done => setTimeout(() => done(1), 2500));"), "OK")
    check_status("a procedure that records the value it was handed", conn.save_procedure(
        "first_run_audit", "import db from 'db'; import args from 'args';"
        " db.save(db.name, '" + AUDIT + "', { _id: 'first-' + args.id + '-' + args.document.v }); return 1;"), "OK")
    check_status("the blocking trigger",
                 conn.save_trigger("first_run_blocker", ["CREATED"], "first_run_blocker", coll=FIRST_RUN_BLOCK_COLL),
                 "OK")
    check_status("the recording trigger", conn.save_trigger(
        "first_run_audit", ["CREATED", "UPDATED"], "first_run_audit", coll=FIRST_RUN_COLL), "OK")

    occupy_both_trigger_workers(conn, "coalesce")
    check_status("save v1", conn.save_doc({"_id": "q", "v": 1}, coll=FIRST_RUN_COLL), "OK")
    check_status("save v2 while v1's run still waits", conn.save_doc({"_id": "q", "v": 2}, coll=FIRST_RUN_COLL),
                 "OK")
    check("the runs deliver the latest version", await_doc(conn, "first-q-2").get("status") == "OK",
          "no run saw v2")
    runs = await_history_rows(conn, "first_run_audit", 2)
    check("both writes still get their own run", len(runs) >= 2, f"rows={runs!r}")
    check("no run saw the superseded version", conn.find("first-q-1", coll=AUDIT).get("status") == "NOT_FOUND",
          "a queued run delivered the value it fired with")

    occupy_both_trigger_workers(conn, "gone")
    check_status("save a document", conn.save_doc({"_id": "gone", "v": 1}, coll=FIRST_RUN_COLL), "OK")
    check_status("delete it before its run starts",
                 conn.send({"type": "DELETE", "databaseName": DB, "collectionName": FIRST_RUN_COLL, "_id": "gone"}),
                 "OK")
    skipped = await_latest(lambda: [row for row in history_rows(conn, kind="TRIGGER", name="first_run_audit")
                                    if row.get("outcome") == "skipped"], bool, 15.0, 0.2)
    check("the run for a document that no longer exists is skipped",
          any("no longer exist" in str(row.get("errorMessage")) for row in skipped), f"rows={skipped!r}")
    check("and nothing was delivered", await_absent(conn, "first-gone-1").get("status") == "NOT_FOUND",
          "the deleted document reached the trigger")


def test_dropping_a_collection_discards_its_dead_letters(conn: Conn):
    section("Dropping a collection discards its pending and dead-lettered trigger runs")
    check_status("create a collection whose runs will die",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": DROPPED_RUNS_COLL}),
                 "OK")
    arrange_failing_trigger(conn, "dropping", "dropping_boom", "install a trigger whose procedure always throws",
                            "throw new Error('dropping boom');", ["CREATED"], "dropped-doc", coll=DROPPED_RUNS_COLL)
    check("the run is dead-lettered", len(await_dead_letters(conn, "dropping")) == 1,
          f"runs={dead_letters_for(conn, 'dropping')!r}")
    check_status("drop the collection",
                 conn.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": DROPPED_RUNS_COLL}), "OK")
    check("its dead letter is gone with it", not dead_letters_for(conn, "dropping"),
          f"runs={dead_letters_for(conn, 'dropping')!r}")
    check_status("re-create a collection of the same name",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": DROPPED_RUNS_COLL}),
                 "OK")
    check("which inherits no run of the dropped one", not dead_letters_for(conn, "dropping"),
          f"runs={dead_letters_for(conn, 'dropping')!r}")
    remove_failing_trigger(conn, "dropping", "remove the dropping trigger", coll=DROPPED_RUNS_COLL)


def test_cascade_deletes(conn: Conn, work_dir: str):
    section("Cascade deletes")
    db_root = os.path.join(work_dir, "db")
    check_status("create a throwaway collection",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": "throwaway"}), "OK")
    check_status("install a trigger on it",
                 conn.save_trigger("temp", ["CREATED"], "auditor", coll="throwaway"), "OK")
    trigger_file = os.path.join(db_root, DB, "throwaway", "throwaway-triggers.json")
    check("the trigger file exists", os.path.isfile(trigger_file), trigger_file)
    check_status("drop the collection",
                 conn.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": "throwaway"}), "OK")
    check("dropping the collection took the trigger file", not os.path.exists(trigger_file), trigger_file)

    check_status("create a throwaway database",
                 conn.send({"type": "CREATE_DATABASE", "databaseName": "proc_drop_db"}), "OK")
    check_status("install a procedure in it",
                 conn.save_procedure("doomed", "return 1;", db="proc_drop_db"), "OK")
    procedures = os.path.join(db_root, "proc_drop_db", ".procedures")
    check("its procedures folder exists", os.path.isdir(procedures), procedures)
    check_status("drop the database",
                 conn.send({"type": "DROP_DATABASE", "databaseName": "proc_drop_db"}), "OK")
    check("dropping the database took the procedures folder", not os.path.exists(procedures), procedures)
    check_status("re-create the database",
                 conn.send({"type": "CREATE_DATABASE", "databaseName": "proc_drop_db"}), "OK")
    check_code("the old procedure is gone", conn.call("doomed", db="proc_drop_db"), "NOT_FOUND", "404-8")
    check_status("a new procedure of the same name",
                 conn.save_procedure("doomed", "return 99;", db="proc_drop_db"), "OK")
    check_result("runs the new body", conn.call("doomed", db="proc_drop_db"), 99)


def test_switches_off(conn: Conn):
    section("Master switches off")
    check_code("scriptsEnabled=false refuses a call", conn.call("greet"), "FORBIDDEN", "403-2")
    check_code("and a save", conn.save_procedure("valid_name", "return 1;"), "FORBIDDEN", "403-2")
    check_status("trigger DDL still works", conn.send(
        {"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": COLL}), "OK")
    check_status("a write still succeeds", conn.save_doc({"_id": "quiet", "n": 1}), "OK")
    quiet = await_absent(conn, "UPDATED-quiet")
    check("triggersEnabled=false fires nothing", quiet.get("status") != "OK", f"got {quiet}")
    check_status("a write guarded by a vetoing before hook still succeeds",
                 conn.save_doc({"_id": "off1", "qty": 1}), "OK")
    check("the write landed", conn.find("off1").get("status") == "OK")
    check_code("TEST_TRIGGER is refused with scripts off",
               conn.test_trigger("off_hook", "CREATED", {"_id": "x"}), "FORBIDDEN", "403-2")
    stats = conn.send({"type": "GET_DATABASE_STATS"})
    triggers = (stats.get("stats") or {}).get("triggers") or {}
    check("stats report triggers as disabled", triggers.get("enabled") is False, f"got {triggers}")
    check("stats report the before-hook counters",
          "beforeApplied" in triggers and "beforeRejected" in triggers, f"got {triggers}")


def main():
    bu.banner("Stored procedures & triggers test suite")

    bu.require_jar()
    if bu.port_open():
        print(f"port {PORT} is already in use", file=sys.stderr)
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-proc-test-")
    log_path = os.path.join(work_dir, "server.out")
    proc = None
    try:
        print(f"work dir: {work_dir}")
        write_config(work_dir, scripts_enabled=True, triggers_enabled=True)
        proc = bu.start_server(work_dir, log_path)

        with admin_conn() as conn:
            setup_data(conn)
            test_procedure_lifecycle(conn)
            test_procedure_validation(conn)
            test_procedure_name_case_collision(conn)
        test_permissions()
        test_procedure_source_requires_manage()
        with admin_conn() as conn:
            test_triggers(conn)
            test_a_transaction_reports_only_its_net_effect(conn)
            install_the_trigger_whose_file_will_break(conn)
            test_trigger_validation(conn)
            test_before_hooks(conn)
            test_before_hook_preserves_custom_spelling(conn)
            test_a_before_hook_getter_is_stored_like_db_save_stores_it(conn)
            test_before_hook_veto(conn)
            test_before_hook_contract(conn)
            test_before_hook_sees_the_same_id_in_and_out_of_a_transaction(conn)
            test_before_hook_schema_recheck(conn)
            test_before_hook_replacement_depth(conn)
            test_before_hook_on_delete(conn)
            test_before_hook_bulk_save(conn)
            test_before_hook_bulk_save_in_a_transaction(conn)
            test_before_hook_chaining(conn)
            test_before_hook_sandbox(conn)
            test_before_hook_no_db(conn)
            test_a_before_hook_does_not_change_the_scripts_suppressed_error(conn)
            test_before_hook_in_transaction(conn)
            test_before_hook_that_shrinks_an_oversized_document(conn)
            test_before_hook_validation(conn)
            test_concurrent_hooked_and_triggered_saves_fire_once_each(conn)
            test_dry_run(conn)
            test_result_cap(conn)
        test_definer_rights()
        test_a_procedure_bound_by_another_definer_is_protected()
        test_a_client_cannot_forge_a_definer()
        test_a_manager_cannot_change_a_trigger_defined_by_someone_else()
        test_a_trigger_whose_definer_lost_collection_read_is_disabled()
        with admin_conn() as conn:
            test_procedure_imports(conn)
            test_trigger_imports(conn)
            test_trigger_import_failure(conn)
            test_trigger_failure_logs_a_stack(conn, log_path)
            test_run_history(conn)
            test_run_history_failures(conn)
            test_trigger_run_operations(conn)
            test_batch_mode(conn)
            test_batch_deleted_trigger_fires_once_per_transaction(conn)
            test_cascade(conn)
            test_triggers_per_collection_are_capped(conn)
            test_a_first_run_delivers_the_document_as_it_is_when_it_runs(conn)
            test_stats(conn)
            test_a_burst_of_runs_lands_in_history(conn)
            test_a_triggered_bulk_save_fires_one_run_per_document(conn)
            test_triggers_drain_while_their_definer_runs_procedures(conn)
            test_a_read_only_trigger_consumes_its_run(conn)
            test_storage_placement(work_dir)
            test_cascade_deletes(conn, work_dir)
            test_before_hook_definer_is_not_enforced(conn)

        bu.stop_server(proc)
        proc = None
        write_config(work_dir, scripts_enabled=True, triggers_enabled=True, max_attempts=2, retry_backoff_ms=200)
        proc = bu.start_server(work_dir, log_path)

        interrupted = admin_conn()
        arrange_a_commit_interrupted_past_its_commit_point(interrupted, work_dir)
        print("\n  Killing the server without a drain ...")
        proc.kill()
        proc.wait(timeout=30)
        proc = None
        interrupted.close()
        os.chmod(fenced_collection_folder(work_dir), 0o755)
        check("the durable commit marker outlived the kill", bu.local_commit_markers(work_dir) == 1,
              "with no marker there is nothing for the restart to finish, so what follows would be vacuous")
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_a_recovered_commit_fires_its_triggers(conn)
            test_a_custom_shaped_history_row_survives_the_restart(conn)
            test_an_unreadable_trigger_file_is_not_an_empty_one(conn, work_dir)
            test_a_corrupt_trigger_file_is_not_an_empty_one(conn, work_dir)
            test_an_unparseable_definition_is_answered_not_dropped(conn, work_dir)
            test_a_deleted_definition_stops_being_served(conn)
            test_retry_and_dead_letters(conn)
            test_dropping_a_collection_discards_its_dead_letters(conn)
            test_a_replayed_dead_letter_gets_a_full_budget(conn)
            test_same_named_triggers_in_one_transaction_dead_letter_their_own_run(conn)
            test_a_host_call_with_too_few_arguments_dead_letters(conn)
            test_a_retried_trigger_sees_its_original_fired_at(conn)
            test_a_dead_letter_with_a_custom_shaped_error_stays_readable(conn)
            test_a_post_commit_error_applies_its_effects_once(conn)
            test_pending_runs_count_the_dead_letters(conn)
            arrange_a_triggered_commit_cut_by_a_kill(conn)
        print("\n  Killing the server in the middle of the trigger drain ...")
        proc.kill()
        proc.wait(timeout=30)
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_a_triggered_commit_fires_exactly_once_across_a_restart(conn)

        bu.stop_server(proc)
        proc = None
        write_config(work_dir, scripts_enabled=True, triggers_enabled=True, max_attempts=3,
                     retry_backoff_ms=LONG_RETRY_BACKOFF_MS, retry_max_backoff_ms=LONG_RETRY_BACKOFF_MS)
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            arrange_a_pending_trigger_retry(conn)
        test_shutdown_does_not_wait_out_its_budget(proc)
        proc = None

        write_config(work_dir, scripts_enabled=True, triggers_enabled=True, max_attempts=3,
                     retry_backoff_ms=200, clustered=True)
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_a_run_left_pending_standalone_is_replayed_once_clustered(conn)
        bu.stop_server(proc)
        proc = None

        write_config(work_dir, scripts_enabled=True, triggers_enabled=True,
                     scriptRunHistoryMaxRows=HISTORY_CAP, scriptRunHistorySweepMs=1000)
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_the_history_size_cap_keeps_the_newest_rows(conn)
        bu.stop_server(proc)
        proc = None

        write_config(work_dir, scripts_enabled=True, triggers_enabled=True, history_retention_ms=1000,
                     scriptRunHistorySweepMs=1000)
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_retention_empties_history(conn)
        bu.stop_server(proc)
        proc = None

        write_config(work_dir, scripts_enabled=False, triggers_enabled=False)
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_switches_off(conn)
    except Exception as exc:  # noqa: BLE001 - the suite reports rather than propagates
        print(f"\nunexpected failure: {exc}", file=sys.stderr)
        bu.dump_log(log_path)
        bu.record_failure()
    finally:
        bu.stop_server(proc)

    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
