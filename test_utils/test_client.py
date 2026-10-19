import gzip
import json
import os
import subprocess
import sys
import tempfile

import base_utils as bu
from base_utils import Conn, check, check_status, section

HOST = "127.0.0.1"
SOURCE_PORT = int(os.environ.get("CLIENT_TEST_PORT", "9446"))
TARGET_PORT = int(os.environ.get("CLIENT_TEST_RESTORE_PORT", "9447"))
SHOP = "client_shop"
CRM = "client_crm"
ORDERS = "orders"
ITEMS = "items"
FIRED = "fired"
LEADS = "leads"
ORDER_COUNT = 2500
CLIENT_TIMEOUT_S = 300

bu.configure(host=HOST, port=SOURCE_PORT)

LIB_SOURCE = "export const factor = 2;"
NORMALIZER_SOURCE = ("import { factor } from 'procedures/lib';"
                     " export default (doc) => ({ ...doc, total: doc.qty * factor });")
NOTIFIER_SOURCE = ("import db from 'db'; import args from 'args';"
                   " db.save(db.name, '" + FIRED + "', { _id: 'fired-' + args.id }); return 'ok';")
ROLLUP_SOURCE = "import args from 'args'; return args.limit;"


def write_config(work_dir: str, port: int, **extra):
    bu.write_config(work_dir, port, scriptsEnabled=True, triggersEnabled=True, schedulesEnabled=True,
                    scriptRunHistoryKinds="RUN_SCRIPT,CALL_PROCEDURE,TRIGGER,SCHEDULE", **extra)


def client(cwd: str, *args, port: int = SOURCE_PORT, stdin: bytes = b"", password=bu.PASSWORD):
    command = ["java", "-jar", bu.JAR_PATH, *args, "--port", str(port)]
    if password is not None:
        command += ["--password", password]
    return subprocess.run(command, input=stdin, capture_output=True, cwd=cwd, timeout=CLIENT_TIMEOUT_S)


def admin(port: int) -> Conn:
    return Conn.authenticated(port=port)


def send(port: int, payload: dict) -> dict:
    with admin(port) as conn:
        reply = conn.send(payload)
    return reply


def documents_of(conn: Conn, db: str, coll: str) -> list:
    response = conn.send({"type": "AGGREGATE", "databaseName": db, "collectionName": coll, "aggregationSteps": []})
    return sorted(json.dumps(doc, sort_keys=True) for doc in response.get("results") or [])


def snapshot(port: int, skip_documents=()) -> dict:
    with admin(port) as conn:
        state = {}
        databases = sorted(conn.send({"type": "LIST_DATABASES"}).get("databases") or [])
        stats = conn.send({"type": "GET_DATABASE_STATS"}).get("stats", {}).get("databases", [])
        indexes = {(db["name"], coll["name"]): sorted(coll.get("indexes", [])) for db in stats
                   for coll in db.get("collections", [])}
        for db in databases:
            collections = sorted(conn.send({"type": "LIST_COLLECTIONS", "databaseName": db}).get("collections") or [])
            entry = {"collections": collections, "documents": {}, "schemas": {}, "indexes": {}}
            for coll in collections:
                if (db, coll) not in skip_documents and coll != "script_runs":
                    entry["documents"][coll] = documents_of(conn, db, coll)
                entry["schemas"][coll] = conn.send({"type": "GET_SCHEMA", "databaseName": db,
                                                    "collectionName": coll}).get("schema")
                entry["indexes"][coll] = indexes.get((db, coll), [])
            procedures = conn.send({"type": "LIST_PROCEDURES", "databaseName": db, "includeSource": True})
            entry["procedures"] = sorted((p["name"], p.get("source"), p.get("enabled"))
                                         for p in procedures.get("procedures") or [])
            triggers = conn.send({"type": "LIST_TRIGGERS", "databaseName": db}).get("triggers") or []
            entry["triggers"] = sorted(json.dumps({key: t.get(key) for key in (
                "collectionName", "name", "events", "procedureName", "mode", "timing", "allowCascade", "enabled")},
                sort_keys=True) for t in triggers if t.get("collectionName") != "script_runs")
            schedules = conn.send({"type": "LIST_SCHEDULES", "databaseName": db, "includeArgs": True})
            entry["schedules"] = sorted(json.dumps({key: s.get(key) for key in (
                "name", "procedureName", "cron", "intervalMs", "args", "timeoutMs", "enabled")}, sort_keys=True)
                for s in schedules.get("schedules") or [])
            state[db] = entry
    return state


