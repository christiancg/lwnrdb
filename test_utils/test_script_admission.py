import os
import tempfile
import threading
import time

import base_utils as bu
from base_utils import check, check_code, check_result, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("ADMISSION_TEST_PORT", "8997"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

DB = "admission_test_db"
COLL = "docs"
AUDIT_COLL = "audit"
TICKS_COLL = "ticks"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)

CAPACITY = 2
CALLERS = 6
PER_USER_CAPACITY = 2
PER_DATABASE_CAPACITY = 3
SLOW_SCRIPT_MS = 600
LONG_WAIT_MS = 10_000

TIMEOUT_MS = 5_000
MAX_MEMORY_BYTES = 4 * 1024 * 1024
MAX_SOURCE_BYTES = 8 * 1024

SLOW_SCRIPT = f"export default new Promise(r => setTimeout(() => r(1), {SLOW_SCRIPT_MS}));"


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

def admin_conn() -> Conn:
    return Conn.authenticated()


class Burst:

    def __init__(self, responses: list, peak_running: int):
        self.responses = responses
        self.peak_running = peak_running
        self.ok = [r for r in responses if r.get("status") == "OK"]
        self.rejected = [r for r in responses if r.get("errorCode") == "503-6"]
        self.other = [r for r in responses if r not in self.ok and r not in self.rejected]


def fire_burst(request: dict, callers: int = CALLERS, credentials: list = None) -> Burst:
    responses = []
    responses_lock = threading.Lock()
    ready = threading.Barrier(callers + 1)
    stop_sampling = threading.Event()
    peak = [0]

    def caller(index: int):
        username, password = credentials[index % len(credentials)] if credentials \
            else (ADMIN_USERNAME, ADMIN_PASSWORD)
        with Conn() as conn:
            conn.authenticate(username, password)
            ready.wait()
            response = conn.send(request)
        with responses_lock:
            responses.append(response)

    def sampler():
        with admin_conn() as conn:
            ready.wait()
            while not stop_sampling.wait(0.005):
                running = conn.script_stats().get("running")
                if isinstance(running, int):
                    peak[0] = max(peak[0], running)

    threads = [threading.Thread(target=caller, args=(i,), name=f"caller-{i}") for i in range(callers)]
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
    latest = [-1]

    def reached() -> bool:
        latest[0] = conn.script_stats().get("available", -1)
        return latest[0] == expected

    bu.wait_until(reached, timeout, 0.1)
    return latest[0]


def await_doc(conn: Conn, doc_id: str, coll: str, timeout=30.0):
    def stored():
        response = conn.find(doc_id, coll=coll)
        return response.get("object") if response.get("status") == "OK" else None

    return bu.wait_until(stored, timeout, 0.2) or None


def write_config(work_dir: str, capacity: int, queue_wait_ms: int, per_user: int = 0, per_database: int = 0):
    bu.write_config(work_dir, PORT, scriptsEnabled=True, maxConcurrentScripts=capacity,
                    maxConcurrentScriptsPerUser=per_user, maxConcurrentScriptsPerDatabase=per_database,
                    scriptQueueWaitMs=queue_wait_ms, scriptTimeoutMs=TIMEOUT_MS,
                    scriptMaxMemoryBytes=MAX_MEMORY_BYTES, scriptMaxSourceBytes=MAX_SOURCE_BYTES,
                    scriptInstructionBudget=10000000, scriptMaxDepth=200, triggersEnabled=True,
                    triggerThreads=2, schedulesEnabled=True, scheduleThreads=2, scheduleTickMs=200,
                    scheduleMinIntervalMs=100,
                    scriptTimeZone="UTC", scriptLocale="en-US")


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


TENANTS = [("tenant_a", "password123"), ("tenant_b", "password123")]


def create_tenants(conn: Conn):
    for username, password in TENANTS:
        conn.send({"type": "DELETE_USER", "username": username})
        check_status(f"create {username}",
                     conn.send({"type": "CREATE_USER", "username": username, "password": password,
                                "admin": True, "globalPermissions": [], "databasePermissions": {},
                                "collectionPermissions": {}}), "OK")


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


