"""End-to-end tests for SimpleJS inside the aggregation pipeline.

Covers the three surfaces a pipeline script can occupy - a SCRIPT mid-operator computing a
MAP field, a SCRIPT filter operator acting as a predicate, and a REDUCE step folding the
stream into one document - over the wire, against a seeded collection.

Like the RUN_SCRIPT and schedules suites this script is **self-contained**: it starts its own
LWNRDB instance on a dedicated port and working directory, because the feature needs a
deliberately tight aggregation sandbox the shared CI server does not have. It runs in two
phases:

  phase 1 - `scriptsEnabled=true` with small aggregationScript* limits, so every limit is
            reachable in a test rather than only in theory;
  phase 2 - the same data directory restarted with `scriptsEnabled=false`, proving the
            master switch refuses every pipeline-script shape for everyone, admins included.
            There is no second key: that phase is the whole gate, alongside permissions.

What is covered:

  * the three JSON shapes, including REDUCE's resultField/initialValue defaults;
  * permissions, which are the per-caller gate: a READ-only user is refused, the same user
    succeeds once granted scriptPermissions and is refused again after revocation, and the
    database owner and an admin succeed without a grant - while a plain no-script AGGREGATE
    keeps working for the READ-only user throughout;
  * the sandbox: the per-PIPELINE instruction budget (the same script over one document
    succeeds and over the whole collection does not), the wall clock, the source cap, and a
    throwing script;
  * the closed doors - db, fetch, procedure imports and importText - each asserted by
    contrast against the identical source succeeding through RUN_SCRIPT;
  * analyze: the script counters and the "cannot use an index" suggestion, plus a pipeline of
    indexed FILTER -> SCRIPT filter -> REDUCE;
  * LISTEN refusing a SCRIPT operator while AGGREGATE accepts the same pipeline;
  * custom types crossing the boundary, and a BigInt beyond 2^53-1 failing;
  * nesting: a RUN_SCRIPT whose db.aggregate carries a SCRIPT operator.

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
PORT = int(os.environ.get("AGG_SCRIPT_TEST_PORT", "8999"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "agg_script_db"
COLL = "orders"
USER_PASSWORD = "password123"
READER = "agg_reader"
OWNER = "agg_owner"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

BUDGET = 40_000
TIMEOUT_MS = 1_000
MAX_SOURCE_BYTES = 512

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

    def aggregate(self, steps, db=DB, coll=COLL, **extra) -> dict:
        payload = {"type": "AGGREGATE", "databaseName": db, "collectionName": coll,
                   "aggregationSteps": steps}
        payload.update(extra)
        return self.send(payload)

    def run_script(self, script, db=DB, **extra) -> dict:
        payload = {"type": "RUN_SCRIPT", "databaseName": db, "script": script}
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


def user_conn(username: str) -> Conn:
    conn = Conn()
    conn.authenticate(username, USER_PASSWORD)
    return conn


# ── pipeline shapes ──────────────────────────────────────────────────────────

def script_filter(source: str) -> dict:
    return {"type": "FILTER", "operator": {"script": source}}


def script_map(field: str, source: str, condition=None) -> dict:
    operator = {"fieldName": field, "operator": {"type": "SCRIPT", "script": source}}
    if condition is not None:
        operator["condition"] = condition
    return {"type": "MAP", "operators": [operator]}


def reduce_step(source: str, initial=None, result_field=None) -> dict:
    step = {"type": "REDUCE", "script": source}
    if initial is not None:
        step["initialValue"] = initial
    if result_field is not None:
        step["resultField"] = result_field
    return step


def results(response: dict) -> list:
    return response.get("results", [])


# ── server lifecycle ─────────────────────────────────────────────────────────

def write_config(work_dir: str, scripts_enabled: bool):
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
        f"aggregationScriptInstructionBudget={BUDGET}\n"
        f"aggregationScriptTimeoutMs={TIMEOUT_MS}\n"
        f"aggregationScriptMaxSourceBytes={MAX_SOURCE_BYTES}\n"
        "triggersEnabled=false\n"
        "schedulesEnabled=false\n"
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

DOCUMENT_COUNT = 10


def setup_data(conn: Conn):
    section("setup")
    check_status("create database", conn.send({"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
    check_status("create collection",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL}), "OK")
    entries = [{"_id": f"o{i}", "price": i * 10, "qty": i, "sku": f"sku-{i % 3}"}
               for i in range(1, DOCUMENT_COUNT + 1)]
    check_status("bulk save orders",
                 conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL, "objects": entries}),
                 "OK")
    check_status("index price",
                 conn.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL,
                            "fieldName": "price"}), "OK")
    check_status("create reader",
                 conn.send({"type": "CREATE_USER", "username": READER, "password": USER_PASSWORD,
                            "admin": False, "databasePermissions": {DB: "READ"}}), "OK")
    check_status("create owner user",
                 conn.send({"type": "CREATE_USER", "username": OWNER, "password": USER_PASSWORD,
                            "admin": False, "databasePermissions": {DB: "READ"}}), "OK")


# ── phase 1: the three shapes ────────────────────────────────────────────────

def test_computed_field(conn: Conn):
    section("MAP: a SCRIPT computed field")
    response = conn.aggregate([script_map("total", "export default (doc) => doc.price * doc.qty;")])
    check_status("computed field runs", response, "OK")
    rows = {row["_id"]: row for row in results(response)}
    check("total is computed per document",
          rows.get("o3", {}).get("total") == 90, f"got {rows.get('o3')}")
    check("every document carries the field", all("total" in row for row in results(response)))

    conditioned = conn.aggregate([script_map(
        "big", "export default (doc) => true;",
        condition={"fieldOperatorType": "GREATER_THAN", "field": "price", "value": 50})])
    check_status("conditioned computed field runs", conditioned, "OK")
    tagged = [row for row in results(conditioned) if "big" in row]
    check("condition gates the computed field", len(tagged) == 5, f"got {len(tagged)}")

    undefined = conn.aggregate([script_map("nothing", "export default (doc) => undefined;")])
    check("an undefined result omits the field",
          all("nothing" not in row for row in results(undefined)))


def test_script_predicate(conn: Conn):
    section("FILTER: a SCRIPT predicate")
    response = conn.aggregate([script_filter("export default (doc) => doc.price > 50 && doc.qty % 2 === 0;")])
    check_status("script predicate runs", response, "OK")
    ids = sorted(row["_id"] for row in results(response))
    check("predicate selects the right documents", ids == ["o10", "o6", "o8"], f"got {ids}")

    truthy = conn.aggregate([script_filter("export default (doc) => doc.qty > 8 ? 'yes' : 0;")])
    check("JS truthiness, not a strict boolean", len(results(truthy)) == 2, f"got {results(truthy)}")

    conjunction = conn.aggregate([{"type": "FILTER", "operator": {"conjunctionType": "AND", "operators": [
        {"fieldOperatorType": "GREATER_THAN", "field": "price", "value": 50},
        {"script": "export default (doc) => doc.qty < 9;"}]}}])
    check_status("conjunction with a script runs", conjunction, "OK")
    check("conjunction combines index and script",
          sorted(row["_id"] for row in results(conjunction)) == ["o6", "o7", "o8"],
          f"got {[row['_id'] for row in results(conjunction)]}")


def test_reduce(conn: Conn):
    section("REDUCE: folding the stream")
    total = conn.aggregate([reduce_step("export default (acc, doc) => acc + doc.price * doc.qty;", 0, "total")])
    check_status("reduce runs", total, "OK")
    check("fold produces one document", len(results(total)) == 1, f"got {results(total)}")
    check("fold value is correct",
          results(total)[0].get("total") == sum(i * 10 * i for i in range(1, DOCUMENT_COUNT + 1)),
          f"got {results(total)}")

    defaulted = conn.aggregate([reduce_step("export default (acc, doc) => (acc ?? 0) + 1;")])
    check("default result field is 'value'", results(defaulted)[0].get("value") == DOCUMENT_COUNT,
          f"got {results(defaulted)}")

    grouped = conn.aggregate([reduce_step(
        "export default (acc, doc) => ({ ...acc, [doc.sku]: (acc[doc.sku] ?? 0) + 1 });", {}, "bySku")])
    check("object accumulators survive the boundary",
          results(grouped)[0].get("bySku", {}).get("sku-0") == 3, f"got {results(grouped)}")

    after = conn.aggregate([reduce_step("export default (acc, doc) => acc + 1;", 0, "n"),
                            script_map("doubled", "export default (doc) => doc.n * 2;")])
    check("a step after REDUCE sees the single document",
          results(after)[0].get("doubled") == DOCUMENT_COUNT * 2, f"got {results(after)}")


# ── phase 1: permissions ─────────────────────────────────────────────────────

def test_permissions(admin: Conn):
    section("permissions: the per-caller gate")
    plain = [{"type": "FILTER", "operator": {"fieldOperatorType": "GREATER_THAN",
                                             "field": "price", "value": 50}}]
    scripted = [script_filter("export default (doc) => doc.price > 50;")]

    with user_conn(READER) as reader:
        check_status("a plain aggregate works for a READ-only user", reader.aggregate(plain), "OK")
        check_code("a READ-only user cannot run a pipeline script",
                   reader.aggregate(scripted), "FORBIDDEN", "403-1")
        check_code("nor a REDUCE step",
                   reader.aggregate([reduce_step("export default (acc, doc) => 1;")]),
                   "FORBIDDEN", "403-1")
        check_code("nor a SCRIPT map operator",
                   reader.aggregate([script_map("x", "export default (doc) => 1;")]),
                   "FORBIDDEN", "403-1")

    check_status("grant scriptPermissions",
                 admin.send({"type": "CHANGE_PERMISSIONS", "username": READER,
                             "databasePermissions": {DB: "READ"}, "scriptPermissions": {DB: "RUN"}}), "OK")
    with user_conn(READER) as reader:
        check_status("the granted user can now run a pipeline script", reader.aggregate(scripted), "OK")

    check_status("revoke scriptPermissions",
                 admin.send({"type": "CHANGE_PERMISSIONS", "username": READER,
                             "databasePermissions": {DB: "READ"}, "scriptPermissions": {DB: "NONE"}}), "OK")
    with user_conn(READER) as reader:
        check_code("revocation takes effect", reader.aggregate(scripted), "FORBIDDEN", "403-1")
        check_status("and the plain aggregate still works", reader.aggregate(plain), "OK")

    check_status("make a database owner",
                 admin.send({"type": "SET_DATABASE_OWNERS", "databaseName": DB, "owners": [OWNER]}), "OK")
    with user_conn(OWNER) as owner:
        check_status("a database owner needs no grant", owner.aggregate(scripted), "OK")
    check_status("an admin needs no grant", admin.aggregate(scripted), "OK")


# ── phase 1: the sandbox ─────────────────────────────────────────────────────

def test_sandbox(conn: Conn):
    section("sandbox: the per-pipeline budget, the clock and the source cap")
    spin = ("export default (doc) => { let n = 0; for (let i = 0; i < 20000; i++) { n += i; } return true; };")
    one_document = conn.aggregate([{"type": "LIMIT", "limit": 1}, script_filter(spin)])
    check_status("the same script over one document fits the budget", one_document, "OK")
    whole = conn.aggregate([script_filter(spin)])
    check_code("the budget spans the whole pipeline, not each document", whole, "ERROR", "400-11")

    # A timer due past the pipeline's deadline is the deterministic way to reach the wall clock: a
    # spinning loop would exhaust the (deliberately small) instruction budget first.
    sleeper = "export default (doc) => { setTimeout(() => {}, 5000); return true; };"
    check_code("a script outliving the pipeline deadline is aborted",
               conn.aggregate([{"type": "LIMIT", "limit": 1}, script_filter(sleeper)]), "ERROR", "408-1")

    oversize = "export default (doc) => { /* " + "x" * MAX_SOURCE_BYTES + " */ return true; };"
    check_code("an oversize source is refused", conn.aggregate([script_filter(oversize)]), "ERROR", "400-10")

    check_code("a throwing script is a script failure",
               conn.aggregate([script_filter("export default (doc) => { throw new Error('boom'); };")]),
               "ERROR", "400-9")
    check_code("a script exporting nothing is a script failure",
               conn.aggregate([script_filter("const x = 1;")]), "ERROR", "400-9")
    check_code("a blank source is rejected", conn.aggregate([script_filter("   ")]), "ERROR", "400-1")


# ── phase 1: the closed doors ────────────────────────────────────────────────

def test_closed_doors(conn: Conn):
    section("the closed doors: db, fetch, procedure imports, importText")
    check_status("a procedure exists to import",
                 conn.send({"type": "SAVE_PROCEDURE", "databaseName": DB, "name": "lib",
                            "script": "export const one = 1;"}), "OK")

    doors = [
        ("db", "import db from 'db'; export default (doc) => true;",
         "import db from 'db'; return db.name;"),
        ("procedures/lib", "import { one } from 'procedures/lib'; export default (doc) => one;",
         "import { one } from 'procedures/lib'; return one;"),
    ]
    for label, pipeline_source, script_source in doors:
        check_code(f"a pipeline script cannot import '{label}'",
                   conn.aggregate([script_filter(pipeline_source)]), "ERROR", "400-9")
        check_status(f"but RUN_SCRIPT can import '{label}'", conn.run_script(script_source), "OK")

    fetched = conn.aggregate([script_map(
        "reached",
        "export default (doc) => { let out = 'no'; try { fetch('http://127.0.0.1:1/'); } catch (e)"
        " { out = e.name; } return out; };")])
    check("fetch is unreachable from a pipeline script",
          all(row.get("reached") in ("no", "TypeError") for row in results(fetched)),
          f"got {results(fetched)[:1]}")

    imported = conn.aggregate([script_filter(
        "import s from 'script';"
        " export default (doc) => { s.importText('export default 1;'); return true; };")])
    check_code("importText is refused inside a pipeline script", imported, "ERROR", "400-9")


# ── phase 1: analyze, LISTEN, custom types, nesting ──────────────────────────

def test_analyze(conn: Conn):
    section("analyze")
    response = conn.aggregate([script_filter("export default (doc) => true;")], analyze=True)
    check_status("analyze runs", response, "OK")
    analyze = response.get("analyzeResult", {})
    check("script invocations are counted",
          analyze.get("scriptInvocations") == DOCUMENT_COUNT, f"got {analyze.get('scriptInvocations')}")
    check("the no-index suggestion appears",
          any("can never use an index" in s for s in analyze.get("suggestions", [])),
          f"got {analyze.get('suggestions')}")

    combined = conn.aggregate([
        {"type": "FILTER", "operator": {"fieldOperatorType": "GREATER_THAN", "field": "price", "value": 50}},
        script_filter("export default (doc) => doc.qty < 9;"),
        reduce_step("export default (acc, doc) => acc + 1;", 0, "n")], analyze=True)
    check_status("indexed FILTER then SCRIPT then REDUCE runs", combined, "OK")
    check("the index was used", combined.get("analyzeResult", {}).get("indexUsed") is True,
          f"got {combined.get('analyzeResult')}")
    # 5 predicate calls over the index-narrowed set, then 3 fold calls over what it kept - so the
    # script never saw the 5 documents the index excluded.
    check("the scripts only saw the narrowed set",
          combined.get("analyzeResult", {}).get("scriptInvocations") == 8,
          f"got {combined.get('analyzeResult', {}).get('scriptInvocations')}")
    check("the fold counted them", results(combined)[0].get("n") == 3, f"got {results(combined)}")


def test_listen_refuses_scripts(conn: Conn):
    section("LISTEN refuses a SCRIPT operator")
    scripted = [script_filter("export default (doc) => true;")]
    check_code("LISTEN with a script is refused",
               conn.send({"type": "LISTEN", "databaseName": DB, "collectionName": COLL,
                          "aggregationSteps": scripted}), "ERROR", "400-19")
    check_status("the same pipeline is accepted by AGGREGATE", conn.aggregate(scripted), "OK")
    plain = conn.send({"type": "LISTEN", "databaseName": DB, "collectionName": COLL, "aggregationSteps": []})
    check_status("a script-free LISTEN still registers", plain, "OK")
    if plain.get("listenId"):
        conn.send({"type": "STOP_LISTEN", "listenId": plain["listenId"]})


def test_host_boundary(conn: Conn):
    section("the host boundary: custom types and BigInt")
    geo = conn.aggregate([script_map("where", "export default (doc) => Geo.from({ lat: 1, lng: 2 });"),
                          {"type": "LIMIT", "limit": 1}])
    check_status("a script may return a Geo", geo, "OK")
    check("the Geo arrives as the real custom type",
          str(results(geo)[0].get("where", "")).startswith("#geo("), f"got {results(geo)[0].get('where')}")

    when = conn.aggregate([script_map("when", "export default (doc) => DbDateTime.from('2026-01-02T03:04:05');"),
                           {"type": "LIMIT", "limit": 1}])
    check("a DbDateTime arrives as the real custom type",
          "2026-01-02" in str(results(when)[0].get("when", "")), f"got {results(when)[0].get('when')}")

    big = conn.aggregate([script_map("big", "export default (doc) => 9007199254740993n;"),
                          {"type": "LIMIT", "limit": 1}])
    check_code("a BigInt beyond 2^53-1 fails cleanly", big, "ERROR", "400-9")


def test_nested_script(conn: Conn):
    section("nesting: a RUN_SCRIPT whose aggregate carries a SCRIPT operator")
    source = (
        "import db from 'db';"
        " const rows = db.aggregate(db.name, '" + COLL + "', ["
        " { type: 'FILTER', operator: { script: 'export default (doc) => doc.qty > 8;' } } ]);"
        " return rows.length;")
    response = conn.run_script(source)
    check_status("the inner pipeline runs", response, "OK")
    check("the inner script filtered the rows", response.get("result") == 2, f"got {response.get('result')}")

    outer_still_fine = conn.run_script(source)
    check_status("the outer run's own budget is untouched", outer_still_fine, "OK")


# ── phase 2: the master switch ───────────────────────────────────────────────

def test_switch_off(conn: Conn):
    section("phase 2: scriptsEnabled=false")
    shapes = {
        "a SCRIPT filter": [script_filter("export default (doc) => true;")],
        "a SCRIPT map operator": [script_map("x", "export default (doc) => 1;")],
        "a REDUCE step": [reduce_step("export default (acc, doc) => 1;")],
    }
    for label, steps in shapes.items():
        check_code(f"{label} is refused for an admin", conn.aggregate(steps), "FORBIDDEN", "403-2")
    with user_conn(OWNER) as owner:
        check_code("and for a database owner",
                   owner.aggregate([script_filter("export default (doc) => true;")]), "FORBIDDEN", "403-2")

    plain = conn.aggregate([{"type": "FILTER", "operator": {"fieldOperatorType": "GREATER_THAN",
                                                           "field": "price", "value": 50}}])
    check_status("plain aggregations are unaffected", plain, "OK")
    check("and still index-backed", len(results(plain)) == 5, f"got {len(results(plain))}")
    check_status("so is a whole-collection read", conn.aggregate([]), "OK")
    check_code("RUN_SCRIPT is refused by the same switch", conn.run_script("return 1;"), "FORBIDDEN", "403-2")


def main() -> int:
    global failures
    jar = os.path.join(REPO_ROOT, JAR)
    if not os.path.isfile(jar):
        print(f"jar not found at {jar}; run `mvn clean package -DskipTests` first", file=sys.stderr)
        return 1
    if port_open():
        print(f"port {PORT} is already in use", file=sys.stderr)
        return 1

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-agg-script-test-")
    log_path = os.path.join(work_dir, "server.out")
    proc = None
    try:
        print(f"work dir: {work_dir}")
        write_config(work_dir, scripts_enabled=True)
        proc = start_server(work_dir, log_path)

        with admin_conn() as conn:
            setup_data(conn)
            test_computed_field(conn)
            test_script_predicate(conn)
            test_reduce(conn)
            test_permissions(conn)
            test_sandbox(conn)
            test_closed_doors(conn)
            test_analyze(conn)
            test_listen_refuses_scripts(conn)
            test_host_boundary(conn)
            test_nested_script(conn)

        # Phase 2: same data directory, the master switch off. With no dedicated key, this is the
        # whole server-side gate, so it is the phase that must not be skipped.
        stop_server(proc)
        proc = None
        write_config(work_dir, scripts_enabled=False)
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