def user_view(state: dict) -> dict:
    view = {}
    for db, entry in state.items():
        copy = dict(entry)
        copy["collections"] = [coll for coll in entry["collections"] if coll != "script_runs"]
        copy["schemas"] = {coll: schema for coll, schema in entry["schemas"].items() if coll != "script_runs"}
        copy["indexes"] = {coll: idx for coll, idx in entry["indexes"].items() if coll != "script_runs"}
        view[db] = copy
    return view


def order(i: int) -> dict:
    return {"_id": f"o{i}", "qty": i % 17, "customer": f"c{i % 40}", "at": f"#datetime(2026-01-{i % 28 + 1:02d}T10:00:00)",
            "where": f"#geo({i % 80}.5,{i % 170}.25)", "embedding": f"#vector(0.{i % 9 + 1},0.5,0.25)",
            "nested": {"tags": ["a", str(i)], "meta": {"n": i}}}


def seed_source():
    section("Seed the source server")
    with admin(SOURCE_PORT) as c:
        for db in (SHOP, CRM):
            check_status(f"create {db}", c.send({"type": "CREATE_DATABASE", "databaseName": db}), "OK")
        for coll in (ORDERS, ITEMS, FIRED):
            check_status(f"create {SHOP}.{coll}", c.send({"type": "CREATE_COLLECTION", "databaseName": SHOP,
                                                         "collectionName": coll}), "OK")
        check_status("create crm.leads", c.send({"type": "CREATE_COLLECTION", "databaseName": CRM,
                                                 "collectionName": LEADS}), "OK")
        for name, source in (("lib", LIB_SOURCE), ("normalizer", NORMALIZER_SOURCE), ("notifier", NOTIFIER_SOURCE),
                             ("rollup", ROLLUP_SOURCE)):
            check_status(f"save procedure {name}", c.send({"type": "SAVE_PROCEDURE", "databaseName": SHOP,
                                                          "name": name, "script": source}), "OK")
        check_status("install the before hook", c.send({
            "type": "SAVE_TRIGGER", "databaseName": SHOP, "collectionName": ORDERS, "name": "normalize",
            "events": ["CREATED", "UPDATED"], "procedureName": "normalizer", "timing": "before"}), "OK")
        for start in range(0, ORDER_COUNT, 500):
            check_status(f"save orders {start}..", c.send({
                "type": "BULK_SAVE", "databaseName": SHOP, "collectionName": ORDERS,
                "objects": [order(i) for i in range(start, start + 500)]}), "OK")
        check_status("save items", c.send({"type": "BULK_SAVE", "databaseName": SHOP, "collectionName": ITEMS,
                                           "objects": [{"_id": f"i{i}", "name": f"item {i}"} for i in range(5)]}),
                     "OK")
        check_status("save leads", c.send({"type": "BULK_SAVE", "databaseName": CRM, "collectionName": LEADS,
                                           "objects": [{"_id": f"l{i}", "score": i} for i in range(7)]}), "OK")
        check_status("index orders.customer", c.send({"type": "CREATE_INDEX", "databaseName": SHOP,
                                                      "collectionName": ORDERS, "fieldName": "customer"}), "OK")
        check_status("schema on orders", c.send({"type": "SAVE_SCHEMA", "databaseName": SHOP,
                                                 "collectionName": ORDERS,
                                                 "schema": {"type": "object", "required": ["qty"]}}), "OK")
        check_status("install the after trigger", c.send({
            "type": "SAVE_TRIGGER", "databaseName": SHOP, "collectionName": ORDERS, "name": "notify",
            "events": ["CREATED"], "procedureName": "notifier"}), "OK")
        check_status("install a schedule with args", c.send({
            "type": "SAVE_SCHEDULE", "databaseName": SHOP, "name": "nightly", "procedureName": "rollup",
            "cron": "0 3 * * *", "args": {"limit": 7}}), "OK")
        check_status("one RUN_SCRIPT", c.send({"type": "RUN_SCRIPT", "databaseName": SHOP, "script": "return 1;"}),
                     "OK")
        check("script_runs exists on the source", bu.wait_until(lambda: "script_runs" in (c.send({
            "type": "LIST_COLLECTIONS", "databaseName": SHOP}).get("collections") or []), 15.0))