def test_wait_absorbs_the_burst(conn: Conn):
    section(f"The same burst with a {LONG_WAIT_MS}ms wait")
    burst = fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT})

    check("nobody was refused", not burst.rejected, f"{len(burst.rejected)} caller(s) got 503-6")
    check("every caller succeeded", len(burst.ok) == CALLERS,
          f"only {len(burst.ok)} of {CALLERS} succeeded; others: {burst.other}")
    check(f"no more than {CAPACITY} interpreters ran at once",
          0 < burst.peak_running <= CAPACITY, f"scripts.running peaked at {burst.peak_running}")

    stats = conn.script_stats()
    check("stats counted no rejections", stats.get("rejected") == 0, f"got {stats!r}")
    check("stats counted the callers that queued", stats.get("waited") >= CALLERS - CAPACITY,
          f"waited={stats.get('waited')}, expected at least {CALLERS - CAPACITY}")
    check("every permit came back", await_available(conn, CAPACITY) == CAPACITY)


def test_wait_may_exceed_the_run_timeout():
    section("The wait is not bounded by the run timeout")
    check(f"the configured wait ({LONG_WAIT_MS}ms) exceeds scriptTimeoutMs ({TIMEOUT_MS}ms)",
          LONG_WAIT_MS > TIMEOUT_MS)
    burst = fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT},
                       callers=CAPACITY + 1)
    check("the caller that had to wait still succeeded", len(burst.ok) == CAPACITY + 1,
          f"only {len(burst.ok)} of {CAPACITY + 1} succeeded; rejected={len(burst.rejected)}")


def test_per_user_cap():
    section("A per-user cap under a generous node-wide cap")
    one_user = fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT},
                          credentials=[TENANTS[0]])

    check("every caller got a response", len(one_user.responses) == CALLERS, f"got {len(one_user.responses)}")
    check("one user is held to its own slice", 1 <= len(one_user.ok) <= PER_USER_CAPACITY,
          f"{len(one_user.ok)} succeeded with a per-user cap of {PER_USER_CAPACITY}")
    check("the surplus is rejected", len(one_user.rejected) == CALLERS - len(one_user.ok),
          f"{len(one_user.ok)} ok + {len(one_user.rejected)} rejected != {CALLERS}")
    check("the rejection names the scope that refused it",
          "user" in (one_user.rejected[0].get("message") or "") if one_user.rejected else False,
          f"got {one_user.rejected[0].get('message') if one_user.rejected else None!r}")
    check(f"no more than {PER_USER_CAPACITY} of that user's runs overlapped",
          0 < one_user.peak_running <= PER_USER_CAPACITY, f"scripts.running peaked at {one_user.peak_running}")

    two_users = fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT},
                           credentials=TENANTS)
    check("two users together exceed one user's ceiling", two_users.peak_running > PER_USER_CAPACITY,
          f"scripts.running peaked at {two_users.peak_running} with two users")
    check("and more of them succeed", len(two_users.ok) > len(one_user.ok),
          f"{len(two_users.ok)} vs {len(one_user.ok)}")


def test_a_tenant_refusal_frees_the_node_permit(conn: Conn):
    section("A per-user refusal does not hold a node-wide permit")
    fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT}, credentials=[TENANTS[0]])
    stats = conn.script_stats()
    check("the node-wide permits are all available again",
          await_available(conn, stats.get("capacity")) == stats.get("capacity"),
          f"available={conn.script_stats().get('available')} capacity={stats.get('capacity')}")
    check("the per-user rejections are counted", (stats.get("rejectedPerUser") or 0) > 0, f"got {stats}")
    check("the capacities are reported",
          stats.get("capacityPerUser") == PER_USER_CAPACITY and "capacityPerDatabase" in stats, f"got {stats}")


def schedule_stats(conn: Conn) -> dict:
    return conn.send({"type": "GET_DATABASE_STATS"}).get("stats", {}).get("schedules", {})


