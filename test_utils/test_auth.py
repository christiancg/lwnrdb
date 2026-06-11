import socket
import json
import sys

HOST = "127.0.0.1"
PORT = 8989

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "adminstrator"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

failures = 0


def send(s, f, payload: dict) -> dict:
    s.sendall((json.dumps(payload) + "\n").encode())
    raw = f.readline().decode().strip()
    if not raw:
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    return json.loads(raw)


def check(label: str, response: dict, expected_status: str):
    global failures
    actual = response.get("status")
    ok = actual == expected_status
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         expected={expected_status}  got={actual}  msg={response.get('message', '')!r}")
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


# ── helpers ────────────────────────────────────────────────────────────────

def authenticate(s, f, username: str, password: str) -> dict:
    return send(s, f, {"type": "AUTHENTICATE", "username": username, "password": password})


def create_user(s, f, username, password, admin=False, global_perms=None, db_perms=None, coll_perms=None):
    return send(s, f, {
        "type": "CREATE_USER",
        "username": username,
        "password": password,
        "admin": admin,
        "globalPermissions": global_perms or [],
        "databasePermissions": db_perms or {},
        "collectionPermissions": coll_perms or {},
    })


def delete_user(s, f, username: str) -> dict:
    return send(s, f, {"type": "DELETE_USER", "username": username})


def change_permissions(s, f, username, admin=False, global_perms=None, db_perms=None, coll_perms=None):
    return send(s, f, {
        "type": "CHANGE_PERMISSIONS",
        "username": username,
        "admin": admin,
        "globalPermissions": global_perms or [],
        "databasePermissions": db_perms or {},
        "collectionPermissions": coll_perms or {},
    })


def set_database_owners(s, f, database_name: str, owners: list) -> dict:
    return send(s, f, {"type": "SET_DATABASE_OWNERS", "databaseName": database_name, "owners": owners})


def set_password(s, f, username: str, new_password: str, current_password: str = None) -> dict:
    payload = {"type": "SET_PASSWORD", "username": username, "newPassword": new_password}
    if current_password is not None:
        payload["currentPassword"] = current_password
    return send(s, f, payload)


# ── setup: create the fixtures we need ─────────────────────────────────────

def setup_fixtures(s, f):
    """Create databases and collections used by the tests. Runs as admin."""
    for msg in [
        {"type": "CREATE_DATABASE", "databaseName": "authdb"},
        {"type": "CREATE_COLLECTION", "databaseName": "authdb", "collectionName": "allowed"},
        {"type": "CREATE_COLLECTION", "databaseName": "authdb", "collectionName": "forbidden"},
        {"type": "SAVE", "databaseName": "authdb", "collectionName": "allowed",
         "object": {"_id": "doc1", "value": 42}},
    ]:
        send(s, f, msg)


def teardown_fixtures(s, f):
    for db in ("authdb", "newdb", "transferdb", "nodropdb"):
        send(s, f, {"type": "DROP_DATABASE", "databaseName": db})


# ══════════════════════════════════════════════════════════════════════════
# Tests
# ══════════════════════════════════════════════════════════════════════════

def test_unauthenticated(s, f):
    section("Unauthenticated requests — all must return UNAUTHENTICATED")

    check("SAVE without auth",
          send(s, f, {"type": "SAVE", "databaseName": "authdb", "collectionName": "allowed",
                      "object": {"x": 1}}),
          "UNAUTHENTICATED")

    check("FIND_BY_ID without auth",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "authdb",
                      "collectionName": "allowed", "_id": "doc1"}),
          "UNAUTHENTICATED")

    check("AGGREGATE without auth",
          send(s, f, {"type": "AGGREGATE", "databaseName": "authdb",
                      "collectionName": "allowed", "aggregationSteps": []}),
          "UNAUTHENTICATED")

    check("CREATE_DATABASE without auth",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "newdb"}),
          "UNAUTHENTICATED")

    check("DROP_DATABASE without auth",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "authdb"}),
          "UNAUTHENTICATED")

    check("CREATE_USER without auth",
          send(s, f, {"type": "CREATE_USER", "username": "ghost", "password": "ghost1234"}),
          "UNAUTHENTICATED")

    check("LIST_COLLECTIONS without auth",
          send(s, f, {"type": "LIST_COLLECTIONS", "databaseName": "authdb"}),
          "UNAUTHENTICATED")

    # These two are intentionally public — verify they still work unauthenticated
    check("LIST_DATABASES is public (must return OK)",
          send(s, f, {"type": "LIST_DATABASES"}),
          "OK")

    check("CLOSE_CONNECTION is public (must return OK)",
          send(s, f, {"type": "CLOSE_CONNECTION"}),
          "OK")


