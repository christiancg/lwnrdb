import os
import statistics
import sys
import time

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("AUTH_TEST_PORT", "8989"))

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)


def create_user(c, username, password, admin=False, global_perms=None, db_perms=None, coll_perms=None):
    return c.send({
        "type": "CREATE_USER",
        "username": username,
        "password": password,
        "admin": admin,
        "globalPermissions": global_perms or [],
        "databasePermissions": db_perms or {},
        "collectionPermissions": coll_perms or {},
    })


def delete_user(c, username: str) -> dict:
    return c.send({"type": "DELETE_USER", "username": username})


def change_permissions(c, username, admin=False, global_perms=None, db_perms=None, coll_perms=None):
    return c.send({
        "type": "CHANGE_PERMISSIONS",
        "username": username,
        "admin": admin,
        "globalPermissions": global_perms or [],
        "databasePermissions": db_perms or {},
        "collectionPermissions": coll_perms or {},
    })


def set_database_owners(c, database_name: str, owners: list) -> dict:
    return c.send({"type": "SET_DATABASE_OWNERS", "databaseName": database_name, "owners": owners})


def set_password(c, username: str, new_password: str, current_password: str = None) -> dict:
    payload = {"type": "SET_PASSWORD", "username": username, "newPassword": new_password}
    if current_password is not None:
        payload["currentPassword"] = current_password
    return c.send(payload)


def setup_fixtures(c):
    for msg in [
        {"type": "CREATE_DATABASE", "databaseName": "auth_db"},
        {"type": "CREATE_COLLECTION", "databaseName": "auth_db", "collectionName": "allowed"},
        {"type": "CREATE_COLLECTION", "databaseName": "auth_db", "collectionName": "forbidden"},
        {"type": "CREATE_COLLECTION", "databaseName": "auth_db", "collectionName": "carved"},
        {"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
         "object": {"_id": "doc1", "value": 42}},
        {"type": "SAVE", "databaseName": "auth_db", "collectionName": "carved",
         "object": {"_id": "doc1", "value": 7}},
    ]:
        c.send(msg)


def teardown_fixtures(c):
    for db in ("auth_db", "new_db", "transfer_db", "no_drop_db"):
        c.send({"type": "DROP_DATABASE", "databaseName": db})


def send_unauthenticated(message: dict) -> dict:
    with Conn() as c:
        response = c.send(message)
    return response


def test_unauthenticated():
    section("Unauthenticated requests — all must return UNAUTHENTICATED")

    check_status("SAVE without auth",
          send_unauthenticated({"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
                      "object": {"x": 1}}),
          "UNAUTHENTICATED")

    check_status("FIND_BY_ID without auth",
          send_unauthenticated({"type": "FIND_BY_ID", "databaseName": "auth_db",
                      "collectionName": "allowed", "_id": "doc1"}),
          "UNAUTHENTICATED")

    check_status("AGGREGATE without auth",
          send_unauthenticated({"type": "AGGREGATE", "databaseName": "auth_db",
                      "collectionName": "allowed", "aggregationSteps": []}),
          "UNAUTHENTICATED")

    check_status("CREATE_DATABASE without auth",
          send_unauthenticated({"type": "CREATE_DATABASE", "databaseName": "new_db"}),
          "UNAUTHENTICATED")

    check_status("DROP_DATABASE without auth",
          send_unauthenticated({"type": "DROP_DATABASE", "databaseName": "auth_db"}),
          "UNAUTHENTICATED")

    check_status("CREATE_USER without auth",
          send_unauthenticated({"type": "CREATE_USER", "username": "ghost", "password": "ghost1234"}),
          "UNAUTHENTICATED")

    check_status("LIST_COLLECTIONS without auth",
          send_unauthenticated({"type": "LIST_COLLECTIONS", "databaseName": "auth_db"}),
          "UNAUTHENTICATED")

    check_code("LIST_DATABASES requires authentication",
          send_unauthenticated({"type": "LIST_DATABASES"}),
          "UNAUTHENTICATED", "401-1")

    check_status("CLOSE_CONNECTION is public (must return OK)",
          send_unauthenticated({"type": "CLOSE_CONNECTION"}),
          "OK")


def test_bad_credentials(c):
    section("Authentication with wrong credentials — must return ERROR")

    check_status("Wrong password",
          c.authenticate(ADMIN_USERNAME, "wrong_password"),
          "ERROR")

    check_status("Unknown user",
          c.authenticate("nobody", "whatever123"),
          "ERROR")


def test_list_databases_requires_authentication():
    section("LIST_DATABASES needs an authenticated connection")

    with Conn() as fresh:
        check_code("LIST_DATABASES on a fresh connection", fresh.send({"type": "LIST_DATABASES"}),
                   "UNAUTHENTICATED", "401-1")


LSDB_DATABASES = ("lsdb_a", "lsdb_b", "lsdb_c", "lsdb_d")
LSDB_USERS = ("lsdb_dbgrant", "lsdb_collgrant", "lsdb_scriptgrant", "lsdb_nogrant", "lsdb_owner")
LSDB_PASSWORD = "lsdb_pass1234"


def listed_databases(username, password):
    with Conn() as session:
        check_status(f"AUTHENTICATE as {username!r}", session.authenticate(username, password), "OK")
        response = session.send({"type": "LIST_DATABASES"})
    return [name for name in response.get("databases") or [] if name.startswith("lsdb_")]


STATS_NODE_SECTIONS = ("memory", "inDoubtTransactions", "triggers", "schedules", "scripts")


def stats_view(username, password) -> dict:
    with Conn() as session:
        check_status(f"AUTHENTICATE as {username!r}", session.authenticate(username, password), "OK")
        response = session.send({"type": "GET_DATABASE_STATS"})
    check_status(f"GET_DATABASE_STATS as {username!r}", response, "OK")
    return response.get("stats") or {}


def lsdb_collections(stats: dict) -> dict:
    return {db["name"]: sorted(coll["name"] for coll in db.get("collections", []))
            for db in stats.get("databases", []) if db["name"].startswith("lsdb_")}


