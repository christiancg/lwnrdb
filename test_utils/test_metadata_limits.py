import os
import tempfile

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("METALIMIT_TEST_PORT", "9445"))
MAX_COLLECTIONS = 3
MAX_DATABASES = 6
MAX_INDEXES = 2
FUNC_DB = "metalimit_func"
CAP_DB = "metalimit_caps"

bu.configure(host=HOST, port=PORT)


def write_config(work_dir: str):
    bu.write_config(work_dir, PORT, maxCollectionsPerDatabase=MAX_COLLECTIONS, maxDatabases=MAX_DATABASES,
                    maxIndexesPerCollection=MAX_INDEXES)


def create_database(conn: Conn, name: str) -> dict:
    return conn.send({"type": "CREATE_DATABASE", "databaseName": name})


def create_collection(conn: Conn, db: str, coll: str) -> dict:
    return conn.send({"type": "CREATE_COLLECTION", "databaseName": db, "collectionName": coll})


def create_index(conn: Conn, db: str, coll: str, field: str) -> dict:
    return conn.send({"type": "CREATE_INDEX", "databaseName": db, "collectionName": coll, "fieldName": field})


def save(conn: Conn, db: str, coll: str, obj: dict) -> dict:
    return conn.send({"type": "SAVE", "databaseName": db, "collectionName": coll, "object": obj})


def aggregate(conn: Conn, db: str, coll: str, steps: list) -> dict:
    return conn.send({"type": "AGGREGATE", "databaseName": db, "collectionName": coll, "aggregationSteps": steps})


def test_contains_is_linear(conn: Conn):
    section("CONTAINS stays linear on adversarial input")
    check_status("search collection", create_collection(conn, FUNC_DB, "search"), "OK")
    check_status("doc saved", save(conn, FUNC_DB, "search", {"_id": "d1", "s": "a" * 200_000}), "OK")
    adversarial = "a" * 100_000 + "b"
    miss = aggregate(conn, FUNC_DB, "search",
                     [{"type": "FILTER",
                       "operator": {"fieldOperatorType": "CONTAINS", "field": "s", "value": adversarial}}])
    check("adversarial CONTAINS returns no rows", len(miss.get("results", [])) == 0, f"resp={miss}")
    hit = aggregate(conn, FUNC_DB, "search",
                    [{"type": "FILTER", "operator": {"fieldOperatorType": "CONTAINS", "field": "s", "value": "aaaa"}}])
    check("ordinary CONTAINS matches", len(hit.get("results", [])) == 1, f"resp={hit}")


def test_in_with_duplicate_and_case_variant_operands(conn: Conn):
    section("Indexed IN collapses duplicate and case-variant operands")
    check_status("members collection", create_collection(conn, FUNC_DB, "members"), "OK")
    for i in range(5):
        check_status(f"member {i}", save(conn, FUNC_DB, "members", {"_id": f"m{i}", "status": "active"}), "OK")
    check_status("idle member", save(conn, FUNC_DB, "members", {"_id": "m9", "status": "idle"}), "OK")
    check_status("status indexed", create_index(conn, FUNC_DB, "members", "status"), "OK")
    operands = ["active"] * 5000 + ["ACTIVE", "Active"]
    result = aggregate(conn, FUNC_DB, "members",
                       [{"type": "FILTER",
                         "operator": {"fieldOperatorType": "IN", "field": "status", "value": operands}},
                        {"type": "COUNT"}])
    check("duplicate IN operands counted once", bu.count_of(result) == 5, f"resp={result}")


def test_index_count_is_capped(conn: Conn):
    section("Index count cap")
    check_status("idx collection", create_collection(conn, FUNC_DB, "idx"), "OK")
    for i in range(MAX_INDEXES):
        check_status(f"index {i} created", create_index(conn, FUNC_DB, "idx", f"f{i}"), "OK")
    check_code("one more index refused", create_index(conn, FUNC_DB, "idx", "overflow"), "ERROR", "400-42")
    check_status("re-creating an existing index still OK", create_index(conn, FUNC_DB, "idx", "f0"), "OK")


def test_collection_count_is_capped(conn: Conn):
    section("Collection count cap")
    check_status("cap db created", create_database(conn, CAP_DB), "OK")
    for i in range(MAX_COLLECTIONS):
        check_status(f"collection {i} created", create_collection(conn, CAP_DB, f"coll{i}"), "OK")
    check_code("one more collection refused", create_collection(conn, CAP_DB, "overflow"), "ERROR", "400-40")
    check_status("re-creating an existing collection still OK", create_collection(conn, CAP_DB, "coll0"), "OK")


def test_database_count_is_capped(conn: Conn):
    section("Database count cap")
    index = 0
    while create_database(conn, f"extra_db_{index}").get("status") == "OK":
        index += 1
        check("database creation stays bounded", index < MAX_DATABASES + 2, f"created {index} extra databases")
    check_code("one more database refused", create_database(conn, f"extra_db_{index}"), "ERROR", "400-41")


def main():
    bu.banner("Metadata limits test suite", HOST, PORT)
    bu.require_jar()
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-metalimits-")
    log_path = os.path.join(work_dir, "server.log")
    write_config(work_dir)
    print(f"  Working dir: {work_dir}")
    proc = None
    try:
        proc = bu.start_server(work_dir, log_path, port=PORT)
        conn = Conn.authenticated()
        check_status("func db created", create_database(conn, FUNC_DB), "OK")
        test_contains_is_linear(conn)
        test_in_with_duplicate_and_case_variant_operands(conn)
        test_index_count_is_capped(conn)
        test_collection_count_is_capped(conn)
        test_database_count_is_capped(conn)
        conn.close()
    finally:
        bu.stop_server(proc, port=PORT, wait_for_port=False)
    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