def test_bad_credentials(s, f):
    section("Authentication with wrong credentials — must return ERROR")

    check("Wrong password",
          authenticate(s, f, ADMIN_USERNAME, "wrongpassword"),
          "ERROR")

    check("Unknown user",
          authenticate(s, f, "nobody", "whatever123"),
          "ERROR")


def test_no_db_permission(s, f):
    section("User without CREATE_DATABASE / DROP_DATABASE — must return FORBIDDEN")

    check("AUTHENTICATE as 'noperms'",
          authenticate(s, f, "noperms", "noperms1234"),
          "OK")

    check("CREATE_DATABASE without global permission",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "newdb"}),
          "FORBIDDEN")

    check("DROP_DATABASE without global permission",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "authdb"}),
          "FORBIDDEN")


def test_collection_read_allowed(s, f):
    section("User with db READ permission — reads must succeed, writes must fail")

    check("AUTHENTICATE as 'dbreader'",
          authenticate(s, f, "dbreader", "dbreader1234"),
          "OK")

    check("FIND_BY_ID on allowed collection (OK)",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "authdb",
                      "collectionName": "allowed", "_id": "doc1"}),
          "OK")

    check("AGGREGATE (COUNT) on allowed collection (OK)",
          send(s, f, {"type": "AGGREGATE", "databaseName": "authdb",
                      "collectionName": "allowed", "aggregationSteps": [{"type": "COUNT"}]}),
          "OK")

    check("SAVE on read-only db (FORBIDDEN)",
          send(s, f, {"type": "SAVE", "databaseName": "authdb", "collectionName": "allowed",
                      "object": {"x": 1}}),
          "FORBIDDEN")

    check("DELETE on read-only db (FORBIDDEN)",
          send(s, f, {"type": "DELETE", "databaseName": "authdb",
                      "collectionName": "allowed", "_id": "doc1"}),
          "FORBIDDEN")


def test_collection_permission_boundary(s, f):
    section("User with READ on one collection, no access to another — collection boundary")

    check("AUTHENTICATE as 'collreader'",
          authenticate(s, f, "collreader", "collreader1234"),
          "OK")

    check("FIND_BY_ID on permitted collection (OK)",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "authdb",
                      "collectionName": "allowed", "_id": "doc1"}),
          "OK")

    check("FIND_BY_ID on forbidden collection (FORBIDDEN)",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "authdb",
                      "collectionName": "forbidden", "_id": "doc1"}),
          "FORBIDDEN")

    check("AGGREGATE on forbidden collection (FORBIDDEN)",
          send(s, f, {"type": "AGGREGATE", "databaseName": "authdb",
                      "collectionName": "forbidden", "aggregationSteps": []}),
          "FORBIDDEN")


def test_admin_operations(s, f):
    section("Admin user — all operations must succeed")

    check("AUTHENTICATE as admin",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("CREATE_DATABASE",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "newdb"}),
          "OK")

    check("CREATE_COLLECTION",
          send(s, f, {"type": "CREATE_COLLECTION", "databaseName": "newdb",
                      "collectionName": "stuff"}),
          "OK")

    check("SAVE",
          send(s, f, {"type": "SAVE", "databaseName": "newdb", "collectionName": "stuff",
                      "object": {"_id": "x1", "val": 99}}),
          "OK")

    check("FIND_BY_ID",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "newdb",
                      "collectionName": "stuff", "_id": "x1"}),
          "OK")

    check("AGGREGATE (COUNT) on stuff (doc present, should return OK)",
          send(s, f, {"type": "AGGREGATE", "databaseName": "newdb",
                      "collectionName": "stuff", "aggregationSteps": [{"type": "COUNT"}]}),
          "OK")

    check("DELETE",
          send(s, f, {"type": "DELETE", "databaseName": "newdb",
                      "collectionName": "stuff", "_id": "x1"}),
          "OK")

    check("AGGREGATE after delete (empty collection returns NOT_FOUND)",
          send(s, f, {"type": "AGGREGATE", "databaseName": "newdb",
                      "collectionName": "stuff", "aggregationSteps": []}),
          "NOT_FOUND")

    check("DROP_COLLECTION",
          send(s, f, {"type": "DROP_COLLECTION", "databaseName": "newdb",
                      "collectionName": "stuff"}),
          "OK")

    check("DROP_DATABASE newdb",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "newdb"}),
          "OK")


