"""End-to-end tests for the RUN_SCRIPT operation (the SimpleJS engine over the wire).

Like the TLS and clustering suites this script is **self-contained**: it starts its own
LWNRDB instance on a dedicated port and working directory, because the feature needs a
configured sandbox that the shared CI server does not have. It runs in two phases:

  phase 1 — `scriptsEnabled=true` with a deliberately tight sandbox
            (small instruction budget, short deadline, low log caps, small source cap)
            so every limit is reachable in a test rather than only in theory;
  phase 2 — the same data directory restarted with `scriptProcedureImportEnabled=false`,
            proving that switch closes the `procedures/` prefix while leaving the
            built-in specifiers and import-free procedures working;
  phase 3 — restarted again with `scriptsEnabled=false`, proving the master switch
            refuses the operation for everyone, admins included.

What is covered:

  * gating: engine disabled, no permission, per-database grants, revocation, ownership,
    unauthenticated, and the reserved/unknown-database and missing-script validations;
  * the sandbox: instruction budget, recursion depth, wall-clock deadline, source size,
    and the console log caps (line count + per-line clipping);
  * the host `db` interface: reads (findById/aggregate/listCollections/listDatabases),
    writes (save/bulkSave/delete), transactions (commit, rollback, async rejection), the
    database scope, and the fact that every refused operation throws into the script;
  * the rest of the host surface: EJson custom types (Geo/Vector/DbDateTime/DbTime),
    `crypto`, the host-gated `fetch` and text-import capabilities, module resolution,
    `import.meta`, and the configured time zone / locale;
  * importing a stored procedure as a module (`procedures/<name>`): every import form,
    library-to-library chains, the scope restriction, disabled procedures, cycles,
    one-evaluation-per-run, budget sharing, and the exports-only contract;
  * `args` in its various shapes, and the result contract (return / export default /
    named exports / awaited promise / undefined);
  * a broad sweep of the ES2026 language surface the engine implements, so a regression
    in the interpreter shows up here and not only in test262.

The server lifecycle is managed via a tracked subprocess handle (not pgrep), so stopping
it never touches an unrelated LWNRDB process.
"""

import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import time

