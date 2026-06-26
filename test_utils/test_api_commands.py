import os
import socket
import json
import sys
import time

HOST = os.environ.get("API_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("API_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "api_test_db"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

failures = 0


def send(s, f, payload: dict) -> dict:
    try:
        s.sendall((json.dumps(payload) + "\n").encode())
    except (BrokenPipeError, OSError):
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    raw = f.readline().decode().strip()
    if not raw:
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        # The server emits a plaintext "The command is not valid" line when a request
        # fails to parse. Surface it as an error response instead of crashing the suite.
        return {"status": "ERROR", "message": raw}


def check(label: str, response: dict, expected_status: str):
    global failures
    actual = response.get("status")
    ok = actual == expected_status
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         expected={expected_status}  got={actual}  msg={response.get('message', '')!r}")
    if not ok:
        failures += 1


def check_true(label: str, ok: bool, detail: str = ""):
    global failures
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    if detail:
        print(f"         {detail}")
    if not ok:
        failures += 1


def _dig(obj, path: str):
    """Walk a dotted/indexed path like 'results.0.count' or 'object.name'."""
    cur = obj
    for part in path.split("."):
        if cur is None:
            return None
        if part.isdigit() and isinstance(cur, list):
            idx = int(part)
            cur = cur[idx] if 0 <= idx < len(cur) else None
        elif isinstance(cur, dict):
            cur = cur.get(part)
        else:
            return None
    return cur


def check_field(label: str, response: dict, path: str, expected):
    """Assert a value inside the response payload (status is not enough)."""
    global failures
    actual = _dig(response, path)
    ok = actual == expected
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         path={path}  expected={expected!r}  got={actual!r}")
    if not ok:
        failures += 1


def check_code(label: str, response: dict, expected_status: str, expected_code: str):
    """Assert both the status and the errorCode of an error response."""
    global failures
    actual_status = response.get("status")
    actual_code = response.get("errorCode")
    ok = actual_status == expected_status and actual_code == expected_code
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         expected={expected_status}/{expected_code}  "
          f"got={actual_status}/{actual_code}  msg={response.get('message', '')!r}")
    if not ok:
        failures += 1


class Conn:
    def __init__(self):
        self.s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.s.connect((HOST, PORT))
        self.f = self.s.makefile("rb")

    def __enter__(self):
        return self.s, self.f

    def __exit__(self, *_):
        self.s.close()


def new_conn():
    return Conn()


def section(title: str):
    print(f"\n{'─' * 60}")
    print(f"  {title}")
    print(f"{'─' * 60}")


# ── operation wrappers ───────────────────────────────────────────────────────

def authenticate(s, f, username: str, password: str) -> dict:
    return send(s, f, {"type": "AUTHENTICATE", "username": username, "password": password})


def create_user(s, f, username, password, admin=False, global_perms=None, db_perms=None, coll_perms=None):
    return send(s, f, {
        "type": "CREATE_USER", "username": username, "password": password, "admin": admin,
        "globalPermissions": global_perms or [], "databasePermissions": db_perms or {},
        "collectionPermissions": coll_perms or {},
    })


def delete_user(s, f, username: str) -> dict:
    return send(s, f, {"type": "DELETE_USER", "username": username})


def create_db(s, f, name=DB) -> dict:
    return send(s, f, {"type": "CREATE_DATABASE", "databaseName": name})


def drop_db(s, f, name=DB) -> dict:
    return send(s, f, {"type": "DROP_DATABASE", "databaseName": name})


def list_databases(s, f) -> dict:
    return send(s, f, {"type": "LIST_DATABASES"})


def list_collections(s, f, db=DB) -> dict:
    return send(s, f, {"type": "LIST_COLLECTIONS", "databaseName": db})


def create_coll(s, f, coll, db=DB) -> dict:
    return send(s, f, {"type": "CREATE_COLLECTION", "databaseName": db, "collectionName": coll})


def drop_coll(s, f, coll, db=DB) -> dict:
    return send(s, f, {"type": "DROP_COLLECTION", "databaseName": db, "collectionName": coll})


def save(s, f, coll, obj, db=DB) -> dict:
    return send(s, f, {"type": "SAVE", "databaseName": db, "collectionName": coll, "object": obj})


def bulk_save(s, f, coll, objs, db=DB) -> dict:
    return send(s, f, {"type": "BULK_SAVE", "databaseName": db, "collectionName": coll, "objects": objs})