def test_exec(cwd: str):
    section("--exec")
    single = client(cwd, "--exec", '{"type":"LIST_DATABASES"}')
    lines = single.stdout.decode().splitlines()
    check("one command exits 0", single.returncode == 0, single.stderr.decode())
    check("and prints one OK line", len(lines) == 1 and json.loads(lines[0]).get("status") == "OK", str(lines))
    double = client(cwd, "--exec", '{"type":"LIST_DATABASES"}', "--exec", '{"type":"LIST_DATABASES"}')
    check("two --exec flags print two lines", len(double.stdout.decode().splitlines()) == 2)
    piped = client(cwd, "--exec", stdin=b'{"type":"LIST_DATABASES"}\n{"type":"LIST_DATABASES"}\n')
    check("commands piped on stdin work", piped.returncode == 0 and len(piped.stdout.decode().splitlines()) == 2,
          piped.stderr.decode())
    missing = client(cwd, "--exec", json.dumps({"type": "FIND_BY_ID", "databaseName": SHOP, "collectionName": ORDERS,
                                                "_id": "nope"}))
    check("a failing command exits 1", missing.returncode == 1, missing.stdout.decode())
    check("a wrong password exits 3", client(cwd, "--exec", "{}", password="wrong").returncode == 3)
    check("no mode exits 2", client(cwd, "--host", HOST).returncode == 2)
    check("a closed port exits 3", client(cwd, "--exec", "{}", port=1).returncode == 3)


def test_shell(cwd: str):
    section("--shell")
    script = (json.dumps({"type": "CREATE_DATABASE", "databaseName": "client_shell_db"}) + "\n"
              + '{"type":"LIST_DATABASES"}\nexit\n').encode()
    result = client(cwd, "--shell", stdin=script)
    out = result.stdout.decode()
    check("the shell exits 0", result.returncode == 0, result.stderr.decode())
    check("replies are pretty-printed", '\n  "status": "OK"' in out, out[:300])
    check("the database exists afterwards", "client_shell_db" in (send(SOURCE_PORT, {"type": "LIST_DATABASES"})
                                                                  .get("databases") or []))
    send(SOURCE_PORT, {"type": "DROP_DATABASE", "databaseName": "client_shell_db"})


def test_round_trip(cwd: str, source_state: dict) -> bytes:
    section("Dump and restore round trip")
    plain = client(cwd, "--dump")
    check("--dump exits 0", plain.returncode == 0, plain.stderr.decode())
    check("progress goes to stderr", f"{SHOP}.{ORDERS}: {ORDER_COUNT} documents" in plain.stderr.decode(),
          plain.stderr.decode())
    compressed = client(cwd, "--dump", "--compress")
    check("--dump --compress exits 0", compressed.returncode == 0, compressed.stderr.decode())
    check("the compressed dump starts with the gzip magic", compressed.stdout[:2] == b"\x1f\x8b")
    check("and gunzips to the plain dump", gzip.decompress(compressed.stdout) == plain.stdout)
    lines = plain.stdout.decode().splitlines()
    check("every line is a request with a type", all("type" in json.loads(line) for line in lines))
    check("script_runs is not dumped", all(json.loads(line).get("collectionName") != "script_runs" for line in lines))

    restored = client(cwd, "--restore", port=TARGET_PORT, stdin=compressed.stdout)
    check("--restore exits 0", restored.returncode == 0, restored.stderr.decode())
    check("restore reports its count", f"restored {len(lines)} requests" in restored.stderr.decode(),
          restored.stderr.decode()[-300:])
    target_state = snapshot(TARGET_PORT)
    for db in (SHOP, CRM):
        check(f"{db} matches the source", user_view(target_state).get(db) == user_view(source_state).get(db))
    check("no script_runs on the target", "script_runs" not in target_state[SHOP]["collections"])
    check("the after trigger did not fire during the restore",
          bu.holds_throughout(lambda: not documents_of_port(TARGET_PORT, SHOP, FIRED), 3.0))

    again = client(cwd, "--restore", port=TARGET_PORT, stdin=compressed.stdout)
    check("restoring the same dump again exits 0", again.returncode == 0, again.stderr.decode())
    skip = {(SHOP, FIRED)}
    check("and leaves the same data (the after trigger's side effects aside)",
          user_view(snapshot(TARGET_PORT, skip)) == user_view(snapshot(SOURCE_PORT, skip)))
    return plain.stdout


