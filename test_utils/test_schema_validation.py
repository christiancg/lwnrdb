import os
import socket
import json
import sys

HOST = os.environ.get("API_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("API_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "schema_test_db"
COLL = "people"

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


def check_code(label: str, response: dict, expected_status: str, expected_code: str):
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

def authenticate(s, f, username=ADMIN_USERNAME, password=ADMIN_PASSWORD) -> dict:
    return send(s, f, {"type": "AUTHENTICATE", "username": username, "password": password})


def create_user(s, f, username, password, admin=False, global_perms=None, db_perms=None, coll_perms=None):
    return send(s, f, {
        "type": "CREATE_USER", "username": username, "password": password, "admin": admin,
        "globalPermissions": global_perms or [], "databasePermissions": db_perms or {},
        "collectionPermissions": coll_perms or {},
    })


def delete_user(s, f, username) -> dict:
    return send(s, f, {"type": "DELETE_USER", "username": username})


def set_database_owners(s, f, database_name, owners) -> dict:
    return send(s, f, {"type": "SET_DATABASE_OWNERS", "databaseName": database_name, "owners": owners})


def create_db(s, f, name=DB) -> dict:
    return send(s, f, {"type": "CREATE_DATABASE", "databaseName": name})


def drop_db(s, f, name=DB) -> dict:
    return send(s, f, {"type": "DROP_DATABASE", "databaseName": name})


def create_coll(s, f, coll, db=DB) -> dict:
    return send(s, f, {"type": "CREATE_COLLECTION", "databaseName": db, "collectionName": coll})


def save(s, f, coll, obj, db=DB) -> dict:
    return send(s, f, {"type": "SAVE", "databaseName": db, "collectionName": coll, "object": obj})


def bulk_save(s, f, coll, objs, db=DB) -> dict:
    return send(s, f, {"type": "BULK_SAVE", "databaseName": db, "collectionName": coll, "objects": objs})


def find_by_id(s, f, coll, _id, db=DB) -> dict:
    return send(s, f, {"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": _id})


def save_schema(s, f, coll, schema, db=DB) -> dict:
    return send(s, f, {"type": "SAVE_SCHEMA", "databaseName": db, "collectionName": coll, "schema": schema})


def delete_schema(s, f, coll, db=DB) -> dict:
    return send(s, f, {"type": "DELETE_SCHEMA", "databaseName": db, "collectionName": coll})


PERSON_SCHEMA = {
    "type": "object",
    "required": ["name", "age"],
    "properties": {
        "name": {"type": "string", "minLength": 1},
        "age": {"type": "integer", "minimum": 0},
        "email": {"type": "string", "format": "email"},
    },
    "additionalProperties": False,
}


# ── setup / teardown ──────────────────────────────────────────────────────────

def teardown(s, f):
    drop_db(s, f, DB)


def setup(s, f):
    teardown(s, f)
    check("setup: create database", create_db(s, f, DB), "OK")
    check("setup: create collection", create_coll(s, f, COLL), "OK")


# ── tests ─────────────────────────────────────────────────────────────────────

def test_save_and_enforce_schema(s, f):
    section("SAVE_SCHEMA and enforcement")
    check("save a valid schema", save_schema(s, f, COLL, PERSON_SCHEMA), "OK")

    check("compliant document is accepted",
          save(s, f, COLL, {"_id": "alice", "name": "Alice", "age": 30}), "OK")

    check_code("missing required field is rejected",
               save(s, f, COLL, {"_id": "bad1", "name": "NoAge"}), "ERROR", "400-7")
    check_code("wrong type is rejected",
               save(s, f, COLL, {"_id": "bad2", "name": "X", "age": "old"}), "ERROR", "400-7")
    check_code("additional property is rejected",
               save(s, f, COLL, {"_id": "bad3", "name": "X", "age": 1, "extra": True}),
               "ERROR", "400-7")

    check_true("rejected documents were not persisted",
               find_by_id(s, f, COLL, "bad1").get("status") == "NOT_FOUND")


def test_bulk_save_atomic(s, f):
    section("BULK_SAVE atomic rejection")
    save_schema(s, f, COLL, PERSON_SCHEMA)
    resp = bulk_save(s, f, COLL, [
        {"_id": "bob", "name": "Bob", "age": 40},
        {"_id": "bulkbad", "name": "NoAge"},
    ])
    check_code("bulk save with one bad doc is rejected", resp, "ERROR", "400-7")
    check_true("offending id is named in the message", "bulkbad" in resp.get("message", ""))
    check_true("no document from the rejected batch was persisted",
               find_by_id(s, f, COLL, "bob").get("status") == "NOT_FOUND")


def test_invalid_schema_rejected(s, f):
    section("Invalid schema rejected")
    check_code("schema with a bad keyword value is rejected",
               save_schema(s, f, COLL, {"type": "object", "required": "name"}), "ERROR", "400-8")
    check_code("schema using an unknown type is rejected",
               save_schema(s, f, COLL, {"type": "objct"}), "ERROR", "400-8")


def test_schema_warnings(s, f):
    section("SAVE_SCHEMA warnings")
    resp = save_schema(s, f, COLL, {"type": "object", "properties": {"name": {"type": "string"}}, "foo": 1})
    check("schema with an unrecognized keyword still saves", resp, "OK")
    warnings = resp.get("warnings", [])
    check_true("a warning is returned for the unrecognized keyword",
               any("foo" in w for w in warnings), detail=str(warnings))


def test_custom_type_enforcement(s, f):
    section("Custom type (geo) enforcement")
    check("save a geo schema", save_schema(s, f, COLL, {
        "type": "object",
        "required": ["loc"],
        "properties": {"loc": {"customType": "geo"}},
    }), "OK")
    check("a geo value is accepted",
          save(s, f, COLL, {"_id": "g1", "loc": "#geo(40.7,-74.0)"}), "OK")
    check_code("a plain string is rejected where a geo is required",
               save(s, f, COLL, {"_id": "g2", "loc": "downtown"}), "ERROR", "400-7")


def test_delete_schema(s, f):
    section("DELETE_SCHEMA")
    save_schema(s, f, COLL, PERSON_SCHEMA)
    check_code("document is rejected while the schema is in force",
               save(s, f, COLL, {"_id": "d1", "name": "X"}), "ERROR", "400-7")
    check("delete the schema", delete_schema(s, f, COLL), "OK")
    check("previously-rejected document now saves",
          save(s, f, COLL, {"_id": "d1", "name": "X"}), "OK")
    check("deleting a schema again is idempotent", delete_schema(s, f, COLL), "OK")


def test_permissions(s, f):
    section("Permissions: admin/owner only")
    # owner user can manage the schema; a read-only user cannot.
    create_user(s, f, "schema_owner", "schema_owner1234")
    create_user(s, f, "schema_reader", "schema_reader1234", db_perms={DB: "READ"})
    set_database_owners(s, f, DB, ["schema_owner"])

    with new_conn() as (os_, of_):
        authenticate(os_, of_, "schema_owner", "schema_owner1234")
        check("database owner can save a schema",
              save_schema(os_, of_, COLL, {"type": "object"}), "OK")
        check("database owner can delete a schema", delete_schema(os_, of_, COLL), "OK")

    with new_conn() as (rs, rf):
        authenticate(rs, rf, "schema_reader", "schema_reader1234")
        check("read-only user cannot save a schema",
              save_schema(rs, rf, COLL, {"type": "object"}), "FORBIDDEN")
        check("read-only user cannot delete a schema",
              delete_schema(rs, rf, COLL), "FORBIDDEN")

    # restore ownership so teardown (admin) can drop the db
    set_database_owners(s, f, DB, [])
    delete_user(s, f, "schema_owner")
    delete_user(s, f, "schema_reader")


def main():
    print("LWNRDB schema-validation test suite")
    with new_conn() as (s, f):
        if authenticate(s, f).get("status") != "OK":
            print("Could not authenticate as admin. Check lwnrdb.cfg defaultAdminUsername/defaultAdminPassword.")
            sys.exit(1)
        setup(s, f)

    groups = [
        test_save_and_enforce_schema,
        test_bulk_save_atomic,
        test_invalid_schema_rejected,
        test_schema_warnings,
        test_custom_type_enforcement,
        test_delete_schema,
        test_permissions,
    ]
    for group in groups:
        with new_conn() as (s, f):
            authenticate(s, f)
            # reset the collection schema before each group so groups are independent
            delete_schema(s, f, COLL)
            group(s, f)

    with new_conn() as (s, f):
        authenticate(s, f)
        teardown(s, f)

    print(f"\n{'═' * 60}")
    if failures == 0:
        print("  All checks passed.")
    else:
        print(f"  {failures} check(s) FAILED.")
    print(f"{'═' * 60}")
    sys.exit(0 if failures == 0 else 1)


if __name__ == "__main__":
    main()