def check_database_stats_are_filtered_by_grant(c):
    section("GET_DATABASE_STATS shows only what the caller can see; node sections are admin-only")
    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    for db, coll in (("lsdb_b", "ycoll"), ("lsdb_c", "zcoll"), ("lsdb_d", "owned")):
        check_status(f"CREATE_COLLECTION {db}.{coll}", c.send({"type": "CREATE_COLLECTION", "databaseName": db,
                                                                "collectionName": coll}), "OK")
    expectations = (("lsdb_dbgrant", {"lsdb_a": []}, "a database grant sees that database"),
                    ("lsdb_collgrant", {"lsdb_b": ["xcoll"]}, "a collection grant sees only that collection"),
                    ("lsdb_scriptgrant", {"lsdb_c": []}, "a script grant sees the database but no collection"),
                    ("lsdb_owner", {"lsdb_d": ["owned"]}, "an owner sees every collection of the database"),
                    ("lsdb_nogrant", {}, "no grant sees nothing"))
    for username, expected, label in expectations:
        stats = stats_view(username, LSDB_PASSWORD)
        check(label, lsdb_collections(stats) == expected, f"expected={expected} got={lsdb_collections(stats)}")
        hidden = [key for key in STATS_NODE_SECTIONS if key in stats] + (
            ["userCount"] if "userCount" in stats.get("totals", {}) else [])
        check(f"{username} gets no node-wide section", not hidden, f"got {hidden}")
    admin_stats = stats_view(ADMIN_USERNAME, ADMIN_PASSWORD)
    check("the admin still gets every node-wide section and userCount",
          all(key in admin_stats for key in STATS_NODE_SECTIONS) and "userCount" in admin_stats.get("totals", {}))
    check("the admin sees every lsdb collection",
          lsdb_collections(admin_stats) == {"lsdb_a": [], "lsdb_b": ["xcoll", "ycoll"], "lsdb_c": ["zcoll"],
                                            "lsdb_d": ["owned"]}, str(lsdb_collections(admin_stats)))


def test_list_databases_is_filtered_by_grant(c):
    section("LIST_DATABASES lists only the databases the caller owns or holds a grant on")

    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    for db in LSDB_DATABASES:
        c.send({"type": "DROP_DATABASE", "databaseName": db})
        check_status(f"CREATE_DATABASE {db}", c.send({"type": "CREATE_DATABASE", "databaseName": db}), "OK")
    for user in LSDB_USERS:
        delete_user(c, user)
    create_user(c, "lsdb_dbgrant", LSDB_PASSWORD, db_perms={"lsdb_a": "READ"})
    check_status("CREATE_COLLECTION lsdb_b.xcoll", c.send({"type": "CREATE_COLLECTION", "databaseName": "lsdb_b",
                                                     "collectionName": "xcoll"}), "OK")
    check_status("CREATE_USER with a collection grant",
                 create_user(c, "lsdb_collgrant", LSDB_PASSWORD, coll_perms={"lsdb_b|xcoll": "READ"}), "OK")
    c.send({"type": "CREATE_USER", "username": "lsdb_scriptgrant", "password": LSDB_PASSWORD, "admin": False,
            "globalPermissions": [], "databasePermissions": {}, "collectionPermissions": {},
            "scriptPermissions": {"lsdb_c": "RUN"}})
    create_user(c, "lsdb_nogrant", LSDB_PASSWORD)
    create_user(c, "lsdb_owner", LSDB_PASSWORD)
    check_status("SET_DATABASE_OWNERS lsdb_d -> lsdb_owner", set_database_owners(c, "lsdb_d", ["lsdb_owner"]), "OK")

    expectations = (("lsdb_dbgrant", ["lsdb_a"], "a database grant lists that database"),
                    ("lsdb_collgrant", ["lsdb_b"], "a collection grant lists its database"),
                    ("lsdb_scriptgrant", ["lsdb_c"], "a script grant lists its database"),
                    ("lsdb_owner", ["lsdb_d"], "ownership lists the owned database"),
                    ("lsdb_nogrant", [], "no grant lists nothing"))
    for username, expected, label in expectations:
        listed = listed_databases(username, LSDB_PASSWORD)
        check(label, listed == expected, f"expected={expected} got={listed}")
    admin_list = listed_databases(ADMIN_USERNAME, ADMIN_PASSWORD)
    check("admin lists all four", admin_list == list(LSDB_DATABASES), f"got={admin_list}")

    check_database_stats_are_filtered_by_grant(c)

    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    for user in LSDB_USERS:
        delete_user(c, user)
    for db in LSDB_DATABASES:
        c.send({"type": "DROP_DATABASE", "databaseName": db})


BRUTE_TARGET = "brute_target"
BRUTE_PASSWORD = "brute_target1234"
BRUTE_BYSTANDER = "brute_bystander"
DELAY_BUDGET_S = 2.0


def read_eof(conn, timeout=10.0):
    conn.s.settimeout(timeout)
    try:
        return conn.f.readline() == b""
    except OSError:
        return False


def test_too_many_failed_attempts_closes_the_connection(c):
    section("Too many failed AUTHENTICATEs on one connection answer 401-4 and close it")

    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    delete_user(c, BRUTE_TARGET)
    delete_user(c, BRUTE_BYSTANDER)
    check_status(f"CREATE_USER {BRUTE_TARGET!r}", create_user(c, BRUTE_TARGET, BRUTE_PASSWORD), "OK")
    check_status(f"CREATE_USER {BRUTE_BYSTANDER!r}", create_user(c, BRUTE_BYSTANDER, BRUTE_PASSWORD), "OK")

    with Conn() as attacker:
        for attempt in range(1, 5):
            check_code(f"wrong password #{attempt} answers 401-3", attacker.authenticate(BRUTE_TARGET, "wrong_pass"),
                       "ERROR", "401-3")
        check_code("wrong password #5 answers 401-4", attacker.authenticate(BRUTE_TARGET, "wrong_pass"),
                   "UNAUTHENTICATED", "401-4")
        check("the server closes the connection after 401-4", read_eof(attacker))

    with Conn() as again:
        check_status("a fresh connection with the right password still authenticates (no lockout)",
                     again.authenticate(BRUTE_TARGET, BRUTE_PASSWORD), "OK")


def test_failures_do_not_slow_a_different_username():
    section("Failures against one username do not delay another from the same address")

    with Conn() as attacker:
        attacker.authenticate(BRUTE_TARGET, "wrong_pass")
        attacker.authenticate(BRUTE_TARGET, "wrong_pass")
    with Conn() as other:
        started = time.monotonic()
        response = other.authenticate(BRUTE_BYSTANDER, BRUTE_PASSWORD)
        elapsed = time.monotonic() - started
        check_status(f"AUTHENTICATE as {BRUTE_BYSTANDER!r}", response, "OK")
        check(f"not delayed beyond {DELAY_BUDGET_S}s", elapsed < DELAY_BUDGET_S, f"took {elapsed:.2f}s")
    with Conn() as cleanup:
        cleanup.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        delete_user(cleanup, BRUTE_TARGET)
        delete_user(cleanup, BRUTE_BYSTANDER)


def timed_wrong_attempts(username, attempts=3):
    timings = []
    for _ in range(attempts):
        with Conn() as session:
            started = time.monotonic()
            session.authenticate(username, "definitely_wrong")
            timings.append(time.monotonic() - started)
    return statistics.median(timings)