def find_by_id(s, f, coll, _id, db=DB, dirty=False) -> dict:
    payload = {"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": _id}
    if dirty:
        payload["dirtyRead"] = True
    return send(s, f, payload)


def delete(s, f, coll, _id, db=DB) -> dict:
    return send(s, f, {"type": "DELETE", "databaseName": db, "collectionName": coll, "_id": _id})


def aggregate(s, f, coll, steps, db=DB) -> dict:
    return send(s, f, {"type": "AGGREGATE", "databaseName": db, "collectionName": coll,
                       "aggregationSteps": steps})


def create_index(s, f, coll, field, db=DB) -> dict:
    return send(s, f, {"type": "CREATE_INDEX", "databaseName": db, "collectionName": coll, "fieldName": field})


def drop_index(s, f, coll, field, db=DB) -> dict:
    return send(s, f, {"type": "DROP_INDEX", "databaseName": db, "collectionName": coll, "fieldName": field})


def reindex(s, f, coll, fields=None, db=DB) -> dict:
    payload = {"type": "REINDEX", "databaseName": db, "collectionName": coll}
    if fields is not None:
        payload["fieldNames"] = fields
    return send(s, f, payload)


def filter_step(field, op, value):
    return {"type": "FILTER", "operator": {"fieldOperatorType": op, "field": field, "value": value}}


def ids_of(response) -> list:
    return sorted(d.get("_id") for d in (response.get("results") or []))