def test_triggers_and_schedules_take_their_definers_slice(conn: Conn):
    section("Triggers and schedules take their definer's per-user slice")
    username, password = TENANTS[0]
    with Conn() as tenant:
        tenant.authenticate(username, password)
        check_status("the tenant installs a trigger",
                     tenant.save_trigger("tenant_audit", "audit", ["CREATED"]), "OK")

    holders = []
    started = threading.Barrier(PER_USER_CAPACITY + 1)

    def holder():
        with Conn() as held:
            held.authenticate(username, password)
            started.wait()
            held.run(f"export default new Promise(r => setTimeout(() => r(1), {SLOW_SCRIPT_MS * 5}));")

    for _ in range(PER_USER_CAPACITY):
        thread = threading.Thread(target=holder)
        thread.start()
        holders.append(thread)
    started.wait()
    check("the tenant's slice is full",
          bu.wait_until(lambda: conn.script_stats().get("running") == PER_USER_CAPACITY, 10.0, 0.05),
          f"running={conn.script_stats().get('running')}")
    skipped_before = schedule_stats(conn).get("skipped", 0)
    check_status("an admin write fires the tenant's trigger", conn.save_doc({"_id": "slice-deferred", "n": 1}),
                 "OK")
    with Conn() as tenant:
        tenant.authenticate(username, password)
        check_status("the tenant installs a fast schedule", tenant.save_schedule(
            "tenant_tick", "tick", intervalMs=200, args={"id": "slice-skipped"}), "OK")
    time.sleep(0.8)
    check("the trigger waits for its definer's slice",
          conn.find("slice-deferred", coll=AUDIT_COLL).get("status") == "NOT_FOUND",
          "the trigger ran past a full per-user slice")
    check("the scheduled run is skipped while the slice is full",
          conn.find("slice-skipped", coll=TICKS_COLL).get("status") == "NOT_FOUND"
          and schedule_stats(conn).get("skipped", 0) > skipped_before,
          f"stats={schedule_stats(conn)}")

    for thread in holders:
        thread.join(120)
    check("the deferred trigger runs once the slice frees, so it was delayed, not dropped",
          await_doc(conn, "slice-deferred", AUDIT_COLL) is not None, "the audit row never appeared")
    check("and the schedule fires again", await_doc(conn, "slice-skipped", TICKS_COLL) is not None,
          "the tick row never appeared")
    check_status("remove the schedule", conn.delete_schedule("tenant_tick"), "OK")
    check_status("remove the trigger",
                 conn.send({"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": COLL,
                            "name": "tenant_audit"}), "OK")


def test_per_database_cap(conn: Conn):
    section("A per-database cap across two users")
    rejected_before = conn.script_stats().get("rejectedPerDatabase") or 0
    burst = fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT}, credentials=TENANTS)
    check("every caller got a response", len(burst.responses) == CALLERS, f"got {len(burst.responses)}")
    check(f"no more than {PER_DATABASE_CAPACITY} runs in the database overlapped",
          0 < burst.peak_running <= PER_DATABASE_CAPACITY, f"scripts.running peaked at {burst.peak_running}")
    check("the database slice refused some of them",
          (conn.script_stats().get("rejectedPerDatabase") or 0) > rejected_before, f"got {conn.script_stats()}")


ORDINARY_RUNNERS = [("runner_a", "password123"), ("runner_b", "password123")]
DB_OWNER = ("db_owner_c", "password123")


def create_ordinary_runners(conn: Conn):
    for username, password in ORDINARY_RUNNERS + [DB_OWNER]:
        conn.send({"type": "DELETE_USER", "username": username})
        check_status(f"create {username}",
                     conn.send({"type": "CREATE_USER", "username": username, "password": password,
                                "admin": False, "globalPermissions": [], "databasePermissions": {DB: "READ"},
                                "collectionPermissions": {}, "scriptPermissions": {DB: "RUN"}}), "OK")
    check_status("make db_owner_c an owner of the database",
                 conn.send({"type": "SET_DATABASE_OWNERS", "databaseName": DB, "owners": [DB_OWNER[0]]}), "OK")