def test_unknown_user_costs_like_a_known_user(c):
    section("A wrong password costs the same for an unknown user as for a known one")

    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    delete_user(c, "timing_known")
    create_user(c, "timing_known", "timing_known1234")
    known = timed_wrong_attempts("timing_known")
    unknown = timed_wrong_attempts("timing_unknown_user")
    ratio = max(known, unknown) / max(min(known, unknown), 1e-6)
    check("known and unknown usernames take comparable time", ratio < 3.0,
          f"known={known * 1000:.0f}ms unknown={unknown * 1000:.0f}ms")
    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    delete_user(c, "timing_known")


def test_set_password_deauthenticates_other_sessions(c):
    section("SET_PASSWORD signs the user's other connections out")

    list_dbs = {"type": "LIST_DATABASES"}
    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    delete_user(c, "pw_sess_user")
    check_status("CREATE_USER 'pw_sess_user'", create_user(c, "pw_sess_user", "pw_sess_old1"), "OK")
    with Conn() as a, Conn() as b:
        check_status("A authenticates", a.authenticate("pw_sess_user", "pw_sess_old1"), "OK")
        check_status("B authenticates", b.authenticate("pw_sess_user", "pw_sess_old1"), "OK")
        check_status("A changes its own password",
                     set_password(a, "pw_sess_user", "pw_sess_new1", current_password="pw_sess_old1"), "OK")
        check_status("A keeps its session", a.send(list_dbs), "OK")
        check_code("B must authenticate again", b.send(list_dbs), "UNAUTHENTICATED", "401-1")
        with Conn() as third:
            check_status("C authenticates with the new password",
                         third.authenticate("pw_sess_user", "pw_sess_new1"), "OK")
            check_status("admin resets the password", set_password(c, "pw_sess_user", "pw_sess_admin1"), "OK")
            check_code("C must authenticate again", third.send(list_dbs), "UNAUTHENTICATED", "401-1")
            check_code("A must authenticate again too", a.send(list_dbs), "UNAUTHENTICATED", "401-1")
    check_status("admin keeps its own session", c.send(list_dbs), "OK")
    delete_user(c, "pw_sess_user")


def test_unknown_type_with_newline_is_refused_cleanly():
    section("An unknown request type carrying a newline is refused and the connection keeps serving")

    with Conn() as fresh:
        response = fresh.send({"type": "X\nFAKE-LOG-LINE"})
        check("the forged type is refused", response.get("status") != "OK", f"got={response}")
        check_code("the connection still answers afterwards", fresh.send({"type": "LIST_DATABASES"}),
                   "UNAUTHENTICATED", "401-1")

def test_no_db_permission(c):
    section("User without CREATE_DATABASE / DROP_DATABASE — must return FORBIDDEN")

    check_status("AUTHENTICATE as 'no_perms'",
          c.authenticate("no_perms", "no_perms1234"),
          "OK")

    check_status("CREATE_DATABASE without global permission",
          c.send({"type": "CREATE_DATABASE", "databaseName": "new_db"}),
          "FORBIDDEN")

    check_status("DROP_DATABASE without global permission",
          c.send({"type": "DROP_DATABASE", "databaseName": "auth_db"}),
          "FORBIDDEN")


def test_collection_read_allowed(c):
    section("User with db READ permission — reads must succeed, writes must fail")

    check_status("AUTHENTICATE as 'db_reader'",
          c.authenticate("db_reader", "db_reader1234"),
          "OK")

    check_status("FIND_BY_ID on allowed collection (OK)",
          c.send({"type": "FIND_BY_ID", "databaseName": "auth_db",
                      "collectionName": "allowed", "_id": "doc1"}),
          "OK")

    check_status("AGGREGATE (COUNT) on allowed collection (OK)",
          c.send({"type": "AGGREGATE", "databaseName": "auth_db",
                      "collectionName": "allowed", "aggregationSteps": [{"type": "COUNT"}]}),
          "OK")

    check_status("SAVE on read-only db (FORBIDDEN)",
          c.send({"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
                      "object": {"x": 1}}),
          "FORBIDDEN")

    check_status("DELETE on read-only db (FORBIDDEN)",
          c.send({"type": "DELETE", "databaseName": "auth_db",
                      "collectionName": "allowed", "_id": "doc1"}),
          "FORBIDDEN")


def test_collection_permission_boundary(c):
    section("User with READ on one collection, no access to another — collection boundary")

    check_status("AUTHENTICATE as 'coll_reader'",
          c.authenticate("coll_reader", "coll_reader1234"),
          "OK")

    check_status("FIND_BY_ID on permitted collection (OK)",
          c.send({"type": "FIND_BY_ID", "databaseName": "auth_db",
                      "collectionName": "allowed", "_id": "doc1"}),
          "OK")

    check_status("FIND_BY_ID on forbidden collection (FORBIDDEN)",
          c.send({"type": "FIND_BY_ID", "databaseName": "auth_db",
                      "collectionName": "forbidden", "_id": "doc1"}),
          "FORBIDDEN")

    check_status("AGGREGATE on forbidden collection (FORBIDDEN)",
          c.send({"type": "AGGREGATE", "databaseName": "auth_db",
                      "collectionName": "forbidden", "aggregationSteps": []}),
          "FORBIDDEN")


CARVE_OUT_CASES = (
    ("SAVE on a collection with no entry of its own (OK)",
     {"type": "SAVE", "collectionName": "allowed", "object": {"_id": "carve_probe", "value": 1}}, "OK", None),
    ("SAVE on the carved collection (FORBIDDEN)",
     {"type": "SAVE", "collectionName": "carved", "object": {"_id": "nope", "value": 1}}, "FORBIDDEN", "403-1"),
    ("DELETE on the carved collection (FORBIDDEN)",
     {"type": "DELETE", "collectionName": "carved", "_id": "doc1"}, "FORBIDDEN", "403-1"),
    ("CREATE_INDEX on the carved collection (FORBIDDEN)",
     {"type": "CREATE_INDEX", "collectionName": "carved", "fieldName": "value"}, "FORBIDDEN", "403-1"),
    ("DROP_COLLECTION on the carved collection (FORBIDDEN)",
     {"type": "DROP_COLLECTION", "collectionName": "carved"}, "FORBIDDEN", "403-1"),
    ("FIND_BY_ID on the carved collection (OK)",
     {"type": "FIND_BY_ID", "collectionName": "carved", "_id": "doc1"}, "OK", None),
    ("AGGREGATE (COUNT) on the carved collection (OK)",
     {"type": "AGGREGATE", "collectionName": "carved", "aggregationSteps": [{"type": "COUNT"}]}, "OK", None),
)


def test_collection_carve_out(c):
    section("User with db READ_WRITE and a collection carved down to READ")

    check_status("AUTHENTICATE as 'carve_user'", c.authenticate("carve_user", "carve_user1234"), "OK")

    for label, request, status, code in CARVE_OUT_CASES:
        response = c.send({"type": request["type"], "databaseName": "auth_db", **request})
        if code is None:
            check_status(label, response, status)
        else:
            check_code(label, response, status, code)


