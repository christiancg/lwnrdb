import os
import json
import sys
import time

import base_utils as bu
from base_utils import Conn, check, check_code, check_field, check_status, section

HOST = os.environ.get("API_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("API_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "api_test_db"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)



# ── operation wrappers ───────────────────────────────────────────────────────

def create_user(c, username, password, admin=False, global_perms=None, db_perms=None, coll_perms=None):
    return c.send({
        "type": "CREATE_USER", "username": username, "password": password, "admin": admin,
        "globalPermissions": global_perms or [], "databasePermissions": db_perms or {},
        "collectionPermissions": coll_perms or {},
    })


def delete_user(c, username: str) -> dict:
    return c.send({"type": "DELETE_USER", "username": username})


def create_db(c, name=DB) -> dict:
    return c.send({"type": "CREATE_DATABASE", "databaseName": name})


def drop_db(c, name=DB) -> dict:
    return c.send({"type": "DROP_DATABASE", "databaseName": name})


def list_databases(c) -> dict:
    return c.send({"type": "LIST_DATABASES"})


def list_collections(c, db=DB) -> dict:
    return c.send({"type": "LIST_COLLECTIONS", "databaseName": db})


def create_coll(c, coll, db=DB) -> dict:
    return c.send({"type": "CREATE_COLLECTION", "databaseName": db, "collectionName": coll})


def drop_coll(c, coll, db=DB) -> dict:
    return c.send({"type": "DROP_COLLECTION", "databaseName": db, "collectionName": coll})


def save(c, coll, obj, db=DB) -> dict:
    return c.send({"type": "SAVE", "databaseName": db, "collectionName": coll, "object": obj})


def bulk_save(c, coll, objs, db=DB) -> dict:
    return c.send({"type": "BULK_SAVE", "databaseName": db, "collectionName": coll, "objects": objs})


def find_by_id(c, coll, _id, db=DB, dirty=False) -> dict:
    payload = {"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": _id}
    if dirty:
        payload["dirtyRead"] = True
    return c.send(payload)


def delete(c, coll, _id, db=DB) -> dict:
    return c.send({"type": "DELETE", "databaseName": db, "collectionName": coll, "_id": _id})


def aggregate(c, coll, steps, db=DB) -> dict:
    return c.send({"type": "AGGREGATE", "databaseName": db, "collectionName": coll,
                       "aggregationSteps": steps})


def analyze(c, coll, steps, db=DB) -> dict:
    return c.send({"type": "AGGREGATE", "databaseName": db, "collectionName": coll,
                       "aggregationSteps": steps, "analyze": True})


def create_index(c, coll, field, db=DB) -> dict:
    return c.send({"type": "CREATE_INDEX", "databaseName": db, "collectionName": coll, "fieldName": field})


def drop_index(c, coll, field, db=DB) -> dict:
    return c.send({"type": "DROP_INDEX", "databaseName": db, "collectionName": coll, "fieldName": field})


def reindex(c, coll, fields=None, db=DB) -> dict:
    payload = {"type": "REINDEX", "databaseName": db, "collectionName": coll}
    if fields is not None:
        payload["fieldNames"] = fields
    return c.send(payload)


def filter_step(field, op, value):
    return {"type": "FILTER", "operator": {"fieldOperatorType": op, "field": field, "value": value}}


def ids_of(response) -> list:
    return sorted(d.get("_id") for d in (response.get("results") or []))


