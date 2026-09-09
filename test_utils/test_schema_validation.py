import os
import sys

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

HOST = os.environ.get("API_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("API_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "schema_test_db"
COLL = "people"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)



# ── operation wrappers ───────────────────────────────────────────────────────

def create_user(c, username, password, admin=False, global_perms=None, db_perms=None, coll_perms=None):
    return c.send({
        "type": "CREATE_USER", "username": username, "password": password, "admin": admin,
        "globalPermissions": global_perms or [], "databasePermissions": db_perms or {},
        "collectionPermissions": coll_perms or {},
    })


def delete_user(c, username) -> dict:
    return c.send({"type": "DELETE_USER", "username": username})


def set_database_owners(c, database_name, owners) -> dict:
    return c.send({"type": "SET_DATABASE_OWNERS", "databaseName": database_name, "owners": owners})


def create_db(c, name=DB) -> dict:
    return c.send({"type": "CREATE_DATABASE", "databaseName": name})


def drop_db(c, name=DB) -> dict:
    return c.send({"type": "DROP_DATABASE", "databaseName": name})


def create_coll(c, coll, db=DB) -> dict:
    return c.send({"type": "CREATE_COLLECTION", "databaseName": db, "collectionName": coll})


def save(c, coll, obj, db=DB) -> dict:
    return c.send({"type": "SAVE", "databaseName": db, "collectionName": coll, "object": obj})


def bulk_save(c, coll, objs, db=DB) -> dict:
    return c.send({"type": "BULK_SAVE", "databaseName": db, "collectionName": coll, "objects": objs})


def find_by_id(c, coll, _id, db=DB) -> dict:
    return c.send({"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": _id})


def save_schema(c, coll, schema, db=DB) -> dict:
    return c.send({"type": "SAVE_SCHEMA", "databaseName": db, "collectionName": coll, "schema": schema})


def delete_schema(c, coll, db=DB) -> dict:
    return c.send({"type": "DELETE_SCHEMA", "databaseName": db, "collectionName": coll})


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

def teardown(c):
    drop_db(c, DB)


def setup(c):
    teardown(c)
    check_status("setup: create database", create_db(c, DB), "OK")
    check_status("setup: create collection", create_coll(c, COLL), "OK")


# ── tests ─────────────────────────────────────────────────────────────────────

def test_save_and_enforce_schema(c):
    section("SAVE_SCHEMA and enforcement")
    check_status("save a valid schema", save_schema(c, COLL, PERSON_SCHEMA), "OK")

    check_status("compliant document is accepted",
          save(c, COLL, {"_id": "alice", "name": "Alice", "age": 30}), "OK")

    check_code("missing required field is rejected",
               save(c, COLL, {"_id": "bad1", "name": "NoAge"}), "ERROR", "400-7")
    check_code("wrong type is rejected",
               save(c, COLL, {"_id": "bad2", "name": "X", "age": "old"}), "ERROR", "400-7")
    check_code("additional property is rejected",
               save(c, COLL, {"_id": "bad3", "name": "X", "age": 1, "extra": True}),
               "ERROR", "400-7")

    check("rejected documents were not persisted",
               find_by_id(c, COLL, "bad1").get("status") == "NOT_FOUND")


def test_bulk_save_atomic(c):
    section("BULK_SAVE atomic rejection")
    save_schema(c, COLL, PERSON_SCHEMA)
    resp = bulk_save(c, COLL, [
        {"_id": "bob", "name": "Bob", "age": 40},
        {"_id": "bulkbad", "name": "NoAge"},
    ])
    check_code("bulk save with one bad doc is rejected", resp, "ERROR", "400-7")
    check("offending id is named in the message", "bulkbad" in resp.get("message", ""))
    check("no document from the rejected batch was persisted",
               find_by_id(c, COLL, "bob").get("status") == "NOT_FOUND")


def test_invalid_schema_rejected(c):
    section("Invalid schema rejected")
    check_code("schema with a bad keyword value is rejected",
               save_schema(c, COLL, {"type": "object", "required": "name"}), "ERROR", "400-8")
    check_code("schema using an unknown type is rejected",
               save_schema(c, COLL, {"type": "objct"}), "ERROR", "400-8")


def test_schema_warnings(c):
    section("SAVE_SCHEMA warnings")
    resp = save_schema(c, COLL, {"type": "object", "properties": {"name": {"type": "string"}}, "foo": 1})
    check_status("schema with an unrecognized keyword still saves", resp, "OK")
    warnings = resp.get("warnings", [])
    check("a warning is returned for the unrecognized keyword",
               any("foo" in w for w in warnings), detail=str(warnings))


def test_custom_type_enforcement(c):
    section("Custom type (geo) enforcement")
    check_status("save a geo schema", save_schema(c, COLL, {
        "type": "object",
        "required": ["loc"],
        "properties": {"loc": {"customType": "geo"}},
    }), "OK")
    check_status("a geo value is accepted",
          save(c, COLL, {"_id": "g1", "loc": "#geo(40.7,-74.0)"}), "OK")
    check_code("a plain string is rejected where a geo is required",
               save(c, COLL, {"_id": "g2", "loc": "downtown"}), "ERROR", "400-7")


def test_delete_schema(c):
    section("DELETE_SCHEMA")
    save_schema(c, COLL, PERSON_SCHEMA)
    check_code("document is rejected while the schema is in force",
               save(c, COLL, {"_id": "d1", "name": "X"}), "ERROR", "400-7")
    check_status("delete the schema", delete_schema(c, COLL), "OK")
    check_status("previously-rejected document now saves",
          save(c, COLL, {"_id": "d1", "name": "X"}), "OK")
    check_status("deleting a schema again is idempotent", delete_schema(c, COLL), "OK")


def test_permissions(c):
    section("Permissions: admin/owner only")
    # owner user can manage the schema; a read-only user cannot.
    create_user(c, "schema_owner", "schema_owner1234")
    create_user(c, "schema_reader", "schema_reader1234", db_perms={DB: "READ"})
    set_database_owners(c, DB, ["schema_owner"])

    with Conn() as oc:
        oc.authenticate("schema_owner", "schema_owner1234")
        check_status("database owner can save a schema",
              save_schema(oc, COLL, {"type": "object"}), "OK")
        check_status("database owner can delete a schema", delete_schema(oc, COLL), "OK")

    with Conn() as rc:
        rc.authenticate("schema_reader", "schema_reader1234")
        check_status("read-only user cannot save a schema",
              save_schema(rc, COLL, {"type": "object"}), "FORBIDDEN")
        check_status("read-only user cannot delete a schema",
              delete_schema(rc, COLL), "FORBIDDEN")

    # restore ownership so teardown (admin) can drop the db
    set_database_owners(c, DB, [])
    delete_user(c, "schema_owner")
    delete_user(c, "schema_reader")


def main():
    bu.banner("Schema validation test suite", HOST, PORT)

    with Conn() as c:
        if c.authenticate().get("status") != "OK":
            print("Could not authenticate as admin. Check lwnrdb.cfg defaultAdminUsername/defaultAdminPassword.")
            sys.exit(1)
        setup(c)

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
        with Conn() as c:
            c.authenticate()
            # reset the collection schema before each group so groups are independent
            delete_schema(c, COLL)
            group(c)

    with Conn() as c:
        c.authenticate()
        teardown(c)

    bu.summary()


if __name__ == "__main__":
    main()