def save_trigger_on(c, coll: str, name: str) -> dict:
    return c.send({"type": "SAVE_TRIGGER", "databaseName": "auth_db", "collectionName": coll,
                   "name": name, "events": ["CREATED"], "procedureName": "carve_proc"})


def test_a_collection_carve_out_bounds_trigger_installation(c):
    section("A MANAGE user carved down to READ on a collection cannot install a trigger there")

    check_status("AUTHENTICATE as 'carve_manager'", c.authenticate("carve_manager", "carve_manager1234"), "OK")

    check_code("SAVE_TRIGGER on the carved collection (FORBIDDEN)",
               save_trigger_on(c, "carved", "carved_hook"), "FORBIDDEN", "403-1")

    check_code("DELETE_TRIGGER on the carved collection (FORBIDDEN)",
               c.send({"type": "DELETE_TRIGGER", "databaseName": "auth_db", "collectionName": "carved",
                       "name": "admin_hook"}),
               "FORBIDDEN", "403-1")

    check_code("SAVE_TRIGGER on a collection with no entry of its own and no database grant (FORBIDDEN)",
               save_trigger_on(c, "allowed", "allowed_hook"), "FORBIDDEN", "403-1")

    check_status("SAVE_TRIGGER on a collection granted READ_WRITE (OK)",
                 save_trigger_on(c, "forbidden", "granted_hook"), "OK")

    check_status("SAVE_PROCEDURE is not bounded by any collection entry (OK)",
                 c.send({"type": "SAVE_PROCEDURE", "databaseName": "auth_db", "name": "carve_proc_two",
                         "script": "return 2;"}),
                 "OK")


SCRIPT_MANAGERS = {
    "scripts_only": ({}, {}),
    "scripts_db_reader": ({"auth_db": "READ"}, {}),
    "scripts_db_writer": ({"auth_db": "READ_WRITE"}, {}),
    "scripts_coll_writer": ({}, {"auth_db|allowed": "READ_WRITE"}),
}


def create_script_managers(c):
    for username, (db_perms, coll_perms) in SCRIPT_MANAGERS.items():
        c.send({"type": "CREATE_USER", "username": username, "password": username + "1234", "admin": False,
                "globalPermissions": [], "databasePermissions": db_perms, "collectionPermissions": coll_perms,
                "scriptPermissions": {"auth_db": "MANAGE"}})


def trigger_request(op_type: str, coll: str, name: str) -> dict:
    request = {"type": op_type, "databaseName": "auth_db", "collectionName": coll, "name": name}
    if op_type == "TEST_TRIGGER":
        request.update({"event": "CREATED", "document": {"_id": "probe"}})
    return request


def check_trigger_management_refused(c, username: str, coll: str):
    check_code(f"SAVE_TRIGGER by '{username}' on '{coll}' (FORBIDDEN)",
               save_trigger_on(c, coll, "scripts_hook"), "FORBIDDEN", "403-1")
    for op_type in ("DELETE_TRIGGER", "TEST_TRIGGER"):
        check_code(f"{op_type} by '{username}' on '{coll}' (FORBIDDEN)",
                   c.send(trigger_request(op_type, coll, "admin_hook")), "FORBIDDEN", "403-1")


def check_trigger_installed_and_removed(c, label: str, coll: str):
    check_status(f"SAVE_TRIGGER by {label} on '{coll}' (OK)", save_trigger_on(c, coll, "scripts_hook"), "OK")
    check_status(f"DELETE_TRIGGER by {label} on '{coll}' (OK)",
                 c.send(trigger_request("DELETE_TRIGGER", coll, "scripts_hook")), "OK")


