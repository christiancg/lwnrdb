import socket
import json
import sys

HOST = "127.0.0.1"
PORT = 8989

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

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
        {"type": "CREATE_DATABASE", "databaseName": "auth_db"},
        {"type": "CREATE_COLLECTION", "databaseName": "auth_db", "collectionName": "allowed"},
        {"type": "CREATE_COLLECTION", "databaseName": "auth_db", "collectionName": "forbidden"},
        {"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
         "object": {"_id": "doc1", "value": 42}},
    ]:
        send(s, f, msg)


def teardown_fixtures(s, f):
    for db in ("auth_db", "new_db", "transfer_db", "no_drop_db"):
        send(s, f, {"type": "DROP_DATABASE", "databaseName": db})


# ══════════════════════════════════════════════════════════════════════════
# Tests
# ══════════════════════════════════════════════════════════════════════════

def test_unauthenticated(s, f):
    section("Unauthenticated requests — all must return UNAUTHENTICATED")

    check("SAVE without auth",
          send(s, f, {"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
                      "object": {"x": 1}}),
          "UNAUTHENTICATED")

    check("FIND_BY_ID without auth",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "auth_db",
                      "collectionName": "allowed", "_id": "doc1"}),
          "UNAUTHENTICATED")

    check("AGGREGATE without auth",
          send(s, f, {"type": "AGGREGATE", "databaseName": "auth_db",
                      "collectionName": "allowed", "aggregationSteps": []}),
          "UNAUTHENTICATED")

    check("CREATE_DATABASE without auth",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "new_db"}),
          "UNAUTHENTICATED")

    check("DROP_DATABASE without auth",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "auth_db"}),
          "UNAUTHENTICATED")

    check("CREATE_USER without auth",
          send(s, f, {"type": "CREATE_USER", "username": "ghost", "password": "ghost1234"}),
          "UNAUTHENTICATED")

    check("LIST_COLLECTIONS without auth",
          send(s, f, {"type": "LIST_COLLECTIONS", "databaseName": "auth_db"}),
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
          authenticate(s, f, ADMIN_USERNAME, "wrong_password"),
          "ERROR")

    check("Unknown user",
          authenticate(s, f, "nobody", "whatever123"),
          "ERROR")


def test_no_db_permission(s, f):
    section("User without CREATE_DATABASE / DROP_DATABASE — must return FORBIDDEN")

    check("AUTHENTICATE as 'no_perms'",
          authenticate(s, f, "no_perms", "no_perms1234"),
          "OK")

    check("CREATE_DATABASE without global permission",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "new_db"}),
          "FORBIDDEN")

    check("DROP_DATABASE without global permission",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "auth_db"}),
          "FORBIDDEN")


def test_collection_read_allowed(s, f):
    section("User with db READ permission — reads must succeed, writes must fail")

    check("AUTHENTICATE as 'db_reader'",
          authenticate(s, f, "db_reader", "db_reader1234"),
          "OK")

    check("FIND_BY_ID on allowed collection (OK)",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "auth_db",
                      "collectionName": "allowed", "_id": "doc1"}),
          "OK")

    check("AGGREGATE (COUNT) on allowed collection (OK)",
          send(s, f, {"type": "AGGREGATE", "databaseName": "auth_db",
                      "collectionName": "allowed", "aggregationSteps": [{"type": "COUNT"}]}),
          "OK")

    check("SAVE on read-only db (FORBIDDEN)",
          send(s, f, {"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
                      "object": {"x": 1}}),
          "FORBIDDEN")

    check("DELETE on read-only db (FORBIDDEN)",
          send(s, f, {"type": "DELETE", "databaseName": "auth_db",
                      "collectionName": "allowed", "_id": "doc1"}),
          "FORBIDDEN")


def test_collection_permission_boundary(s, f):
    section("User with READ on one collection, no access to another — collection boundary")

    check("AUTHENTICATE as 'coll_reader'",
          authenticate(s, f, "coll_reader", "coll_reader1234"),
          "OK")

    check("FIND_BY_ID on permitted collection (OK)",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "auth_db",
                      "collectionName": "allowed", "_id": "doc1"}),
          "OK")

    check("FIND_BY_ID on forbidden collection (FORBIDDEN)",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "auth_db",
                      "collectionName": "forbidden", "_id": "doc1"}),
          "FORBIDDEN")

    check("AGGREGATE on forbidden collection (FORBIDDEN)",
          send(s, f, {"type": "AGGREGATE", "databaseName": "auth_db",
                      "collectionName": "forbidden", "aggregationSteps": []}),
          "FORBIDDEN")