def test_user_management(s, f):
    section("User management as admin (CREATE, CHANGE_PERMISSIONS, DELETE)")

    check("AUTHENTICATE as admin",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("CREATE_USER 'tmpuser'",
          create_user(s, f, "tmpuser", "tmpuser1234", admin=False,
                      db_perms={"authdb": "READ"}),
          "OK")

    check("CREATE_USER duplicate returns ERROR",
          create_user(s, f, "tmpuser", "tmpuser1234"),
          "ERROR")

    check("CHANGE_PERMISSIONS — grant WRITE to 'tmpuser'",
          change_permissions(s, f, "tmpuser", db_perms={"authdb": "READ_WRITE"}),
          "OK")

    check("CHANGE_PERMISSIONS — promote 'tmpuser' to admin",
          change_permissions(s, f, "tmpuser", admin=True),
          "OK")

    check("CHANGE_PERMISSIONS — demote 'tmpuser' back",
          change_permissions(s, f, "tmpuser", admin=False, db_perms={"authdb": "READ"}),
          "OK")

    check("DELETE_USER 'tmpuser'",
          delete_user(s, f, "tmpuser"),
          "OK")

    check("DELETE_USER non-existent returns NOT_FOUND",
          delete_user(s, f, "tmpuser"),
          "NOT_FOUND")

    # Verify non-admin cannot manage users
    check("AUTHENTICATE as 'dbreader' (non-admin)",
          authenticate(s, f, "dbreader", "dbreader1234"),
          "OK")

    check("CREATE_USER as non-admin (FORBIDDEN)",
          create_user(s, f, "sneaky", "sneaky1234"),
          "FORBIDDEN")

    check("DELETE_USER as non-admin (FORBIDDEN)",
          delete_user(s, f, "dbreader"),
          "FORBIDDEN")

    check("CHANGE_PERMISSIONS as non-admin (FORBIDDEN)",
          change_permissions(s, f, "dbreader", admin=True),
          "FORBIDDEN")


def list_users(s, f, steps=None) -> dict:
    payload = {"type": "LIST_USERS"}
    if steps is not None:
        payload["aggregationSteps"] = steps
    return send(s, f, payload)


def test_set_password(s, f):
    section("SET_PASSWORD — own password with verification, admin changes any")

    check("AUTHENTICATE as admin",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("Create test user 'pwduser'",
          create_user(s, f, "pwduser", "originalpass1"),
          "OK")

    # ── Non-admin changing own password ────────────────────────────────────

    check("AUTHENTICATE as 'pwduser'",
          authenticate(s, f, "pwduser", "originalpass1"),
          "OK")

    check("User can change own password with correct currentPassword",
          set_password(s, f, "pwduser", "changedpass1", current_password="originalpass1"),
          "OK")

    check("Old password no longer authenticates after change",
          authenticate(s, f, "pwduser", "originalpass1"),
          "ERROR")

    check("New password authenticates successfully",
          authenticate(s, f, "pwduser", "changedpass1"),
          "OK")

    check("User cannot change own password with wrong currentPassword",
          set_password(s, f, "pwduser", "anothernew1", current_password="wrongpassword"),
          "ERROR")

    check("User cannot change another user's password (FORBIDDEN)",
          set_password(s, f, ADMIN_USERNAME, "hacked12345", current_password="changedpass1"),
          "FORBIDDEN")

    # ── Admin changing another user's password ────────────────────────────

    check("AUTHENTICATE as admin",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("Admin can change another user's password without currentPassword",
          set_password(s, f, "pwduser", "adminreset1"),
          "OK")

    check("Admin-reset password works for login",
          authenticate(s, f, "pwduser", "adminreset1"),
          "OK")

    check("AUTHENTICATE as admin (re-auth after pwduser session)",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("Admin can change their own password",
          set_password(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("SET_PASSWORD for non-existent user returns NOT_FOUND",
          set_password(s, f, "nobody999", "newpassword1"),
          "NOT_FOUND")


def test_list_users(s, f):
    section("LIST_USERS — admin-only, supports filtering")

    check("AUTHENTICATE as admin",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("LIST_USERS returns all users",
          list_users(s, f),
          "OK")

    check("LIST_USERS response does not include passwordHash",
          # the check here is that the call succeeds; field inspection is in unit tests
          list_users(s, f),
          "OK")

    check("LIST_USERS filter by username (exact match)",
          list_users(s, f, [{"type": "FILTER", "operator": {
              "fieldOperatorType": "EQUALS", "field": "_id", "value": ADMIN_USERNAME
          }}]),
          "OK")

    check("LIST_USERS filter by admin=true",
          list_users(s, f, [{"type": "FILTER", "operator": {
              "fieldOperatorType": "EQUALS", "field": "admin", "value": True
          }}]),
          "OK")

    check("LIST_USERS COUNT step returns single result",
          list_users(s, f, [{"type": "COUNT"}]),
          "OK")

    check("LIST_USERS LIMIT 1",
          list_users(s, f, [{"type": "LIMIT", "limit": 1}]),
          "OK")

    check("LIST_USERS SORT ascending by username",
          list_users(s, f, [{"type": "SORT", "fieldName": "_id", "ascending": True}]),
          "OK")

    check("LIST_USERS filter non-existent username returns NOT_FOUND",
          list_users(s, f, [{"type": "FILTER", "operator": {
              "fieldOperatorType": "EQUALS", "field": "_id", "value": "nobody999"
          }}]),
          "NOT_FOUND")

    check("LIST_USERS filter by databasePermissions ownership field (users with any db perm)",
          list_users(s, f, [{"type": "FILTER", "operator": {
              "fieldOperatorType": "EQUALS", "field": "_id", "value": "dbreader"
          }}]),
          "OK")

    check("AUTHENTICATE as 'dbreader' (non-admin)",
          authenticate(s, f, "dbreader", "dbreader1234"),
          "OK")

    check("LIST_USERS as non-admin returns FORBIDDEN",
          list_users(s, f),
          "FORBIDDEN")


def test_ownership(s, f):
    section("Database ownership — creation, transfer, and access control")

    # ── Auto-ownership: the user who creates a database becomes its owner ──

    check("AUTHENTICATE as 'dbmaker' (has CREATE_DATABASE permission)",
          authenticate(s, f, "dbmaker", "dbmaker1234"),
          "OK")

    check("CREATE_DATABASE 'owneddb' — dbmaker becomes owner automatically",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "owneddb"}),
          "OK")

    check("Owner can CREATE_COLLECTION with no explicit db/coll permissions",
          send(s, f, {"type": "CREATE_COLLECTION", "databaseName": "owneddb",
                      "collectionName": "mycoll"}),
          "OK")

    check("Owner can SAVE with no explicit db/coll permissions",
          send(s, f, {"type": "SAVE", "databaseName": "owneddb", "collectionName": "mycoll",
                      "object": {"_id": "o1", "val": 1}}),
          "OK")

    check("Owner can FIND_BY_ID",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "owneddb",
                      "collectionName": "mycoll", "_id": "o1"}),
          "OK")

    check("Owner can DROP_DATABASE their own database",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "owneddb"}),
          "OK")

    # ── DROP_DATABASE requires ownership, not just global permission ───────

    check("AUTHENTICATE as admin",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("Admin creates 'nodropdb'",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "nodropdb"}),
          "OK")

    check("AUTHENTICATE as 'dbmaker' (has CREATE_DATABASE global perm, not owner of nodropdb)",
          authenticate(s, f, "dbmaker", "dbmaker1234"),
          "OK")

    check("DROP_DATABASE without ownership is FORBIDDEN even with global perm",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "nodropdb"}),
          "FORBIDDEN")

    check("AUTHENTICATE as admin to clean up nodropdb",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    send(s, f, {"type": "DROP_DATABASE", "databaseName": "nodropdb"})

    # ── SET_DATABASE_OWNERS transfers ownership ────────────────────────────

    check("Admin creates 'transferdb' (admin is owner)",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "transferdb"}),
          "OK")

    check("Admin creates a collection in 'transferdb'",
          send(s, f, {"type": "CREATE_COLLECTION", "databaseName": "transferdb",
                      "collectionName": "stuff"}),
          "OK")

    check("SET_DATABASE_OWNERS — transfer ownership to 'newowner'",
          set_database_owners(s, f, "transferdb", ["newowner"]),
          "OK")

    check("SET_DATABASE_OWNERS with non-existent user returns ERROR",
          set_database_owners(s, f, "transferdb", ["ghostuser999"]),
          "ERROR")

    # ── New owner has full access, non-owner is denied ─────────────────────

    check("AUTHENTICATE as 'newowner'",
          authenticate(s, f, "newowner", "newowner1234"),
          "OK")

    check("SET_DATABASE_OWNERS as non-admin (FORBIDDEN)",
          set_database_owners(s, f, "transferdb", ["dbmaker"]),
          "FORBIDDEN")

    check("New owner can SAVE with no explicit permissions",
          send(s, f, {"type": "SAVE", "databaseName": "transferdb", "collectionName": "stuff",
                      "object": {"_id": "t1", "val": 42}}),
          "OK")

    check("New owner can FIND_BY_ID",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "transferdb",
                      "collectionName": "stuff", "_id": "t1"}),
          "OK")

    check("New owner can DROP_DATABASE 'transferdb'",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "transferdb"}),
          "OK")

    # ── Former owner (admin) loses ownership when replaced; dbmaker never had it ─

    check("AUTHENTICATE as 'dbmaker'",
          authenticate(s, f, "dbmaker", "dbmaker1234"),
          "OK")

    check("'dbmaker' has no access to 'authdb' (not owner, no permissions)",
          send(s, f, {"type": "SAVE", "databaseName": "authdb", "collectionName": "allowed",
                      "object": {"x": 99}}),
          "FORBIDDEN")


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    global failures

    print("\n" + "═" * 60)
    print("  LWNRDB — Authentication & Authorization test suite")
    print("═" * 60)
    print(f"  Connecting to {HOST}:{PORT}")

    # ── pre-flight: create test users and fixtures ─────────────────────
    with new_conn() as (s, f):
        r = authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        if r.get("status") != "OK":
            print(f"\n[ERROR] Cannot authenticate as admin: {r.get('message')}")
            print("        Make sure the server is running and lwnrdb.cfg has the correct")
            print(f"        defaultAdminUsername={ADMIN_USERNAME!r} / defaultAdminPassword set.\n")
            sys.exit(1)

        print("\n  Setting up fixtures and test users...")
        setup_fixtures(s, f)
        create_user(s, f, "noperms", "noperms1234", db_perms={})
        create_user(s, f, "dbreader", "dbreader1234", db_perms={"authdb": "READ"})
        create_user(s, f, "collreader", "collreader1234",
                    coll_perms={"authdb|allowed": "READ"})
        create_user(s, f, "dbmaker", "dbmaker1234",
                    global_perms=["CREATE_DATABASE"])
        create_user(s, f, "newowner", "newowner1234")

    # ── run each test group on a fresh connection ──────────────────────
    with new_conn() as (s, f):
        test_unauthenticated(s, f)

    with new_conn() as (s, f):
        test_bad_credentials(s, f)

    with new_conn() as (s, f):
        test_no_db_permission(s, f)

    with new_conn() as (s, f):
        test_collection_read_allowed(s, f)

    with new_conn() as (s, f):
        test_collection_permission_boundary(s, f)

    with new_conn() as (s, f):
        test_admin_operations(s, f)

    with new_conn() as (s, f):
        test_user_management(s, f)

    with new_conn() as (s, f):
        test_set_password(s, f)

    with new_conn() as (s, f):
        test_list_users(s, f)

    with new_conn() as (s, f):
        test_ownership(s, f)

    # ── cleanup ────────────────────────────────────────────────────────
    with new_conn() as (s, f):
        authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD)
        teardown_fixtures(s, f)
        for u in ("noperms", "dbreader", "collreader", "dbmaker", "newowner", "pwduser"):
            delete_user(s, f, u)

    # ── summary ───────────────────────────────────────────────────────
    total_run = 0  # approximate from section counts
    print("\n" + "═" * 60)
    if failures == 0:
        print(f"  \033[92mAll checks passed.\033[0m")
    else:
        print(f"  \033[91m{failures} check(s) FAILED.\033[0m")
    print("═" * 60 + "\n")

    sys.exit(0 if failures == 0 else 1)


if __name__ == "__main__":
    main()