def documents_of_port(port: int, db: str, coll: str) -> list:
    with admin(port) as conn:
        documents = documents_of(conn, db, coll)
    return documents


def test_restore_one_database(cwd: str):
    section("Restore one database")
    dumped = client(cwd, "--dump", "--databases", CRM)
    requests = [json.loads(line) for line in dumped.stdout.decode().splitlines()]
    check("the subset dump holds only that database", {r["databaseName"] for r in requests} == {CRM})
    shop_before = user_view(snapshot(TARGET_PORT, {(SHOP, FIRED)}))[SHOP]
    check_status("drop it on the target", send(TARGET_PORT, {"type": "DROP_DATABASE", "databaseName": CRM}), "OK")
    restored = client(cwd, "--restore", port=TARGET_PORT, stdin=dumped.stdout)
    check("restoring the subset exits 0", restored.returncode == 0, restored.stderr.decode())
    check("the database matches the source", snapshot(TARGET_PORT)[CRM] == snapshot(SOURCE_PORT)[CRM])
    check("the other database is untouched", user_view(snapshot(TARGET_PORT, {(SHOP, FIRED)}))[SHOP] == shop_before)


def test_restore_one_collection_into_a_live_server(cwd: str):
    section("Restore one collection into a live server")
    with admin(TARGET_PORT) as c:
        for i in range(3):
            c.send({"type": "SAVE", "databaseName": SHOP, "collectionName": ORDERS,
                    "object": {**order(i), "qty": 1000 + i}})
        for i in (10, 11):
            check_status(f"delete o{i} on the target", c.send({"type": "DELETE", "databaseName": SHOP,
                                                               "collectionName": ORDERS, "_id": f"o{i}"}), "OK")
        check_status("add a document the source lacks", c.send({
            "type": "SAVE", "databaseName": SHOP, "collectionName": ORDERS, "object": {**order(99999)}}), "OK")
    items_before = documents_of_port(TARGET_PORT, SHOP, ITEMS)
    schedules_before = snapshot(TARGET_PORT, {(SHOP, FIRED)})[SHOP]["schedules"]

    dumped = client(cwd, "--dump", "--collections", f"{SHOP}.{ORDERS}")
    requests = [json.loads(line) for line in dumped.stdout.decode().splitlines()]
    types = [r["type"] for r in requests]
    check("one CREATE_DATABASE and one CREATE_COLLECTION",
          types.count("CREATE_DATABASE") == 1 and types.count("CREATE_COLLECTION") == 1, str(types[:5]))
    check("its triggers and the procedures they need, imports included",
          sorted(r["name"] for r in requests if r["type"] == "SAVE_PROCEDURE") == ["lib", "normalizer", "notifier"]
          and sorted(r["name"] for r in requests if r["type"] == "SAVE_TRIGGER") == ["normalize", "notify"])
    check("no other collection and no schedule",
          {r.get("collectionName") for r in requests if "collectionName" in r} == {ORDERS}
          and "SAVE_SCHEDULE" not in types)

    restored = client(cwd, "--restore", port=TARGET_PORT, stdin=dumped.stdout)
    check("the merge exits 0 although the database exists", restored.returncode == 0, restored.stderr.decode())
    target = {json.loads(d)["_id"]: json.loads(d) for d in documents_of_port(TARGET_PORT, SHOP, ORDERS)}
    source = {json.loads(d)["_id"]: json.loads(d) for d in documents_of_port(SOURCE_PORT, SHOP, ORDERS)}
    check("the changed documents match the source again", all(target.get(f"o{i}") == source[f"o{i}"] for i in range(3)))
    check("the deleted ones are back", all(target.get(f"o{i}") == source[f"o{i}"] for i in (10, 11)))
    check("the extra document is still there", "o99999" in target)
    check("collection B is unchanged", documents_of_port(TARGET_PORT, SHOP, ITEMS) == items_before)
    check("the schedules are unchanged", snapshot(TARGET_PORT, {(SHOP, FIRED)})[SHOP]["schedules"] == schedules_before)

    ghost = client(cwd, "--dump", "--collections", f"{SHOP}.ghost")
    check("a collection the source lacks exits 1", ghost.returncode == 1, ghost.stderr.decode())
    check("before anything reaches stdout", ghost.stdout == b"")