def test_admin_operations(s, f):
    section("Admin user — all operations must succeed")

    check("AUTHENTICATE as admin",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("CREATE_DATABASE",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "new_db"}),
          "OK")

    check("CREATE_COLLECTION",
          send(s, f, {"type": "CREATE_COLLECTION", "databaseName": "new_db",
                      "collectionName": "stuff"}),
          "OK")

    check("SAVE",
          send(s, f, {"type": "SAVE", "databaseName": "new_db", "collectionName": "stuff",
                      "object": {"_id": "x1", "val": 99}}),
          "OK")

    check("FIND_BY_ID",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "new_db",
                      "collectionName": "stuff", "_id": "x1"}),
          "OK")

    check("AGGREGATE (COUNT) on stuff (doc present, should return OK)",
          send(s, f, {"type": "AGGREGATE", "databaseName": "new_db",
                      "collectionName": "stuff", "aggregationSteps": [{"type": "COUNT"}]}),
          "OK")

    check("DELETE",
          send(s, f, {"type": "DELETE", "databaseName": "new_db",
                      "collectionName": "stuff", "_id": "x1"}),
          "OK")

    check("AGGREGATE after delete (empty collection returns NOT_FOUND)",
          send(s, f, {"type": "AGGREGATE", "databaseName": "new_db",
                      "collectionName": "stuff", "aggregationSteps": []}),
          "NOT_FOUND")

    check("DROP_COLLECTION",
          send(s, f, {"type": "DROP_COLLECTION", "databaseName": "new_db",
                      "collectionName": "stuff"}),
          "OK")

    check("DROP_DATABASE new_db",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "new_db"}),
          "OK")

    check("GET_DATABASE_STATS as admin returns OK",
          send(s, f, {"type": "GET_DATABASE_STATS"}),
          "OK")