def wait_for_index(s, f, coll, field, db=DB, timeout_s=15.0):
    """Indexes are built in the background; poll GET_DATABASE_STATS until the field shows up."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        r = send(s, f, {"type": "GET_DATABASE_STATS"})
        for d in r.get("stats", {}).get("databases", []):
            if d.get("name") != db:
                continue
            for c in d.get("collections", []):
                if c.get("name") == coll and field in (c.get("indexes") or []):
                    return True
        time.sleep(0.3)
    time.sleep(2)  # fallback grace period in case stats lag the actual files
    return False


# ── fixtures ─────────────────────────────────────────────────────────────────

COLL_CRUD = "crud"
COLL_AGG = "agg"
COLL_JOIN_LEFT = "agg_join_left"
COLL_JOIN_RIGHT = "agg_join_right"
COLL_TYPES = "types"
COLL_FLOWS = "flows"
COLL_PERM = "perm_coll"

TEST_USERS = ("api_reader", "api_join_user")


def teardown(s, f):
    drop_db(s, f, DB)
    for u in TEST_USERS:
        delete_user(s, f, u)


def setup(s, f):
    teardown(s, f)  # idempotent: clean any leftovers from a previous run
    create_db(s, f, DB)
    for coll in (COLL_CRUD, COLL_AGG, COLL_JOIN_LEFT, COLL_JOIN_RIGHT, COLL_TYPES, COLL_FLOWS, COLL_PERM):
        create_coll(s, f, coll)

    # A read-only user (no write/index perms) and a user that can read the left
    # join collection but NOT the right one (for the JOIN permission negative).
    create_user(s, f, "api_reader", "api_reader1234", db_perms={DB: "READ"})
    create_user(s, f, "api_join_user", "api_join_user1234",
                coll_perms={f"{DB}|{COLL_JOIN_LEFT}": "READ"})

    # Seed the aggregation dataset: varied types so every operator has matches.
    bulk_save(s, f, COLL_AGG, [
        {"_id": "a1", "name": "alice", "age": 30, "rating": 4.5, "active": True,
         "nick": None, "tags": ["x", "y"], "meta": {"k": 1}},
        {"_id": "a2", "name": "bob", "age": 25, "rating": 3.5, "active": False,
         "nick": None, "tags": ["y", "z"], "meta": {"k": 2}},
        {"_id": "a3", "name": "carol", "age": 40, "rating": 4.5, "active": True,
         "tags": ["x", "z"], "meta": {"k": 1}},
        {"_id": "a4", "name": "dave", "age": 25, "rating": 2.0, "active": False,
         "tags": ["w"], "meta": {"k": 3}},
    ])

    # JOIN fixtures: left rows reference right rows by key.
    bulk_save(s, f, COLL_JOIN_LEFT, [
        {"_id": "l1", "key": "k1"}, {"_id": "l2", "key": "k2"},
    ])
    bulk_save(s, f, COLL_JOIN_RIGHT, [
        {"_id": "r1", "key": "k1", "label": "first"},
        {"_id": "r2", "key": "k2", "label": "second"},
    ])


# ══════════════════════════════════════════════════════════════════════════
# Groups
# ══════════════════════════════════════════════════════════════════════════

def test_database_and_collection_ops(s, f):
    section("Database & collection operations (DDL + metadata)")

    check("LIST_DATABASES (public, OK)", list_databases(s, f), "OK")
    check_true("LIST_DATABASES contains api_test_db",
               DB in (list_databases(s, f).get("databases") or []))

    check("CREATE_DATABASE ddl_db", create_db(s, f, "ddl_db"), "OK")
    check_code("CREATE_DATABASE duplicate -> 409-2", create_db(s, f, "ddl_db"), "ERROR", "409-2")
    check_code("CREATE_DATABASE invalid name (too short) -> 400-1",
               create_db(s, f, "ab"), "ERROR", "400-1")
    check_code("CREATE_DATABASE reserved name 'admin' -> 400-1",
               create_db(s, f, "admin"), "ERROR", "400-1")

    check("CREATE_COLLECTION ddl_db/things", create_coll(s, f, "things", db="ddl_db"), "OK")
    check("LIST_COLLECTIONS ddl_db", list_collections(s, f, "ddl_db"), "OK")
    check_true("LIST_COLLECTIONS contains 'things'",
               "things" in (list_collections(s, f, "ddl_db").get("collections") or []))
    check_code("LIST_COLLECTIONS missing db -> 404-4",
               list_collections(s, f, "no_such_db"), "NOT_FOUND", "404-4")

    check("DROP_COLLECTION ddl_db/things", drop_coll(s, f, "things", db="ddl_db"), "OK")
    check("DROP_DATABASE ddl_db", drop_db(s, f, "ddl_db"), "OK")

    r = send(s, f, {"type": "GET_DATABASE_STATS"})
    check("GET_DATABASE_STATS (admin, OK)", r, "OK")
    check_true("GET_DATABASE_STATS reports at least one database",
               (_dig(r, "stats.totals.databaseCount") or 0) >= 1)


def test_crud(s, f):
    section("CRUD & document round-trip")

    check("SAVE with explicit _id", save(s, f, COLL_CRUD, {"_id": "c1", "name": "Alice"}), "OK")
    r = find_by_id(s, f, COLL_CRUD, "c1")
    check("FIND_BY_ID c1 (OK)", r, "OK")
    check_field("FIND_BY_ID returns the saved _id", r, "object._id", "c1")
    check_field("FIND_BY_ID returns the saved name", r, "object.name", "Alice")

    r = save(s, f, COLL_CRUD, {"name": "no-id"})
    check("SAVE without _id (auto-assigned)", r, "OK")
    gen_id = r.get("_id")
    check_true("SAVE returns a generated _id", bool(gen_id), detail=f"_id={gen_id!r}")
    check("FIND_BY_ID on the generated id", find_by_id(s, f, COLL_CRUD, gen_id), "OK")

    check("FIND_BY_ID with dirtyRead", find_by_id(s, f, COLL_CRUD, "c1", dirty=True), "OK")
    check_code("FIND_BY_ID missing id -> 404-2",
               find_by_id(s, f, COLL_CRUD, "does_not_exist"), "NOT_FOUND", "404-2")

    check("DELETE c1", delete(s, f, COLL_CRUD, "c1"), "OK")
    check_code("FIND_BY_ID after delete -> 404-2",
               find_by_id(s, f, COLL_CRUD, "c1"), "NOT_FOUND", "404-2")
    check_code("DELETE missing id -> 404-2",
               delete(s, f, COLL_CRUD, "c1"), "NOT_FOUND", "404-2")

    r = bulk_save(s, f, COLL_CRUD, [{"_id": "b1", "v": 1}, {"_id": "b2", "v": 2}])
    check("BULK_SAVE two docs", r, "OK")
    check("FIND_BY_ID b1", find_by_id(s, f, COLL_CRUD, "b1"), "OK")
    check("FIND_BY_ID b2", find_by_id(s, f, COLL_CRUD, "b2"), "OK")
    check_code("BULK_SAVE duplicate _id in batch -> 400-3",
               bulk_save(s, f, COLL_CRUD, [{"_id": "d", "v": 1}, {"_id": "d", "v": 2}]),
               "ERROR", "400-3")

    check_code("SAVE invalid _id (illegal chars) -> 400-1",
               save(s, f, COLL_CRUD, {"_id": "bad id!", "v": 1}), "ERROR", "400-1")
    check_code("SAVE invalid _id (too long) -> 400-1",
               save(s, f, COLL_CRUD, {"_id": "x" * 65, "v": 1}), "ERROR", "400-1")


def test_value_types(s, f):
    section("Data-type fidelity (round-trip every supported type)")

    docs = {
        "t_string": {"v": "hello"},
        "t_bool_true": {"v": True},
        "t_bool_false": {"v": False},
        "t_null": {"v": None},
        "t_int": {"v": 42},
        "t_double": {"v": 3.14},
        "t_obj": {"v": {"a": 1, "b": {"c": 2}}},
        "t_arr_scalar": {"v": [1, 2, 3]},
        "t_arr_obj": {"v": [{"x": 1}, {"x": 2}]},
        "t_datetime": {"v": "#datetime(2025-06-26T14:30:45)"},
        "t_time": {"v": "#time(14:30:45)"},
    }
    for _id, body in docs.items():
        body = dict(body, _id=_id)
        save(s, f, COLL_TYPES, body)

    check_field("String round-trips", find_by_id(s, f, COLL_TYPES, "t_string"), "object.v", "hello")
    check_field("Boolean true round-trips", find_by_id(s, f, COLL_TYPES, "t_bool_true"), "object.v", True)
    check_field("Boolean false round-trips", find_by_id(s, f, COLL_TYPES, "t_bool_false"), "object.v", False)
    check_field("null round-trips", find_by_id(s, f, COLL_TYPES, "t_null"), "object.v", None)
    check_field("Integer round-trips as 42 (not 42.0)", find_by_id(s, f, COLL_TYPES, "t_int"), "object.v", 42)
    check_field("Double round-trips", find_by_id(s, f, COLL_TYPES, "t_double"), "object.v", 3.14)
    check_field("Nested object round-trips", find_by_id(s, f, COLL_TYPES, "t_obj"), "object.v.b.c", 2)
    check_field("Scalar array round-trips", find_by_id(s, f, COLL_TYPES, "t_arr_scalar"), "object.v", [1, 2, 3])
    check_field("Array-of-objects element round-trips",
                find_by_id(s, f, COLL_TYPES, "t_arr_obj"), "object.v.1.x", 2)
    check_field("DateTime round-trips",
                find_by_id(s, f, COLL_TYPES, "t_datetime"), "object.v", "#datetime(2025-06-26T14:30:45)")
    check_field("Time round-trips", find_by_id(s, f, COLL_TYPES, "t_time"), "object.v", "#time(14:30:45)")

    # The integer must not be serialized as "42.0": inspect the raw response line.
    s.sendall((json.dumps({"type": "FIND_BY_ID", "databaseName": DB,
                           "collectionName": COLL_TYPES, "_id": "t_int"}) + "\n").encode())
    raw = f.readline().decode()
    check_true("Integer is serialized without a trailing .0 on the wire",
               '"v":42' in raw and '"v":42.0' not in raw, detail=raw.strip())


def test_filter_operators(s, f):
    section("FILTER — every field operator across types (scan path)")

    check_field("EQUALS String", aggregate(s, f, COLL_AGG, [filter_step("name", "EQUALS", "alice")]),
                "results.0._id", "a1")
    check_true("NOT_EQUALS String excludes the match",
               "a1" not in ids_of(aggregate(s, f, COLL_AGG, [filter_step("name", "NOT_EQUALS", "alice")])))
    check_true("EQUALS Integer", ids_of(aggregate(s, f, COLL_AGG, [filter_step("age", "EQUALS", 25)])) == ["a2", "a4"])
    check_true("EQUALS Double", ids_of(aggregate(s, f, COLL_AGG, [filter_step("rating", "EQUALS", 4.5)])) == ["a1", "a3"])
    check_true("EQUALS Boolean", ids_of(aggregate(s, f, COLL_AGG, [filter_step("active", "EQUALS", True)])) == ["a1", "a3"])
    check_true("EQUALS null", ids_of(aggregate(s, f, COLL_AGG, [filter_step("nick", "EQUALS", None)])) == ["a1", "a2"])
    check_true("EQUALS Object (element-match)",
               ids_of(aggregate(s, f, COLL_AGG, [filter_step("meta", "EQUALS", {"k": 1})])) == ["a1", "a3"])
    check_true("EQUALS Array (element-match)",
               ids_of(aggregate(s, f, COLL_AGG, [filter_step("tags", "EQUALS", ["x", "y"])])) == ["a1"])

    check_true("GREATER_THAN Integer",
               ids_of(aggregate(s, f, COLL_AGG, [filter_step("age", "GREATER_THAN", 30)])) == ["a3"])
    check_true("GREATER_THAN_EQUALS Integer",
               ids_of(aggregate(s, f, COLL_AGG, [filter_step("age", "GREATER_THAN_EQUALS", 30)])) == ["a1", "a3"])
    check_true("SMALLER_THAN Double",
               ids_of(aggregate(s, f, COLL_AGG, [filter_step("rating", "SMALLER_THAN", 3.5)])) == ["a4"])
    check_true("SMALLER_THAN_EQUALS Double",
               ids_of(aggregate(s, f, COLL_AGG, [filter_step("rating", "SMALLER_THAN_EQUALS", 3.5)])) == ["a2", "a4"])

    check_true("IN String list",
               ids_of(aggregate(s, f, COLL_AGG, [filter_step("name", "IN", ["alice", "bob"])])) == ["a1", "a2"])
    check_true("NOT_IN String list excludes listed",
               ids_of(aggregate(s, f, COLL_AGG, [filter_step("name", "NOT_IN", ["alice", "bob"])])) == ["a3", "a4"])
    check_true("IN over a list of Objects (element-match)",
               ids_of(aggregate(s, f, COLL_AGG, [filter_step("meta", "IN", [{"k": 1}, {"k": 3}])])) == ["a1", "a3", "a4"])

    check_true("CONTAINS on an array field",
               ids_of(aggregate(s, f, COLL_AGG, [filter_step("tags", "CONTAINS", "z")])) == ["a2", "a3"])
    check_true("CONTAINS on a string field",
               "a1" in ids_of(aggregate(s, f, COLL_AGG, [filter_step("name", "CONTAINS", "lic")])))

    check_code("FILTER with no match -> 404-3",
               aggregate(s, f, COLL_AGG, [filter_step("name", "EQUALS", "nobody")]), "NOT_FOUND", "404-3")


def test_filter_with_indexes(s, f):
    section("FILTER — index path agrees with the scan path")

    cases = [
        ("name EQUALS string", "name", [filter_step("name", "EQUALS", "carol")]),
        ("age GREATER_THAN int", "age", [filter_step("age", "GREATER_THAN", 25)]),
        ("meta EQUALS object", "meta", [filter_step("meta", "EQUALS", {"k": 1})]),
        ("tags EQUALS array", "tags", [filter_step("tags", "EQUALS", ["x", "z"])]),
    ]
    # Capture scan results first, then build the index and re-query.
    scan = {name: ids_of(aggregate(s, f, COLL_AGG, steps)) for name, _field, steps in cases}
    for _name, field, _steps in cases:
        create_index(s, f, COLL_AGG, field)
    for _name, field, _steps in cases:
        wait_for_index(s, f, COLL_AGG, field)
    for name, _field, steps in cases:
        indexed = ids_of(aggregate(s, f, COLL_AGG, steps))
        check_true(f"index path matches scan path: {name}", indexed == scan[name],
                   detail=f"scan={scan[name]}  indexed={indexed}")


def test_conjunctions(s, f):
    section("Conjunction operators (AND / OR / NOR / XOR / NAND)")

    def conj(op, *leaves):
        return [{"type": "FILTER", "operator": {"conjunctionType": op, "operators": list(leaves)}}]

    age25 = {"fieldOperatorType": "EQUALS", "field": "age", "value": 25}
    active = {"fieldOperatorType": "EQUALS", "field": "active", "value": True}
    rating45 = {"fieldOperatorType": "EQUALS", "field": "rating", "value": 4.5}

    check_true("AND (active AND rating=4.5)",
               ids_of(aggregate(s, f, COLL_AGG, conj("AND", active, rating45))) == ["a1", "a3"])
    check_true("OR (age=25 OR active)",
               ids_of(aggregate(s, f, COLL_AGG, conj("OR", age25, active))) == ["a1", "a2", "a3", "a4"])
    check_true("XOR (active XOR rating=4.5) -> exactly one true",
               ids_of(aggregate(s, f, COLL_AGG, conj("XOR", active, rating45))) == [])
    check_true("NOR (NOT age=25 AND NOT active)",
               ids_of(aggregate(s, f, COLL_AGG, conj("NOR", age25, active))) == [])
    check_true("NAND (NOT(active AND rating=4.5))",
               ids_of(aggregate(s, f, COLL_AGG, conj("NAND", active, rating45))) == ["a2", "a4"])

    # NOR/NAND complement against the PK universe via the index-only COUNT path.
    r = aggregate(s, f, COLL_AGG, conj("NAND", active, rating45) + [{"type": "COUNT"}])
    check_field("COUNT after NAND conjunction", r, "results.0.count", 2)


def test_aggregation_steps(s, f):
    section("Aggregation steps (MAP / GROUP_BY / JOIN / COUNT / DISTINCT / LIMIT / SKIP / SORT)")

    # A MAP operator without an "operator" field removes the named field from each doc.
    r = aggregate(s, f, COLL_AGG, [{"type": "MAP", "operators": [{"fieldName": "meta"}]}])
    check("MAP remove-field (OK)", r, "OK")
    check_true("MAP keeps one row per input doc", len(r.get("results") or []) == 4)
    check_true("MAP removed the 'meta' field but kept 'name'",
               all(("meta" not in d and "name" in d) for d in (r.get("results") or [])),
               detail=f"first row={ (r.get('results') or [{}])[0] }")

    r = aggregate(s, f, COLL_AGG, [{"type": "GROUP_BY", "fieldName": "age"}])
    check("GROUP_BY age (OK)", r, "OK")
    groups = {d.get("age"): len(d.get("group") or []) for d in (r.get("results") or [])}
    check_true("GROUP_BY age=25 has two members", groups.get(25) == 2, detail=f"groups={groups}")

    r = aggregate(s, f, COLL_JOIN_LEFT, [{"type": "JOIN", "joinCollection": COLL_JOIN_RIGHT,
                                          "localField": "key", "remoteField": "key", "asField": "joined"}])
    check("JOIN left->right (OK)", r, "OK")
    by_id = {d.get("_id"): d for d in (r.get("results") or [])}
    check_true("JOIN populates asField for l1",
               (by_id.get("l1", {}).get("joined") or [{}])[0].get("label") == "first",
               detail=f"l1.joined={by_id.get('l1', {}).get('joined')}")

    r = aggregate(s, f, COLL_AGG, [{"type": "COUNT"}])
    check_field("COUNT whole collection", r, "results.0.count", 4)
    r = aggregate(s, f, COLL_AGG, [filter_step("active", "EQUALS", True), {"type": "COUNT"}])
    check_field("COUNT with filter", r, "results.0.count", 2)

    r = aggregate(s, f, COLL_AGG, [{"type": "DISTINCT", "fieldName": "rating"}])
    check("DISTINCT on rating (OK)", r, "OK")
    distinct_ratings = sorted({d.get("rating") for d in (r.get("results") or [])})
    check_true("DISTINCT rating yields {2.0, 3.5, 4.5}", distinct_ratings == [2.0, 3.5, 4.5],
               detail=f"got={distinct_ratings}")
    check("DISTINCT whole documents (no fieldName)",
          aggregate(s, f, COLL_AGG, [{"type": "DISTINCT"}]), "OK")

    r = aggregate(s, f, COLL_AGG, [{"type": "SORT", "fieldName": "age", "ascending": True}])
    check_true("SORT ascending by age",
               [d.get("age") for d in (r.get("results") or [])] == [25, 25, 30, 40])
    r = aggregate(s, f, COLL_AGG, [{"type": "SORT", "fieldName": "age", "ascending": False}])
    check_true("SORT descending by age",
               [d.get("age") for d in (r.get("results") or [])] == [40, 30, 25, 25])

    r = aggregate(s, f, COLL_AGG, [{"type": "SORT", "fieldName": "age", "ascending": True},
                                   {"type": "LIMIT", "limit": 2}])
    check_true("LIMIT 2 truncates", len(r.get("results") or []) == 2)
    r = aggregate(s, f, COLL_AGG, [{"type": "SORT", "fieldName": "age", "ascending": True},
                                   {"type": "SKIP", "skip": 2}])
    check_true("SKIP 2 drops the first two", len(r.get("results") or []) == 2)

    check_code("LIMIT 0 is invalid -> 400-1",
               aggregate(s, f, COLL_AGG, [{"type": "LIMIT", "limit": 0}]), "ERROR", "400-1")
    check_code("SKIP negative is invalid -> 400-1",
               aggregate(s, f, COLL_AGG, [{"type": "SKIP", "skip": -1}]), "ERROR", "400-1")

    check("Empty aggregationSteps returns all docs",
          aggregate(s, f, COLL_AGG, []), "OK")

    # Multi-step pipeline: FILTER -> SORT -> LIMIT.
    r = aggregate(s, f, COLL_AGG, [filter_step("active", "EQUALS", True),
                                   {"type": "SORT", "fieldName": "age", "ascending": True},
                                   {"type": "LIMIT", "limit": 1}])
    check_field("FILTER->SORT->LIMIT yields the youngest active user", r, "results.0._id", "a1")


def test_empty_collection_aggregate(s, f):
    section("Aggregate on an empty collection")
    create_coll(s, f, "empty_coll")
    check_code("AGGREGATE on empty collection -> 404-3",
               aggregate(s, f, "empty_coll", []), "NOT_FOUND", "404-3")
    drop_coll(s, f, "empty_coll")


def test_index_ops(s, f):
    section("Index lifecycle (CREATE_INDEX / DROP_INDEX / REINDEX)")

    create_coll(s, f, "idx_coll")
    bulk_save(s, f, "idx_coll", [{"_id": "i1", "email": "a@x.io"}, {"_id": "i2", "email": "b@x.io"}])

    check("CREATE_INDEX email", create_index(s, f, "idx_coll", "email"), "OK")
    wait_for_index(s, f, "idx_coll", "email")
    check_true("Query is correct after CREATE_INDEX",
               ids_of(aggregate(s, f, "idx_coll", [filter_step("email", "EQUALS", "a@x.io")])) == ["i1"])

    check("REINDEX a specific field", reindex(s, f, "idx_coll", ["email"]), "OK")
    check("REINDEX all fields (omit fieldNames)", reindex(s, f, "idx_coll"), "OK")
    check_true("Query is correct after REINDEX",
               ids_of(aggregate(s, f, "idx_coll", [filter_step("email", "EQUALS", "b@x.io")])) == ["i2"])

    check("DROP_INDEX email", drop_index(s, f, "idx_coll", "email"), "OK")
    # DROP_INDEX is idempotent: dropping a field with no index is a no-op that returns OK.
    check("DROP_INDEX on a field with no index is idempotent (OK)",
          drop_index(s, f, "idx_coll", "no_such_field"), "OK")

    drop_coll(s, f, "idx_coll")


def test_flows(s, f):
    section("Multi-operation flows (save -> update -> verify)")

    # Upsert flow: same _id overwrites, count stays 1.
    save(s, f, COLL_FLOWS, {"_id": "f1", "status": "new"})
    check_field("Initial value is 'new'", find_by_id(s, f, COLL_FLOWS, "f1"), "object.status", "new")
    save(s, f, COLL_FLOWS, {"_id": "f1", "status": "done"})
    check_field("Value updated to 'done'", find_by_id(s, f, COLL_FLOWS, "f1"), "object.status", "done")
    # Upsert overwrote f1 rather than inserting a second doc: exactly one doc has status 'done'.
    check_field("Upsert did not insert a duplicate (count==1)",
                aggregate(s, f, COLL_FLOWS, [filter_step("status", "EQUALS", "done"), {"type": "COUNT"}]),
                "results.0.count", 1)

    # Grow-update / relocation flow: tiny doc then a much larger one (same id).
    save(s, f, COLL_FLOWS, {"_id": "grow", "payload": "x"})
    big = "y" * 4096
    save(s, f, COLL_FLOWS, {"_id": "grow", "payload": big})
    check_field("Grow-update reads back the larger value",
                find_by_id(s, f, COLL_FLOWS, "grow"), "object.payload", big)

    # Index-consistency flow: write then immediately query the index-backed path.
    create_index(s, f, COLL_FLOWS, "status")
    wait_for_index(s, f, COLL_FLOWS, "status")
    save(s, f, COLL_FLOWS, {"_id": "ic", "status": "fresh"})
    check_true("Just-written value is found immediately (no false negative)",
               "ic" in ids_of(aggregate(s, f, COLL_FLOWS, [filter_step("status", "EQUALS", "fresh")])))
    save(s, f, COLL_FLOWS, {"_id": "ic", "status": "changed"})
    check_true("Old value no longer matches immediately after update",
               "ic" not in ids_of(aggregate(s, f, COLL_FLOWS, [filter_step("status", "EQUALS", "fresh")])))
    check_true("New value matches immediately after update",
               "ic" in ids_of(aggregate(s, f, COLL_FLOWS, [filter_step("status", "EQUALS", "changed")])))

    # Delete-then-recreate flow.
    save(s, f, COLL_FLOWS, {"_id": "dr", "v": 1})
    delete(s, f, COLL_FLOWS, "dr")
    check_code("Deleted doc is gone -> 404-2", find_by_id(s, f, COLL_FLOWS, "dr"), "NOT_FOUND", "404-2")
    save(s, f, COLL_FLOWS, {"_id": "dr", "v": 2})
    check_field("Re-created doc reads back", find_by_id(s, f, COLL_FLOWS, "dr"), "object.v", 2)

    # Bulk-update flow: insert then bulk-update all to longer values.
    bulk_save(s, f, COLL_FLOWS, [{"_id": f"bu_{i}", "v": "short"} for i in range(4)])
    bulk_save(s, f, COLL_FLOWS, [{"_id": f"bu_{i}", "v": f"updated-longer-value-{i}"} for i in range(4)])
    bad = sum(1 for i in range(4)
              if _dig(find_by_id(s, f, COLL_FLOWS, f"bu_{i}"), "object.v") != f"updated-longer-value-{i}")
    check_true("Bulk update reads back intact for every doc", bad == 0, detail=f"{bad}/4 stale")


def test_operation_permissions(s, f):
    section("Operation-level permission edges (non-overlapping with test_auth)")

    # Unauthenticated connection.
    with new_conn() as (us, uf):
        check_code("AGGREGATE before auth -> 401-1",
                   aggregate(us, uf, COLL_AGG, []), "UNAUTHENTICATED", "401-1")

    # Read-only user: writes and index ops are forbidden.
    with new_conn() as (rs, rf):
        authenticate(rs, rf, "api_reader", "api_reader1234")
        check_code("read-only user SAVE -> 403-1",
                   save(rs, rf, COLL_CRUD, {"_id": "z", "v": 1}), "FORBIDDEN", "403-1")
        check_code("read-only user BULK_SAVE -> 403-1",
                   bulk_save(rs, rf, COLL_CRUD, [{"_id": "z", "v": 1}]), "FORBIDDEN", "403-1")
        check_code("read-only user CREATE_INDEX -> 403-1",
                   create_index(rs, rf, COLL_AGG, "name"), "FORBIDDEN", "403-1")
        check_code("read-only user REINDEX -> 403-1",
                   reindex(rs, rf, COLL_AGG), "FORBIDDEN", "403-1")
        check("read-only user can still AGGREGATE", aggregate(rs, rf, COLL_AGG, []), "OK")

    # JOIN requires READ on the remote collection too.
    with new_conn() as (js, jf):
        authenticate(js, jf, "api_join_user", "api_join_user1234")
        check("join user can read the left collection", aggregate(js, jf, COLL_JOIN_LEFT, []), "OK")
        check_code("JOIN without READ on the remote collection -> 403-1",
                   aggregate(js, jf, COLL_JOIN_LEFT,
                             [{"type": "JOIN", "joinCollection": COLL_JOIN_RIGHT,
                               "localField": "key", "remoteField": "key", "asField": "j"}]),
                   "FORBIDDEN", "403-1")


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    print("\n" + "═" * 60)
    print("  LWNRDB — API commands & aggregations integration suite")
    print("═" * 60)
    print(f"  Connecting to {HOST}:{PORT}")

    with new_conn() as (s, f):
        r = authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            print("        Make sure the server is running and lwnrdb.cfg has the correct")
            print(f"        defaultAdminUsername={ADMIN_USERNAME!r} / defaultAdminPassword set.\n")
            sys.exit(1)
        print("\n  Setting up fixtures...")
        setup(s, f)

    groups = [
        test_database_and_collection_ops,
        test_crud,
        test_value_types,
        test_filter_operators,
        test_filter_with_indexes,
        test_conjunctions,
        test_aggregation_steps,
        test_empty_collection_aggregate,
        test_index_ops,
        test_flows,
        test_operation_permissions,
    ]
    for group in groups:
        with new_conn() as (s, f):
            authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
            group(s, f)

    with new_conn() as (s, f):
        authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown(s, f)

    print("\n" + "═" * 60)
    if failures == 0:
        print(f"  \033[92mAll checks passed.\033[0m")
    else:
        print(f"  \033[91m{failures} check(s) FAILED.\033[0m")
    print("═" * 60 + "\n")

    sys.exit(0 if failures == 0 else 1)


if __name__ == "__main__":
    main()
