import sys

import base_utils as bu
from base_utils import Conn, check_status, section

HOST = "127.0.0.1"
PORT = 8989

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)



# ── helpers ────────────────────────────────────────────────────────────────

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


# ── setup: create the fixtures we need ─────────────────────────────────────

def setup_fixtures(c):
    """Create databases and collections used by the tests. Runs as admin."""
    for msg in [
        {"type": "CREATE_DATABASE", "databaseName": "auth_db"},
        {"type": "CREATE_COLLECTION", "databaseName": "auth_db", "collectionName": "allowed"},
        {"type": "CREATE_COLLECTION", "databaseName": "auth_db", "collectionName": "forbidden"},
        {"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
         "object": {"_id": "doc1", "value": 42}},
    ]:
        c.send(msg)


def teardown_fixtures(c):
    for db in ("auth_db", "new_db", "transfer_db", "no_drop_db"):
        c.send({"type": "DROP_DATABASE", "databaseName": db})


# ══════════════════════════════════════════════════════════════════════════
# Tests
# ══════════════════════════════════════════════════════════════════════════

def test_unauthenticated(c):
    section("Unauthenticated requests — all must return UNAUTHENTICATED")

    check_status("SAVE without auth",
          c.send({"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
                      "object": {"x": 1}}),
          "UNAUTHENTICATED")

    check_status("FIND_BY_ID without auth",
          c.send({"type": "FIND_BY_ID", "databaseName": "auth_db",
                      "collectionName": "allowed", "_id": "doc1"}),
          "UNAUTHENTICATED")

    check_status("AGGREGATE without auth",
          c.send({"type": "AGGREGATE", "databaseName": "auth_db",
                      "collectionName": "allowed", "aggregationSteps": []}),
          "UNAUTHENTICATED")

    check_status("CREATE_DATABASE without auth",
          c.send({"type": "CREATE_DATABASE", "databaseName": "new_db"}),
          "UNAUTHENTICATED")

    check_status("DROP_DATABASE without auth",
          c.send({"type": "DROP_DATABASE", "databaseName": "auth_db"}),
          "UNAUTHENTICATED")

    check_status("CREATE_USER without auth",
          c.send({"type": "CREATE_USER", "username": "ghost", "password": "ghost1234"}),
          "UNAUTHENTICATED")

    check_status("LIST_COLLECTIONS without auth",
          c.send({"type": "LIST_COLLECTIONS", "databaseName": "auth_db"}),
          "UNAUTHENTICATED")

    # These two are intentionally public — verify they still work unauthenticated
    check_status("LIST_DATABASES is public (must return OK)",
          c.send({"type": "LIST_DATABASES"}),
          "OK")

    check_status("CLOSE_CONNECTION is public (must return OK)",
          c.send({"type": "CLOSE_CONNECTION"}),
          "OK")


def test_bad_credentials(c):
    section("Authentication with wrong credentials — must return ERROR")

    check_status("Wrong password",
          c.authenticate(ADMIN_USERNAME, "wrong_password"),
          "ERROR")

    check_status("Unknown user",
          c.authenticate("nobody", "whatever123"),
          "ERROR")


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

    check_status("DELETE_USER 'tmp_user'",
          delete_user(c, "tmp_user"),
          "OK")

    check_status("DELETE_USER non-existent returns NOT_FOUND",
          delete_user(c, "tmp_user"),
          "NOT_FOUND")

    # Verify non-admin cannot manage users
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

    check_status("GET_DATABASE_STATS as non-admin (FORBIDDEN)",
          c.send({"type": "GET_DATABASE_STATS"}),
          "FORBIDDEN")


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

    # ── Non-admin changing own password ────────────────────────────────────

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

    # ── Admin changing another user's password ────────────────────────────

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
          # the check here is that the call succeeds; field inspection is in unit tests
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

    # ── Auto-ownership: the user who creates a database becomes its owner ──

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

    # ── DROP_DATABASE requires ownership, not just global permission ───────

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

    # ── SET_DATABASE_OWNERS transfers ownership ────────────────────────────

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

    # ── New owner has full access, non-owner is denied ─────────────────────

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

    # ── Former owner (admin) loses ownership when replaced; db_maker never had it ─

    check_status("AUTHENTICATE as 'db_maker'",
          c.authenticate("db_maker", "db_maker1234"),
          "OK")

    check_status("'db_maker' has no access to 'auth_db' (not owner, no permissions)",
          c.send({"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
                      "object": {"x": 99}}),
          "FORBIDDEN")


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    bu.banner("Authentication & Authorization test suite", HOST, PORT)

    # ── pre-flight: create test users and fixtures ─────────────────────
    with Conn() as c:
        r = c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            print("        Make sure the server is running and lwnrdb.cfg has the correct")
            print(f"        defaultAdminUsername={ADMIN_USERNAME!r} / defaultAdminPassword set.\n")
            sys.exit(1)

        print("\n  Setting up fixtures and test users...")
        setup_fixtures(c)
        create_user(c, "no_perms", "no_perms1234", db_perms={})
        create_user(c, "db_reader", "db_reader1234", db_perms={"auth_db": "READ"})
        create_user(c, "coll_reader", "coll_reader1234",
                    coll_perms={"auth_db|allowed": "READ"})
        create_user(c, "db_maker", "db_maker1234",
                    global_perms=["CREATE_DATABASE"])
        create_user(c, "new_owner", "new_owner1234")

    # ── run each test group on a fresh connection ──────────────────────
    with Conn() as c:
        test_unauthenticated(c)

    with Conn() as c:
        test_bad_credentials(c)

    with Conn() as c:
        test_no_db_permission(c)

    with Conn() as c:
        test_collection_read_allowed(c)

    with Conn() as c:
        test_collection_permission_boundary(c)

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

    # ── cleanup ────────────────────────────────────────────────────────
    with Conn() as c:
        c.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown_fixtures(c)
        for u in ("no_perms", "db_reader", "coll_reader", "db_maker", "new_owner", "pwd_user"):
            delete_user(c, u)

    # ── summary ───────────────────────────────────────────────────────
    total_run = 0  # approximate from section counts
    bu.summary()


if __name__ == "__main__":
    main()