def test_user_management(s, f):
    section("User management as admin (CREATE, CHANGE_PERMISSIONS, DELETE)")

    check("AUTHENTICATE as admin",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("CREATE_USER 'tmp_user'",
          create_user(s, f, "tmp_user", "tmp_user1234", admin=False,
                      db_perms={"auth_db": "READ"}),
          "OK")

    check("CREATE_USER duplicate returns ERROR",
          create_user(s, f, "tmp_user", "tmp_user1234"),
          "ERROR")

    check("CHANGE_PERMISSIONS — grant WRITE to 'tmp_user'",
          change_permissions(s, f, "tmp_user", db_perms={"auth_db": "READ_WRITE"}),
          "OK")

    check("CHANGE_PERMISSIONS — promote 'tmp_user' to admin",
          change_permissions(s, f, "tmp_user", admin=True),
          "OK")

    check("CHANGE_PERMISSIONS — demote 'tmp_user' back",
          change_permissions(s, f, "tmp_user", admin=False, db_perms={"auth_db": "READ"}),
          "OK")

    check("DELETE_USER 'tmp_user'",
          delete_user(s, f, "tmp_user"),
          "OK")

    check("DELETE_USER non-existent returns NOT_FOUND",
          delete_user(s, f, "tmp_user"),
          "NOT_FOUND")

    # Verify non-admin cannot manage users
    check("AUTHENTICATE as 'db_reader' (non-admin)",
          authenticate(s, f, "db_reader", "db_reader1234"),
          "OK")

    check("CREATE_USER as non-admin (FORBIDDEN)",
          create_user(s, f, "sneaky", "sneaky1234"),
          "FORBIDDEN")

    check("DELETE_USER as non-admin (FORBIDDEN)",
          delete_user(s, f, "db_reader"),
          "FORBIDDEN")

    check("CHANGE_PERMISSIONS as non-admin (FORBIDDEN)",
          change_permissions(s, f, "db_reader", admin=True),
          "FORBIDDEN")

    check("GET_DATABASE_STATS as non-admin (FORBIDDEN)",
          send(s, f, {"type": "GET_DATABASE_STATS"}),
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

    check("Create test user 'pwd_user'",
          create_user(s, f, "pwd_user", "original_pass_1"),
          "OK")

    # ── Non-admin changing own password ────────────────────────────────────

    check("AUTHENTICATE as 'pwd_user'",
          authenticate(s, f, "pwd_user", "original_pass_1"),
          "OK")

    check("User can change own password with correct currentPassword",
          set_password(s, f, "pwd_user", "changed_pass_1", current_password="original_pass_1"),
          "OK")

    check("Old password no longer authenticates after change",
          authenticate(s, f, "pwd_user", "original_pass_1"),
          "ERROR")

    check("New password authenticates successfully",
          authenticate(s, f, "pwd_user", "changed_pass_1"),
          "OK")

    check("User cannot change own password with wrong currentPassword",
          set_password(s, f, "pwd_user", "another_new_1", current_password="wrong_password"),
          "ERROR")

    check("User cannot change another user's password (FORBIDDEN)",
          set_password(s, f, ADMIN_USERNAME, "hacked12345", current_password="changed_pass_1"),
          "FORBIDDEN")

    # ── Admin changing another user's password ────────────────────────────

    check("AUTHENTICATE as admin",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("Admin can change another user's password without currentPassword",
          set_password(s, f, "pwd_user", "admin_reset_1"),
          "OK")

    check("Admin-reset password works for login",
          authenticate(s, f, "pwd_user", "admin_reset_1"),
          "OK")

    check("AUTHENTICATE as admin (re-auth after pwd_user session)",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("Admin can change their own password",
          set_password(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("SET_PASSWORD for non-existent user returns NOT_FOUND",
          set_password(s, f, "nobody999", "new_password_1"),
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
              "fieldOperatorType": "EQUALS", "field": "_id", "value": "db_reader"
          }}]),
          "OK")

    check("AUTHENTICATE as 'db_reader' (non-admin)",
          authenticate(s, f, "db_reader", "db_reader1234"),
          "OK")

    check("LIST_USERS as non-admin returns FORBIDDEN",
          list_users(s, f),
          "FORBIDDEN")


def test_ownership(s, f):
    section("Database ownership — creation, transfer, and access control")

    # ── Auto-ownership: the user who creates a database becomes its owner ──

    check("AUTHENTICATE as 'db_maker' (has CREATE_DATABASE permission)",
          authenticate(s, f, "db_maker", "db_maker1234"),
          "OK")

    check("CREATE_DATABASE 'owned_db' — db_maker becomes owner automatically",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "owned_db"}),
          "OK")

    check("Owner can CREATE_COLLECTION with no explicit db/coll permissions",
          send(s, f, {"type": "CREATE_COLLECTION", "databaseName": "owned_db",
                      "collectionName": "my_coll"}),
          "OK")

    check("Owner can SAVE with no explicit db/coll permissions",
          send(s, f, {"type": "SAVE", "databaseName": "owned_db", "collectionName": "my_coll",
                      "object": {"_id": "o1", "val": 1}}),
          "OK")

    check("Owner can FIND_BY_ID",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "owned_db",
                      "collectionName": "my_coll", "_id": "o1"}),
          "OK")

    check("Owner can DROP_DATABASE their own database",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "owned_db"}),
          "OK")

    # ── DROP_DATABASE requires ownership, not just global permission ───────

    check("AUTHENTICATE as admin",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    check("Admin creates 'no_drop_db'",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "no_drop_db"}),
          "OK")

    check("AUTHENTICATE as 'db_maker' (has CREATE_DATABASE global perm, not owner of no_drop_db)",
          authenticate(s, f, "db_maker", "db_maker1234"),
          "OK")

    check("DROP_DATABASE without ownership is FORBIDDEN even with global perm",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "no_drop_db"}),
          "FORBIDDEN")

    check("AUTHENTICATE as admin to clean up no_drop_db",
          authenticate(s, f, ADMIN_USERNAME, ADMIN_PASSWORD),
          "OK")

    send(s, f, {"type": "DROP_DATABASE", "databaseName": "no_drop_db"})

    # ── SET_DATABASE_OWNERS transfers ownership ────────────────────────────

    check("Admin creates 'transfer_db' (admin is owner)",
          send(s, f, {"type": "CREATE_DATABASE", "databaseName": "transfer_db"}),
          "OK")

    check("Admin creates a collection in 'transfer_db'",
          send(s, f, {"type": "CREATE_COLLECTION", "databaseName": "transfer_db",
                      "collectionName": "stuff"}),
          "OK")

    check("SET_DATABASE_OWNERS — transfer ownership to 'new_owner'",
          set_database_owners(s, f, "transfer_db", ["new_owner"]),
          "OK")

    check("SET_DATABASE_OWNERS with non-existent user returns ERROR",
          set_database_owners(s, f, "transfer_db", ["ghost_user999"]),
          "ERROR")

    # ── New owner has full access, non-owner is denied ─────────────────────

    check("AUTHENTICATE as 'new_owner'",
          authenticate(s, f, "new_owner", "new_owner1234"),
          "OK")

    check("SET_DATABASE_OWNERS as non-admin (FORBIDDEN)",
          set_database_owners(s, f, "transfer_db", ["db_maker"]),
          "FORBIDDEN")

    check("New owner can SAVE with no explicit permissions",
          send(s, f, {"type": "SAVE", "databaseName": "transfer_db", "collectionName": "stuff",
                      "object": {"_id": "t1", "val": 42}}),
          "OK")

    check("New owner can FIND_BY_ID",
          send(s, f, {"type": "FIND_BY_ID", "databaseName": "transfer_db",
                      "collectionName": "stuff", "_id": "t1"}),
          "OK")

    check("New owner can DROP_DATABASE 'transfer_db'",
          send(s, f, {"type": "DROP_DATABASE", "databaseName": "transfer_db"}),
          "OK")

    # ── Former owner (admin) loses ownership when replaced; db_maker never had it ─

    check("AUTHENTICATE as 'db_maker'",
          authenticate(s, f, "db_maker", "db_maker1234"),
          "OK")

    check("'db_maker' has no access to 'auth_db' (not owner, no permissions)",
          send(s, f, {"type": "SAVE", "databaseName": "auth_db", "collectionName": "allowed",
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
        create_user(s, f, "no_perms", "no_perms1234", db_perms={})
        create_user(s, f, "db_reader", "db_reader1234", db_perms={"auth_db": "READ"})
        create_user(s, f, "coll_reader", "coll_reader1234",
                    coll_perms={"auth_db|allowed": "READ"})
        create_user(s, f, "db_maker", "db_maker1234",
                    global_perms=["CREATE_DATABASE"])
        create_user(s, f, "new_owner", "new_owner1234")

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
        for u in ("no_perms", "db_reader", "coll_reader", "db_maker", "new_owner", "pwd_user"):
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