def test_owner_keeps_a_database_slot_when_users_fill_it(conn: Conn):
    section(f"The last of {PER_DATABASE_CAPACITY} database slots is kept for owners and admins")
    create_ordinary_runners(conn)
    holders = []
    started = threading.Barrier(len(ORDINARY_RUNNERS) + 1)

    def holder(holder_name: str, holder_password: str):
        with Conn() as held:
            held.authenticate(holder_name, holder_password)
            started.wait()
            held.run(f"export default new Promise(r => setTimeout(() => r(1), {SLOW_SCRIPT_MS * 5}));")

    for username, password in ORDINARY_RUNNERS:
        thread = threading.Thread(target=holder, args=(username, password))
        thread.start()
        holders.append(thread)
    started.wait()
    check("ordinary users hold every shared slot",
          bu.wait_until(lambda: conn.script_stats().get("running") == len(ORDINARY_RUNNERS), 10.0, 0.05),
          f"running={conn.script_stats().get('running')}")
    with Conn() as runner:
        runner.authenticate(*ORDINARY_RUNNERS[0])
        refused = runner.run("export default 7;")
        check_code("a third ordinary run is refused at the database slice", refused, "ERROR", "503-6")
        check("the refusal names the database scope", "database" in (refused.get("message") or ""),
              f"got {refused.get('message')!r}")
    with Conn() as owner:
        owner.authenticate(*DB_OWNER)
        check_result("the database owner still runs in the reserved slot", owner.run("export default 7;"), 7)
    check_result("so does an admin", conn.run("export default 7;"), 7)
    for thread in holders:
        thread.join(120)
    check_status("clear the database owners",
                 conn.send({"type": "SET_DATABASE_OWNERS", "databaseName": DB, "owners": []}), "OK")


def test_cap_disabled(conn: Conn):
    section("The cap disabled (maxConcurrentScripts=0)")
    stats_before = conn.script_stats()
    check("stats report no capacity", stats_before.get("capacity") == 0, f"got {stats_before!r}")

    burst = fire_burst({"type": "RUN_SCRIPT", "databaseName": DB, "script": SLOW_SCRIPT})
    check("every caller succeeded", len(burst.ok) == CALLERS,
          f"only {len(burst.ok)} of {CALLERS} succeeded; rejected={len(burst.rejected)}; others={burst.other}")
    check("nobody was refused", not burst.rejected)

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


def main():
    bu.banner("script admission control test suite")

    bu.require_jar()

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-admission-")
    log_path = os.path.join(work_dir, "server.log")
    print(f"  Working dir: {work_dir}")

    proc = None
    try:
        write_config(work_dir, capacity=CAPACITY, queue_wait_ms=0)
        print(f"  Starting server (capacity {CAPACITY}, no wait) on {HOST}:{PORT} ...")
        proc = bu.start_server(work_dir, log_path)

        with admin_conn() as conn:
            setup_data(conn)
            test_burst_is_capped(conn)
            test_call_procedure_is_capped(conn)
            test_rejection_leaves_the_connection_usable(conn)
            test_doomed_requests_do_not_spend_a_permit(conn)
            test_permits_survive_failing_runs(conn)
            test_triggers_are_exempt(conn)
            test_schedules_are_exempt(conn)

        bu.stop_server(proc)
        proc = None

        write_config(work_dir, capacity=CAPACITY, queue_wait_ms=LONG_WAIT_MS)
        print(f"\n  Restarting server (capacity {CAPACITY}, {LONG_WAIT_MS}ms wait) on {HOST}:{PORT} ...")
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_wait_absorbs_the_burst(conn)
            test_wait_may_exceed_the_run_timeout()

        bu.stop_server(proc)
        proc = None

        write_config(work_dir, capacity=CALLERS * 2, queue_wait_ms=0, per_user=PER_USER_CAPACITY,
                     per_database=PER_DATABASE_CAPACITY)
        print(f"\n  Restarting server (per-user cap {PER_USER_CAPACITY}) on {HOST}:{PORT} ...")
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            create_tenants(conn)
            test_per_user_cap()
            test_a_tenant_refusal_frees_the_node_permit(conn)
            test_triggers_and_schedules_take_their_definers_slice(conn)
            test_per_database_cap(conn)
            test_owner_keeps_a_database_slot_when_users_fill_it(conn)

        bu.stop_server(proc)
        proc = None

        write_config(work_dir, capacity=0, queue_wait_ms=0)
        print(f"\n  Restarting server (cap disabled) on {HOST}:{PORT} ...")
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_cap_disabled(conn)
            cleanup(conn)
    finally:
        bu.stop_server(proc)

    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
