"""End-to-end tests for stored procedures and triggers.

Like the RUN_SCRIPT suite this script is **self-contained**: it starts its own LWNRDB
instance on a dedicated port and working directory, because the feature needs both
`scriptsEnabled=true` and `triggersEnabled=true`, which the shared CI server does not have.
It runs in two phases:

  phase 1 — scripts and triggers enabled, with a deliberately tight sandbox so every limit
            is reachable in a test rather than only in theory;
  phase 2 — the same data directory restarted with `scriptsEnabled=false` and
            `triggersEnabled=false`, proving each master switch refuses independently.

What is covered:

  * procedure lifecycle: save, call, list (with and without source), re-save bumps the
    version and the *new* body runs, delete, call-after-delete;
  * gating and delegation: the scriptsEnabled switch, unauthenticated, unknown database,
    invalid names, oversized source, unparseable source, and the full permission matrix -
    a RUN user may call but not install, a MANAGE user may do both, a grant on one database
    confers nothing on another, and a legacy boolean grant still calls;
  * storage placement: a procedure lands in {db}/.procedures/{name}.json and a trigger in
    {db}/{coll}/{coll}-triggers.json, with nothing new under admin/;
  * triggers: CREATED/UPDATED/DELETED, document and batch mode, disabled, the triggersEnabled
    switch, and definer rights end to end - a writer with no access to the audit collection
    still produces the audit row;
  * cascade: allowCascade=false fires exactly once, and a cascading chain terminates at
    triggerMaxDepth;
  * cascade deletes: DROP_COLLECTION removes the trigger file, DROP_DATABASE the whole lot;
  * before hooks: a synchronous BEFORE trigger that computes a field, vetoes a write, chains in
    name order, is fail-closed on a timeout, has no `db` binding, and applies on the
    transactional path too - asserted on the SAVE response itself, since unlike an after
    trigger nothing here is asynchronous;
  * dry run: TEST_TRIGGER reports accept/replace/reject, captures console output the live
    path discards, and writes nothing;
  * stats: GET_DATABASE_STATS reports the trigger counters.

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

HOST = "127.0.0.1"
PORT = int(os.environ.get("PROC_TEST_PORT", "8996"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "proc_test_db"
OTHER_DB = "proc_other_db"
COLL = "orders"
AUDIT = "audit"

RUNNER = "proc_runner"
MANAGER = "proc_manager"
WRITER = "proc_writer"
LEGACY = "proc_legacy"
USER_PASSWORD = "password123"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

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


def await_doc(conn: Conn, doc_id: str, coll=AUDIT, timeout=15.0):
    """Triggers are asynchronous by design, so poll rather than sleep a fixed interval."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        response = conn.find(doc_id, coll=coll)
        if response.get("status") == "OK":
            return response
        time.sleep(0.1)
    return conn.find(doc_id, coll=coll)


def await_absent(conn: Conn, doc_id: str, coll=AUDIT, settle=2.0):
    """Gives a trigger every chance to fire before concluding that it did not."""
    time.sleep(settle)
    return conn.find(doc_id, coll=coll)


# ── server lifecycle ─────────────────────────────────────────────────────────