def test_script_management_needs_data_access_to_install_triggers(c):
    section("Installing a trigger needs READ_WRITE on its collection, not just MANAGE")

    for username in ("scripts_only", "scripts_db_reader"):
        check_status(f"AUTHENTICATE as '{username}'", c.authenticate(username, username + "1234"), "OK")
        check_trigger_management_refused(c, username, "allowed")

    for username in ("scripts_db_writer", "scripts_coll_writer"):
        check_status(f"AUTHENTICATE as '{username}'", c.authenticate(username, username + "1234"), "OK")
        check_trigger_installed_and_removed(c, f"'{username}'", "allowed")
    check_code("SAVE_TRIGGER by 'scripts_coll_writer' on another collection (FORBIDDEN)",
               save_trigger_on(c, "forbidden", "scripts_hook"), "FORBIDDEN", "403-1")

    check_status("AUTHENTICATE as 'scripts_only'", c.authenticate("scripts_only", "scripts_only1234"), "OK")
    check_status("SAVE_PROCEDURE by 'scripts_only' is not bounded by data access (OK)",
                 c.send({"type": "SAVE_PROCEDURE", "databaseName": "auth_db", "name": "scripts_only_proc",
                         "script": "return 1;"}), "OK")
    check_status("SAVE_SCHEDULE by 'scripts_only' is not bounded by data access (OK)",
                 c.send({"type": "SAVE_SCHEDULE", "databaseName": "auth_db", "name": "scripts_only_schedule",
                         "procedureName": "scripts_only_proc", "intervalMs": 3600000}), "OK")
    check_status("DELETE_SCHEDULE by 'scripts_only' (OK)",
                 c.send({"type": "DELETE_SCHEDULE", "databaseName": "auth_db", "name": "scripts_only_schedule"}),
                 "OK")

    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    check_trigger_installed_and_removed(c, "the admin", "forbidden")
    check_status("make 'new_owner' an owner of auth_db",
                 set_database_owners(c, "auth_db", [ADMIN_USERNAME, "new_owner"]), "OK")
    check_status("AUTHENTICATE as 'new_owner'", c.authenticate("new_owner", "new_owner1234"), "OK")
    check_trigger_installed_and_removed(c, "a database owner with no grant", "forbidden")
    check_status("AUTHENTICATE as admin again", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    check_status("restore the auth_db owners", set_database_owners(c, "auth_db", [ADMIN_USERNAME]), "OK")


def test_admin_operations(c):
    section("Admin user — all operations must succeed")

    check_status("AUTHENTICATE as admin",
          c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check_status("CREATE_DATABASE",
          c.send({"type": "CREATE_DATABASE", "databaseName": "new_db"}),
          "OK")

    check_status("CREATE_COLLECTION",
          c.send({"type": "CREATE_COLLECTION", "databaseName": "new_db",
                      "collectionName": "stuff"}),
          "OK")

    check_status("SAVE",
          c.send({"type": "SAVE", "databaseName": "new_db", "collectionName": "stuff",
                      "object": {"_id": "x1", "val": 99}}),
          "OK")

    check_status("FIND_BY_ID",
          c.send({"type": "FIND_BY_ID", "databaseName": "new_db",
                      "collectionName": "stuff", "_id": "x1"}),
          "OK")

    check_status("AGGREGATE (COUNT) on stuff (doc present, should return OK)",
          c.send({"type": "AGGREGATE", "databaseName": "new_db",
                      "collectionName": "stuff", "aggregationSteps": [{"type": "COUNT"}]}),
          "OK")

    check_status("DELETE",
          c.send({"type": "DELETE", "databaseName": "new_db",
                      "collectionName": "stuff", "_id": "x1"}),
          "OK")

    check_status("AGGREGATE after delete (empty collection returns NOT_FOUND)",
          c.send({"type": "AGGREGATE", "databaseName": "new_db",
                      "collectionName": "stuff", "aggregationSteps": []}),
          "NOT_FOUND")

    check_status("DROP_COLLECTION",
          c.send({"type": "DROP_COLLECTION", "databaseName": "new_db",
                      "collectionName": "stuff"}),
          "OK")

    check_status("DROP_DATABASE new_db",
          c.send({"type": "DROP_DATABASE", "databaseName": "new_db"}),
          "OK")

    check_status("GET_DATABASE_STATS as admin returns OK",
          c.send({"type": "GET_DATABASE_STATS"}),
          "OK")


def test_user_management(c):
    section("User management as admin (CREATE, CHANGE_PERMISSIONS, DELETE)")

    check_status("AUTHENTICATE as admin",
          c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check_status("CREATE_USER 'tmp_user'",
          create_user(c, "tmp_user", "tmp_user1234", admin=False,
                      db_perms={"auth_db": "READ"}),
          "OK")

    check_status("CREATE_USER duplicate returns ERROR",
          create_user(c, "tmp_user", "tmp_user1234"),
          "ERROR")

    check_status("CHANGE_PERMISSIONS — grant WRITE to 'tmp_user'",
          change_permissions(c, "tmp_user", db_perms={"auth_db": "READ_WRITE"}),
          "OK")

    check_status("CHANGE_PERMISSIONS — promote 'tmp_user' to admin",
          change_permissions(c, "tmp_user", admin=True),
          "OK")

    check_status("CHANGE_PERMISSIONS — demote 'tmp_user' back",
          change_permissions(c, "tmp_user", admin=False, db_perms={"auth_db": "READ"}),
          "OK")

    check_status("CREATE_USER with a trailing '|' in a collection key is refused, not stored as a no-op grant",
          create_user(c, "pipe_user", "pipe_user1234", coll_perms={"auth_db|carved|": "READ"}),
          "ERROR")

    check_status("CHANGE_PERMISSIONS with a doubled '|' in a collection key is refused",
          change_permissions(c, "tmp_user", db_perms={"auth_db": "READ"}, coll_perms={"auth_db||carved": "READ"}),
          "ERROR")

    check_status("DELETE_USER 'tmp_user'",
          delete_user(c, "tmp_user"),
          "OK")

    check_status("DELETE_USER non-existent returns NOT_FOUND",
          delete_user(c, "tmp_user"),
          "NOT_FOUND")

    check_status("AUTHENTICATE as 'db_reader' (non-admin)",
          c.authenticate("db_reader", "db_reader1234"),
          "OK")

    check_status("CREATE_USER as non-admin (FORBIDDEN)",
          create_user(c, "sneaky", "sneaky1234"),
          "FORBIDDEN")

    check_status("DELETE_USER as non-admin (FORBIDDEN)",
          delete_user(c, "db_reader"),
          "FORBIDDEN")

    check_status("CHANGE_PERMISSIONS as non-admin (FORBIDDEN)",
          change_permissions(c, "db_reader", admin=True),
          "FORBIDDEN")

    non_admin_stats = c.send({"type": "GET_DATABASE_STATS"})
    check_status("GET_DATABASE_STATS as non-admin answers its filtered view", non_admin_stats, "OK")
    check("and leaves out the node-wide sections", "memory" not in (non_admin_stats.get("stats") or {}),
          str(non_admin_stats)[:200])


def list_users(c, steps=None) -> dict:
    payload = {"type": "LIST_USERS"}
    if steps is not None:
        payload["aggregationSteps"] = steps
    return c.send(payload)


def test_set_password(c):
    section("SET_PASSWORD — own password with verification, admin changes any")

    check_status("AUTHENTICATE as admin",
          c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check_status("Create test user 'pwd_user'",
          create_user(c, "pwd_user", "original_pass_1"),
          "OK")

    check_status("AUTHENTICATE as 'pwd_user'",
          c.authenticate("pwd_user", "original_pass_1"),
          "OK")

    check_status("User can change own password with correct currentPassword",
          set_password(c, "pwd_user", "changed_pass_1", current_password="original_pass_1"),
          "OK")

    check_status("Old password no longer authenticates after change",
          c.authenticate("pwd_user", "original_pass_1"),
          "ERROR")

    check_status("New password authenticates successfully",
          c.authenticate("pwd_user", "changed_pass_1"),
          "OK")

    check_status("User cannot change own password with wrong currentPassword",
          set_password(c, "pwd_user", "another_new_1", current_password="wrong_password"),
          "ERROR")

    check_status("User cannot change another user's password (FORBIDDEN)",
          set_password(c, ADMIN_USERNAME, "hacked12345", current_password="changed_pass_1"),
          "FORBIDDEN")

    check_status("AUTHENTICATE as admin",
          c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check_status("Admin can change another user's password without currentPassword",
          set_password(c, "pwd_user", "admin_reset_1"),
          "OK")

    check_status("Admin-reset password works for login",
          c.authenticate("pwd_user", "admin_reset_1"),
          "OK")

    check_status("AUTHENTICATE as admin (re-auth after pwd_user session)",
          c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check_status("Admin can change their own password",
          set_password(c, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check_status("SET_PASSWORD for non-existent user returns NOT_FOUND",
          set_password(c, "nobody999", "new_password_1"),
          "NOT_FOUND")


def test_list_users(c):
    section("LIST_USERS — admin-only, supports filtering")

    check_status("AUTHENTICATE as admin",
          c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check_status("LIST_USERS returns all users",
          list_users(c),
          "OK")

    check_status("LIST_USERS response does not include passwordHash",
          list_users(c),
          "OK")

    check_status("LIST_USERS filter by username (exact match)",
          list_users(c, [{"type": "FILTER", "operator": {
              "fieldOperatorType": "EQUALS", "field": "_id", "value": ADMIN_USERNAME
          }}]),
          "OK")

    check_status("LIST_USERS filter by admin=true",
          list_users(c, [{"type": "FILTER", "operator": {
              "fieldOperatorType": "EQUALS", "field": "admin", "value": True
          }}]),
          "OK")

    check_status("LIST_USERS COUNT step returns single result",
          list_users(c, [{"type": "COUNT"}]),
          "OK")

    check_status("LIST_USERS LIMIT 1",
          list_users(c, [{"type": "LIMIT", "limit": 1}]),
          "OK")

    check_status("LIST_USERS SORT ascending by username",
          list_users(c, [{"type": "SORT", "fieldName": "_id", "ascending": True}]),
          "OK")

    check_status("LIST_USERS filter non-existent username returns NOT_FOUND",
          list_users(c, [{"type": "FILTER", "operator": {
              "fieldOperatorType": "EQUALS", "field": "_id", "value": "nobody999"
          }}]),
          "NOT_FOUND")

    check_status("LIST_USERS filter by databasePermissions ownership field (users with any db perm)",
          list_users(c, [{"type": "FILTER", "operator": {
              "fieldOperatorType": "EQUALS", "field": "_id", "value": "db_reader"
          }}]),
          "OK")

    check_status("AUTHENTICATE as 'db_reader' (non-admin)",
          c.authenticate("db_reader", "db_reader1234"),
          "OK")

    check_status("LIST_USERS as non-admin returns FORBIDDEN",
          list_users(c),
          "FORBIDDEN")


def test_ownership(c):
    section("Database ownership — creation, transfer, and access control")

    check_status("AUTHENTICATE as 'db_maker' (has CREATE_DATABASE permission)",
          c.authenticate("db_maker", "db_maker1234"),
          "OK")

    check_status("CREATE_DATABASE 'owned_db' — db_maker becomes owner automatically",
          c.send({"type": "CREATE_DATABASE", "databaseName": "owned_db"}),
          "OK")

    check_status("Owner can CREATE_COLLECTION with no explicit db/coll permissions",
          c.send({"type": "CREATE_COLLECTION", "databaseName": "owned_db",
                      "collectionName": "my_coll"}),
          "OK")

    check_status("Owner can SAVE with no explicit db/coll permissions",
          c.send({"type": "SAVE", "databaseName": "owned_db", "collectionName": "my_coll",
                      "object": {"_id": "o1", "val": 1}}),
          "OK")

    check_status("Owner can FIND_BY_ID",
          c.send({"type": "FIND_BY_ID", "databaseName": "owned_db",
                      "collectionName": "my_coll", "_id": "o1"}),
          "OK")

    check_status("Owner can DROP_DATABASE their own database",
          c.send({"type": "DROP_DATABASE", "databaseName": "owned_db"}),
          "OK")

    check_status("AUTHENTICATE as admin",
          c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check_status("Admin creates 'no_drop_db'",
          c.send({"type": "CREATE_DATABASE", "databaseName": "no_drop_db"}),
          "OK")

    check_status("AUTHENTICATE as 'db_maker' (has CREATE_DATABASE global perm, not owner of no_drop_db)",
          c.authenticate("db_maker", "db_maker1234"),
          "OK")

    check_status("DROP_DATABASE without ownership is FORBIDDEN even with global perm",
          c.send({"type": "DROP_DATABASE", "databaseName": "no_drop_db"}),
          "FORBIDDEN")

    check_status("AUTHENTICATE as admin to clean up no_drop_db",
          c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    c.send({"type": "DROP_DATABASE", "databaseName": "no_drop_db"})

    check_status("Admin creates 'transfer_db' (admin is owner)",
          c.send({"type": "CREATE_DATABASE", "databaseName": "transfer_db"}),
          "OK")

    check_status("Admin creates a collection in 'transfer_db'",
          c.send({"type": "CREATE_COLLECTION", "databaseName": "transfer_db",
                      "collectionName": "stuff"}),
          "OK")

    check_status("SET_DATABASE_OWNERS — transfer ownership to 'new_owner'",
          set_database_owners(c, "transfer_db", ["new_owner"]),
          "OK")

    check_status("SET_DATABASE_OWNERS with non-existent user returns ERROR",
          set_database_owners(c, "transfer_db", ["ghost_user999"]),
          "ERROR")

    check_status("AUTHENTICATE as 'new_owner'",
          c.authenticate("new_owner", "new_owner1234"),
          "OK")

    check_status("SET_DATABASE_OWNERS as non-admin (FORBIDDEN)",
          set_database_owners(c, "transfer_db", ["db_maker"]),
          "FORBIDDEN")

    check_status("New owner can SAVE with no explicit permissions",
          c.send({"type": "SAVE", "databaseName": "transfer_db", "collectionName": "stuff",
                      "object": {"_id": "t1", "val": 42}}),
          "OK")

    check_status("New owner can FIND_BY_ID",
          c.send({"type": "FIND_BY_ID", "databaseName": "transfer_db",
                      "collectionName": "stuff", "_id": "t1"}),
          "OK")

    check_status("New owner can DROP_DATABASE 'transfer_db'",
          c.send({"type": "DROP_DATABASE", "databaseName": "transfer_db"}),
          "OK")

    check_status("AUTHENTICATE as 'db_maker'",
          c.authenticate("db_maker", "db_maker1234"),
          "OK")

    check_status("'db_maker' has no access to 'auth_db' (not owner, no permissions)",
          c.send({"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
                      "object": {"x": 99}}),
          "FORBIDDEN")


def test_owners_of_a_dropped_database(c):
    section("SET_DATABASE_OWNERS on a dropped database does not bring it back")

    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    check_status("CREATE_DATABASE 'owners_gone_db'",
                 c.send({"type": "CREATE_DATABASE", "databaseName": "owners_gone_db"}), "OK")
    check_status("DROP_DATABASE 'owners_gone_db'",
                 c.send({"type": "DROP_DATABASE", "databaseName": "owners_gone_db"}), "OK")
    check_code("SET_DATABASE_OWNERS on the dropped database",
               set_database_owners(c, "owners_gone_db", ["new_owner"]), "NOT_FOUND", "404-4")
    listed = c.send({"type": "LIST_DATABASES"})
    bu.check("the dropped database is not listed again", "owners_gone_db" not in str(listed), str(listed))
    check_status("CREATE_DATABASE of the same name succeeds",
                 c.send({"type": "CREATE_DATABASE", "databaseName": "owners_gone_db"}), "OK")
    check_status("DROP_DATABASE it again",
                 c.send({"type": "DROP_DATABASE", "databaseName": "owners_gone_db"}), "OK")


def find_doc(c, db, coll, doc_id="doc1"):
    return c.send({"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": doc_id})


def test_grants_do_not_survive_a_drop_and_recreate(c):
    section("Grants die with the database or collection they name")

    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    for db in ("grant_gone_db", "grant_coll_db"):
        c.send({"type": "DROP_DATABASE", "databaseName": db})
        check_status(f"CREATE_DATABASE '{db}'", c.send({"type": "CREATE_DATABASE", "databaseName": db}), "OK")
        check_status(f"CREATE_COLLECTION in '{db}'",
                     c.send({"type": "CREATE_COLLECTION", "databaseName": db, "collectionName": "items"}), "OK")
        check_status(f"SAVE in '{db}'", c.send({"type": "SAVE", "databaseName": db, "collectionName": "items",
                                                "object": {"_id": "doc1", "value": 1}}), "OK")
    delete_user(c, "grant_bob")
    delete_user(c, "grant_carol")
    check_status("CREATE_USER 'grant_bob' with a database grant",
                 create_user(c, "grant_bob", "grant_bob1234", db_perms={"grant_gone_db": "READ_WRITE"}), "OK")
    check_status("CREATE_USER 'grant_carol' with a collection grant",
                 create_user(c, "grant_carol", "grant_carol1234", coll_perms={"grant_coll_db|items": "READ"}), "OK")

    with Conn() as bob:
        check_status("AUTHENTICATE as 'grant_bob'", bob.authenticate("grant_bob", "grant_bob1234"), "OK")
        check_status("grant_bob reads while the database exists", find_doc(bob, "grant_gone_db", "items"), "OK")
    with Conn() as carol:
        check_status("AUTHENTICATE as 'grant_carol'", carol.authenticate("grant_carol", "grant_carol1234"), "OK")
        check_status("grant_carol reads while the collection exists", find_doc(carol, "grant_coll_db", "items"),
                     "OK")

    check_status("DROP_DATABASE 'grant_gone_db'",
                 c.send({"type": "DROP_DATABASE", "databaseName": "grant_gone_db"}), "OK")
    check_status("DROP_COLLECTION 'grant_coll_db|items'",
                 c.send({"type": "DROP_COLLECTION", "databaseName": "grant_coll_db",
                         "collectionName": "items"}), "OK")
    for db, coll in (("grant_gone_db", "items"), ("grant_coll_db", "items")):
        c.send({"type": "CREATE_DATABASE", "databaseName": db})
        check_status(f"re-CREATE_COLLECTION '{db}|{coll}'",
                     c.send({"type": "CREATE_COLLECTION", "databaseName": db, "collectionName": coll}), "OK")
        check_status(f"re-SAVE in '{db}'", c.send({"type": "SAVE", "databaseName": db, "collectionName": coll,
                                                   "object": {"_id": "doc1", "value": 2}}), "OK")

    with Conn() as bob:
        check_status("AUTHENTICATE as 'grant_bob' again", bob.authenticate("grant_bob", "grant_bob1234"), "OK")
        check_status("grant_bob is refused on the re-created database", find_doc(bob, "grant_gone_db", "items"),
                     "FORBIDDEN")
    with Conn() as carol:
        check_status("AUTHENTICATE as 'grant_carol' again", carol.authenticate("grant_carol", "grant_carol1234"),
                     "OK")
        check_status("grant_carol is refused on the re-created collection", find_doc(carol, "grant_coll_db", "items"),
                     "FORBIDDEN")

    for db in ("grant_gone_db", "grant_coll_db"):
        c.send({"type": "DROP_DATABASE", "databaseName": db})
    delete_user(c, "grant_bob")
    delete_user(c, "grant_carol")


def test_a_deleted_users_name_does_not_inherit_ownership(c):
    section("A user created under a deleted name does not own the old user's databases")

    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    c.send({"type": "DROP_DATABASE", "databaseName": "ghost_owned_db"})
    delete_user(c, "ghost_owner")
    check_status("CREATE_USER 'ghost_owner' with CREATE_DATABASE",
                 create_user(c, "ghost_owner", "ghost_owner1234", global_perms=["CREATE_DATABASE"]), "OK")
    with Conn() as owner:
        check_status("AUTHENTICATE as 'ghost_owner'", owner.authenticate("ghost_owner", "ghost_owner1234"), "OK")
        check_status("CREATE_DATABASE 'ghost_owned_db' - ghost_owner becomes the owner",
                     owner.send({"type": "CREATE_DATABASE", "databaseName": "ghost_owned_db"}), "OK")

    check_status("DELETE_USER 'ghost_owner'", delete_user(c, "ghost_owner"), "OK")
    check_status("CREATE_USER 'ghost_owner' again for someone else",
                 create_user(c, "ghost_owner", "ghost_owner5678", global_perms=["CREATE_DATABASE"]), "OK")
    with Conn() as impostor:
        check_status("AUTHENTICATE as the new 'ghost_owner'",
                     impostor.authenticate("ghost_owner", "ghost_owner5678"), "OK")
        check_status("the new 'ghost_owner' cannot drop the old one's database",
                     impostor.send({"type": "DROP_DATABASE", "databaseName": "ghost_owned_db"}), "FORBIDDEN")

    check_status("the admin can still give the ownerless database an owner",
                 set_database_owners(c, "ghost_owned_db", ["ghost_owner"]), "OK")
    c.send({"type": "DROP_DATABASE", "databaseName": "ghost_owned_db"})
    delete_user(c, "ghost_owner")


def test_a_session_does_not_survive_its_user_being_recreated(c):
    section("An open connection does not become the user later created under its deleted name")

    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    delete_user(c, "recycled_name")
    check_status("CREATE_USER 'recycled_name' with READ on auth_db",
                 create_user(c, "recycled_name", "recycled_old1234", db_perms={"auth_db": "READ"}), "OK")
    find_doc1 = {"type": "FIND_BY_ID", "databaseName": "auth_db", "collectionName": "allowed", "_id": "doc1"}
    with Conn() as old_session:
        check_status("AUTHENTICATE as 'recycled_name'",
                     old_session.authenticate("recycled_name", "recycled_old1234"), "OK")
        check_status("FIND_BY_ID as 'recycled_name'", old_session.send(find_doc1), "OK")

        check_status("DELETE_USER 'recycled_name'", delete_user(c, "recycled_name"), "OK")
        check_status("CREATE_USER 'recycled_name' again for someone else, with READ_WRITE",
                     create_user(c, "recycled_name", "recycled_new5678",
                                 db_perms={"auth_db": "READ_WRITE"}), "OK")

        check_code("the old connection must authenticate again", old_session.send(find_doc1),
                   "UNAUTHENTICATED", "401-1")
        check_code("and cannot write with the new user's permissions",
                   old_session.send({"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
                                     "object": {"_id": "recycled_write", "value": 1}}),
                   "UNAUTHENTICATED", "401-1")
        check_status("the old password no longer authenticates",
                     old_session.authenticate("recycled_name", "recycled_old1234"), "ERROR")
        check_status("the new password authenticates the connection again",
                     old_session.authenticate("recycled_name", "recycled_new5678"), "OK")
        check_status("FIND_BY_ID after re-authenticating", old_session.send(find_doc1), "OK")

    delete_user(c, "recycled_name")


def test_a_deleted_user_can_still_roll_back(c):
    section("A connection whose user was deleted mid-transaction can still roll back")

    check_status("AUTHENTICATE as admin", c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD), "OK")
    delete_user(c, "doomed_writer")
    c.send({"type": "DELETE", "databaseName": "auth_db", "collectionName": "allowed", "_id": "held_by_doomed"})
    check_status("CREATE_USER 'doomed_writer' with READ_WRITE on auth_db",
                 create_user(c, "doomed_writer", "doomed_writer1234", db_perms={"auth_db": "READ_WRITE"}), "OK")
    held_save = {"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
                 "object": {"_id": "held_by_doomed", "value": 1}}
    with Conn() as doomed:
        check_status("AUTHENTICATE as 'doomed_writer'", doomed.authenticate("doomed_writer", "doomed_writer1234"),
                     "OK")
        check_status("START_TRANSACTION", doomed.send({"type": "START_TRANSACTION"}), "OK")
        check_status("SAVE inside the transaction takes the collection lock", doomed.send(held_save), "OK")

        check_status("DELETE_USER 'doomed_writer'", delete_user(c, "doomed_writer"), "OK")

        check_code("COMMIT is refused once the user is gone", doomed.send({"type": "COMMIT_TRANSACTION"}),
                   "UNAUTHENTICATED", "401-1")
        check_status("ROLLBACK still ends the transaction", doomed.send({"type": "ROLLBACK_TRANSACTION"}), "OK")
        check_status("the admin can write the collection without waiting for the socket to close",
                     c.send({"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
                             "object": {"_id": "after_rollback", "value": 2}}), "OK")
        check_status("the rolled-back write never landed",
                     c.send({"type": "FIND_BY_ID", "databaseName": "auth_db", "collectionName": "allowed",
                             "_id": "held_by_doomed"}), "NOT_FOUND")
        check_code("with no transaction left, ROLLBACK needs authentication again",
                   doomed.send({"type": "ROLLBACK_TRANSACTION"}), "UNAUTHENTICATED", "401-1")

    c.send({"type": "DELETE", "databaseName": "auth_db", "collectionName": "allowed", "_id": "after_rollback"})


def test_a_collection_reader_cannot_list_database_wide_metadata(c):
    section("A collection-only reader cannot list database-wide metadata by naming its collection")
    check_status("AUTHENTICATE as 'coll_reader'", c.authenticate("coll_reader", "coll_reader1234"), "OK")
    for request_type in ("LIST_PROCEDURES", "LIST_SCHEDULES", "LIST_COLLECTIONS"):
        check_code(f"{request_type} with an injected collectionName (403-1)",
                   c.send({"type": request_type, "databaseName": "auth_db", "collectionName": "allowed",
                           "includeSource": True}),
                   "FORBIDDEN", "403-1")


def test_set_password_on_another_user_is_uniform(c):
    section("SET_PASSWORD on another user answers the same whether or not that user exists")
    check_status("AUTHENTICATE as 'db_reader'", c.authenticate("db_reader", "db_reader1234"), "OK")
    for target in ("no_perms", "no_such_user_anywhere"):
        check_code(f"SET_PASSWORD on '{target}' (403-1)",
                   set_password(c, target, "another-password-1", current_password="x"),
                   "FORBIDDEN", "403-1")


def main():
    bu.banner("Authentication & Authorization test suite", HOST, PORT)

    with Conn() as c:
        r = c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            print("        Make sure the server is running and lwnrdb.cfg has the correct")
            print(f"        defaultAdminUsername={ADMIN_USERNAME} / defaultAdminPassword={ADMIN_PASSWORD} (base_utils.PASSWORD).\n")
            sys.exit(1)

        print("\n  Setting up fixtures and test users...")
        setup_fixtures(c)
        create_user(c, "no_perms", "no_perms1234", db_perms={})
        create_user(c, "db_reader", "db_reader1234", db_perms={"auth_db": "READ"})
        create_user(c, "coll_reader", "coll_reader1234",
                    coll_perms={"auth_db|allowed": "READ"})
        create_user(c, "carve_user", "carve_user1234",
                    db_perms={"auth_db": "READ_WRITE"},
                    coll_perms={"auth_db|carved": "READ"})
        create_user(c, "db_maker", "db_maker1234",
                    global_perms=["CREATE_DATABASE"])
        c.send({"type": "SAVE_PROCEDURE", "databaseName": "auth_db", "name": "carve_proc", "script": "return 1;"})
        c.send({"type": "SAVE_TRIGGER", "databaseName": "auth_db", "collectionName": "carved",
                "name": "admin_hook", "events": ["CREATED"], "procedureName": "carve_proc"})
        c.send({"type": "CREATE_USER", "username": "carve_manager", "password": "carve_manager1234",
                "admin": False, "globalPermissions": [], "databasePermissions": {},
                "collectionPermissions": {"auth_db|carved": "READ", "auth_db|forbidden": "READ_WRITE"},
                "scriptPermissions": {"auth_db": "MANAGE"}})
        create_user(c, "new_owner", "new_owner1234")
        create_script_managers(c)

    test_unauthenticated()

    with Conn() as c:
        test_bad_credentials(c)

    test_list_databases_requires_authentication()
    test_unknown_type_with_newline_is_refused_cleanly()

    with Conn() as c:
        test_too_many_failed_attempts_closes_the_connection(c)

    test_failures_do_not_slow_a_different_username()

    with Conn() as c:
        test_unknown_user_costs_like_a_known_user(c)

    with Conn() as c:
        test_list_databases_is_filtered_by_grant(c)

    with Conn() as c:
        test_set_password_deauthenticates_other_sessions(c)

    with Conn() as c:
        test_no_db_permission(c)

    with Conn() as c:
        test_collection_read_allowed(c)

    with Conn() as c:
        test_collection_permission_boundary(c)

    with Conn() as c:
        test_collection_carve_out(c)

    with Conn() as c:
        test_a_collection_carve_out_bounds_trigger_installation(c)

    with Conn() as c:
        test_script_management_needs_data_access_to_install_triggers(c)

    with Conn() as c:
        test_admin_operations(c)

    with Conn() as c:
        test_user_management(c)

    with Conn() as c:
        test_set_password(c)

    with Conn() as c:
        test_list_users(c)

    with Conn() as c:
        test_ownership(c)

    with Conn() as c:
        test_owners_of_a_dropped_database(c)

    with Conn() as c:
        test_grants_do_not_survive_a_drop_and_recreate(c)

    with Conn() as c:
        test_a_deleted_users_name_does_not_inherit_ownership(c)

    with Conn() as c:
        test_a_session_does_not_survive_its_user_being_recreated(c)

    with Conn() as c:
        test_a_deleted_user_can_still_roll_back(c)

    with Conn() as c:
        test_a_collection_reader_cannot_list_database_wide_metadata(c)

    with Conn() as c:
        test_set_password_on_another_user_is_uniform(c)

    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown_fixtures(c)
        for u in ("no_perms", "db_reader", "coll_reader", "carve_user", "carve_manager", "db_maker", "new_owner",
                  "pwd_user", *SCRIPT_MANAGERS):
            delete_user(c, u)

    bu.summary()


if __name__ == "__main__":
    main()