USER_PASSWORD = "client_user_pass1"


def create_user(port: int, name: str, db_perms=None, script_perms=None):
    check_status(f"create {name} on {port}", send(port, {
        "type": "CREATE_USER", "username": name, "password": USER_PASSWORD, "admin": False, "globalPermissions": [],
        "databasePermissions": db_perms or {}, "collectionPermissions": {}, "scriptPermissions": script_perms or {}}),
                 "OK")


def as_user(cwd: str, user: str, *args, port: int = SOURCE_PORT, stdin: bytes = b""):
    return client(cwd, *args, "--user", user, port=port, stdin=stdin, password=USER_PASSWORD)


def database_names(dump: bytes) -> set:
    return {json.loads(line).get("databaseName") for line in dump.decode().splitlines()}


def dumped_types(dump: bytes) -> list:
    return [json.loads(line)["type"] for line in dump.decode().splitlines()]


def document_of(port: int, db: str, coll: str, doc_id: str) -> dict:
    return send(port, {"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": doc_id}).get("object")


def test_non_admin_dump_and_restore(cwd: str):
    section("Non-admin dump and restore")
    for port in (SOURCE_PORT, TARGET_PORT):
        create_user(port, "client_owner", script_perms={CRM: "RUN"})
        check_status(f"client_owner owns {SHOP} on {port}", send(port, {
            "type": "SET_DATABASE_OWNERS", "databaseName": SHOP, "owners": ["client_owner"]}), "OK")
        create_user(port, "client_reader", db_perms={CRM: "READ"})
        create_user(port, "client_writer", db_perms={SHOP: "READ_WRITE"}, script_perms={SHOP: "MANAGE"})
    create_user(SOURCE_PORT, "client_nobody")

    owner_dump = as_user(cwd, "client_owner", "--dump", "--databases", SHOP)
    check("an owner dumps their database", owner_dump.returncode == 0, owner_dump.stderr.decode())
    admin_dump = client(cwd, "--dump", "--databases", SHOP)
    check("and gets the same dump an admin gets", owner_dump.stdout == admin_dump.stdout)
    check_status("change an order on the target", send(TARGET_PORT, {
        "type": "SAVE", "databaseName": SHOP, "collectionName": ORDERS, "object": {**order(5), "qty": 777}}), "OK")
    owner_restore = as_user(cwd, "client_owner", "--restore", port=TARGET_PORT, stdin=owner_dump.stdout)
    check("the owner restores into their existing database", owner_restore.returncode == 0,
          owner_restore.stderr.decode()[-400:])
    check("without sending its CREATE_DATABASE", "skipped 1 existing databases" in owner_restore.stderr.decode(),
          owner_restore.stderr.decode()[-200:])
    check("the changed order matches the source again",
          document_of(TARGET_PORT, SHOP, ORDERS, "o5") == document_of(SOURCE_PORT, SHOP, ORDERS, "o5"))
    whole = as_user(cwd, "client_owner", "--dump")
    check("the owner's whole-server dump exits 0", whole.returncode == 0, whole.stderr.decode())
    check("and holds only the readable database", database_names(whole.stdout) == {SHOP},
          str(database_names(whole.stdout)))
    check("and notes the visible but unreadable one", f"note: {CRM} was not dumped" in whole.stderr.decode(),
          whole.stderr.decode())

    collection_ref = f"{SHOP}.{ORDERS}"
    owner_orders = as_user(cwd, "client_owner", "--dump", "--collections", collection_ref)
    check("an owner dumps one collection", owner_orders.returncode == 0, owner_orders.stderr.decode())
    admin_orders = client(cwd, "--dump", "--collections", collection_ref)
    check("and gets the same collection dump an admin gets", owner_orders.stdout == admin_orders.stdout)
    owner_orders_types = dumped_types(owner_orders.stdout)
    check("with the collection's triggers and the procedures they need",
          owner_orders_types.count("SAVE_PROCEDURE") == 3 and owner_orders_types.count("SAVE_TRIGGER") == 2,
          str(owner_orders_types))
    readmin = client(cwd, "--restore", port=TARGET_PORT, stdin=admin_orders.stdout)
    check("an admin re-saves the collection's definitions on the target", readmin.returncode == 0,
          readmin.stderr.decode()[-300:])
    check_status("change an order on the target", send(TARGET_PORT, {
        "type": "SAVE", "databaseName": SHOP, "collectionName": ORDERS, "object": {**order(6), "qty": 888}}), "OK")
    check_status("delete an order on the target", send(TARGET_PORT, {
        "type": "DELETE", "databaseName": SHOP, "collectionName": ORDERS, "_id": "o7"}), "OK")
    owner_orders_restore = as_user(cwd, "client_owner", "--restore", port=TARGET_PORT, stdin=owner_orders.stdout)
    check("the owner restores the collection over definitions the admin defined",
          owner_orders_restore.returncode == 0, owner_orders_restore.stderr.decode()[-400:])
    check("without sending its CREATE_DATABASE",
          "skipped 1 existing databases" in owner_orders_restore.stderr.decode(),
          owner_orders_restore.stderr.decode()[-200:])
    check("the changed and deleted orders match the source again",
          all(document_of(TARGET_PORT, SHOP, ORDERS, doc_id) == document_of(SOURCE_PORT, SHOP, ORDERS, doc_id)
              for doc_id in ("o6", "o7")))
    target_triggers = send(TARGET_PORT, {"type": "LIST_TRIGGERS", "databaseName": SHOP}).get("triggers") or []
    check("and the collection's triggers are now defined by the owner",
          sorted(t["definer"] for t in target_triggers if t.get("collectionName") == ORDERS)
          == ["client_owner", "client_owner"], str([(t["name"], t.get("definer")) for t in target_triggers]))

    reader_dump = as_user(cwd, "client_reader", "--dump", "--databases", CRM)
    reader_types = dumped_types(reader_dump.stdout)
    check("a READ user dumps data", reader_dump.returncode == 0 and "BULK_SAVE" in reader_types,
          reader_dump.stderr.decode())
    check("without definitions", not {"SAVE_PROCEDURE", "SAVE_TRIGGER", "SAVE_SCHEDULE"} & set(reader_types),
          str(reader_types))
    check("and notes what it skipped", f"note: procedures of {CRM} were not dumped" in reader_dump.stderr.decode(),
          reader_dump.stderr.decode())
    reader_restore = as_user(cwd, "client_reader", "--restore", port=TARGET_PORT, stdin=reader_dump.stdout)
    check("a READ user cannot restore", reader_restore.returncode == 1, reader_restore.stderr.decode())
    check("and is stopped right after the skipped CREATE_DATABASE", "line 2: " in reader_restore.stderr.decode()
          and "FORBIDDEN" in reader_restore.stderr.decode(), reader_restore.stderr.decode()[-300:])

    check_status("add an item the source lacks", send(TARGET_PORT, {
        "type": "SAVE", "databaseName": SHOP, "collectionName": ITEMS, "object": {"_id": "i_extra", "name": "x"}}),
                 "OK")
    items_dump = as_user(cwd, "client_writer", "--dump", "--collections", f"{SHOP}.{ITEMS}")
    check("a READ_WRITE + MANAGE user dumps a collection", items_dump.returncode == 0, items_dump.stderr.decode())
    items_restore = as_user(cwd, "client_writer", "--restore", port=TARGET_PORT, stdin=items_dump.stdout)
    check("and merges it into the target", items_restore.returncode == 0, items_restore.stderr.decode()[-300:])
    target_items = documents_of_port(TARGET_PORT, SHOP, ITEMS)
    check("keeping the target's extra item",
          any(json.loads(doc)["_id"] == "i_extra" for doc in target_items)
          and set(documents_of_port(SOURCE_PORT, SHOP, ITEMS)) <= set(target_items))
    orders_dump = as_user(cwd, "client_writer", "--dump", "--collections", f"{SHOP}.{ORDERS}")
    orders_restore = as_user(cwd, "client_writer", "--restore", port=TARGET_PORT, stdin=orders_dump.stdout)
    check("definitions bound by another user stop the writer's restore",
          orders_restore.returncode == 1 and "403-4" in orders_restore.stderr.decode(),
          orders_restore.stderr.decode()[-300:])

    nobody = as_user(cwd, "client_nobody", "--dump")
    check("a user with no grant dumps nothing", nobody.returncode == 0 and nobody.stdout == b"",
          nobody.stderr.decode())