def wait_for_index(c, coll, field, db=DB, timeout_s=15.0):
    """Indexes are built in the background; poll GET_DATABASE_STATS until the field shows up."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        r = c.send({"type": "GET_DATABASE_STATS"})
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


def teardown(c):
    drop_db(c, DB)
    for u in TEST_USERS:
        delete_user(c, u)


def setup(c):
    teardown(c)  # idempotent: clean any leftovers from a previous run
    create_db(c, DB)
    for coll in (COLL_CRUD, COLL_AGG, COLL_JOIN_LEFT, COLL_JOIN_RIGHT, COLL_TYPES, COLL_FLOWS, COLL_PERM):
        create_coll(c, coll)

    # A read-only user (no write/index perms) and a user that can read the left
    # join collection but NOT the right one (for the JOIN permission negative).
    create_user(c, "api_reader", "api_reader1234", db_perms={DB: "READ"})
    create_user(c, "api_join_user", "api_join_user1234",
                coll_perms={f"{DB}|{COLL_JOIN_LEFT}": "READ"})

    # Seed the aggregation dataset: varied types so every operator has matches.
    bulk_save(c, COLL_AGG, [
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
    bulk_save(c, COLL_JOIN_LEFT, [
        {"_id": "l1", "key": "k1"}, {"_id": "l2", "key": "k2"},
    ])
    bulk_save(c, COLL_JOIN_RIGHT, [
        {"_id": "r1", "key": "k1", "label": "first"},
        {"_id": "r2", "key": "k2", "label": "second"},
    ])


# ══════════════════════════════════════════════════════════════════════════
# Groups
# ══════════════════════════════════════════════════════════════════════════

def test_reserved_script_runs_collection(c):
    section("Reserved script_runs collection")

    # The history collection is the server's to write. Every mutation is refused by validation, whether or
    # not the collection exists yet.
    check_code("SAVE into script_runs -> 400-1",
               save(c, "script_runs", {"_id": "x", "a": 1}), "ERROR", "400-1")
    check_code("BULK_SAVE into script_runs -> 400-1",
               bulk_save(c, "script_runs", [{"_id": "x"}]), "ERROR", "400-1")
    check_code("DELETE from script_runs -> 400-1",
               delete(c, "script_runs", "x"), "ERROR", "400-1")
    check_code("CREATE_COLLECTION script_runs -> 400-1",
               create_coll(c, "script_runs"), "ERROR", "400-1")
    check_code("DROP_COLLECTION script_runs -> 400-1",
               drop_coll(c, "script_runs"), "ERROR", "400-1")

    # Reads stay open: the collection exists to be queried, and is simply empty until a script has run.
    # What matters is that a read is never refused for naming a reserved collection.
    read = aggregate(c, "script_runs", [{"type": "COUNT"}])
    check("AGGREGATE over script_runs is not refused as reserved",
               read.get("errorCode") != "400-1", detail=f"got {read}")
    check_code("FIND_BY_ID in an unwritten script_runs -> 404-2",
               find_by_id(c, "script_runs", "nope"), "NOT_FOUND", "404-2")
    check_status("CREATE_INDEX on script_runs is allowed", create_index(c, "script_runs", "outcome"), "OK")


def test_database_and_collection_ops(c):
    section("Database & collection operations (DDL + metadata)")

    check_status("LIST_DATABASES (public, OK)", list_databases(c), "OK")
    check("LIST_DATABASES contains api_test_db",
               DB in (list_databases(c).get("databases") or []))

    check_status("CREATE_DATABASE ddl_db", create_db(c, "ddl_db"), "OK")
    check_code("CREATE_DATABASE duplicate -> 409-2", create_db(c, "ddl_db"), "ERROR", "409-2")
    check_code("CREATE_DATABASE invalid name (too short) -> 400-1",
               create_db(c, "ab"), "ERROR", "400-1")
    check_code("CREATE_DATABASE reserved name 'admin' -> 400-1",
               create_db(c, "admin"), "ERROR", "400-1")

    check_status("CREATE_COLLECTION ddl_db/things", create_coll(c, "things", db="ddl_db"), "OK")
    check_status("LIST_COLLECTIONS ddl_db", list_collections(c, "ddl_db"), "OK")
    check("LIST_COLLECTIONS contains 'things'",
               "things" in (list_collections(c, "ddl_db").get("collections") or []))
    check_code("LIST_COLLECTIONS missing db -> 404-4",
               list_collections(c, "no_such_db"), "NOT_FOUND", "404-4")

    check_status("DROP_COLLECTION ddl_db/things", drop_coll(c, "things", db="ddl_db"), "OK")
    check_status("DROP_DATABASE ddl_db", drop_db(c, "ddl_db"), "OK")

    r = c.send({"type": "GET_DATABASE_STATS"})
    check_status("GET_DATABASE_STATS (admin, OK)", r, "OK")
    check("GET_DATABASE_STATS reports at least one database",
               (bu.dig(r, "stats.totals.databaseCount") or 0) >= 1)


def test_crud(c):
    section("CRUD & document round-trip")

    check_status("SAVE with explicit _id", save(c, COLL_CRUD, {"_id": "c1", "name": "Alice"}), "OK")
    r = find_by_id(c, COLL_CRUD, "c1")
    check_status("FIND_BY_ID c1 (OK)", r, "OK")
    check_field("FIND_BY_ID returns the saved _id", r, "object._id", "c1")
    check_field("FIND_BY_ID returns the saved name", r, "object.name", "Alice")

    r = save(c, COLL_CRUD, {"name": "no-id"})
    check_status("SAVE without _id (auto-assigned)", r, "OK")
    gen_id = r.get("_id")
    check("SAVE returns a generated _id", bool(gen_id), detail=f"_id={gen_id!r}")
    check_status("FIND_BY_ID on the generated id", find_by_id(c, COLL_CRUD, gen_id), "OK")

    check_status("FIND_BY_ID with dirtyRead", find_by_id(c, COLL_CRUD, "c1", dirty=True), "OK")
    check_code("FIND_BY_ID missing id -> 404-2",
               find_by_id(c, COLL_CRUD, "does_not_exist"), "NOT_FOUND", "404-2")

    check_status("DELETE c1", delete(c, COLL_CRUD, "c1"), "OK")
    check_code("FIND_BY_ID after delete -> 404-2",
               find_by_id(c, COLL_CRUD, "c1"), "NOT_FOUND", "404-2")
    check_code("DELETE missing id -> 404-2",
               delete(c, COLL_CRUD, "c1"), "NOT_FOUND", "404-2")

    r = bulk_save(c, COLL_CRUD, [{"_id": "b1", "v": 1}, {"_id": "b2", "v": 2}])
    check_status("BULK_SAVE two docs", r, "OK")
    check_status("FIND_BY_ID b1", find_by_id(c, COLL_CRUD, "b1"), "OK")
    check_status("FIND_BY_ID b2", find_by_id(c, COLL_CRUD, "b2"), "OK")
    check_code("BULK_SAVE duplicate _id in batch -> 400-3",
               bulk_save(c, COLL_CRUD, [{"_id": "d", "v": 1}, {"_id": "d", "v": 2}]),
               "ERROR", "400-3")

    check_code("SAVE invalid _id (illegal chars) -> 400-1",
               save(c, COLL_CRUD, {"_id": "bad id!", "v": 1}), "ERROR", "400-1")
    check_code("SAVE invalid _id (too long) -> 400-1",
               save(c, COLL_CRUD, {"_id": "x" * 65, "v": 1}), "ERROR", "400-1")


def test_value_types(c):
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
        save(c, COLL_TYPES, body)

    check_field("String round-trips", find_by_id(c, COLL_TYPES, "t_string"), "object.v", "hello")
    check_field("Boolean true round-trips", find_by_id(c, COLL_TYPES, "t_bool_true"), "object.v", True)
    check_field("Boolean false round-trips", find_by_id(c, COLL_TYPES, "t_bool_false"), "object.v", False)
    check_field("null round-trips", find_by_id(c, COLL_TYPES, "t_null"), "object.v", None)
    check_field("Integer round-trips as 42 (not 42.0)", find_by_id(c, COLL_TYPES, "t_int"), "object.v", 42)
    check_field("Double round-trips", find_by_id(c, COLL_TYPES, "t_double"), "object.v", 3.14)
    check_field("Nested object round-trips", find_by_id(c, COLL_TYPES, "t_obj"), "object.v.b.c", 2)
    check_field("Scalar array round-trips", find_by_id(c, COLL_TYPES, "t_arr_scalar"), "object.v", [1, 2, 3])
    check_field("Array-of-objects element round-trips",
                find_by_id(c, COLL_TYPES, "t_arr_obj"), "object.v.1.x", 2)
    check_field("DateTime round-trips",
                find_by_id(c, COLL_TYPES, "t_datetime"), "object.v", "#datetime(2025-06-26T14:30:45)")
    check_field("Time round-trips", find_by_id(c, COLL_TYPES, "t_time"), "object.v", "#time(14:30:45)")

    # The integer must not be serialized as "42.0": inspect the raw response line.
    c.s.sendall((json.dumps({"type": "FIND_BY_ID", "databaseName": DB,
                             "collectionName": COLL_TYPES, "_id": "t_int"}) + "\n").encode())
    raw = c.f.readline().decode()
    check("Integer is serialized without a trailing .0 on the wire",
               '"v":42' in raw and '"v":42.0' not in raw, detail=raw.strip())


def test_filter_operators(c):
    section("FILTER — every field operator across types (scan path)")

    check_field("EQUALS String", aggregate(c, COLL_AGG, [filter_step("name", "EQUALS", "alice")]),
                "results.0._id", "a1")
    check("NOT_EQUALS String excludes the match",
               "a1" not in ids_of(aggregate(c, COLL_AGG, [filter_step("name", "NOT_EQUALS", "alice")])))
    check("EQUALS Integer", ids_of(aggregate(c, COLL_AGG, [filter_step("age", "EQUALS", 25)])) == ["a2", "a4"])
    check("EQUALS Double", ids_of(aggregate(c, COLL_AGG, [filter_step("rating", "EQUALS", 4.5)])) == ["a1", "a3"])
    check("EQUALS Boolean", ids_of(aggregate(c, COLL_AGG, [filter_step("active", "EQUALS", True)])) == ["a1", "a3"])
    check("EQUALS null", ids_of(aggregate(c, COLL_AGG, [filter_step("nick", "EQUALS", None)])) == ["a1", "a2"])
    check("EQUALS Object (element-match)",
               ids_of(aggregate(c, COLL_AGG, [filter_step("meta", "EQUALS", {"k": 1})])) == ["a1", "a3"])
    check("EQUALS Array (element-match)",
               ids_of(aggregate(c, COLL_AGG, [filter_step("tags", "EQUALS", ["x", "y"])])) == ["a1"])

    check("GREATER_THAN Integer",
               ids_of(aggregate(c, COLL_AGG, [filter_step("age", "GREATER_THAN", 30)])) == ["a3"])
    check("GREATER_THAN_EQUALS Integer",
               ids_of(aggregate(c, COLL_AGG, [filter_step("age", "GREATER_THAN_EQUALS", 30)])) == ["a1", "a3"])
    check("SMALLER_THAN Double",
               ids_of(aggregate(c, COLL_AGG, [filter_step("rating", "SMALLER_THAN", 3.5)])) == ["a4"])
    check("SMALLER_THAN_EQUALS Double",
               ids_of(aggregate(c, COLL_AGG, [filter_step("rating", "SMALLER_THAN_EQUALS", 3.5)])) == ["a2", "a4"])

    check("IN String list",
               ids_of(aggregate(c, COLL_AGG, [filter_step("name", "IN", ["alice", "bob"])])) == ["a1", "a2"])
    check("NOT_IN String list excludes listed",
               ids_of(aggregate(c, COLL_AGG, [filter_step("name", "NOT_IN", ["alice", "bob"])])) == ["a3", "a4"])
    check("IN over a list of Objects (element-match)",
               ids_of(aggregate(c, COLL_AGG, [filter_step("meta", "IN", [{"k": 1}, {"k": 3}])])) == ["a1", "a3", "a4"])

    check("CONTAINS on an array field",
               ids_of(aggregate(c, COLL_AGG, [filter_step("tags", "CONTAINS", "z")])) == ["a2", "a3"])
    check("CONTAINS on a string field",
               "a1" in ids_of(aggregate(c, COLL_AGG, [filter_step("name", "CONTAINS", "lic")])))

    check_code("FILTER with no match -> 404-3",
               aggregate(c, COLL_AGG, [filter_step("name", "EQUALS", "nobody")]), "NOT_FOUND", "404-3")


def test_filter_with_indexes(c):
    section("FILTER — index path agrees with the scan path")

    cases = [
        ("name EQUALS string", "name", [filter_step("name", "EQUALS", "carol")]),
        ("age GREATER_THAN int", "age", [filter_step("age", "GREATER_THAN", 25)]),
        ("meta EQUALS object", "meta", [filter_step("meta", "EQUALS", {"k": 1})]),
        ("tags EQUALS array", "tags", [filter_step("tags", "EQUALS", ["x", "z"])]),
    ]
    # Capture scan results first, then build the index and re-query.
    scan = {name: ids_of(aggregate(c, COLL_AGG, steps)) for name, _field, steps in cases}
    for _name, field, _steps in cases:
        create_index(c, COLL_AGG, field)
    for _name, field, _steps in cases:
        wait_for_index(c, COLL_AGG, field)
    for name, _field, steps in cases:
        indexed = ids_of(aggregate(c, COLL_AGG, steps))
        check(f"index path matches scan path: {name}", indexed == scan[name],
                   detail=f"scan={scan[name]}  indexed={indexed}")


def test_conjunctions(c):
    section("Conjunction operators (AND / OR / NOR / XOR / NAND)")

    def conj(op, *leaves):
        return [{"type": "FILTER", "operator": {"conjunctionType": op, "operators": list(leaves)}}]

    age25 = {"fieldOperatorType": "EQUALS", "field": "age", "value": 25}
    active = {"fieldOperatorType": "EQUALS", "field": "active", "value": True}
    rating45 = {"fieldOperatorType": "EQUALS", "field": "rating", "value": 4.5}

    check("AND (active AND rating=4.5)",
               ids_of(aggregate(c, COLL_AGG, conj("AND", active, rating45))) == ["a1", "a3"])
    check("OR (age=25 OR active)",
               ids_of(aggregate(c, COLL_AGG, conj("OR", age25, active))) == ["a1", "a2", "a3", "a4"])
    check("XOR (active XOR rating=4.5) -> exactly one true",
               ids_of(aggregate(c, COLL_AGG, conj("XOR", active, rating45))) == [])
    check("NOR (NOT age=25 AND NOT active)",
               ids_of(aggregate(c, COLL_AGG, conj("NOR", age25, active))) == [])
    check("NAND (NOT(active AND rating=4.5))",
               ids_of(aggregate(c, COLL_AGG, conj("NAND", active, rating45))) == ["a2", "a4"])

    # NOR/NAND complement against the PK universe via the index-only COUNT path.
    r = aggregate(c, COLL_AGG, conj("NAND", active, rating45) + [{"type": "COUNT"}])
    check_field("COUNT after NAND conjunction", r, "results.0.count", 2)


def test_aggregation_steps(c):
    section("Aggregation steps (MAP / GROUP_BY / JOIN / COUNT / DISTINCT / LIMIT / SKIP / SORT)")

    # A MAP operator without an "operator" field removes the named field from each doc.
    r = aggregate(c, COLL_AGG, [{"type": "MAP", "operators": [{"fieldName": "meta"}]}])
    check_status("MAP remove-field (OK)", r, "OK")
    check("MAP keeps one row per input doc", len(r.get("results") or []) == 4)
    check("MAP removed the 'meta' field but kept 'name'",
               all(("meta" not in d and "name" in d) for d in (r.get("results") or [])),
               detail=f"first row={ (r.get('results') or [{}])[0] }")

    r = aggregate(c, COLL_AGG, [{"type": "GROUP_BY", "fieldName": "age"}])
    check_status("GROUP_BY age (OK)", r, "OK")
    groups = {d.get("age"): len(d.get("group") or []) for d in (r.get("results") or [])}
    check("GROUP_BY age=25 has two members", groups.get(25) == 2, detail=f"groups={groups}")

    r = aggregate(c, COLL_JOIN_LEFT, [{"type": "JOIN", "joinCollection": COLL_JOIN_RIGHT,
                                          "localField": "key", "remoteField": "key", "asField": "joined"}])
    check_status("JOIN left->right (OK)", r, "OK")
    by_id = {d.get("_id"): d for d in (r.get("results") or [])}
    check("JOIN populates asField for l1",
               (by_id.get("l1", {}).get("joined") or [{}])[0].get("label") == "first",
               detail=f"l1.joined={by_id.get('l1', {}).get('joined')}")

    r = aggregate(c, COLL_AGG, [{"type": "COUNT"}])
    check_field("COUNT whole collection", r, "results.0.count", 4)
    r = aggregate(c, COLL_AGG, [filter_step("active", "EQUALS", True), {"type": "COUNT"}])
    check_field("COUNT with filter", r, "results.0.count", 2)

    r = aggregate(c, COLL_AGG, [{"type": "DISTINCT", "fieldName": "rating"}])
    check_status("DISTINCT on rating (OK)", r, "OK")
    distinct_ratings = sorted({d.get("rating") for d in (r.get("results") or [])})
    check("DISTINCT rating yields {2.0, 3.5, 4.5}", distinct_ratings == [2.0, 3.5, 4.5],
               detail=f"got={distinct_ratings}")
    check_status("DISTINCT whole documents (no fieldName)",
          aggregate(c, COLL_AGG, [{"type": "DISTINCT"}]), "OK")

    r = aggregate(c, COLL_AGG, [{"type": "SORT", "fieldName": "age", "ascending": True}])
    check("SORT ascending by age",
               [d.get("age") for d in (r.get("results") or [])] == [25, 25, 30, 40])
    r = aggregate(c, COLL_AGG, [{"type": "SORT", "fieldName": "age", "ascending": False}])
    check("SORT descending by age",
               [d.get("age") for d in (r.get("results") or [])] == [40, 30, 25, 25])

    r = aggregate(c, COLL_AGG, [{"type": "SORT", "fieldName": "age", "ascending": True},
                                   {"type": "LIMIT", "limit": 2}])
    check("LIMIT 2 truncates", len(r.get("results") or []) == 2)
    r = aggregate(c, COLL_AGG, [{"type": "SORT", "fieldName": "age", "ascending": True},
                                   {"type": "SKIP", "skip": 2}])
    check("SKIP 2 drops the first two", len(r.get("results") or []) == 2)

    check_code("LIMIT 0 is invalid -> 400-1",
               aggregate(c, COLL_AGG, [{"type": "LIMIT", "limit": 0}]), "ERROR", "400-1")
    check_code("SKIP negative is invalid -> 400-1",
               aggregate(c, COLL_AGG, [{"type": "SKIP", "skip": -1}]), "ERROR", "400-1")

    check_status("Empty aggregationSteps returns all docs",
          aggregate(c, COLL_AGG, []), "OK")

    # Multi-step pipeline: FILTER -> SORT -> LIMIT.
    r = aggregate(c, COLL_AGG, [filter_step("active", "EQUALS", True),
                                   {"type": "SORT", "fieldName": "age", "ascending": True},
                                   {"type": "LIMIT", "limit": 1}])
    check_field("FILTER->SORT->LIMIT yields the youngest active user", r, "results.0._id", "a1")


def test_analyze(c):
    section("Explain / Analyze (AGGREGATE analyze=true)")

    coll = "analyze_coll"
    create_coll(c, coll)
    bulk_save(c, coll, [
        {"_id": "z1", "name": "alice", "age": 30},
        {"_id": "z2", "name": "bob", "age": 25},
        {"_id": "z3", "name": "carol", "age": 40},
    ])

    # Scan path (no index yet): the diagnostic reports indexUsed=false, names the field as an
    # index candidate, and still returns the matching results alongside the analyzeResult.
    r = analyze(c, coll, [filter_step("name", "EQUALS", "alice")])
    check_status("AGGREGATE analyze=true returns OK", r, "OK")
    check("analyzeResult object is present", isinstance(r.get("analyzeResult"), dict))
    check_field("Scan path reports no index used", r, "analyzeResult.indexUsed", False)
    check("Scan path scanned at least one document",
               (bu.dig(r, "analyzeResult.documentsScanned") or 0) >= 1,
               detail=f"documentsScanned={bu.dig(r, 'analyzeResult.documentsScanned')}")
    check("Scan path suggests indexing the filtered field",
               any("name" in sug for sug in (bu.dig(r, "analyzeResult.suggestions") or [])),
               detail=f"suggestions={bu.dig(r, 'analyzeResult.suggestions')}")
    duration = bu.dig(r, "analyzeResult.durationMillis")
    check("Timing durationMillis is present (non-negative)",
               duration is not None and duration >= 0,
               detail=f"durationMillis={duration}")
    check("analyze still returns the matching results", ids_of(r) == ["z1"])

    # Index path: once the index exists the diagnostic reports indexUsed=true, names the index,
    # and records the field-index read lock.
    create_index(c, coll, "name")
    wait_for_index(c, coll, "name")
    r = analyze(c, coll, [filter_step("name", "EQUALS", "alice")])
    check_field("Index path reports an index used", r, "analyzeResult.indexUsed", True)
    check("indexesUsed names the 'name' field",
               "name" in (bu.dig(r, "analyzeResult.indexesUsed") or []),
               detail=f"indexesUsed={bu.dig(r, 'analyzeResult.indexesUsed')}")
    check("locksAcquired includes the field-index lock for 'name'",
               any(lock.endswith("|name") for lock in (bu.dig(r, "analyzeResult.locksAcquired") or [])),
               detail=f"locksAcquired={bu.dig(r, 'analyzeResult.locksAcquired')}")

    # Empty result in analyze mode returns OK (not the NO_RESULTS error) with the diagnostic.
    r = analyze(c, coll, [filter_step("name", "EQUALS", "nobody")])
    check_status("analyze on a no-match query returns OK (not NO_RESULTS)", r, "OK")
    check("analyzeResult present even with no results",
               isinstance(r.get("analyzeResult"), dict))
    check("no-match analyze returns an empty results list",
               (r.get("results") or []) == [], detail=f"results={r.get('results')}")

    # A FILTER that is not the first step yields a "move it to the top" suggestion.
    r = analyze(c, coll, [{"type": "SORT", "fieldName": "age", "ascending": True},
                             filter_step("name", "EQUALS", "alice")])
    check("suggests moving a non-leading FILTER to the top of the pipeline",
               any("move it to the top" in sug for sug in (bu.dig(r, "analyzeResult.suggestions") or [])),
               detail=f"suggestions={bu.dig(r, 'analyzeResult.suggestions')}")

    # analyze=false (the default) leaves the response untouched: no analyzeResult field.
    r = aggregate(c, coll, [filter_step("name", "EQUALS", "alice")])
    check("plain AGGREGATE carries no analyzeResult", r.get("analyzeResult") is None)

    drop_coll(c, coll)


def test_empty_collection_aggregate(c):
    section("Aggregate on an empty collection")
    create_coll(c, "empty_coll")
    check_code("AGGREGATE on empty collection -> 404-3",
               aggregate(c, "empty_coll", []), "NOT_FOUND", "404-3")
    drop_coll(c, "empty_coll")


def test_index_ops(c):
    section("Index lifecycle (CREATE_INDEX / DROP_INDEX / REINDEX)")

    create_coll(c, "idx_coll")
    bulk_save(c, "idx_coll", [{"_id": "i1", "email": "a@x.io"}, {"_id": "i2", "email": "b@x.io"}])

    check_status("CREATE_INDEX email", create_index(c, "idx_coll", "email"), "OK")
    wait_for_index(c, "idx_coll", "email")
    check("Query is correct after CREATE_INDEX",
               ids_of(aggregate(c, "idx_coll", [filter_step("email", "EQUALS", "a@x.io")])) == ["i1"])

    check_status("REINDEX a specific field", reindex(c, "idx_coll", ["email"]), "OK")
    check_status("REINDEX all fields (omit fieldNames)", reindex(c, "idx_coll"), "OK")
    check("Query is correct after REINDEX",
               ids_of(aggregate(c, "idx_coll", [filter_step("email", "EQUALS", "b@x.io")])) == ["i2"])

    check_status("DROP_INDEX email", drop_index(c, "idx_coll", "email"), "OK")
    # DROP_INDEX is idempotent: dropping a field with no index is a no-op that returns OK.
    check_status("DROP_INDEX on a field with no index is idempotent (OK)",
          drop_index(c, "idx_coll", "no_such_field"), "OK")

    drop_coll(c, "idx_coll")


def test_flows(c):
    section("Multi-operation flows (save -> update -> verify)")

    # Upsert flow: same _id overwrites, count stays 1.
    save(c, COLL_FLOWS, {"_id": "f1", "status": "new"})
    check_field("Initial value is 'new'", find_by_id(c, COLL_FLOWS, "f1"), "object.status", "new")
    save(c, COLL_FLOWS, {"_id": "f1", "status": "done"})
    check_field("Value updated to 'done'", find_by_id(c, COLL_FLOWS, "f1"), "object.status", "done")
    # Upsert overwrote f1 rather than inserting a second doc: exactly one doc has status 'done'.
    check_field("Upsert did not insert a duplicate (count==1)",
                aggregate(c, COLL_FLOWS, [filter_step("status", "EQUALS", "done"), {"type": "COUNT"}]),
                "results.0.count", 1)

    # Grow-update / relocation flow: tiny doc then a much larger one (same id).
    save(c, COLL_FLOWS, {"_id": "grow", "payload": "x"})
    big = "y" * 4096
    save(c, COLL_FLOWS, {"_id": "grow", "payload": big})
    check_field("Grow-update reads back the larger value",
                find_by_id(c, COLL_FLOWS, "grow"), "object.payload", big)

    # Index-consistency flow: write then immediately query the index-backed path.
    create_index(c, COLL_FLOWS, "status")
    wait_for_index(c, COLL_FLOWS, "status")
    save(c, COLL_FLOWS, {"_id": "ic", "status": "fresh"})
    check("Just-written value is found immediately (no false negative)",
               "ic" in ids_of(aggregate(c, COLL_FLOWS, [filter_step("status", "EQUALS", "fresh")])))
    save(c, COLL_FLOWS, {"_id": "ic", "status": "changed"})
    check("Old value no longer matches immediately after update",
               "ic" not in ids_of(aggregate(c, COLL_FLOWS, [filter_step("status", "EQUALS", "fresh")])))
    check("New value matches immediately after update",
               "ic" in ids_of(aggregate(c, COLL_FLOWS, [filter_step("status", "EQUALS", "changed")])))

    # Delete-then-recreate flow.
    save(c, COLL_FLOWS, {"_id": "dr", "v": 1})
    delete(c, COLL_FLOWS, "dr")
    check_code("Deleted doc is gone -> 404-2", find_by_id(c, COLL_FLOWS, "dr"), "NOT_FOUND", "404-2")
    save(c, COLL_FLOWS, {"_id": "dr", "v": 2})
    check_field("Re-created doc reads back", find_by_id(c, COLL_FLOWS, "dr"), "object.v", 2)

    # Bulk-update flow: insert then bulk-update all to longer values.
    bulk_save(c, COLL_FLOWS, [{"_id": f"bu_{i}", "v": "short"} for i in range(4)])
    bulk_save(c, COLL_FLOWS, [{"_id": f"bu_{i}", "v": f"updated-longer-value-{i}"} for i in range(4)])
    bad = sum(1 for i in range(4)
              if bu.dig(find_by_id(c, COLL_FLOWS, f"bu_{i}"), "object.v") != f"updated-longer-value-{i}")
    check("Bulk update reads back intact for every doc", bad == 0, detail=f"{bad}/4 stale")


def test_operation_permissions(c):
    section("Operation-level permission edges (non-overlapping with test_auth)")

    # Unauthenticated connection.
    with Conn() as uc:
        check_code("AGGREGATE before auth -> 401-1",
                   aggregate(uc, COLL_AGG, []), "UNAUTHENTICATED", "401-1")

    # Read-only user: writes and index ops are forbidden.
    with Conn() as rc:
        rc.authenticate("api_reader", "api_reader1234")
        check_code("read-only user SAVE -> 403-1",
                   save(rc, COLL_CRUD, {"_id": "z", "v": 1}), "FORBIDDEN", "403-1")
        check_code("read-only user BULK_SAVE -> 403-1",
                   bulk_save(rc, COLL_CRUD, [{"_id": "z", "v": 1}]), "FORBIDDEN", "403-1")
        check_code("read-only user CREATE_INDEX -> 403-1",
                   create_index(rc, COLL_AGG, "name"), "FORBIDDEN", "403-1")
        check_code("read-only user REINDEX -> 403-1",
                   reindex(rc, COLL_AGG), "FORBIDDEN", "403-1")
        check_status("read-only user can still AGGREGATE", aggregate(rc, COLL_AGG, []), "OK")

    # JOIN requires READ on the remote collection too.
    with Conn() as jc:
        jc.authenticate("api_join_user", "api_join_user1234")
        check_status("join user can read the left collection", aggregate(jc, COLL_JOIN_LEFT, []), "OK")
        check_code("JOIN without READ on the remote collection -> 403-1",
                   aggregate(jc, COLL_JOIN_LEFT,
                             [{"type": "JOIN", "joinCollection": COLL_JOIN_RIGHT,
                               "localField": "key", "remoteField": "key", "asField": "j"}]),
                   "FORBIDDEN", "403-1")


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    bu.banner("API commands & aggregations integration suite", HOST, PORT)

    with Conn() as c:
        r = c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            print("        Make sure the server is running and lwnrdb.cfg has the correct")
            print(f"        defaultAdminUsername={ADMIN_USERNAME!r} / defaultAdminPassword set.\n")
            sys.exit(1)
        print("\n  Setting up fixtures...")
        setup(c)

    groups = [
        test_database_and_collection_ops,
        test_reserved_script_runs_collection,
        test_crud,
        test_value_types,
        test_filter_operators,
        test_filter_with_indexes,
        test_conjunctions,
        test_aggregation_steps,
        test_analyze,
        test_empty_collection_aggregate,
        test_index_ops,
        test_flows,
        test_operation_permissions,
    ]
    for group in groups:
        with Conn() as c:
            c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
            group(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown(c)

    bu.summary()


if __name__ == "__main__":
    main()
