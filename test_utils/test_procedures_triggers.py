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
TRIGGER_MAX_DEPTH = 2
TRIGGER_TIMEOUT_MS = 4_000

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

def write_config(work_dir: str, scripts_enabled: bool, triggers_enabled: bool):
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
        "scriptTextImportEnabled=false\n"
        "scriptTimeZone=UTC\n"
        "scriptLocale=en-US\n"
        "procedureCacheSize=16\n"
        f"triggersEnabled={'true' if triggers_enabled else 'false'}\n"
        "triggerThreads=2\n"
        "triggerQueueSize=1000\n"
        f"triggerMaxDepth={TRIGGER_MAX_DEPTH}\n"
        f"triggerTimeoutMs={TRIGGER_TIMEOUT_MS}\n"
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


def test_stats(conn: Conn):
    section("Statistics")
    stats = conn.send({"type": "GET_DATABASE_STATS"})
    triggers = (stats.get("stats") or {}).get("triggers") or {}
    check("stats report the trigger counters", "fired" in triggers and "dropped" in triggers, f"got {stats}")
    check("triggers are reported as enabled", triggers.get("enabled") is True, f"got {triggers}")
    check("and at least one has fired", (triggers.get("fired") or 0) > 0, f"got {triggers}")


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
    stats = conn.send({"type": "GET_DATABASE_STATS"})
    triggers = (stats.get("stats") or {}).get("triggers") or {}
    check("stats report triggers as disabled", triggers.get("enabled") is False, f"got {triggers}")


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
        test_definer_rights()
        with admin_conn() as conn:
            test_batch_mode(conn)
            test_cascade(conn)
            test_stats(conn)
            test_storage_placement(work_dir)
            test_cascade_deletes(conn, work_dir)

        # Phase 2: same data directory, both switches off
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