def test_hygiene(cwd: str, plain_dump: bytes):
    section("Hygiene")
    check("the client working directory stays empty", os.listdir(cwd) == [], str(os.listdir(cwd)))
    check("every dump line parses as a request",
          all(isinstance(json.loads(line).get("type"), str) for line in plain_dump.decode().splitlines()))


def test_tls(cwd: str, work_dir: str, log_path: str):
    section("TLS")
    write_config(work_dir, SOURCE_PORT, tlsEnabled=True, tlsKeystorePath="certs/lwnrdb.p12",
                 tlsKeystorePassword="changeit")
    proc = bu.start_server(work_dir, log_path, port=SOURCE_PORT)
    try:
        trusted = bu.wait_until(lambda: client(cwd, "--tls", "--insecure", "--exec", '{"type":"LIST_DATABASES"}')
                                .returncode == 0, 30.0)
        check("--tls --insecure exits 0", bool(trusted))
        verified = client(cwd, "--tls", "--exec", '{"type":"LIST_DATABASES"}')
        check("--tls against the self-signed certificate exits 3", verified.returncode == 3, verified.stderr.decode())
    finally:
        bu.stop_server(proc, port=SOURCE_PORT)


def main():
    bu.banner("Client (shell, exec, dump/restore) test suite", HOST, SOURCE_PORT)
    bu.require_jar()
    for port in (SOURCE_PORT, TARGET_PORT):
        if bu.port_open(HOST, port):
            print(f"port {port} is already in use", file=sys.stderr)
            sys.exit(1)

    source_dir = tempfile.mkdtemp(prefix="lwnrdb-client-source-")
    target_dir = tempfile.mkdtemp(prefix="lwnrdb-client-target-")
    client_dir = tempfile.mkdtemp(prefix="lwnrdb-client-cwd-")
    source_log = os.path.join(source_dir, "server.out")
    target_log = os.path.join(target_dir, "server.out")
    source = target = None
    try:
        write_config(source_dir, SOURCE_PORT)
        write_config(target_dir, TARGET_PORT)
        source = bu.start_server(source_dir, source_log, port=SOURCE_PORT)
        target = bu.start_server(target_dir, target_log, port=TARGET_PORT)
        seed_source()
        source_state = snapshot(SOURCE_PORT)
        test_exec(client_dir)
        test_shell(client_dir)
        plain_dump = test_round_trip(client_dir, source_state)
        test_restore_one_database(client_dir)
        test_restore_one_collection_into_a_live_server(client_dir)
        test_non_admin_dump_and_restore(client_dir)
        test_hygiene(client_dir, plain_dump)
        bu.stop_server(source, port=SOURCE_PORT)
        source = None
        test_tls(client_dir, source_dir, source_log)
    except Exception as exc:  # noqa: BLE001 - the suite reports rather than propagates
        print(f"\nunexpected failure: {exc}", file=sys.stderr)
        bu.dump_log(source_log)
        bu.dump_log(target_log)
        bu.record_failure()
    finally:
        bu.stop_server(source, port=SOURCE_PORT)
        bu.stop_server(target, port=TARGET_PORT)

    bu.summary(on_failure=lambda: (bu.dump_log(source_log), bu.dump_log(target_log)))


if __name__ == "__main__":
    main()
