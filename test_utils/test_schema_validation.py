import os
import re
import sys
import time
from typing import Callable

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

HOST = os.environ.get("API_TEST_HOST", "127.0.0.1")
PORT = int(os.environ.get("API_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

DB = "schema_test_db"
COLL = "people"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)


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


def teardown(c):
    drop_db(c, DB)


def setup(c):
    teardown(c)
    check_status("setup: create database", create_db(c, DB), "OK")
    check_status("setup: create collection", create_coll(c, COLL), "OK")


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


def test_transactional_writes_answer_like_plain_writes(c):
    section("Transactional writes are validated like plain ones")
    save_schema(c, COLL, PERSON_SCHEMA)
    plain = save(c, COLL, {"_id": "plainbad", "name": "NoAge"})
    check_code("a plain non-compliant save is rejected", plain, "ERROR", "400-7")

    check_status("start a transaction", c.send({"type": "START_TRANSACTION"}), "OK")
    in_tx = save(c, COLL, {"_id": "txbad", "name": "NoAge"})
    check_code("the same document inside a transaction is rejected with the same code", in_tx, "ERROR", "400-7")
    check("and the same message, apart from the document",
          in_tx.get("message", "").split(":")[0] == plain.get("message", "").split(":")[0],
          f"plain={plain.get('message')!r} tx={in_tx.get('message')!r}")
    bulk = bulk_save(c, COLL, [{"_id": "txgood", "name": "Ok", "age": 1}, {"_id": "txbulkbad", "name": "NoAge"}])
    check_code("a transactional bulk save with one bad document is rejected", bulk, "ERROR", "400-7")
    check_status("commit what is left", c.send({"type": "COMMIT_TRANSACTION"}), "OK")

    check("nothing the transaction refused was persisted",
          all(find_by_id(c, COLL, _id).get("status") == "NOT_FOUND" for _id in ("txbad", "txgood", "txbulkbad")))


def test_multiple_of_rejects_a_tiny_non_multiple(c):
    section("multipleOf rejects a nonzero value smaller than its divisor")
    check_status("save a multipleOf schema",
                 save_schema(c, COLL, {"type": "object", "properties": {
                     "big": {"multipleOf": 1000000}, "cents": {"multipleOf": 0.01}}}), "OK")
    check_code("0.001 is not a multiple of 1000000", save(c, COLL, {"_id": "tiny", "big": 0.001}), "ERROR", "400-7")
    check("the refused document was not persisted", find_by_id(c, COLL, "tiny").get("status") == "NOT_FOUND")
    check_status("2000000 is a multiple of 1000000", save(c, COLL, {"_id": "whole", "big": 2000000}), "OK")
    check_status("0 is a multiple of anything", save(c, COLL, {"_id": "zero", "big": 0}), "OK")
    check_status("19.99 is a multiple of 0.01", save(c, COLL, {"_id": "price", "cents": 19.99}), "OK")
    check_code("19.995 is not a multiple of 0.01", save(c, COLL, {"_id": "halfcent", "cents": 19.995}),
               "ERROR", "400-7")


def test_multiple_of_is_exact_for_huge_values(c):
    section("multipleOf stays exact when the quotient overflows or passes the double integer range")
    check_status("save a multipleOf schema",
                 save_schema(c, COLL, {"type": "object", "properties": {
                     "odd": {"multipleOf": 0.123456789}, "three": {"multipleOf": 3}}}), "OK")
    check_code("1e308 is not a multiple of 0.123456789", save(c, COLL, {"_id": "huge_odd", "odd": 1e308}),
               "ERROR", "400-7")
    check_code("1e20 is not a multiple of 3", save(c, COLL, {"_id": "huge_three", "three": 10 ** 20}),
               "ERROR", "400-7")
    check_status("3e20 is a multiple of 3", save(c, COLL, {"_id": "huge_three_ok", "three": 3 * 10 ** 20}), "OK")


def test_pattern_uses_ecma_semantics(c):
    section("pattern follows ECMA-262, not Java regex")
    check_status("save a pattern schema",
                 save_schema(c, COLL, {"type": "object", "properties": {"slug": {"pattern": "^[a-z]+$"}}}), "OK")
    check_status("a matching value is accepted", save(c, COLL, {"_id": "slug-ok", "slug": "abc"}), "OK")
    check_code("'$' does not match before a trailing newline",
               save(c, COLL, {"_id": "slug-nl", "slug": "abc\n"}), "ERROR", "400-7")
    check("the refused document was not persisted", find_by_id(c, COLL, "slug-nl").get("status") == "NOT_FOUND")
    check_status("save a non-whitespace pattern schema",
                 save_schema(c, COLL, {"type": "object", "properties": {"token": {"pattern": "^\\S+$"}}}), "OK")
    check_code("a no-break space is whitespace in ECMA-262",
               save(c, COLL, {"_id": "nbsp", "token": "a\u00a0b"}), "ERROR", "400-7")
    check_code("a Java-only possessive quantifier is refused at save",
               save_schema(c, COLL, {"type": "object", "properties": {"p": {"pattern": "a++"}}}), "ERROR", "400-8")
    check_code("a Java-only inline flag is refused in patternProperties",
               save_schema(c, COLL, {"type": "object", "patternProperties": {"(?i)^a": {}}}), "ERROR", "400-8")


HOSTILE = "x" * 800 + "!"
REDOS_SCHEMA = {"type": "object", "properties": {"name": {"type": "string", "pattern": "(x+x+)+y"}}}
BUDGET_DEADLINE_S = 15


def diamond_chain(depth):
    defs = {f"d{i}": {"allOf": [{"$ref": f"#/$defs/d{i + 1}"}, {"$ref": f"#/$defs/d{i + 1}"}]} for i in range(depth)}
    defs[f"d{depth}"] = {"type": "object"}
    return {"$ref": "#/$defs/d0", "$defs": defs}


def timed(call: Callable[[], dict]):
    started = time.monotonic()
    response = call()
    return response, time.monotonic() - started


def test_pattern_redos_is_refused_within_a_deadline(c):
    section("A catastrophic pattern is refused by the validation budget")
    check_status("save a schema with a catastrophic pattern", save_schema(c, COLL, REDOS_SCHEMA), "OK")
    resp, elapsed = timed(lambda: save(c, COLL, {"_id": "redos", "name": HOSTILE}))
    check_code("the hostile document is refused with 400-23", resp, "ERROR", "400-23")
    check("and refused inside the deadline", elapsed < BUDGET_DEADLINE_S, f"elapsed={elapsed:.1f}s")
    check("the refused document was not persisted", find_by_id(c, COLL, "redos").get("status") == "NOT_FOUND")
    check_status("a cheap matching document still saves", save(c, COLL, {"_id": "cheap", "name": "xxy"}), "OK")


def test_pattern_properties_redos_is_refused(c):
    section("A catastrophic patternProperties key is refused by the validation budget")
    check_status("save a patternProperties schema",
                 save_schema(c, COLL, {"type": "object", "patternProperties": {"(x+x+)+y": {"type": "number"}}}), "OK")
    resp, elapsed = timed(lambda: save(c, COLL, {"_id": "redos-key", HOSTILE: 1}))
    check_code("the hostile key is refused with 400-23", resp, "ERROR", "400-23")
    check("and refused inside the deadline", elapsed < BUDGET_DEADLINE_S, f"elapsed={elapsed:.1f}s")


def test_bulk_save_with_one_redos_document_is_atomic(c):
    section("BULK_SAVE with one document over the budget writes nothing")
    save_schema(c, COLL, REDOS_SCHEMA)
    resp = bulk_save(c, COLL, [{"_id": "bulk-cheap", "name": "xxy"}, {"_id": "bulk-redos", "name": HOSTILE}])
    check_code("the batch is refused with 400-23", resp, "ERROR", "400-23")
    check("no document from the batch was persisted",
          all(find_by_id(c, COLL, _id).get("status") == "NOT_FOUND" for _id in ("bulk-cheap", "bulk-redos")))


def test_transactional_save_answers_400_23(c):
    section("A transactional save over the budget answers 400-23 and the transaction stays usable")
    save_schema(c, COLL, REDOS_SCHEMA)
    check_status("start a transaction", c.send({"type": "START_TRANSACTION"}), "OK")
    check_code("the hostile save is refused", save(c, COLL, {"_id": "tx-redos", "name": HOSTILE}), "ERROR", "400-23")
    check_status("a cheap save in the same transaction is buffered",
                 save(c, COLL, {"_id": "tx-cheap", "name": "xxy"}), "OK")
    check_status("commit", c.send({"type": "COMMIT_TRANSACTION"}), "OK")
    check("the cheap document committed", find_by_id(c, COLL, "tx-cheap").get("status") == "OK")
    check("the hostile one did not", find_by_id(c, COLL, "tx-redos").get("status") == "NOT_FOUND")


def test_large_unique_items_is_fast_or_budgeted(c):
    section("uniqueItems over a large array answers quickly")
    check_status("save a uniqueItems schema", save_schema(c, COLL, {
        "type": "object", "properties": {"tags": {"type": "array", "uniqueItems": True}}}), "OK")
    distinct = list(range(20000))
    resp, elapsed = timed(lambda: save(c, COLL, {"_id": "unique-big", "tags": distinct}))
    check("20000 distinct items are accepted or refused by the budget",
          resp.get("status") == "OK" or resp.get("errorCode") == "400-23", f"got {resp}")
    check("inside the deadline", elapsed < BUDGET_DEADLINE_S, f"elapsed={elapsed:.1f}s")
    resp, elapsed = timed(lambda: save(c, COLL, {"_id": "unique-dup", "tags": distinct[:-1] + [0]}))
    check("a duplicate among 20000 items is refused",
          resp.get("errorCode") in ("400-7", "400-23"), f"got {resp.get('errorCode')}")
    check("inside the deadline", elapsed < BUDGET_DEADLINE_S, f"elapsed={elapsed:.1f}s")
    check("the duplicated document was not persisted",
          find_by_id(c, COLL, "unique-dup").get("status") == "NOT_FOUND")


def test_the_reported_error_list_is_capped(c):
    section("A document failing many times reports at most 100 errors")
    check_status("save an items schema", save_schema(c, COLL, {
        "type": "object", "properties": {"tags": {"type": "array", "items": {"type": "number"}}}}), "OK")
    resp = save(c, COLL, {"_id": "many-errors", "tags": ["s"] * 500})
    check_code("the document is refused", resp, "ERROR", "400-7")
    check("the message names how many errors were left out", re.search(r"\.\.\. and \d+ more", resp.get("message") or "") is not None,
          f"message tail={(resp.get('message') or '')[-80:]!r}")


def test_complex_ref_chain_save_schema_is_refused(c):
    section("SAVE_SCHEMA refuses a $ref diamond that expands past the node limit")
    resp, elapsed = timed(lambda: save_schema(c, COLL, diamond_chain(28)))
    check_code("a 28-level allOf diamond is refused with 400-24", resp, "ERROR", "400-24")
    check("and refused inside the deadline", elapsed < BUDGET_DEADLINE_S, f"elapsed={elapsed:.1f}s")
    check_status("nothing was stored: an unconstrained save passes", save(c, COLL, {"_id": "free", "any": 1}), "OK")


def test_diamond_chain_under_limit_is_accepted_and_bounded(c):
    section("A $ref diamond under the node limit is accepted and validates in bounded time")
    check_status("a 12-level diamond is accepted", save_schema(c, COLL, diamond_chain(12)), "OK")
    resp, elapsed = timed(lambda: save(c, COLL, {"_id": "diamond", "name": "d"}))
    check("a save against it answers OK or 400-23", resp.get("status") == "OK" or resp.get("errorCode") == "400-23",
          f"got {resp.get('status')} {resp.get('errorCode')}")
    check("and answers inside the deadline", elapsed < BUDGET_DEADLINE_S, f"elapsed={elapsed:.1f}s")


def test_an_oversized_pattern_is_refused_at_save(c):
    section("SAVE_SCHEMA refuses a regular expression longer than schemaMaxPatternLength")
    check_code("a 4097-character pattern is refused with 400-31",
               save_schema(c, COLL, {"type": "object", "properties": {"p": {"pattern": "a" * 4097}}}), "ERROR", "400-31")
    check_code("so is a 4097-character patternProperties key nested in $defs",
               save_schema(c, COLL, {"$defs": {"d": {"patternProperties": {"b" * 4097: {}}}}}), "ERROR", "400-31")
    check_status("nothing was stored: an unconstrained save passes", save(c, COLL, {"_id": "free-pattern", "p": 1}), "OK")
    check_status("a 4096-character pattern is accepted",
                 save_schema(c, COLL, {"type": "object", "properties": {"p": {"pattern": "a" * 4096}}}), "OK")


def test_bulk_save_shares_one_budget(c):
    section("A BULK_SAVE spends one validation budget across all its documents")
    check_status("save a schema whose pattern costs millions of steps to reject",
                 save_schema(c, COLL, {"type": "object", "properties": {"name": {"not": {"pattern": "(x+x+)+y"}}}}),
                 "OK")
    costly = "x" * 90 + "!"
    check_status("one costly document fits the budget on its own", save(c, COLL, {"_id": "costly-1", "name": costly}),
                 "OK")
    resp, elapsed = timed(lambda: bulk_save(c, COLL, [{"_id": "costly-2", "name": costly},
                                                      {"_id": "costly-3", "name": costly}]))
    check_code("two of them in one BULK_SAVE exhaust it -> 400-23", resp, "ERROR", "400-23")
    check("and are refused inside the deadline", elapsed < BUDGET_DEADLINE_S, f"elapsed={elapsed:.1f}s")
    check("nothing of the batch was written",
          all(find_by_id(c, COLL, _id).get("status") == "NOT_FOUND" for _id in ("costly-2", "costly-3")))


def test_invalid_schema_rejected(c):
    section("Invalid schema rejected")
    check_code("schema with a bad keyword value is rejected",
               save_schema(c, COLL, {"type": "object", "required": "name"}), "ERROR", "400-8")
    check_code("schema using an unknown type is rejected",
               save_schema(c, COLL, {"type": "objct"}), "ERROR", "400-8")


def test_cyclic_ref_rejected(c):
    section("Cyclic $ref rejected at save")
    check_code("a schema whose root refers to itself is rejected",
               save_schema(c, COLL, {"$ref": "#"}), "ERROR", "400-8")
    check_code("a cycle through $defs is rejected",
               save_schema(c, COLL, {"$ref": "#/$defs/a",
                                     "$defs": {"a": {"$ref": "#/$defs/b"}, "b": {"$ref": "#/$defs/a"}}}),
               "ERROR", "400-8")
    check_code("a cycle through an applicator is rejected",
               save_schema(c, COLL, {"allOf": [{"$ref": "#"}]}), "ERROR", "400-8")
    check_code("an unresolvable pointer is rejected",
               save_schema(c, COLL, {"$ref": "#/$defs/missing"}), "ERROR", "400-8")
    check_code("a pointer that resolves to a non-schema node is rejected",
               save_schema(c, COLL, {"required": ["a"], "properties": {"x": {"$ref": "#/required"}}}),
               "ERROR", "400-8")
    check_status("a pointer to a boolean schema is accepted",
                 save_schema(c, COLL, {"$defs": {"any": True}, "properties": {"x": {"$ref": "#/$defs/any"}}}), "OK")
    check_status("recursion bounded by instance depth is still accepted", save_schema(c, COLL, {
        "type": "object",
        "properties": {"name": {"type": "string"}, "child": {"$ref": "#"}},
    }), "OK")
    check_status("a nested document validates against the recursive schema",
                 save(c, COLL, {"_id": "rec1", "name": "a", "child": {"name": "b"}}), "OK")
    check_code("a nested document that breaks the recursive schema is refused",
               save(c, COLL, {"_id": "rec2", "name": "a", "child": {"name": 3}}), "ERROR", "400-7")
    check_status("the connection survives a schema-heavy exchange",
                 find_by_id(c, COLL, "rec1"), "OK")


def test_ref_targets_are_checked(c):
    section("$ref targets are validated as schemas wherever they live")
    check_code("a malformed enum behind a $ref into definitions is rejected",
               save_schema(c, COLL, {"definitions": {"role": {"enum": "admin"}},
                                     "properties": {"role": {"$ref": "#/definitions/role"}}}),
               "ERROR", "400-8")
    resp = save_schema(c, COLL, {"definitions": {"role": {"enum": ["admin"]}},
                                 "properties": {"role": {"$ref": "#/definitions/role"}}})
    check_status("a well-formed target behind definitions is accepted", resp, "OK")
    check("definitions itself is still only a warning",
          any("definitions" in w for w in resp.get("warnings", [])), detail=str(resp.get("warnings")))
    check_code("the target's enum is enforced",
               save(c, COLL, {"_id": "ref1", "role": "guest"}), "ERROR", "400-7")
    check_status("a value the target's enum allows saves",
                 save(c, COLL, {"_id": "ref2", "role": "admin"}), "OK")


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

    set_database_owners(c, DB, [])
    delete_user(c, "schema_owner")
    delete_user(c, "schema_reader")


def main():
    bu.banner("Schema validation test suite", HOST, PORT)

    with Conn() as c:
        if c.authenticate().get("status") != "OK":
            print(f"Could not authenticate as admin. Check lwnrdb.cfg defaultAdminUsername/defaultAdminPassword={bu.PASSWORD}.")
            sys.exit(1)
        setup(c)

    groups = [
        test_save_and_enforce_schema,
        test_bulk_save_atomic,
        test_transactional_writes_answer_like_plain_writes,
        test_multiple_of_rejects_a_tiny_non_multiple,
        test_multiple_of_is_exact_for_huge_values,
        test_pattern_uses_ecma_semantics,
        test_pattern_redos_is_refused_within_a_deadline,
        test_pattern_properties_redos_is_refused,
        test_bulk_save_with_one_redos_document_is_atomic,
        test_transactional_save_answers_400_23,
        test_large_unique_items_is_fast_or_budgeted,
        test_the_reported_error_list_is_capped,
        test_complex_ref_chain_save_schema_is_refused,
        test_diamond_chain_under_limit_is_accepted_and_bounded,
        test_an_oversized_pattern_is_refused_at_save,
        test_bulk_save_shares_one_budget,
        test_invalid_schema_rejected,
        test_cyclic_ref_rejected,
        test_ref_targets_are_checked,
        test_schema_warnings,
        test_custom_type_enforcement,
        test_delete_schema,
        test_permissions,
    ]
    for group in groups:
        with Conn() as c:
            c.authenticate()
            delete_schema(c, COLL)
            group(c)

    with Conn() as c:
        c.authenticate()
        teardown(c)

    bu.summary()


if __name__ == "__main__":
    main()