HOST = "127.0.0.1"
PORT = int(os.environ.get("SCRIPT_TEST_PORT", "8995"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "script_test_db"
OTHER_DB = "script_other_db"
COLL = "docs"
COLL2 = "more"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Sandbox for phase 1. Tight enough that every limit is reachable, loose enough that a
# legitimate script on a slow shared runner never trips one by accident.
INSTRUCTION_BUDGET = 2_000_000
TIMEOUT_MS = 5_000
MAX_DEPTH = 64
MAX_SOURCE_BYTES = 8 * 1024
MAX_LOG_LINES = 5
MAX_LOG_LINE_CHARS = 200
MAX_MEMORY_BYTES = 4 * 1024 * 1024
MAX_RESULT_BYTES = 64 * 1024
CURSOR_BATCH_SIZE = 100
CURSOR_MAX_BATCH_SIZE = 500

# The paged-read collection: big enough that materialising it at once costs more than
# MAX_MEMORY_BYTES, so the cursor is the only way a script can walk it.
BIG_COLL = "big"
EMPTY_COLL = "empty"
BIG_DOCS = 300
BIG_PAYLOAD_CHARS = 8 * 1024

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


def check_failed_script(label: str, response: dict, expected_code: str, message_contains: str = ""):
    ok = (response.get("status") == "ERROR" and response.get("errorCode") == expected_code
          and message_contains in (response.get("message") or ""))
    check(label, ok,
          f"expected {expected_code} containing {message_contains!r} got "
          f"{response.get('errorCode')} msg={response.get('message')!r}")


def check_stack(label: str, response: dict, frames_contain=(), min_frames: int = 1):
    stack = response.get("stack")
    ok = isinstance(stack, list) and len(stack) >= min_frames \
        and all(any(needle in frame for frame in stack) for needle in frames_contain)
    check(label, ok, f"expected >= {min_frames} frames containing {list(frames_contain)!r} got {stack!r}")


def check_no_stack(label: str, response: dict):
    check(label, not response.get("stack"), f"expected no stack, got {response.get('stack')!r}")


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

    def run(self, script: str, args=None, db=DB) -> dict:
        payload = {"type": "RUN_SCRIPT", "databaseName": db, "script": script}
        if args is not None:
            payload["args"] = args
        return self.send(payload)

    def save_procedure(self, name: str, script: str, db=DB, **extra) -> dict:
        payload = {"type": "SAVE_PROCEDURE", "databaseName": db, "name": name, "script": script}
        payload.update(extra)
        return self.send(payload)

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


# ── server lifecycle ─────────────────────────────────────────────────────────

def write_config(work_dir: str, scripts_enabled: bool, procedure_imports_enabled: bool = True):
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
        f"scriptMaxLogLines={MAX_LOG_LINES}\n"
        f"scriptMaxLogLineChars={MAX_LOG_LINE_CHARS}\n"
        f"scriptMaxMemoryBytes={MAX_MEMORY_BYTES}\n"
        f"scriptMaxResultBytes={MAX_RESULT_BYTES}\n"
        f"scriptCursorBatchSize={CURSOR_BATCH_SIZE}\n"
        f"scriptCursorMaxBatchSize={CURSOR_MAX_BATCH_SIZE}\n"
        "scriptTextImportEnabled=false\n"
        f"scriptProcedureImportEnabled={'true' if procedure_imports_enabled else 'false'}\n"
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
    for db in (DB, OTHER_DB):
        conn.send({"type": "DROP_DATABASE", "databaseName": db})
        check_status(f"create database {db}", conn.send({"type": "CREATE_DATABASE", "databaseName": db}), "OK")
    for coll in (COLL, COLL2, BIG_COLL, EMPTY_COLL):
        check_status(f"create collection {coll}",
                     conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    check_status("create collection in the other database",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": OTHER_DB, "collectionName": COLL}), "OK")
    docs = [
        {"_id": "d1", "name": "alpha", "n": 1, "tags": ["x", "y"]},
        {"_id": "d2", "name": "beta", "n": 5, "tags": ["y"]},
        {"_id": "d3", "name": "gamma", "n": 9, "tags": []},
    ]
    check_status("seed documents",
                 conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL, "objects": docs}), "OK")
    payload = "x" * BIG_PAYLOAD_CHARS
    seeded = True
    for start in range(0, BIG_DOCS, 50):
        batch = [{"_id": f"big{i:04d}", "payload": payload} for i in range(start, start + 50)]
        response = conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": BIG_COLL, "objects": batch})
        seeded = seeded and response.get("status") == "OK"
    check(f"seed {BIG_DOCS} large documents into {BIG_COLL}", seeded)


# ══════════════════════════════════════════════════════════════════════════
# Phase 1 tests — scripts enabled
# ══════════════════════════════════════════════════════════════════════════

def test_basics(conn: Conn):
    section("Basics and the result contract")
    check_result("top-level return", conn.run("return 1 + 1;"), 2)
    check_result("export default", conn.run("export default 'defaulted';"), "defaulted")
    check_result("named exports", conn.run("export const a = 1;\nexport const b = 'two';"),
                 {"a": 1, "b": "two"})
    check_result("no value at all is JSON null", conn.run("const unused = 1;"), None)
    check_result("returned promise is awaited",
                 conn.run("return (async () => { await null; return 'awaited'; })();"), "awaited")
    check_result("objects and arrays round-trip",
                 conn.run("return { list: [1, 2, 3], nested: { ok: true }, missing: null };"),
                 {"list": [1, 2, 3], "nested": {"ok": True}, "missing": None})
    check_failed_script("a rejected promise fails the run",
                        conn.run("return Promise.reject(new Error('nope'));"), "400-9", "Error: nope")
    check_result("an accessor-valued property is read through its getter",
                 conn.run("return { get total() { return 6 * 7; }, plain: 1 };"), {"total": 42, "plain": 1})
    check_failed_script("a throwing getter fails the run",
                        conn.run("return { get bad() { throw new RangeError('nope'); } };"),
                        "400-9", "RangeError: nope")


def test_stack_traces(conn: Conn):
    section("Stack traces")
    thrown = conn.run("function inner() {\n"
                      "  throw new Error('boom');\n"
                      "}\n"
                      "function outer() {\n"
                      "  inner();\n"
                      "}\n"
                      "outer();")
    check_failed_script("a nested throw fails the run", thrown, "400-9", "Error: boom")
    check_stack("frames name each function", thrown, ("inner (", "outer (", "main:"), min_frames=3)
    frames = thrown.get("stack") or []
    check("the innermost frame comes first", bool(frames) and frames[0].startswith("inner ("),
          f"stack={frames!r}")
    check("frames carry a line and column", bool(frames) and re.search(r":\d+:\d+\)$", frames[0]) is not None,
          f"stack={frames!r}")

    runtime = conn.run("function read() {\n"
                       "  const x = null;\n"
                       "  return x.total;\n"
                       "}\n"
                       "read();")
    check_code("a runtime TypeError fails the run", runtime, "ERROR", "400-9")
    check_stack("a runtime error carries frames too", runtime, ("read (",))

    check_no_stack("a successful run carries no stack", conn.run("return 1;"))

    # A sandbox abort is not a program error, so it deliberately reports no frames
    check_no_stack("an instruction-budget abort carries no stack", conn.run("while (true) {}"))
    check_no_stack("a depth abort carries no stack",
                   conn.run("function recurse(n) { return recurse(n + 1); }\nreturn recurse(0);"))

    deep = conn.run("function down(n) {\n"
                    "  if (n === 0) { throw new Error('bottom'); }\n"
                    "  return down(n - 1);\n"
                    "}\n"
                    "down(60);")
    deep_frames = deep.get("stack") or []
    check("a deep stack is truncated with a marker",
          bool(deep_frames) and deep_frames[-1].startswith("... "), f"stack tail={deep_frames[-1:]!r}")


def test_pending_promise(conn: Conn):
    section("A result promise that never settles")
    check_code("a never-settling returned promise is reported",
               conn.run("return new Promise(function () {});"), "ERROR", "400-20")
    check_code("a never-settling default export is reported",
               conn.run("export default new Promise(function () {});"), "ERROR", "400-20")
    check_result("an explicit null result is still a value", conn.run("return null;"), None)
    check_result("a promise settled by a timer still succeeds",
                 conn.run("return new Promise(function (resolve) { setTimeout(function () "
                          "{ resolve('late'); }, 5); });"), "late")


def test_compiled_script_cache(conn: Conn):
    section("Compiled-script cache")
    for attempt in range(3):
        check_result(f"the same source answers identically (run {attempt + 1})",
                     conn.run("return 6 * 7;"), 42)
    for attempt in range(3):
        check_failed_script(f"a syntax error stays 400-9 (run {attempt + 1})",
                            conn.run("return (;"), "400-9", "SyntaxError")
    stats = conn.send({"type": "GET_DATABASE_STATS"})
    entries = ((stats.get("stats") or {}).get("scripts") or {}).get("compiledCacheEntries")
    check("the cache reports its entry count", isinstance(entries, (int, float)) and entries >= 1,
          f"compiledCacheEntries={entries!r}")


def test_console_output(conn: Conn):
    section("Console output")
    response = conn.run("console.log('one'); console.log('two'); return 'done';")
    check_result("script value alongside logs", response, "done")
    check("logs are returned in order", response.get("logs") == ["one", "two"],
          f"logs={response.get('logs')!r}")
    check("logsTruncated is false when nothing was dropped", response.get("logsTruncated") is False,
          f"logsTruncated={response.get('logsTruncated')!r}")

    over = conn.run("for (let i = 0; i < 8; i++) console.log('line ' + i);\nreturn 'logged';")
    check(f"only the newest {MAX_LOG_LINES} lines are kept",
          over.get("logs") == [f"line {i}" for i in range(3, 8)], f"logs={over.get('logs')!r}")
    check("logsTruncated reports the drop", over.get("logsTruncated") is True,
          f"logsTruncated={over.get('logsTruncated')!r}")

    clipped = conn.run("console.log('x'.repeat(500)); return 'clipped';")
    lines = clipped.get("logs") or [""]
    check(f"an overlong line is clipped to {MAX_LOG_LINE_CHARS} chars", len(lines[0]) == MAX_LOG_LINE_CHARS,
          f"len={len(lines[0])}")

    errored = conn.run("console.log('before the throw'); throw new TypeError('boom');")
    check_failed_script("a failed run still returns its logs", errored, "400-9", "TypeError: boom")
    check("logs survive the failure", errored.get("logs") == ["before the throw"],
          f"logs={errored.get('logs')!r}")


def test_sandbox_limits(conn: Conn):
    section("Sandbox limits")
    check_code("an infinite loop exhausts the instruction budget",
               conn.run("while (true) {}"), "ERROR", "400-11")
    check_code("unbounded recursion hits the depth cap",
               conn.run("function recurse(n) { return recurse(n + 1); }\nreturn recurse(0);"), "ERROR", "400-11")
    check_code("waiting past the deadline times out",
               conn.run("return (async () => { await new Promise(r => setTimeout(r, 60000)); return 'never'; })();"),
               "ERROR", "408-1")
    oversized = "// padding\n" + ("x".ljust(80, 'x') + "\n") * 120 + "return 1;"
    check("the oversized source is actually over the cap", len(oversized.encode()) > MAX_SOURCE_BYTES,
          f"bytes={len(oversized.encode())}")
    check_code("a source over the size cap is rejected", conn.run(oversized), "ERROR", "400-10")
    check_result("a script just under the caps still runs",
                 conn.run("let total = 0;\nfor (let i = 0; i < 1000; i++) total += i;\nreturn total;"), 499500)
    check_code("a single oversized allocation exceeds the memory budget",
               conn.run('return "x".repeat(100000000);'), "ERROR", "400-12")
    check_code("string doubling exceeds the memory budget",
               conn.run('let s = "x";\nfor (let i = 0; i < 40; i++) { s = s + s; }\nreturn s.length;'),
               "ERROR", "400-12")
    check_code("a pre-sized array-like exceeds the memory budget",
               conn.run("return Array.from({length: 2000000000}).length;"), "ERROR", "400-12")
    check_result("a script allocating well under the memory budget still runs",
                 conn.run('return "x".repeat(1000).length;'), 1000)
    check_result("incremental string building is not penalised by the memory budget",
                 conn.run('let s = "";\nfor (let i = 0; i < 20000; i++) s += "x";\nreturn s.length;'), 20000)
    # The result conversion runs inside the sandbox, so a runaway getter aborts rather than hanging
    check_code("a runaway getter hits the instruction budget",
               conn.run("return { get n() { while (true) {} } };"), "ERROR", "400-11")


def test_request_validation(conn: Conn):
    section("Request validation")
    check_code("unknown database", conn.run("return 1;", db="no_such_db"), "NOT_FOUND", "404-4")
    check_code("the reserved admin database", conn.run("return 1;", db="admin"), "ERROR", "400-1")
    check_code("the reserved admin_pages database", conn.run("return 1;", db="admin_pages"), "ERROR", "400-1")
    check_code("a missing script", conn.send({"type": "RUN_SCRIPT", "databaseName": DB}), "ERROR", "400-1")
    check_code("a blank script", conn.run("   "), "ERROR", "400-1")
    check_code("a malformed database name", conn.run("return 1;", db="ab"), "ERROR", "400-1")
    check_failed_script("a syntax error is reported as a script failure",
                        conn.run("function ("), "400-9", "SyntaxError")

    check_status("open a transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_code("RUN_SCRIPT is refused inside a transaction", conn.run("return 1;"), "ERROR", "409-6")
    check_status("roll the transaction back", conn.send({"type": "ROLLBACK_TRANSACTION"}), "OK")


def test_host_reads(conn: Conn):
    section("Host interface — reads")
    check_result("db.name is the scoped database", conn.run('import db from "db";\nreturn db.name;'), DB)
    check_result("findById returns the document",
                 conn.run(f'import db from "db";\nreturn db.findById(db.name, "{COLL}", "d2");'),
                 {"_id": "d2", "name": "beta", "n": 5, "tags": ["y"]})
    check_result("findById of a missing document is null",
                 conn.run(f'import db from "db";\nreturn db.findById(db.name, "{COLL}", "nope") === null;'), True)
    check_result("aggregate with a filter",
                 conn.run('import db from "db";\n'
                          "const pipeline = [{ type: 'FILTER', operator: "
                          "{ fieldOperatorType: 'GREATER_THAN_EQUALS', field: 'n', value: 5 } }];\n"
                          f'return db.aggregate(db.name, "{COLL}", pipeline).map(d => d._id).sort();'),
                 ["d2", "d3"])
    check_result("aggregate with a count step",
                 conn.run('import db from "db";\n'
                          f'return db.aggregate(db.name, "{COLL}", [{{ type: "COUNT" }}])[0].count;'), 3)
    check_result("an empty pipeline result is an empty array",
                 conn.run('import db from "db";\n'
                          "const pipeline = [{ type: 'FILTER', operator: "
                          "{ fieldOperatorType: 'EQUALS', field: 'name', value: 'nobody' } }];\n"
                          f'return db.aggregate(db.name, "{COLL}", pipeline).length;'), 0)
    check_result("listCollections lists the scoped database",
                 conn.run('import db from "db";\nreturn db.listCollections(db.name).sort();'),
                 sorted([COLL, COLL2, BIG_COLL, EMPTY_COLL]))
    check_result("listDatabases answers only the scope",
                 conn.run('import db from "db";\nreturn db.listDatabases();'), [DB])


def test_host_writes(conn: Conn):
    section("Host interface — writes")
    check_result("save then read back",
                 conn.run('import db from "db";\n'
                          f'db.save(db.name, "{COLL}", {{ _id: "w1", name: "written", n: 42 }});\n'
                          f'return db.findById(db.name, "{COLL}", "w1").n;'), 42)
    check_result("a getter-valued property is persisted, not dropped",
                 conn.run('import db from "db";\n'
                          f'db.save(db.name, "{COLL}", {{ _id: "g1", get computed() {{ return 21 * 2; }} }});\n'
                          f'return db.findById(db.name, "{COLL}", "g1").computed;'), 42)
    check_result("save is an upsert",
                 conn.run('import db from "db";\n'
                          f'db.save(db.name, "{COLL}", {{ _id: "w1", name: "updated", n: 43 }});\n'
                          f'return db.findById(db.name, "{COLL}", "w1").name;'), "updated")
    check_result("bulkSave reports inserted and updated ids",
                 conn.run('import db from "db";\n'
                          f'const outcome = db.bulkSave(db.name, "{COLL}", '
                          '[{ _id: "b1", n: 1 }, { _id: "w1", n: 44 }]);\n'
                          "return { inserted: outcome.inserted, updated: outcome.updated };"),
                 {"inserted": ["b1"], "updated": ["w1"]})
    check_result("delete removes the document",
                 conn.run('import db from "db";\n'
                          f'db.delete(db.name, "{COLL}", "b1");\n'
                          f'return db.findById(db.name, "{COLL}", "b1") === null;'), True)
    check_result("deleting an absent document is a no-op",
                 conn.run('import db from "db";\n'
                          f'db.delete(db.name, "{COLL}", "never-existed");\nreturn "no-op";'), "no-op")
    check_result("a document written by a script is visible to the wire protocol",
                 conn.run('import db from "db";\n'
                          f'db.save(db.name, "{COLL}", {{ _id: "visible", via: "script" }});\nreturn "saved";'),
                 "saved")
    check_status("… and FIND_BY_ID sees it",
                 conn.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": "visible"}), "OK")


def test_transactions(conn: Conn):
    section("Host interface — transactions")
    check_result("a transaction commits both collections",
                 conn.run('import db from "db";\n'
                          "db.transaction(() => {\n"
                          f'  db.save(db.name, "{COLL}", {{ _id: "tx1", from: "tx" }});\n'
                          f'  db.save(db.name, "{COLL2}", {{ _id: "tx2", from: "tx" }});\n'
                          "});\n"
                          f'return [db.findById(db.name, "{COLL}", "tx1") !== null, '
                          f'db.findById(db.name, "{COLL2}", "tx2") !== null];'), [True, True])
    check_result("a throw inside the callback rolls everything back",
                 conn.run('import db from "db";\n'
                          "let message = null;\n"
                          "try {\n"
                          "  db.transaction(() => {\n"
                          f'    db.save(db.name, "{COLL}", {{ _id: "tx-rb", from: "tx" }});\n'
                          "    throw new Error('abort');\n"
                          "  });\n"
                          "} catch (e) { message = e.message; }\n"
                          f'return {{ message, written: db.findById(db.name, "{COLL}", "tx-rb") !== null }};'),
                 {"message": "abort", "written": False})
    check_result("an async transaction callback is refused",
                 conn.run('import db from "db";\n'
                          "try { db.transaction(async () => {}); return 'accepted'; }\n"
                          "catch (e) { return e.constructor.name; }"), "TypeError")
    check_result("writes still work after a rolled-back transaction",
                 conn.run('import db from "db";\n'
                          f'db.save(db.name, "{COLL}", {{ _id: "after-tx", ok: true }});\n'
                          f'return db.findById(db.name, "{COLL}", "after-tx").ok;'), True)


def test_scope_and_failures(conn: Conn):
    section("Database scope and failure surfacing")
    check_result("another database is unreachable",
                 conn.run('import db from "db";\n'
                          f'try {{ db.findById("{OTHER_DB}", "{COLL}", "d1"); return "reached"; }}\n'
                          "catch (e) { return e.message; }"),
                 f"This script may only access database '{DB}'")
    check_result("the admin database is unreachable",
                 conn.run('import db from "db";\n'
                          'try { db.findById("admin", "users", "admin"); return "reached"; }\n'
                          "catch (e) { return e instanceof Error; }"), True)
    check_result("a refused write throws into the script",
                 conn.run('import db from "db";\n'
                          'try { db.save(db.name, "neverCreated", { _id: "x" }); return "wrote"; }\n'
                          "catch (e) { return e instanceof Error; }"), True)
    check_failed_script("an unhandled refusal fails the run",
                        conn.run('import db from "db";\n'
                                 'db.save(db.name, "neverCreated", { _id: "x" });'), "400-9")
    check_result("an unknown module specifier is catchable",
                 conn.run("try { await import('nope'); return 'loaded'; }\n"
                          "catch (e) { return e.message; }"), "Cannot find module 'nope'")


def test_schema_interaction(conn: Conn):
    section("Collection schemas apply to a script's writes")
    schema = {"type": "object", "required": ["name"], "properties": {"name": {"type": "string"}}}
    check_status("attach a schema to the second collection",
                 conn.send({"type": "SAVE_SCHEMA", "databaseName": DB, "collectionName": COLL2, "schema": schema}),
                 "OK")
    check_result("a compliant write is accepted",
                 conn.run('import db from "db";\n'
                          f'db.save(db.name, "{COLL2}", {{ _id: "s-ok", name: "fine" }});\nreturn "saved";'), "saved")
    check_result("a non-compliant write is refused inside the script",
                 conn.run('import db from "db";\n'
                          f'try {{ db.save(db.name, "{COLL2}", {{ _id: "s-bad", other: 1 }}); return "wrote"; }}\n'
                          "catch (e) { return e instanceof Error; }"), True)
    check_status("remove the schema",
                 conn.send({"type": "DELETE_SCHEMA", "databaseName": DB, "collectionName": COLL2}), "OK")


def test_arguments(conn: Conn):
    section("Arguments")
    check_result("named access", conn.run('import args from "args";\nreturn args.name;', {"name": "alpha"}), "alpha")
    check_result("bracket access", conn.run('import args from "args";\nreturn args["n"] * 2;', {"n": 21}), 42)
    check_result("nested objects and arrays",
                 conn.run('import args from "args";\nreturn args.outer.inner[1];',
                          {"outer": {"inner": ["a", "b"]}}), "b")
    check_result("a missing argument is undefined",
                 conn.run('import args from "args";\nreturn args.absent === undefined;', {"present": 1}), True)
    check_result("no args at all yields an empty object",
                 conn.run('import args from "args";\nreturn Object.keys(args).length;'), 0)
    check_result("namespace import form",
                 conn.run('import * as args from "args";\nreturn args.value;', {"value": "ns"}), "ns")
    check_result("arguments drive a query",
                 conn.run('import db from "db";\nimport args from "args";\n'
                          "const pipeline = [{ type: 'FILTER', operator: "
                          "{ fieldOperatorType: 'EQUALS', field: 'name', value: args.wanted } }];\n"
                          f'return db.aggregate(db.name, "{COLL}", pipeline)[0]._id;', {"wanted": "gamma"}), "d3")
    check_result("dynamic import of the args module",
                 conn.run("const mod = await import('args');\nreturn mod.default.k;", {"k": "dyn"}), "dyn")


def test_custom_types(conn: Conn):
    section("Host interface — EJson custom types")
    check_result("Geo round-trips through the database",
                 conn.run('import db from "db";\n'
                          f'db.save(db.name, "{COLL}", {{ _id: "geo1", loc: Geo.from("#geo(40.4,-3.7)") }});\n'
                          f'const stored = db.findById(db.name, "{COLL}", "geo1");\n'
                          "return { lat: stored.loc.lat, lng: stored.loc.lng, text: stored.loc.toString() };"),
                 {"lat": 40.4, "lng": -3.7, "text": "#geo(40.4,-3.7)"})
    check_result("Vector round-trips through the database",
                 conn.run('import db from "db";\n'
                          f'db.save(db.name, "{COLL}", {{ _id: "vec1", v: Vector.from([1, 2, 3]) }});\n'
                          f'const stored = db.findById(db.name, "{COLL}", "vec1");\n'
                          "return { length: stored.v.length, first: stored.v.at(0) };"),
                 {"length": 3, "first": 1})
    check_result("DbDateTime exposes its components and bridges to Temporal",
                 conn.run('import db from "db";\n'
                          f'db.save(db.name, "{COLL}", '
                          '{ _id: "dt1", at: DbDateTime.from("#datetime(2024-07-12T12:30:00)") });\n'
                          f'const stored = db.findById(db.name, "{COLL}", "dt1");\n'
                          "return { year: stored.at.year, hour: stored.at.hour, "
                          "temporal: stored.at.toTemporal().toString().slice(0, 10) };"),
                 {"year": 2024, "hour": 12, "temporal": "2024-07-12"})
    check_result("DbTime round-trips",
                 conn.run('import db from "db";\n'
                          f'db.save(db.name, "{COLL}", {{ _id: "t1", when: DbTime.from("#time(23:15:42)") }});\n'
                          f'return db.findById(db.name, "{COLL}", "t1").when.toString();'), "#time(23:15:42)")
    check_result("a geo value is queryable from the wire after a script wrote it",
                 conn.run('import db from "db";\nreturn typeof Geo.from("#geo(1,2)").geoHash;'), "string")


def test_capabilities(conn: Conn):
    section("Host interface — capabilities and environment")
    check_result("crypto.randomUUID", conn.run("return crypto.randomUUID().length;"), 36)
    check_result("crypto.getRandomValues",
                 conn.run("const bytes = crypto.getRandomValues(new Uint8Array(8));\nreturn bytes.length;"), 8)
    check_result("crypto.hash is Node-shaped and synchronous",
                 conn.run("return crypto.hash('sha256', 'abc');"),
                 "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    check_result("fetch is unavailable by default",
                 conn.run("try { await fetch('http://example.com'); return 'fetched'; }\n"
                          "catch (e) { return e.message; }"), "fetch is not available")
    check_result("text import is disabled by default",
                 conn.run("import script from 'script';\n"
                          "try { script.importText('export default 1;'); return 'imported'; }\n"
                          "catch (e) { return e instanceof Error; }"), True)
    check_result("import.meta is available", conn.run("return import.meta.url;"), "simplejs:main")
    check_result("the configured time zone is used",
                 conn.run("return Temporal.Now.timeZoneId();"), "UTC")
    check_result("dates are formatted in the configured zone",
                 conn.run("return new Date(0).getHours();"), 0)
    check_result("the configured locale is used",
                 conn.run("return (1234.5).toLocaleString();"), "1,234.5")


def test_procedure_imports(conn: Conn):
    section("Module imports — procedures/<name>")

    check_status("store a library exporting a named function",
                 conn.save_procedure("lib_money", "export function cents(n) { return Math.round(n * 100); }\n"
                                                  "export const RATE = 0.21;"), "OK")
    check_result("a named export is importable",
                 conn.run("import { cents } from 'procedures/lib_money';\nreturn cents(12.345);"), 1235)
    check_result("several named exports at once",
                 conn.run("import { cents, RATE } from 'procedures/lib_money';\nreturn [cents(1), RATE];"),
                 [100, 0.21])
    check_result("a renamed named export",
                 conn.run("import { cents as toCents } from 'procedures/lib_money';\nreturn toCents(2);"), 200)

    check_status("store a library with a default export",
                 conn.save_procedure("lib_default", "export default (n) => n + 1;"), "OK")
    check_result("a default import binds the default export, not the namespace",
                 conn.run("import bump from 'procedures/lib_default';\nreturn bump(41);"), 42)
    check_result("a namespace import exposes members and the default",
                 conn.run("import * as ns from 'procedures/lib_money';\n"
                          "return [typeof ns.cents, ns.RATE];"), ["function", 0.21])
    check_result("a dynamic import resolves a procedure",
                 conn.run("const ns = await import('procedures/lib_money');\nreturn ns.cents(3);"), 300)
    check_status("store a library whose body has a side effect",
                 conn.save_procedure("lib_marker", "import db from 'db';\n"
                                                   "db.save(db.name, '%s', { _id: 'import-marker', seen: 1 });"
                                     % COLL), "OK")
    check_result("a side-effect-only import runs the module body",
                 conn.run("import db from 'db';\n"
                          "import 'procedures/lib_marker';\n"
                          "return db.findById(db.name, '%s', 'import-marker').seen;" % COLL), 1)

    check_status("store a library that imports another library",
                 conn.save_procedure("lib_chain", "import { cents } from 'procedures/lib_money';\n"
                                                  "export function twiceCents(n) { return cents(n) * 2; }"), "OK")
    check_result("a library may import another library",
                 conn.run("import { twiceCents } from 'procedures/lib_chain';\nreturn twiceCents(1.5);"), 300)

    # Absence, not failure, is still an error here: an unresolvable specifier throws into the script.
    check_result("an unknown procedure throws a catchable error",
                 conn.run("try { await import('procedures/nope'); return 'resolved'; }\n"
                          "catch (e) { return e.message; }"),
                 "Cannot find module 'procedures/nope'")
    check_result("a bare specifier is still unresolvable",
                 conn.run("try { await import('lodash'); return 'resolved'; }\n"
                          "catch (e) { return e.message; }"), "Cannot find module 'lodash'")
    check_result("a nested name is refused (no traversal)",
                 conn.run("try { await import('procedures/a/b'); return 'resolved'; }\n"
                          "catch (e) { return e.message; }"), "Cannot find module 'procedures/a/b'")
    check_result("a traversal attempt is refused",
                 conn.run("try { await import('procedures/../lib_money'); return 'resolved'; }\n"
                          "catch (e) { return e.message; }"), "Cannot find module 'procedures/../lib_money'")
    check_failed_script("a static import of an unknown procedure fails the run",
                        conn.run("import { x } from 'procedures/nope';\nreturn x;"),
                        "400-9", "Cannot find module 'procedures/nope'")

    # An unresolvable import is refused when the procedure is installed, not when it is called.
    check_code("saving a procedure whose import does not exist is refused",
               conn.save_procedure("lib_broken", "import { x } from 'procedures/absent';\nexport const y = x;"),
               "ERROR", "400-18")
    check_status("… and nothing was stored",
                 conn.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "lib_broken"}), "OK")
    check_status("the same save succeeds once the library exists",
                 conn.save_procedure("lib_broken", "import { cents } from 'procedures/lib_money';\n"
                                                   "export const y = cents;"), "OK")
    check_status("the built-in specifiers are never checked",
                 conn.save_procedure("lib_builtins", "import db from 'db';\nexport const n = typeof db;"), "OK")

    # The re-export forms carry a specifier too, so they are checked and they fail the same way.
    check_code("a re-export from a missing procedure is refused at save time",
               conn.save_procedure("lib_reexport", "export { cents } from 'procedures/absent';"), "ERROR", "400-18")
    check_code("an export-all from a missing procedure is refused at save time",
               conn.save_procedure("lib_starexport", "export * from 'procedures/absent';"), "ERROR", "400-18")
    check_failed_script("a re-export from a missing procedure fails a run",
                        conn.run("export { cents } from 'procedures/absent';"),
                        "400-9", "Cannot find module 'procedures/absent'")
    check_failed_script("an export-all from a missing procedure fails a run",
                        conn.run("export * from 'procedures/absent';"),
                        "400-9", "Cannot find module 'procedures/absent'")

    check_result("a named re-export takes the library's binding",
                 conn.run("export { RATE } from 'procedures/lib_money';"), {"RATE": 0.21})
    check_result("… even when a local of the same name is in scope",
                 conn.run("const RATE = 999;\nexport { RATE } from 'procedures/lib_money';"), {"RATE": 0.21})
    check_result("a renamed re-export",
                 conn.run("export { RATE as rate } from 'procedures/lib_money';"), {"rate": 0.21})
    check_status("store a library with a default export to re-export",
                 conn.save_procedure("lib_def", "export default 'the-default'; export const other = 1;"), "OK")
    check_result("a default can be re-exported under a name",
                 conn.run("export { default as picked } from 'procedures/lib_def';"), {"picked": "the-default"})
    check_result("export * carries the named exports but not the default",
                 conn.run("export * from 'procedures/lib_def';"), {"other": 1})
    check_result("a sourceless export still reads the local scope",
                 conn.run("const local = 5;\nexport { local };"), {"local": 5})

    # A computed specifier is not statically visible, so the save-time check cannot see it. It must still
    # fail when it runs - the install-time check is an early warning, not a guarantee.
    check_status("a computed specifier passes the save-time check",
                 conn.save_procedure("lib_computed",
                                     "const where = 'procedures/' + 'absent';\n"
                                     "const mod = await import(where);\n"
                                     "return mod.anything;"), "OK")
    computed = conn.send({"type": "CALL_PROCEDURE", "databaseName": DB, "procedureName": "lib_computed"})
    check("… and still fails when it runs",
          computed.get("errorCode") == "400-9"
          and "Cannot find module 'procedures/absent'" in (computed.get("message") or ""),
          f"got {computed.get('errorCode')} {computed.get('message')!r}")

    # A library deleted after its importer was installed: the check ran when there was nothing wrong.
    check_status("store a base library", conn.save_procedure("lib_base", "export const base = 7;"), "OK")
    check_status("store a library that imports it",
                 conn.save_procedure("lib_mid", "import { base } from 'procedures/lib_base';\n"
                                                "export function mid() { return base; }"), "OK")
    check_result("the chain works while both exist",
                 conn.run("import { mid } from 'procedures/lib_mid';\nreturn mid();"), 7)
    check_status("delete the base library",
                 conn.send({"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "lib_base"}), "OK")
    check_failed_script("a nested import that has since been deleted fails, naming the missing module",
                        conn.run("import { mid } from 'procedures/lib_mid';\nreturn mid();"),
                        "400-9", "Cannot find module 'procedures/lib_base'")

    # Console output still comes back when the failure is an import. A static import is bound before any
    # statement runs, so the logging has to happen before a dynamic one for the point to be observable.
    failed = conn.run("console.log('before the import');\n"
                      "await import('procedures/absent');\n"
                      "return 'unreachable';")
    check_failed_script("an import failure is reported as a script failure", failed, "400-9",
                        "Cannot find module 'procedures/absent'")
    check("… and the console output up to that point is still returned",
          failed.get("logs") == ["before the import"], f"got {failed.get('logs')!r}")

    check_status("store a disabled library",
                 conn.save_procedure("lib_off", "export const value = 1;", enabled=False), "OK")
    check_code("importing a disabled procedure is refused at save time too",
               conn.save_procedure("lib_wants_off", "import { value } from 'procedures/lib_off';\n"
                                                    "export const v = value;"), "ERROR", "400-18")
    check_failed_script("a disabled procedure is not importable",
                        conn.run("import { value } from 'procedures/lib_off';\nreturn value;"),
                        "400-9", "Cannot find module 'procedures/lib_off'")

    check_status("store a library in the other database",
                 conn.save_procedure("lib_elsewhere", "export const value = 1;", db=OTHER_DB), "OK")
    check_failed_script("a procedure of another database is out of scope",
                        conn.run("import { value } from 'procedures/lib_elsewhere';\nreturn value;"),
                        "400-9", "Cannot find module 'procedures/lib_elsewhere'")

    check_status("store a self-importing procedure",
                 conn.save_procedure("lib_loop", "import { self } from 'procedures/lib_loop';\n"
                                                 "export function self() { return 1; }"), "OK")
    check_failed_script("a self-import is a cycle",
                        conn.run("import { self } from 'procedures/lib_loop';\nreturn self();"),
                        "400-9", "Circular import of module")
    # A save refuses an import that does not resolve yet, so the pair is built dependency-first and
    # the cycle is closed by updating the second one afterwards.
    check_status("store the second half without its import first",
                 conn.save_procedure("lib_b", "export function b() { return 1; }"), "OK")
    check_status("store the first half of a mutual import",
                 conn.save_procedure("lib_a", "import { b } from 'procedures/lib_b';\n"
                                              "export function a() { return b(); }"), "OK")
    check_status("close the cycle by updating the second half",
                 conn.save_procedure("lib_b", "import { a } from 'procedures/lib_a';\n"
                                              "export function b() { return a(); }"), "OK")
    check_failed_script("a mutual import is a cycle",
                        conn.run("import { a } from 'procedures/lib_a';\nreturn a();"),
                        "400-9", "Circular import of module")

    check_status("store a library that counts its own evaluations",
                 conn.save_procedure("lib_counter", "import db from 'db';\n"
                                                    "const prev = db.findById(db.name, '%s', 'eval-count');\n"
                                                    "const seen = (prev === null ? 0 : prev.seen) + 1;\n"
                                                    "db.save(db.name, '%s', { _id: 'eval-count', seen });\n"
                                                    "export const marker = seen;" % (COLL, COLL)), "OK")
    check_result("an imported module evaluates once per run",
                 conn.run("import db from 'db';\n"
                          "import 'procedures/lib_counter';\n"
                          "await import('procedures/lib_counter');\n"
                          "return db.findById(db.name, '%s', 'eval-count').seen;" % COLL), 1)

    # The importer's own budget pays for the import, so a runaway library cannot buy fresh compute.
    check_status("store a library that never terminates",
                 conn.save_procedure("lib_spin", "while (true) { }"), "OK")
    check_failed_script("an imported module shares the importing run's budget",
                        conn.run("import 'procedures/lib_spin';\nreturn 'never';"), "400-11")

    # A procedure written for CALL_PROCEDURE returns rather than exports, and only exports are
    # importable - so it imports as undefined. The trap worth pinning, not a defect.
    check_status("store a procedure written in the CALL_PROCEDURE style",
                 conn.save_procedure("lib_returning", "return 5;"), "OK")
    check_result("a procedure written with a top-level return imports as undefined",
                 conn.run("import lib from 'procedures/lib_returning';\nreturn typeof lib;"), "undefined")
    check_result("… and it is still callable as a procedure",
                 conn.send({"type": "CALL_PROCEDURE", "databaseName": DB, "procedureName": "lib_returning"}), 5)

    check_status("store a library that throws while evaluating",
                 conn.save_procedure("lib_throwing", "throw new Error('library boom');"), "OK")
    check_result("an error thrown by a library is catchable in the importer",
                 conn.run("try { await import('procedures/lib_throwing'); return 'imported'; }\n"
                          "catch (e) { return e.message; }"), "library boom")

    check_status("store a library that reads through db",
                 conn.save_procedure("lib_reader", "import db from 'db';\n"
                                                   "export function count() {\n"
                                                   "    return db.aggregate(db.name, '%s', []).length;\n"
                                                   "}" % COLL), "OK")
    check_result("a library reaches the same db binding as its importer",
                 conn.run("import db from 'db';\n"
                          "import { count } from 'procedures/lib_reader';\n"
                          "return count() === db.aggregate(db.name, '%s', []).length;" % COLL), True)


def test_language_surface(conn: Conn):
    section("Language surface (ES2026)")
    cases = [
        ("generators and spread", "function* g() { yield 1; yield 2; yield 3; }\nreturn [...g()];", [1, 2, 3]),
        ("async/await with Promise.all",
         "const values = await Promise.all([1, Promise.resolve(2)]);\nreturn values;", [1, 2]),
        ("Promise.allSettled",
         "const r = await Promise.allSettled([Promise.resolve(1), Promise.reject(new Error('x'))]);\n"
         "return r.map(e => e.status);", ["fulfilled", "rejected"]),
        ("Promise.any and AggregateError",
         "try { await Promise.any([Promise.reject(new Error('a')), Promise.reject(new Error('b'))]); }\n"
         "catch (e) { return { name: e.name, count: e.errors.length }; }", {"name": "AggregateError", "count": 2}),
        ("Promise.withResolvers",
         "const { promise, resolve } = Promise.withResolvers();\nresolve('resolved');\nreturn await promise;",
         "resolved"),
        ("async generators and for await",
         "async function* g() { yield 1; yield 2; }\nlet total = 0;\nfor await (const v of g()) total += v;\n"
         "return total;", 3),
        ("classes: private fields, getters, static blocks",
         "class Counter {\n  static created = 0;\n  static { Counter.created = 100; }\n  #count = 0;\n"
         "  bump() { this.#count++; return this; }\n  get value() { return this.#count; }\n}\n"
         "const c = new Counter().bump().bump();\n"
         "return { value: c.value, created: Counter.created, isCounter: c instanceof Counter };",
         {"value": 2, "created": 100, "isCounter": True}),
        ("inheritance and super",
         "class A { greet() { return 'a'; } }\nclass B extends A { greet() { return super.greet() + 'b'; } }\n"
         "return new B().greet();", "ab"),
        ("destructuring, defaults, rest",
         "const { a, b = 2, ...rest } = { a: 1, c: 3, d: 4 };\nconst [x, , z = 9] = [10, 20];\n"
         "return { a, b, rest, x, z };", {"a": 1, "b": 2, "rest": {"c": 3, "d": 4}, "x": 10, "z": 9}),
        ("optional chaining and nullish coalescing",
         "const o = { a: { b: null } };\nreturn [o?.a?.b ?? 'fallback', o?.x?.y?.z, o.missing?.()];",
         ["fallback", None, None]),
        ("tagged templates and String.raw",
         "const tag = (s, ...v) => s.raw.join('|') + '#' + v.join(',');\n"
         "return [tag`a${1}b`, String.raw`x\\ny`];", ["a|b#1", "x\\ny"]),
        ("labeled break and continue", "let seen = 0;\nouter: for (let i = 0; i < 3; i++) "
                                       "{ for (let j = 0; j < 3; j++) { seen++; if (j === 1) continue outer; "
                                       "if (i === 2) break outer; } }\nreturn seen;", 5),
        ("closures capture per-iteration let",
         "const fns = [];\nfor (let i = 0; i < 3; i++) fns.push(() => i);\nreturn fns.map(f => f());", [0, 1, 2]),
        ("Map, Set and Set operations",
         "const m = new Map([['a', 1]]);\nconst s = new Set([1, 2]);\n"
         "return { got: m.get('a'), union: [...s.union(new Set([3]))], "
         "inter: [...s.intersection(new Set([2, 9]))] };", {"got": 1, "union": [1, 2, 3], "inter": [2]}),
        ("iterator helpers", "return [1, 2, 3, 4].values().map(x => x * 2).take(3).toArray();", [2, 4, 6]),
        ("custom iterable via Symbol.iterator",
         "class R { constructor(n) { this.n = n; } *[Symbol.iterator]() { for (let i = 0; i < this.n; i++) yield i; } }\n"
         "return [...new R(3)];", [0, 1, 2]),
        ("typed arrays and DataView",
         "const buf = new ArrayBuffer(8);\nnew DataView(buf).setFloat64(0, 1.5);\n"
         "return { f: new DataView(buf).getFloat64(0), bytes: new Uint8Array(buf).length };",
         {"f": 1.5, "bytes": 8}),
        ("resizable ArrayBuffer",
         "const buf = new ArrayBuffer(4, { maxByteLength: 16 });\nbuf.resize(8);\n"
         "return { size: buf.byteLength, resizable: buf.resizable };", {"size": 8, "resizable": True}),
        ("Uint8Array hex helpers",
         "const bytes = new Uint8Array([1, 2, 255]);\n"
         "return { hex: bytes.toHex(), back: [...Uint8Array.fromHex('0102ff')] };",
         {"hex": "0102ff", "back": [1, 2, 255]}),
        ("BigInt arithmetic", "return (2n ** 64n).toString();", "18446744073709551616"),
        ("Symbol and well-known symbols",
         "const s = Symbol('tag');\nconst o = { [s]: 1, [Symbol.toStringTag]: 'Custom' };\n"
         "return { viaSymbol: o[s], tag: Object.prototype.toString.call(o), type: typeof s };",
         {"viaSymbol": 1, "tag": "[object Custom]", "type": "symbol"}),
        ("Proxy and Reflect",
         "const p = new Proxy({ a: 1 }, { get: (t, k) => k in t ? t[k] : 'trapped' });\n"
         "return { hit: p.a, miss: p.b, keys: Reflect.ownKeys({ x: 1, y: 2 }) };",
         {"hit": 1, "miss": "trapped", "keys": ["x", "y"]}),
        ("property descriptors and freeze",
         "const o = Object.freeze({ a: 1 });\nlet threw = false;\n"
         "try { o.a = 2; } catch (e) { threw = e instanceof TypeError; }\n"
         "return { frozen: Object.isFrozen(o), threw, value: o.a };",
         {"frozen": True, "threw": True, "value": 1}),
        ("regex: named groups and the d flag",
         "const m = /(?<year>\\d{4})-(?<month>\\d{2})/d.exec('2024-07');\n"
         "return { year: m.groups.year, indices: m.indices.groups.month };", {"year": "2024", "indices": [5, 7]}),
        ("regex: lookbehind and unicode property escapes",
         "return [/(?<=a)b/.test('ab'), /\\p{Script=Greek}/u.test('\\u03b1'), /[\\p{ASCII}--[a-z]]/v.test('A')];",
         [True, True, True]),
        ("regex: replace with a function and named group references",
         "return 'a-b'.replace(/(?<first>\\w)-(?<second>\\w)/, '$<second>-$<first>');", "b-a"),
        ("RegExp.escape",
         "const re = new RegExp(RegExp.escape('a.b'));\nreturn [re.test('a.b'), re.test('axb')];", [True, False]),
        ("explicit resource management",
         "const order = [];\n{\n  using first = { [Symbol.dispose]() { order.push('first'); } };\n"
         "  using second = { [Symbol.dispose]() { order.push('second'); } };\n}\nreturn order;",
         ["second", "first"]),
        ("DisposableStack",
         "const order = [];\n{\n  using stack = new DisposableStack();\n"
         "  stack.defer(() => order.push('deferred'));\n}\nreturn order;", ["deferred"]),
        ("Temporal arithmetic",
         "const date = Temporal.PlainDate.from('2024-01-31');\n"
         "return { plus: date.add({ months: 1 }).toString(), "
         "days: Temporal.Duration.from({ hours: 48 }).total('days') };",
         {"plus": "2024-02-29", "days": 2}),
        ("Temporal.Now", "return typeof Temporal.Now.plainDateISO().toString();", "string"),
        ("structuredClone is a deep copy",
         "const src = { nested: { list: [1, 2] } };\nconst copy = structuredClone(src);\n"
         "copy.nested.list.push(3);\nreturn { src: src.nested.list.length, copy: copy.nested.list.length };",
         {"src": 2, "copy": 3}),
        ("Object.groupBy and Map.groupBy",
         "const grouped = Object.groupBy([1, 2, 3, 4], n => n % 2 ? 'odd' : 'even');\n"
         "return { odd: grouped.odd, mapped: Map.groupBy([1, 2], n => n).get(1) };",
         {"odd": [1, 3], "mapped": [1]}),
        ("Array.fromAsync", "return await Array.fromAsync([Promise.resolve('a'), 'b']);", ["a", "b"]),
        ("array by-copy methods",
         "const base = [3, 1, 2];\nreturn { sorted: base.toSorted(), reversed: base.toReversed(), "
         "replaced: base.with(0, 9), untouched: base };",
         {"sorted": [1, 2, 3], "reversed": [2, 1, 3], "replaced": [9, 1, 2], "untouched": [3, 1, 2]}),
        ("JSON replacer and reviver",
         "const text = JSON.stringify({ a: 1, b: 2 }, ['a']);\n"
         "const revived = JSON.parse('{\"n\":1}', (k, v) => typeof v === 'number' ? v * 10 : v);\n"
         "return { text, revived: revived.n };", {"text": '{"a":1}', "revived": 10}),
        ("error cause and instanceof through the chain",
         "const e = new RangeError('outer', { cause: 'inner' });\n"
         "return { cause: e.cause, isRange: e instanceof RangeError, isError: e instanceof Error, name: e.name };",
         {"cause": "inner", "isRange": True, "isError": True, "name": "RangeError"}),
        ("try/catch/finally ordering",
         "const order = [];\ntry { order.push('try'); throw new Error('x'); }\n"
         "catch { order.push('catch'); } finally { order.push('finally'); }\nreturn order;",
         ["try", "catch", "finally"]),
        ("strict mode is always on",
         "try { undeclaredGlobal = 1; return 'assigned'; } catch (e) { return e.constructor.name; }",
         "ReferenceError"),
        ("Math and number formatting",
         "return { trunc: Math.trunc(-4.7), imul: Math.imul(3, 4), precise: Math.sumPrecise([0.1, 0.2]), "
         "big: 1e21.toString(), fixed: (1.005).toFixed(2) };",
         {"trunc": -4, "imul": 12, "precise": 0.30000000000000004, "big": "1e+21", "fixed": "1.00"}),
        ("globalThis reflects top-level values",
         "var topLevel = 'visible';\nreturn globalThis.topLevel;", "visible"),
        ("getters, setters and the sequence operator",
         "const o = { _v: 1, get v() { return this._v; }, set v(n) { this._v = n * 2; } };\n"
         "o.v = 5;\nreturn (0, o.v);", 10),
        ("timers run on the event loop",
         "return await new Promise(resolve => setTimeout(() => resolve('timed'), 5));", "timed"),
        ("microtasks run before timers",
         "const order = [];\nsetTimeout(() => order.push('timeout'), 0);\n"
         "await Promise.resolve().then(() => order.push('micro'));\n"
         "await new Promise(r => setTimeout(r, 5));\nreturn order;", ["micro", "timeout"]),
    ]
    for label, script, expected in cases:
        check_result(label, conn.run(script), expected)


def test_result_cap(conn: Conn):
    section("Result cap")
    big = conn.run("console.log('ran before returning');\nreturn new Array(5000).fill('0123456789');")
    check_code("an oversized result is refused", big, "ERROR", "400-15")
    check("… and the console output still comes back", big.get("logs") == ["ran before returning"],
          f"logs={big.get('logs')!r}")
    check("… and the message names the error", "ScriptResultTooLargeError" in (big.get("message") or ""),
          f"message={big.get('message')!r}")
    check_result("the same script returning a small value succeeds",
                 conn.run("return new Array(5).fill('0123456789').length;"), 5)
    check_result("a whole-collection read cannot be returned, but its count can",
                 conn.run('import db from "db";\n'
                          f'let n = 0;\nfor (const doc of db.cursor(db.name, "{BIG_COLL}", '
                          "[{ type: 'SORT', fieldName: '_id', ascending: true }], { batchSize: 20 })) n++;\n"
                          "return n;"), BIG_DOCS)


def test_cursor(conn: Conn):
    section("db.cursor — paged reads")
    sort = "[{ type: 'SORT', fieldName: '_id', ascending: true }]"
    walk = ('import db from "db";\n'
            "const seen = new Set();\n"
            f'for (const doc of db.cursor(db.name, "{BIG_COLL}", {sort}, {{ batchSize: 20 }})) '
            "seen.add(doc._id);\n"
            "return seen.size;")
    check_result("a walk visits every document exactly once", conn.run(walk), BIG_DOCS)

    counted = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": BIG_COLL,
                         "aggregationSteps": [{"type": "COUNT"}]})
    wire_count = (counted.get("results") or [{}])[0].get("count")
    check("… and the total matches a plain AGGREGATE count", wire_count == BIG_DOCS,
          f"AGGREGATE count={wire_count!r}")

    # The premise of the whole feature: the same work, one batch at a time, fits a budget the
    # all-at-once read cannot.
    check_code("db.aggregate of the whole collection exceeds the memory budget",
               conn.run('import db from "db";\n'
                        f'return db.aggregate(db.name, "{BIG_COLL}", []).length;'), "ERROR", "400-12")
    check_result("… while the same walk through db.cursor succeeds", conn.run(walk), BIG_DOCS)

    check_result("an absent options object walks with the configured batch size",
                 conn.run('import db from "db";\n'
                          f'let n = 0;\nfor (const doc of db.cursor(db.name, "{BIG_COLL}", {sort})) n++;\n'
                          "return n;"), BIG_DOCS)
    check_result("a batchSize above the maximum is clamped, not refused",
                 conn.run('import db from "db";\n'
                          f'let n = 0;\nfor (const doc of db.cursor(db.name, "{COLL}", {sort}, '
                          "{ batchSize: 100000 })) n++;\nreturn n > 0;"), True)
    check_failed_script("a non-positive batchSize is refused",
                        conn.run('import db from "db";\n'
                                 f'db.cursor(db.name, "{COLL}", {sort}, {{ batchSize: 0 }}).next();'),
                        "400-9", "RangeError")
    check_result("spread and the iterator helpers work on a cursor",
                 conn.run('import db from "db";\n'
                          f'const cursor = db.cursor(db.name, "{BIG_COLL}", {sort}, {{ batchSize: 20 }});\n'
                          "return cursor.take(3).toArray().map(d => d._id);"),
                 ["big0000", "big0001", "big0002"])
    check_result("a cursor over a collection with no documents yields nothing",
                 conn.run('import db from "db";\n'
                          f'return [...db.cursor(db.name, "{EMPTY_COLL}", {sort})].length;'), 0)
    # Absence stays a value on this surface: an empty batch simply ends the walk, exactly as
    # db.aggregate answers [] for a collection with no results.
    check_result("a cursor over an unknown collection ends the walk instead of throwing",
                 conn.run('import db from "db";\n'
                          f'return [...db.cursor(db.name, "neverCreated", {sort})].length;'), 0)
    check_result("abandoning a cursor mid-walk leaves nothing to release",
                 conn.run('import db from "db";\n'
                          f'for (const doc of db.cursor(db.name, "{BIG_COLL}", {sort}, {{ batchSize: 20 }})) '
                          "break;\nreturn 'released';"), "released")


def test_permissions(conn: Conn):
    section("Permissions")
    granted = {"type": "CREATE_USER", "username": "script_granted", "password": "password1234", "admin": False,
               "globalPermissions": [], "databasePermissions": {DB: "READ_WRITE", OTHER_DB: "READ_WRITE"},
               "collectionPermissions": {}, "scriptPermissions": {DB: True}}
    denied = {"type": "CREATE_USER", "username": "script_denied", "password": "password1234", "admin": False,
              "globalPermissions": [], "databasePermissions": {DB: "READ_WRITE"},
              "collectionPermissions": {}, "scriptPermissions": {}}
    reader = {"type": "CREATE_USER", "username": "script_reader", "password": "password1234", "admin": False,
              "globalPermissions": [], "databasePermissions": {DB: "READ"},
              "collectionPermissions": {}, "scriptPermissions": {DB: True}}
    owner = {"type": "CREATE_USER", "username": "script_owner", "password": "password1234", "admin": False,
             "globalPermissions": [], "databasePermissions": {}, "collectionPermissions": {},
             "scriptPermissions": {}}
    for payload in (granted, denied, reader, owner):
        conn.send({"type": "DELETE_USER", "username": payload["username"]})
        check_status(f"create {payload['username']}", conn.send(payload), "OK")

    with Conn() as anon:
        check_code("an unauthenticated connection is refused", anon.run("return 1;"), "UNAUTHENTICATED", "401-1")

    with Conn() as c:
        c.authenticate("script_denied", "password1234")
        check_code("a user with no script grant is forbidden", c.run("return 1;"), "FORBIDDEN", "403-1")

    with Conn() as c:
        c.authenticate("script_granted", "password1234")
        check_result("a granted user may run a script on that database", c.run("return 'ran';"), "ran")
        check_code("… but not on another database, even with READ_WRITE there",
                   c.run("return 1;", db=OTHER_DB), "FORBIDDEN", "403-1")

    with Conn() as c:
        c.authenticate("script_reader", "password1234")
        check_result("a read-only grantee can read", c.run(
            f'import db from "db";\nreturn db.findById(db.name, "{COLL}", "d1")._id;'), "d1")
        check_result("… but its writes are denied inside the script", c.run(
            'import db from "db";\n'
            f'try {{ db.save(db.name, "{COLL}", {{ _id: "denied" }}); return "wrote"; }}\n'
            "catch (e) { return e instanceof Error; }"), True)

    check_status("make script_owner an owner of the database",
                 conn.send({"type": "SET_DATABASE_OWNERS", "databaseName": DB, "owners": ["script_owner"]}), "OK")
    with Conn() as c:
        c.authenticate("script_owner", "password1234")
        check_result("a database owner needs no explicit grant", c.run("return 'owner ran';"), "owner ran")
    check_status("drop the ownership again",
                 conn.send({"type": "SET_DATABASE_OWNERS", "databaseName": DB, "owners": []}), "OK")

    check_status("revoke the grant", conn.send(
        {"type": "CHANGE_PERMISSIONS", "username": "script_granted", "admin": False, "globalPermissions": [],
         "databasePermissions": {DB: "READ_WRITE"}, "collectionPermissions": {}, "scriptPermissions": {}}), "OK")
    with Conn() as c:
        c.authenticate("script_granted", "password1234")
        check_code("the revoked user is forbidden again", c.run("return 1;"), "FORBIDDEN", "403-1")

    check_status("an explicit false grant is stored", conn.send(
        {"type": "CHANGE_PERMISSIONS", "username": "script_granted", "admin": False, "globalPermissions": [],
         "databasePermissions": {DB: "READ_WRITE"}, "collectionPermissions": {},
         "scriptPermissions": {DB: False}}), "OK")
    with Conn() as c:
        c.authenticate("script_granted", "password1234")
        check_code("an explicit false is a denial", c.run("return 1;"), "FORBIDDEN", "403-1")

    check_code("a grant naming the reserved admin database is rejected", conn.send(
        {"type": "CHANGE_PERMISSIONS", "username": "script_granted", "admin": False, "globalPermissions": [],
         "databasePermissions": {}, "collectionPermissions": {}, "scriptPermissions": {"admin": True}}),
        "ERROR", "400-1")
    check_code("a grant value that is neither a boolean nor a level name is rejected", conn.send(
        {"type": "CHANGE_PERMISSIONS", "username": "script_granted", "admin": False, "globalPermissions": [],
         "databasePermissions": {}, "collectionPermissions": {}, "scriptPermissions": {DB: "READ"}}),
        "ERROR", "400-1")

    users = conn.send({"type": "LIST_USERS", "aggregationSteps": [
        {"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "_id", "value": "script_reader"}}]})
    listed = (users.get("users") or [{}])[0]
    # The grant is reported as a level; a boolean sent by an older client reads as RUN.
    check("LIST_USERS reports the script grants", listed.get("scriptPermissions") == {DB: "RUN"},
          f"scriptPermissions={listed.get('scriptPermissions')!r}")


def test_admin_is_unrestricted(conn: Conn):
    section("Admins")
    check_result("an admin needs no grant", conn.run("return 'admin ran';"), "admin ran")
    check_result("an admin script is still bound to the requested database",
                 conn.run('import db from "db";\n'
                          f'try {{ db.findById("{OTHER_DB}", "{COLL}", "d1"); return "reached"; }}\n'
                          "catch (e) { return 'refused'; }"), "refused")
    check_result("an admin may script the other database in its own run",
                 conn.run("return 'other db';", db=OTHER_DB), "other db")


# ══════════════════════════════════════════════════════════════════════════
# Phase 2 — scripts disabled
# ══════════════════════════════════════════════════════════════════════════

def test_procedure_imports_disabled(conn: Conn):
    section("Procedure imports disabled (scriptProcedureImportEnabled=false)")
    check_failed_script("the procedures/ prefix is unresolvable",
                        conn.run("import { cents } from 'procedures/lib_money';\nreturn cents(1);"),
                        "400-9", "Cannot find module 'procedures/lib_money'")
    check_result("… including dynamically",
                 conn.run("try { await import('procedures/lib_money'); return 'resolved'; }\n"
                          "catch (e) { return e.message; }"),
                 "Cannot find module 'procedures/lib_money'")
    check_result("the built-in specifiers still resolve",
                 conn.run("import db from 'db';\nreturn typeof db.findById;"), "function")
    check_status("a procedure with no imports still runs",
                 conn.send({"type": "CALL_PROCEDURE", "databaseName": DB, "procedureName": "lib_returning"}), "OK")


def test_engine_disabled():
    section("Engine disabled (scriptsEnabled=false)")
    with Conn() as conn:
        conn.authenticate()
        check_code("an admin is refused", conn.run("return 1;"), "FORBIDDEN", "403-2")
        check_code("… on any database", conn.run("return 1;", db=OTHER_DB), "FORBIDDEN", "403-2")
    with Conn() as conn:
        conn.authenticate("script_reader", "password1234")
        check_code("a granted user is refused too", conn.run("return 1;"), "FORBIDDEN", "403-2")
    with Conn() as conn:
        conn.authenticate()
        check_status("other operations keep working",
                     conn.send({"type": "LIST_COLLECTIONS", "databaseName": DB}), "OK")


def cleanup(conn: Conn):
    section("Cleanup")
    for username in ("script_granted", "script_denied", "script_reader", "script_owner"):
        conn.send({"type": "DELETE_USER", "username": username})
    for db in (DB, OTHER_DB):
        check_status(f"drop {db}", conn.send({"type": "DROP_DATABASE", "databaseName": db}), "OK")


# ══════════════════════════════════════════════════════════════════════════

def main():
    print("\n" + "═" * 70)
    print("  LWNRDB — RUN_SCRIPT (SimpleJS over the wire) test suite")
    print("═" * 70)

    jar = os.path.join(REPO_ROOT, JAR)
    if not os.path.isfile(jar):
        print(f"\n[ERROR] Jar not found at {jar}. Build it first: mvn package -DskipTests\n")
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-runscript-")
    log_path = os.path.join(work_dir, "server.log")
    print(f"  Working dir: {work_dir}")

    proc = None
    try:
        write_config(work_dir, scripts_enabled=True)
        print(f"  Starting server (scripts enabled) on {HOST}:{PORT} ...")
        proc = start_server(work_dir, log_path)

        with admin_conn() as conn:
            setup_data(conn)
            test_basics(conn)
            test_stack_traces(conn)
            test_pending_promise(conn)
            test_compiled_script_cache(conn)
            test_console_output(conn)
            test_sandbox_limits(conn)
            test_request_validation(conn)
            test_host_reads(conn)
            test_host_writes(conn)
            test_transactions(conn)
            test_scope_and_failures(conn)
            test_schema_interaction(conn)
            test_arguments(conn)
            test_custom_types(conn)
            test_capabilities(conn)
            test_procedure_imports(conn)
            test_language_surface(conn)
            test_result_cap(conn)
            test_cursor(conn)
            test_permissions(conn)
            test_admin_is_unrestricted(conn)

        stop_server(proc)
        proc = None

        write_config(work_dir, scripts_enabled=True, procedure_imports_enabled=False)
        print(f"\n  Restarting server (procedure imports disabled) on {HOST}:{PORT} ...")
        proc = start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_procedure_imports_disabled(conn)

        stop_server(proc)
        proc = None

        write_config(work_dir, scripts_enabled=False)
        print(f"\n  Restarting server (scripts disabled) on {HOST}:{PORT} ...")
        proc = start_server(work_dir, log_path)
        test_engine_disabled()

        with admin_conn() as conn:
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