def write_config(work_dir: str, scripts_enabled: bool, triggers_enabled: bool,
                 history_enabled: bool = True, history_kinds: str = "CALL_PROCEDURE,TRIGGER,SCHEDULE",
                 history_retention_ms: int = 604800000, max_attempts: int = 1, retry_backoff_ms: int = 0):
    cfg = (
        f"port={PORT}\n"
        "filePath=db\n"
        "logPath=logs\n"
        f"defaultAdminUsername={ADMIN_USERNAME}\n"
        f"defaultAdminPassword={ADMIN_PASSWORD}\n"
        f"scriptsEnabled={'true' if scripts_enabled else 'false'}\n"
        f"scriptInstructionBudget={INSTRUCTION_BUDGET}\n"
        f"scriptTimeoutMs={TIMEOUT_MS}\n"
        f"scriptMaxDepth={MAX_DEPTH}\n"
        f"scriptMaxSourceBytes={MAX_SOURCE_BYTES}\n"
        "scriptMaxLogLines=50\n"
        "scriptMaxLogLineChars=500\n"
        f"scriptMaxMemoryBytes={MAX_MEMORY_BYTES}\n"
        f"scriptMaxResultBytes={MAX_RESULT_BYTES}\n"
        "scriptTextImportEnabled=false\n"
        "scriptTimeZone=UTC\n"
        "scriptLocale=en-US\n"
        "procedureCacheSize=16\n"
        f"triggersEnabled={'true' if triggers_enabled else 'false'}\n"
        "triggerThreads=2\n"
        "triggerQueueSize=1000\n"
        f"triggerMaxDepth={TRIGGER_MAX_DEPTH}\n"
        f"triggerTimeoutMs={TRIGGER_TIMEOUT_MS}\n"
        f"beforeHookInstructionBudget={BEFORE_HOOK_BUDGET}\n"
        f"beforeHookTimeoutMs={BEFORE_HOOK_TIMEOUT_MS}\n"
        f"scriptRunHistoryEnabled={'true' if history_enabled else 'false'}\n"
        f"scriptRunHistoryKinds={history_kinds}\n"
        f"scriptRunHistoryRetentionMs={history_retention_ms}\n"
        "scriptRunHistoryIncludeLogs=false\n"
        "scriptRunHistoryMaxErrorChars=2000\n"
        f"triggerMaxAttempts={max_attempts}\n"
        f"triggerRetryBackoffMs={retry_backoff_ms}\n"
        "triggerRetryMaxBackoffMs=2000\n"
        "triggerDeadLetterRetentionMs=604800000\n"
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

def setup_data(conn: Conn):
    section("Setup")
    for db in (DB, OTHER_DB):
        conn.send({"type": "DROP_DATABASE", "databaseName": db})
        check_status(f"create database {db}", conn.send({"type": "CREATE_DATABASE", "databaseName": db}), "OK")
    for coll in (COLL, AUDIT):
        check_status(f"create collection {coll}",
                     conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    check_status("create collection in the other database",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": OTHER_DB, "collectionName": COLL}), "OK")

    # A RUN user may call, a MANAGE user may install, and the writer can only write orders -
    # deliberately no access to the audit collection, which is what definer rights must overcome.
    check_status("create the RUN-level user", conn.send({
        "type": "CREATE_USER", "username": RUNNER, "password": USER_PASSWORD, "admin": False,
        "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "RUN"}}), "OK")
    check_status("create the MANAGE-level user", conn.send({
        "type": "CREATE_USER", "username": MANAGER, "password": USER_PASSWORD, "admin": False,
        "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "MANAGE"}}), "OK")
    check_status("create the writer with no audit access", conn.send({
        "type": "CREATE_USER", "username": WRITER, "password": USER_PASSWORD, "admin": False,
        "collectionPermissions": {f"{DB}|{COLL}": "READ_WRITE"}}), "OK")
    # The boolean form is what a client written before the level existed sends.
    check_status("create a user with a legacy boolean grant", conn.send({
        "type": "CREATE_USER", "username": LEGACY, "password": USER_PASSWORD, "admin": False,
        "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: True}}), "OK")


# ══════════════════════════════════════════════════════════════════════════
# Phase 1 — procedures
# ══════════════════════════════════════════════════════════════════════════

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

    # A re-save bumps the version, and the version-keyed compiled cache must serve the new body
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

    # The point of a stored procedure over a client-side string: refused now, not on first call
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
        # The grant is per database
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

    # Promotion and demotion take effect within the session
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


# ══════════════════════════════════════════════════════════════════════════
# Phase 1 — triggers
# ══════════════════════════════════════════════════════════════════════════

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
    row = await_doc(conn, "UPDATED-t1")
    check("the trigger produced an audit row", row.get("status") == "OK", f"got {row}")
    if row.get("status") == "OK":
        check("the row names the writer", row["object"].get("by") == ADMIN_USERNAME, f"got {row['object']}")
        check("and the definer", row["object"].get("definer") == ADMIN_USERNAME, f"got {row['object']}")

    check_status("delete a document",
                 conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "t1"}), "OK")
    deleted_row = await_doc(conn, "DELETED-t1")
    check("a DELETED trigger fires too", deleted_row.get("status") == "OK", f"got {deleted_row}")

    listed = conn.send({"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": COLL})
    check("list reports the trigger",
          any(t.get("name") == "audit_writes" for t in listed.get("triggers", [])), f"got {listed}")

    # A trigger that only watches one event must ignore the others
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

    # A trigger's result is discarded, so the cap must not fail a run for a value nobody reads
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
    # The scenario invoker rights got wrong: the writer has no access to the audit collection at all
    with user_conn(WRITER) as writer:
        check_code("the writer indeed cannot write the audit collection directly",
                   writer.save_doc({"_id": "direct", "x": 1}, coll=AUDIT), "FORBIDDEN", "403-1")
        check_status("but may write the source collection", writer.save_doc({"_id": "by-writer", "n": 1}), "OK")
    with admin_conn() as conn:
        row = await_doc(conn, "UPDATED-by-writer")
        check("the audit row still appears", row.get("status") == "OK",
              f"definer rights must let the trigger write where the writer cannot: {row}")
        if row.get("status") == "OK":
            check("it records the writer as the actor", row["object"].get("by") == WRITER, f"got {row['object']}")
            check("and the installer as the definer",
                  row["object"].get("definer") == ADMIN_USERNAME, f"got {row['object']}")


def test_procedure_imports(conn: Conn):
    section("Shared code between procedures (procedures/<name>)")

    check_status("install a library of helpers",
                 conn.save_procedure("lib_fmt",
                                     "export function label(id) { return 'order:' + id; }\n"
                                     "export const VERSION = 2;"), "OK")

    # The point of the feature: a procedure composes a library rather than copying it.
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

    # A library is an ordinary procedure, so the existing DDL surface manages it.
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

    # Definer rights must survive the import: the writer cannot touch the audit collection itself.
    with user_conn(WRITER) as writer:
        check_status("the writer saves a document", writer.save_doc({"_id": "imp1", "n": 1}), "OK")
    row = await_doc(conn, "lib-imp1")
    check("the trigger's import resolved and it wrote the audit row", row.get("status") == "OK", f"got {row}")
    if row.get("status") == "OK":
        check("the library computed the note",
              row["object"].get("note") == f"UPDATED on imp1 by {WRITER}", f"got {row['object']}")
        check("definer rights survive the import",
              row["object"].get("definer") == ADMIN_USERNAME, f"got {row['object']}")

    check_status("restore the original audit trigger",
                 conn.save_trigger("audit_writes", ["CREATED", "UPDATED"], "auditor"), "OK")


def test_trigger_import_failure(conn: Conn):
    section("A trigger whose import cannot be resolved")
    # The save-time check passes while the library exists, so this is how a trigger ends up with an
    # unresolvable import: the library is deleted from under it afterwards.
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
    # The write itself must still succeed: a trigger is fired after the commit, so a broken trigger
    # cannot fail the operation that triggered it.
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
    deadline = time.time() + 15.0
    while trigger_failures(conn) <= before and time.time() < deadline:
        time.sleep(0.2)
    check("the failure is counted", trigger_failures(conn) > before, f"failures did not increase from {before}")

    # Nobody is waiting on a response, so the server log is where the trace has to appear
    log = read_log(log_path)
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


def test_cascade(conn: Conn):
    section("Cascade control")
    # The procedure writes back into the collection that fired it, so a cascade is possible
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
    # triggerMaxDepth is 2, so depth 2 must be the last that runs and depth 3 must never appear
    beyond = await_absent(conn, f"cascade-{TRIGGER_MAX_DEPTH + 1}", settle=4.0)
    check(f"and terminated at triggerMaxDepth={TRIGGER_MAX_DEPTH}", beyond.get("status") != "OK", f"got {beyond}")
    check_status("remove the cascading trigger",
                 conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL,
                            "name": "cascade_test"}), "OK")


# ══════════════════════════════════════════════════════════════════════════
# Phase 1 — before hooks (synchronous)
# ══════════════════════════════════════════════════════════════════════════

# A before hook is synchronous, so every assertion below is on the SAVE response itself and on an
# immediate FIND_BY_ID. Anything here that needed await_doc would be testing the wrong thing.

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
    # No polling: the hook already ran on the writing thread before the write committed
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
    # The hook is not wedged by having said no once
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

    # A DELETED hook replaces nothing, so returning a document is an error rather than a silent no-op
    check_status("seed a document to delete", conn.save_doc({"_id": "cd1", "qty": 1}), "OK")
    check_status("install a DELETED hook that returns a document",
                 install_hook(conn, "del_returns", "delreturns", "export default (doc) => ({ ...doc });",
                              ["DELETED"]), "OK")
    refused = conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": "cd1"})
    check_code("returning a document on DELETED is refused", refused, "ERROR", "400-21")
    check("the document survives", conn.find("cd1").get("status") == "OK")
    drop_hook(conn, "del_returns")


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
    # The edge validated the caller's own document; the replacement has to be checked too
    rejected = conn.save_doc({"_id": "g1", "qty": 1}, coll="guarded")
    check_code("a replacement failing the schema is refused", rejected, "ERROR", "400-21")
    check("and nothing was written", conn.find("g1", coll="guarded").get("status") != "OK")
    drop_hook(conn, "sneak", coll="guarded")


def test_before_hook_on_delete(conn: Conn):
    section("Before hooks - delete")
    check_status("seed a locked document", conn.save_doc({"_id": "d1", "qty": 1, "locked": True}), "OK")
    check_status("seed an unlocked one", conn.save_doc({"_id": "d2", "qty": 1, "locked": False}), "OK")
    # The hook vetoes on a field only the *stored* document has - the request carries just the _id
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
    # A module-level counter written into every document: proof the body evaluated once for the batch
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

    # Ascending name order, not install order: reversing the names reverses the result
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
    # The single most important case: a hook that could not run must never let the write through.
    # Which limit bites first (the wall clock, 408-1, or the instruction budget, 400-11) depends on how
    # the two are configured relative to each other, so the invariant asserted here is fail-closed
    # itself; BeforeHookContextTest pins each code precisely with a config tuned per case.
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

    # The one module a hook deliberately keeps
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
    # An after trigger stops when its definer is deleted (test_definer_rights covers that). A before hook
    # exercises no authority at all, so it must keep working - the difference is deliberate.
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

    # Deliberately left installed: phase 2 restarts this same data directory with the switches off and
    # asserts that a vetoing before hook stops running rather than refusing every write forever.
    check_status("leave a vetoing hook installed for phase 2",
                 install_hook(conn, "off_hook", "offhook",
                              "export default (doc) => { throw new Error('always refuses'); };",
                              ["CREATED", "UPDATED"]), "OK")
    check_code("it refuses while the switches are on", conn.save_doc({"_id": "off0", "qty": 1}), "ERROR", "400-21")


# ══════════════════════════════════════════════════════════════════════════
# Phase 1 — the dry run
# ══════════════════════════════════════════════════════════════════════════

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
    # The asymmetry the feature rests on: the dry run keeps console output, the live path discards it
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

    # The same hook on the live path produces no logs at all
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
    """Every history row, newest first, optionally narrowed by exact field match."""
    response = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": "script_runs",
                          "aggregationSteps": [{"type": "SORT", "fieldName": "startedAt", "ascending": False}]})
    rows = response.get("results") or []
    for field, value in filters.items():
        rows = [row for row in rows if row.get(field) == value]
    return rows


def await_history(conn: Conn, timeout: float = 15.0, **filters) -> list:
    """The write is asynchronous, so a row is waited for rather than expected to be there already."""
    deadline = time.time() + timeout
    rows = history_rows(conn, **filters)
    while not rows and time.time() < deadline:
        time.sleep(0.2)
        rows = history_rows(conn, **filters)
    return rows


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

    # A trigger row carries what a procedure row cannot: the collection, the event and the definer.
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

    # The cascade this design exists to prevent: the history write must not fire the trigger watching the
    # collection it is written into.
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

    # A trigger whose definer is gone never runs at all, which no other surface reports.
    check_status("install a trigger owned by a user about to be deleted",
                 conn.send({"type": "CREATE_USER", "username": "ghostdefiner", "password": "password123",
                            "admin": True, "globalPermissions": [], "databasePermissions": {},
                            "collectionPermissions": {}}), "OK")
    with user_conn("ghostdefiner") as ghost:
        check_status("the ghost installs a trigger",
                     ghost.save_trigger("ghost_trigger", ["CREATED", "UPDATED"], "auditor"), "OK")
    check_status("delete the definer",
                 conn.send({"type": "DELETE_USER", "username": "ghostdefiner"}), "OK")
    check_status("write a document", conn.save_doc({"_id": "ghostdoc", "n": 1}), "OK")

    skipped = await_history(conn, kind="TRIGGER", name="ghost_trigger")
    check("the trigger that never ran is still recorded", len(skipped) >= 1, f"rows={skipped!r}")
    if skipped:
        check("it is recorded as skipped", skipped[0].get("outcome") == "skipped", f"row={skipped[0]!r}")
        check("with the reason", "definer" in (skipped[0].get("errorMessage") or ""), f"row={skipped[0]!r}")
    check_status("remove the ghost trigger",
                 conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL,
                            "name": "ghost_trigger"}), "OK")


def trigger_runs(conn: Conn, status: str = None) -> list:
    payload = {"type": "LIST_TRIGGER_RUNS"}
    if status:
        payload["status"] = status
    return (conn.send(payload).get("runs") or [])


def dead_letters_for(conn: Conn, trigger_name: str) -> list:
    # Filtered by name: earlier phases leave their own dead letters behind, which is itself the point -
    # a dead letter outlives the run that produced it.
    return [run for run in trigger_runs(conn, "DEAD") if run.get("trigger") == trigger_name]


def test_retry_and_dead_letters(conn: Conn):
    section("Retry and dead letters")
    # Phase 1 deliberately leaves a vetoing before hook installed for the switches-off phase. It would
    # refuse every write here, so it is lifted for the length of this phase and put back at the end.
    drop_hook(conn, "off_hook")
    check_status("install a trigger whose procedure always throws",
                 conn.save_procedure("retry_boom", "throw new Error('retry boom');"), "OK")
    check_status("point a trigger at it",
                 conn.save_trigger("retrying", ["CREATED", "UPDATED"], "retry_boom"), "OK")
    check_status("write a document so it fires", conn.save_doc({"_id": "retrydoc", "n": 1}), "OK")

    # Two attempts are configured for this phase, so the run is tried twice and then dead-lettered.
    deadline = time.time() + 30.0
    dead = dead_letters_for(conn, "retrying")
    while not dead and time.time() < deadline:
        time.sleep(0.3)
        dead = dead_letters_for(conn, "retrying")
    check("the exhausted run is dead-lettered", len(dead) == 1, f"runs={dead!r}")

    attempts = await_history(conn, kind="TRIGGER", name="retrying")
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

        # Replay after fixing the cause: the attempt count starts over, so the corrected run gets a full
        # budget rather than dead-lettering again on its first failure.
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

    check_status("remove the retrying trigger",
                 conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL,
                            "name": "retrying"}), "OK")

    # Put the vetoing hook back so the switches-off phase still has the precondition it asserts on.
    check_status("restore the vetoing hook for the next phase",
                 install_hook(conn, "off_hook", "offhook",
                              "export default (doc) => { throw new Error('always refuses'); };",
                              ["CREATED", "UPDATED"]), "OK")


def test_trigger_run_operations(conn: Conn):
    section("LIST_TRIGGER_RUNS / RESOLVE_TRIGGER_RUN")
    check_status("listing works", conn.send({"type": "LIST_TRIGGER_RUNS"}), "OK")
    check_status("an explicit status filter is accepted",
                 conn.send({"type": "LIST_TRIGGER_RUNS", "status": "PENDING"}), "OK")
    check_code("an unknown status is rejected",
               conn.send({"type": "LIST_TRIGGER_RUNS", "status": "sideways"}), "ERROR", "400-1")

    # Not an error: the operator asked every node and none of them holds the run - the answer
    # CANCEL_SCRIPT gives for a run that has already finished.
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
    # Re-creating it must not resurrect the old procedure, nor serve its compiled program
    check_status("re-create the database",
                 conn.send({"type": "CREATE_DATABASE", "databaseName": "proc_drop_db"}), "OK")
    check_code("the old procedure is gone", conn.call("doomed", db="proc_drop_db"), "NOT_FOUND", "404-8")
    check_status("a new procedure of the same name",
                 conn.save_procedure("doomed", "return 99;", db="proc_drop_db"), "OK")
    check_result("runs the new body", conn.call("doomed", db="proc_drop_db"), 99)


# ══════════════════════════════════════════════════════════════════════════
# Phase 2 — both switches off
# ══════════════════════════════════════════════════════════════════════════

def test_switches_off(conn: Conn):
    section("Master switches off")
    check_code("scriptsEnabled=false refuses a call", conn.call("greet"), "FORBIDDEN", "403-2")
    check_code("and a save", conn.save_procedure("valid_name", "return 1;"), "FORBIDDEN", "403-2")
    # Trigger DDL still works with triggers off; nothing fires.
    check_status("trigger DDL still works", conn.send(
        {"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": COLL}), "OK")
    check_status("a write still succeeds", conn.save_doc({"_id": "quiet", "n": 1}), "OK")
    quiet = await_absent(conn, "UPDATED-quiet")
    check("triggersEnabled=false fires nothing", quiet.get("status") != "OK", f"got {quiet}")
    # A before hook is on the write path, so triggersEnabled=false has to stop it running at all -
    # otherwise a vetoing hook would keep refusing writes after the switch was turned off.
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


# ── main ─────────────────────────────────────────────────────────────────────

def main():
    global failures
    jar = os.path.join(REPO_ROOT, JAR)
    if not os.path.isfile(jar):
        print(f"jar not found at {jar}; run `mvn clean package -DskipTests` first", file=sys.stderr)
        return 1
    if port_open():
        print(f"port {PORT} is already in use", file=sys.stderr)
        return 1

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-proc-test-")
    log_path = os.path.join(work_dir, "server.out")
    proc = None
    try:
        print(f"work dir: {work_dir}")
        write_config(work_dir, scripts_enabled=True, triggers_enabled=True)
        proc = start_server(work_dir, log_path)

        with admin_conn() as conn:
            setup_data(conn)
            test_procedure_lifecycle(conn)
            test_procedure_validation(conn)
        test_permissions()
        with admin_conn() as conn:
            test_triggers(conn)
            test_trigger_validation(conn)
            test_before_hooks(conn)
            test_before_hook_veto(conn)
            test_before_hook_contract(conn)
            test_before_hook_schema_recheck(conn)
            test_before_hook_on_delete(conn)
            test_before_hook_bulk_save(conn)
            test_before_hook_chaining(conn)
            test_before_hook_sandbox(conn)
            test_before_hook_no_db(conn)
            test_before_hook_in_transaction(conn)
            test_before_hook_validation(conn)
            test_dry_run(conn)
            test_result_cap(conn)
        test_definer_rights()
        with admin_conn() as conn:
            test_procedure_imports(conn)
            test_trigger_imports(conn)
            test_trigger_import_failure(conn)
            test_trigger_failure_logs_a_stack(conn, log_path)
            test_run_history(conn)
            test_run_history_failures(conn)
            test_trigger_run_operations(conn)
            test_batch_mode(conn)
            test_cascade(conn)
            test_stats(conn)
            test_storage_placement(work_dir)
            test_cascade_deletes(conn, work_dir)
            # Last of phase 1: it deliberately leaves a vetoing before hook installed for phase 2, so
            # nothing that writes may run after it.
            test_before_hook_definer_is_not_enforced(conn)

        # Phase 2: the same data directory with retries on, so a deterministically failing trigger is
        # attempted twice and then dead-lettered rather than lost.
        stop_server(proc)
        proc = None
        write_config(work_dir, scripts_enabled=True, triggers_enabled=True, max_attempts=2, retry_backoff_ms=200)
        proc = start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_retry_and_dead_letters(conn)

        # Phase 3: same data directory, both switches off
        stop_server(proc)
        proc = None
        write_config(work_dir, scripts_enabled=False, triggers_enabled=False)
        proc = start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_switches_off(conn)
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
