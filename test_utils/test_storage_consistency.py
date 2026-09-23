"""End-to-end regression tests for the storage-layer consistency fixes.

This suite is **self-contained**: it starts its own LWNRDB instance on a dedicated port and
working directory, because both features it pins need something the shared CI server cannot
give them — a deliberately tiny `maxPageSize` so a small collection really spans several page
files, and a restart of that same data directory partway through.

What is covered:

  * page-occupancy metadata is persisted for user collections, and a full scan after a restart
    still returns every document. Before the fix, `admin/pages/<db>_<coll>` stayed empty for
    user collections, so a restart left the page list empty; the first write after that restart
    repopulated exactly one page entry, and `Cache.streamCollectionFromDisk` then treated that
    partial list as authoritative and silently skipped every other page. A 40-document
    collection answered a full scan with 13 documents and status OK.

  * `maxPageSize` is still enforced after a restart, which is the same defect seen from the
    write side: with no page list, first-fit had nothing to fit against.

  * dropping a database while other connections are writing to it does not strand a collection
    lock. `ResourceLocking.removeLock` used to evict a lock that was still held, so the holder's
    release became a silent no-op: every thread already queued on that lock blocked for the
    life of the process, and the next caller minted a fresh lock object for the same key and so
    lost mutual exclusion entirely. This case is inherently timing-dependent — it can miss the
    window, but it can never fail when the engine is correct, so it is safe in CI. The
    deterministic proof lives in ResourceLockingTest.

  * page-occupancy metadata keeps up with an admin row that grows on update. The update branch of
    `AdminOperationHelper.writeAdminEntry` emitted no `UPDATED` delta while its insert and erase
    branches always did, so the recorded size in `admin/pages/admin_<coll>` drifted below the real
    page file, first-fit kept packing rows into a page it believed still had room, and the page
    grew past `maxPageSize`. Nothing recomputes page sizes except a restart.

  * a PK-index self-heal never erases a write that committed while it was reading.
    `PkIndexStore.readWholePkIndexFile` rewrote `{coll}-pk.idx` in full from a snapshot
    taken before it released the read lock, so an append that landed in between was dropped — and
    the loss is permanent, because `REINDEX` rebuilds field indexes only and nothing rebuilds the
    PK index from the document store. The last phase restarts with the cache disabled so that every
    read really reaches the file rather than the one cached copy. Like the lock case above, this is
    timing-dependent: it can miss the window, but it can never fail when the engine is correct. The
    deterministic proof lives in FileSystemPkIndexTest.
"""

import json
import os
import sys
import tempfile
import threading
import time

import base_utils as bu
from base_utils import check, check_code, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("STORAGE_CONSISTENCY_TEST_PORT", "8996"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "storage_db"
COLL = "docs"
LOCK_DB = "lock_db"
RECREATE_DB = "recreate_db"
SCHEMA_COLL = "schema_guarded"
LOCK_COLL = "items"
DIRTY_COLL = "dirty_index_docs"
HEAL_COLL = "heal_race_docs"

JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"
REPO_ROOT = bu.REPO_ROOT

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)

# Small enough that a few dozen small documents span several pages, so a partial page list is
# observable at all. maxEntrySize must stay strictly below it.
MAX_PAGE_SIZE = "4kb"
MAX_ENTRY_SIZE = "1kb"
SEEDED_DOCS = 40
PAD = "p" * 300

# -1 disables the user cache outright, which is what makes every read in the last phase reach
# the PK index file instead of the single copy the first read would otherwise cache.
CACHE_DISABLED = "-1"

HEAL_SEEDED = 1000
RACE_SECONDS = 3.0

PERM_USER_PREFIX = "page_grow_user_"
PERM_FILL_PREFIX = "page_fill_user_"
PERM_USERS = 6
PERM_ROUNDS = 10


class Conn(bu.Conn):
    def save(self, doc, db=DB, coll=COLL) -> dict:
        return self.send({"type": "SAVE", "databaseName": db, "collectionName": coll, "object": doc})

    def count_via_pk(self, db=DB, coll=COLL) -> int:
        response = self.send({"type": "AGGREGATE", "databaseName": db, "collectionName": coll,
                              "aggregationSteps": [{"type": "COUNT"}]})
        return ((response.get("results") or [{}])[0]).get("count")

    def count_via_scan(self, db=DB, coll=COLL) -> int:
        response = self.send({"type": "AGGREGATE", "databaseName": db, "collectionName": coll,
                              "aggregationSteps": [
                                  {"type": "FILTER",
                                   "operator": {"fieldOperatorType": "NOT_EQUALS", "field": "pad",
                                                "value": "__no_document_has_this__"}},
                                  {"type": "COUNT"}]})
        return ((response.get("results") or [{}])[0]).get("count")


def admin_conn() -> Conn:
    conn = Conn()
    conn.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
    return conn


def write_config(work_dir: str, max_memory: str = "256mb"):
    cfg = (
        f"port={PORT}\n"
        "filePath=db\n"
        "logPath=logs\n"
        f"maxMemory={max_memory}\n"
        f"maxPageSize={MAX_PAGE_SIZE}\n"
        f"maxEntrySize={MAX_ENTRY_SIZE}\n"
        f"defaultAdminUsername={ADMIN_USERNAME}\n"
        f"defaultAdminPassword={ADMIN_PASSWORD}\n"
    )
    with open(os.path.join(work_dir, "lwnrdb.cfg"), "w") as fp:
        fp.write(cfg)


def page_files(work_dir: str, db=DB, coll=COLL):
    folder = os.path.join(work_dir, "db", db, coll)
    if not os.path.isdir(folder):
        return []
    return sorted(f for f in os.listdir(folder) if f.endswith(".dat"))


def page_metadata_bytes(work_dir: str, db=DB, coll=COLL) -> int:
    folder = os.path.join(work_dir, "db", "admin", "pages", f"{db}_{coll}")
    if not os.path.isdir(folder):
        return 0
    return sum(os.path.getsize(os.path.join(folder, f))
               for f in os.listdir(folder) if f.endswith(".dat"))


def recorded_pages(work_dir: str, db=DB, coll=COLL) -> dict:
    """What the engine believes each page of `db|coll` holds, keyed by page number."""
    folder = os.path.join(work_dir, "db", "admin", "pages", f"{db}_{coll}")
    # The folder name is ambiguous (names admit "_"), so rows are identified by their own id.
    prefix = f"{db}|{coll}|"
    rows = {}
    if not os.path.isdir(folder):
        return rows
    for name in sorted(f for f in os.listdir(folder) if f.endswith(".dat")):
        with open(os.path.join(folder, name), "r", encoding="utf-8", errors="replace") as fp:
            for line in fp:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if str(row.get("_id", "")).startswith(prefix):
                    rows[int(row["page"])] = row
    return rows


def actual_pages(work_dir: str, db=DB, coll=COLL) -> dict:
    folder = os.path.join(work_dir, "db", db, coll)
    pages = {}
    for name in page_files(work_dir, db, coll):
        with open(os.path.join(folder, name), "rb") as fp:
            content = fp.read()
        pages[int(name[len(coll) + 1:-len(".dat")])] = (len(content), content.count(b"\n"))
    return pages


def pk_index_file(work_dir: str, db=DB, coll=COLL) -> str:
    return os.path.join(work_dir, "db", db, coll, f"{coll}-pk.idx")


def unescape_index_token(token: str) -> str:
    if "\\" not in token:
        return token
    out = []
    i = 0
    while i < len(token):
        if token[i] != "\\" or i + 1 >= len(token):
            out.append(token[i])
            i += 1
            continue
        nxt = token[i + 1]
        out.append({"\\": "\\", "n": "\n", "r": "\r", "s": "\x1f"}.get(nxt, "\\" + nxt))
        i += 2
    return "".join(out)


def pk_index_ids(index_file: str) -> set:
    ids = set()
    with open(index_file, "r", encoding="utf-8", errors="replace") as fp:
        for line in fp:
            line = line.strip()
            fields = line.split("\x1f")
            if len(fields) == 5:
                ids.add(unescape_index_token(fields[0]))
    return ids


def append_torn_pk_line(work_dir: str, db=DB, coll=COLL) -> None:
    with open(pk_index_file(work_dir, db, coll), "a", encoding="utf-8") as fp:
        fp.write("torn pk index line\n")


# ── phase 1: seed ────────────────────────────────────────────────────────────

def seed(conn: Conn, work_dir: str):
    section("Seeding a collection that spans several pages")
    check_status("create the database", conn.send({"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
    check_status("create the collection",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL}), "OK")
    for i in range(SEEDED_DOCS):
        conn.save({"_id": f"id{i:03d}", "pad": PAD})

    check_status("create the drop/recreate database",
                 conn.send({"type": "CREATE_DATABASE", "databaseName": RECREATE_DB}), "OK")
    check_status("create its collection",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": RECREATE_DB, "collectionName": COLL}), "OK")
    check_status("seed it", conn.save({"_id": "shared", "pad": "before"}, db=RECREATE_DB), "OK")

    check_status("create the schema-guarded collection",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": SCHEMA_COLL}), "OK")
    check_status("give it a schema", conn.send({
        "type": "SAVE_SCHEMA", "databaseName": DB, "collectionName": SCHEMA_COLL,
        "schema": {"type": "object", "required": ["name"], "properties": {"name": {"type": "string"}}}}), "OK")

    check_status("create the collection whose PK index gets corrupted",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": HEAL_COLL}), "OK")
    for start in range(0, HEAL_SEEDED, 200):
        conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": HEAL_COLL,
                   "objects": [{"_id": f"seed{i:05d}", "pad": "x"}
                               for i in range(start, min(start + 200, HEAL_SEEDED))]})
    check("it holds every seeded document", conn.count_via_pk(coll=HEAL_COLL) == HEAL_SEEDED,
          f"got {conn.count_via_pk(coll=HEAL_COLL)} of {HEAL_SEEDED}")

    files = page_files(work_dir)
    check("the collection really spans several pages", len(files) > 1, f"got {files}")
    check("page metadata is persisted for a user collection", page_metadata_bytes(work_dir) > 0,
          "admin/pages/<db>_<coll> is empty, so a restart would lose the page list")
    check("the full scan sees every seeded document", conn.count_via_scan() == SEEDED_DOCS,
          f"got {conn.count_via_scan()} of {SEEDED_DOCS}")


# ── phase 2: after a restart ─────────────────────────────────────────────────

def test_scan_is_complete_after_restart(conn: Conn):
    section("Full scans after a restart")
    pk = conn.count_via_pk()
    scan = conn.count_via_scan()
    check("a cold restart scans every document", pk == scan == SEEDED_DOCS, f"pk={pk} scan={scan}")


def test_scan_is_complete_after_the_first_write(conn: Conn):
    check_status("write one document after the restart", conn.save({"_id": "after", "pad": PAD}), "OK")
    pk = conn.count_via_pk()
    scan = conn.count_via_scan()
    check("one write after a restart does not truncate full scans", pk == scan,
          f"pk={pk} but the full scan returned {scan}")


def test_page_cap_is_enforced_after_restart(conn: Conn, work_dir: str):
    section("Page cap after a restart")
    # Delete across several pages first: the DELETE path used to charge every decrement to page 0, whose
    # recorded size then went negative and made first-fit pile every later insert into it.
    for i in range(0, SEEDED_DOCS, 3):
        conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": f"id{i:03d}"})
    for i in range(SEEDED_DOCS):
        conn.save({"_id": f"post{i:03d}", "pad": PAD})
    folder = os.path.join(work_dir, "db", DB, COLL)
    oversized = [f for f in page_files(work_dir)
                 if os.path.getsize(os.path.join(folder, f)) > 4096 * 2]
    check("no page grew far past maxPageSize after the restart", not oversized, f"oversized: {oversized}")

    pk = conn.count_via_pk()
    scan = conn.count_via_scan()
    check("the collection is still fully scannable", pk == scan, f"pk={pk} scan={scan}")


def change_permissions(conn: Conn, username: str, db_perms: dict) -> dict:
    return conn.send({"type": "CHANGE_PERMISSIONS", "username": username, "admin": False,
                      "globalPermissions": [], "databasePermissions": db_perms,
                      "collectionPermissions": {}, "scriptPermissions": {}})


def create_user(conn: Conn, username: str) -> dict:
    return conn.send({"type": "CREATE_USER", "username": username, "password": "page_grow_1234",
                      "admin": False, "globalPermissions": [], "databasePermissions": {},
                      "collectionPermissions": {}, "scriptPermissions": {}})


def test_page_metadata_follows_an_admin_row_that_grows_on_update(conn: Conn, work_dir: str):
    section("Page occupancy for an admin row that grows on update")
    refused = []
    for user in range(PERM_USERS):
        refused.append(create_user(conn, f"{PERM_USER_PREFIX}{user}"))
    for round_index in range(1, PERM_ROUNDS + 1):
        granted = {f"perm_db_{i:04d}": "READ" for i in range(round_index * 3)}
        for user in range(PERM_USERS):
            refused.append(change_permissions(conn, f"{PERM_USER_PREFIX}{user}", granted))
        refused.append(create_user(conn, f"{PERM_FILL_PREFIX}{round_index:02d}"))
    refused = [response for response in refused if response.get("status") != "OK"]
    check(f"{PERM_USERS} admin rows grew over {PERM_ROUNDS} rounds of updates", not refused,
          f"{len(refused)} requests failed; first: {refused[:1]}")

    recorded = recorded_pages(work_dir, "admin", "users")
    actual = actual_pages(work_dir, "admin", "users")
    check("every admin/users page on disk is described by page metadata",
          set(recorded) == set(actual), f"recorded={sorted(recorded)} on disk={sorted(actual)}")

    drifted = [f"page {page}: recorded {recorded[page]['size']}B/{recorded[page]['entryCount']} entries,"
               f" on disk {actual[page][0]}B/{actual[page][1]} entries"
               for page in sorted(actual)
               if page not in recorded
               or recorded[page]["size"] != actual[page][0]
               or recorded[page]["entryCount"] != actual[page][1]]
    check("the recorded page occupancy still matches the page on disk", not drifted,
          "first-fit is packing rows into a page it believes has room — " + "; ".join(drifted) if drifted else "")


def test_drop_database_does_not_strand_a_collection_lock(conn: Conn):
    section("DROP_DATABASE against concurrent writers")
    check_status("create the database", conn.send({"type": "CREATE_DATABASE", "databaseName": LOCK_DB}), "OK")
    check_status("create the collection",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": LOCK_DB, "collectionName": LOCK_COLL}),
                 "OK")

    finished = []
    errors = []

    def writer(index: int):
        try:
            with admin_conn() as writer_conn:
                for i in range(20):
                    writer_conn.save({"_id": f"w{index}-{i}", "pad": "x"}, db=LOCK_DB, coll=LOCK_COLL)
            finished.append(index)
        except Exception as exc:
            errors.append(f"writer {index}: {exc}")
            finished.append(index)

    threads = [threading.Thread(target=writer, args=(n,), daemon=True) for n in range(4)]
    for thread in threads:
        thread.start()
    time.sleep(0.2)
    conn.send({"type": "DROP_DATABASE", "databaseName": LOCK_DB})
    for thread in threads:
        thread.join(timeout=30)

    check("every concurrent writer finished rather than blocking forever",
          len(finished) == len(threads), f"{len(finished)} of {len(threads)} finished; errors={errors}")

    check_status("the database can be created again",
                 conn.send({"type": "CREATE_DATABASE", "databaseName": LOCK_DB}), "OK")
    check_status("and written to",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": LOCK_DB, "collectionName": LOCK_COLL}),
                 "OK")
    check_status("a write after the re-create succeeds",
                 conn.save({"_id": "after-drop", "pad": "x"}, db=LOCK_DB, coll=LOCK_COLL), "OK")


def test_drop_and_recreate_a_database_does_not_serve_stale_documents(conn: Conn):
    section("DROP_DATABASE then CREATE_DATABASE with the same ids")
    # Seeded before the restart, so nothing about this collection is cached now. The COUNT below is
    # an index-only read: it loads the pk index without ever populating the document cache, which is
    # the state evictDatabase used to leave behind for a recreated collection to inherit.
    conn.count_via_pk(db=RECREATE_DB)

    check_status("drop the database", conn.send({"type": "DROP_DATABASE", "databaseName": RECREATE_DB}), "OK")
    check_status("create it again", conn.send({"type": "CREATE_DATABASE", "databaseName": RECREATE_DB}), "OK")
    check_status("create the collection again",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": RECREATE_DB, "collectionName": COLL}), "OK")
    check_status("write the same id again", conn.save({"_id": "shared", "pad": "after"}, db=RECREATE_DB), "OK")

    found = conn.send({"type": "FIND_BY_ID", "databaseName": RECREATE_DB, "collectionName": COLL, "_id": "shared"})
    check_status("the recreated document is readable", found, "OK")
    check("the recreated document is the new one, not a stale read at a dead offset",
          (found.get("object") or {}).get("pad") == "after",
          f"got {(found.get('object') or {}).get('pad')!r}")
    check("the recreated collection holds exactly one document", conn.count_via_pk(db=RECREATE_DB) == 1,
          f"got {conn.count_via_pk(db=RECREATE_DB)}")


def test_an_unreadable_schema_refuses_the_write(conn: Conn, work_dir: str):
    section("A schema that cannot be read closes the collection to writes")

    schema_file = os.path.join(work_dir, "db", DB, SCHEMA_COLL, f"{SCHEMA_COLL}-schema.json")
    check("the schema was persisted beside its collection", os.path.isfile(schema_file), schema_file)
    with open(schema_file, "w", encoding="utf-8") as fp:
        fp.write('{"type": "obj')

    refused = conn.save({"_id": "unvalidated", "name": "alice"}, coll=SCHEMA_COLL)
    check_code("a write against an unreadable schema is refused", refused, "ERROR", "503-11")


BULK_COLL = "bulk_rollback_coll"
TXN_COLL = "txn_durability_coll"


def test_a_bulk_insert_leaves_no_document_the_pk_index_cannot_reach(conn: Conn):
    """bulkInsertIntoCollection appended across pages and then wrote the pk index with no rollback,
    so a failed index write orphaned every record: visible to a scan, invisible to FIND_BY_ID, and a
    later re-save of the same id appended a second copy."""
    section("bulk insert: scan and the pk index agree")
    conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": BULK_COLL})
    objects = [{"_id": f"bulk_{i:04d}", "pad": PAD} for i in range(60)]
    check_status("bulk insert across several pages",
                 conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": BULK_COLL,
                            "objects": objects}), "OK")

    scanned = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": BULK_COLL,
                         "aggregationSteps": []})
    scan_ids = sorted(d.get("_id") for d in (scanned.get("results") or []))
    missing = [i for i in scan_ids
               if conn.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": BULK_COLL,
                             "_id": i}).get("status") != "OK"]
    check("every bulk-inserted document a scan returns is reachable by FIND_BY_ID",
          len(scan_ids) == len(objects) and not missing,
          f"scanned={len(scan_ids)} expected={len(objects)} unreachable={missing[:5]}")
    check("no id appears twice on the page", len(scan_ids) == len(set(scan_ids)),
          f"scanned={len(scan_ids)} distinct={len(set(scan_ids))}")


def test_a_committed_transaction_survives_a_restart_whole(conn: Conn):
    """A commit that cannot finish applying keeps its locks and its marker so recovery can finish
    the slice. Teardown used to delete the ops the marker pointed at, after which startup recovery
    replayed an empty slice and logged that it had finished the transaction."""
    section("transaction durability across a restart")
    conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": TXN_COLL})
    check_status("start transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    for i in range(5):
        conn.send({"type": "SAVE", "databaseName": DB, "collectionName": TXN_COLL,
                   "object": {"_id": f"txn_{i}", "pad": PAD}})
    check_status("commit transaction", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")


def test_the_committed_transaction_is_all_there_after_the_restart(conn: Conn):
    found = [i for i in range(5)
             if conn.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": TXN_COLL,
                           "_id": f"txn_{i}"}).get("status") == "OK"]
    check("a committed transaction is entirely present after a restart, never partly",
          len(found) == 5, f"present after restart: {found}")


def test_writes_to_an_unknown_collection_are_refused_cleanly(conn: Conn):
    section("writes naming a collection that does not exist")
    for op, request in (
        ("SAVE", {"type": "SAVE", "databaseName": DB, "collectionName": "ghost",
                  "object": {"_id": "a", "pad": "x"}}),
        ("BULK_SAVE", {"type": "BULK_SAVE", "databaseName": DB, "collectionName": "ghost",
                       "objects": [{"_id": "b", "pad": "x"}]}),
        ("DELETE", {"type": "DELETE", "databaseName": DB, "collectionName": "ghost", "_id": "a"}),
    ):
        bu.check_code(f"{op} answers 404-11 rather than leaking the I/O failure as a 5xx",
                      conn.send(request), "NOT_FOUND", "404-11")



def test_index_operations_cannot_destroy_the_pk_index(conn: Conn, work_dir: str):
    section("CREATE_INDEX / DROP_INDEX never take the pk index or the tombstones with them")
    # Index files are selected by name. An unanchored match on "-<field>-" also matched
    # <coll>-pk.idx and <coll>-tombstones.idx, and collection names admit '-', so an ordinary
    # CREATE_INDEX on field "data" in collection "user-data" deleted both. Losing pk.idx is
    # unrecoverable in-product: REINDEX rebuilds field indexes, never the pk index.
    coll = "user-data"
    bu.check_status("create a hyphenated collection",
                    conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    for i in range(3):
        conn.send({"type": "SAVE", "databaseName": DB, "collectionName": coll,
                   "object": {"_id": f"d{i}", "data": i}})

    bu.check_code("DROP_INDEX on _id is refused",
                  conn.send({"type": "DROP_INDEX", "databaseName": DB, "collectionName": coll,
                             "fieldName": "_id"}), "ERROR", "400-1")
    bu.check_code("CREATE_INDEX on _id is refused",
                  conn.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": coll,
                             "fieldName": "_id"}), "ERROR", "400-1")

    bu.check_status("CREATE_INDEX on a field whose name is a token of the collection name",
                    conn.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": coll,
                               "fieldName": "data"}), "OK")

    pk_file = pk_index_file(work_dir, DB, coll)
    bu.check("the pk index file still exists", os.path.isfile(pk_file), detail=pk_file)
    for i in range(3):
        bu.check_status(f"FIND_BY_ID still resolves d{i}",
                        conn.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": coll,
                                   "_id": f"d{i}"}), "OK")

    # With pk.idx gone the re-save is classified as an insert, which appends a second physical
    # document for one _id; the scan then returns the id twice while FIND_BY_ID answers 404.
    conn.send({"type": "SAVE", "databaseName": DB, "collectionName": coll,
               "object": {"_id": "d0", "data": 99}})
    scanned = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                         "aggregationSteps": []})
    ids = [d.get("_id") for d in (scanned.get("results") or [])]
    bu.check("a re-save does not duplicate the _id", len(ids) == len(set(ids)) == 3, detail=f"ids={ids}")



def aggregate_ids(conn: Conn, coll: str, steps: list) -> list:
    resp = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                      "aggregationSteps": steps})
    return sorted(d.get("_id") for d in (resp.get("results") or []))


def test_a_filter_on_id_cannot_destroy_the_pk_index(conn: Conn, work_dir: str):
    section("A FILTER on _id is answered by the pk index and never rewrites it")
    # The pk index used to be written as <coll>-_id-String.idx, byte-for-byte the field-index naming
    # scheme, and rawIndexMatchingIds was the one index-read entry point with no hasNoIndex gate. A
    # FILTER on _id therefore parsed the pk index as a string field index, failed on every line, and
    # the torn-line self-heal rewrote it empty. REINDEX never rebuilds pk.idx, so that was permanent.
    coll = "id_filter"
    bu.check_status("create the collection",
                    conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    for i in range(3):
        conn.send({"type": "SAVE", "databaseName": DB, "collectionName": coll,
                   "object": {"_id": f"d{i}", "n": i}})

    pk_file = pk_index_file(work_dir, DB, coll)
    bu.check("the pk index file is named <coll>-pk.idx", os.path.isfile(pk_file), detail=pk_file)
    size_before = os.path.getsize(pk_file)
    ids_before = pk_index_ids(pk_file)

    cases = [
        ("EQUALS", "d0", ["d0"]),
        ("NOT_EQUALS", "d0", ["d1", "d2"]),
        ("CONTAINS", "d", ["d0", "d1", "d2"]),
        ("IN", ["d0", "d2"], ["d0", "d2"]),
        ("NOT_IN", ["d0", "d2"], ["d1"]),
    ]
    for op_type, value, expected in cases:
        got = aggregate_ids(conn, coll, [{"type": "FILTER", "operator": {
            "fieldOperatorType": op_type, "field": "_id", "value": value}}])
        bu.check(f"FILTER _id {op_type} answers correctly", got == expected,
                 detail=f"expected {expected}, got {got}")

    bu.check("the pk index file was not resized", os.path.getsize(pk_file) == size_before,
             detail=f"{size_before} -> {os.path.getsize(pk_file)}")
    bu.check("the pk index still holds every id", pk_index_ids(pk_file) == ids_before,
             detail=f"{sorted(ids_before)} -> {sorted(pk_index_ids(pk_file))}")
    for i in range(3):
        bu.check_status(f"FIND_BY_ID still resolves d{i}",
                        conn.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": coll,
                                   "_id": f"d{i}"}), "OK")
    conn.send({"type": "SAVE", "databaseName": DB, "collectionName": coll, "object": {"_id": "d0", "n": 99}})
    scanned = aggregate_ids(conn, coll, [])
    bu.check("a re-save after the filter does not duplicate the _id",
             len(scanned) == len(set(scanned)) == 3, detail=f"ids={scanned}")


def test_a_filter_on_id_agrees_with_find_by_id_on_case(conn: Conn):
    section("FILTER _id and FIND_BY_ID name the same document")
    # FieldPredicateFactory routed _id through the generic string branch, whose EQUALS is
    # equalsIgnoreCase, while FIND_BY_ID/SAVE/DELETE binary-search a case-sensitively sorted list. A
    # document was findable by a filter and unaddressable by everything else.
    coll = "id_case"
    bu.check_status("create the collection",
                    conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    for doc_id in ("Case1", "case1"):
        conn.send({"type": "SAVE", "databaseName": DB, "collectionName": coll,
                   "object": {"_id": doc_id, "which": doc_id}})

    for doc_id in ("Case1", "case1"):
        filtered = aggregate_ids(conn, coll, [{"type": "FILTER", "operator": {
            "fieldOperatorType": "EQUALS", "field": "_id", "value": doc_id}}])
        found = conn.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": coll, "_id": doc_id})
        bu.check(f"FILTER _id EQUALS {doc_id} names exactly that document", filtered == [doc_id],
                 detail=f"got {filtered}")
        bu.check(f"FIND_BY_ID {doc_id} agrees with the filter",
                 (found.get("object") or {}).get("_id") == doc_id, detail=str(found)[:200])


def test_a_filter_on_id_reads_only_the_matching_document(conn: Conn):
    section("A FILTER on _id is resolved by the pk index, not by a collection scan")
    coll = "id_analyze"
    bu.check_status("create the collection",
                    conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll}), "OK")
    for i in range(25):
        conn.send({"type": "SAVE", "databaseName": DB, "collectionName": coll,
                   "object": {"_id": f"a{i:03d}", "n": i}})

    resp = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll, "analyze": True,
                      "aggregationSteps": [{"type": "FILTER", "operator": {
                          "fieldOperatorType": "EQUALS", "field": "_id", "value": "a007"}}]})
    analysis = resp.get("analyzeResult") or {}
    bu.check("the filter still answers correctly",
             [d.get("_id") for d in (resp.get("results") or [])] == ["a007"], detail=str(resp)[:200])
    bu.check("the pk index is reported as used", "_id" in (analysis.get("indexesUsed") or []),
             detail=str(analysis)[:300])
    bu.check("only the matching document was read", analysis.get("documentsScanned") == 1,
             detail=f"documentsScanned={analysis.get('documentsScanned')} of 25")

# ── phase 3: an unclean stop, then a restart with the cache disabled ─────────

def dirty_markers(work_dir: str, db=DB, coll=DIRTY_COLL):
    folder = os.path.join(work_dir, "db", db, coll)
    if not os.path.isdir(folder):
        return []
    return sorted(f for f in os.listdir(folder) if f.endswith("-indexes.dirty"))


def write_until_indexes_are_dirty(conn: Conn, work_dir: str) -> bool:
    check_status("create the collection whose indexes will be left dirty",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": DIRTY_COLL}), "OK")
    check_status("index a field on it",
                 conn.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": DIRTY_COLL,
                            "fieldName": "n"}), "OK")
    deadline = time.time() + 15.0
    index = 0
    while time.time() < deadline:
        for _ in range(50):
            conn.save({"_id": f"d{index:05d}", "n": index, "pad": PAD}, coll=DIRTY_COLL)
            index += 1
        if dirty_markers(work_dir):
            return True
    return False


def test_an_unclean_stop_is_reported_at_the_next_startup(work_dir: str, log_path: str, log_offset: int):
    section("Startup names a collection whose field-index work never drained")

    with open(log_path, "rb") as fp:
        fp.seek(log_offset)
        restart_log = fp.read().decode(errors="replace")

    check("startup warns about the collection left dirty",
          f"{DB}|{DIRTY_COLL}" in restart_log and "REINDEX" in restart_log,
          "the restart log does not name the collection, so an operator has no signal to rebuild it")


def test_reindex_clears_the_marker_only_once_every_index_was_rebuilt(conn: Conn, work_dir: str):
    section("REINDEX retires the index-dirty marker the startup warning told the operator to act on")

    check("the marker survived the unclean stop", dirty_markers(work_dir),
          "without a marker there is nothing for REINDEX to retire and the rest of this case proves nothing")
    check_status("add a second index so a partial rebuild is possible",
                 conn.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": DIRTY_COLL,
                            "fieldName": "pad"}), "OK")

    check_status("rebuild only one of the two indexes",
                 conn.send({"type": "REINDEX", "databaseName": DB, "collectionName": DIRTY_COLL,
                            "fieldNames": ["n"]}), "OK")
    check("a partial rebuild leaves the marker in place", dirty_markers(work_dir),
          "the fields that run skipped were never re-derived, so the collection is still suspect")

    check_status("rebuild every index",
                 conn.send({"type": "REINDEX", "databaseName": DB, "collectionName": DIRTY_COLL}), "OK")
    check("a whole-collection rebuild retires the marker", not dirty_markers(work_dir),
          "REINDEX is the remedy the startup warning names, so the warning must not outlive it")


def test_a_self_heal_never_erases_a_committed_write(conn: Conn, work_dir: str, log_path: str):
    section("A PK-index self-heal racing committed writes")

    index_file = pk_index_file(work_dir, DB, HEAL_COLL)
    check("the corrupted PK index is where the suite expects it", os.path.isfile(index_file), index_file)

    log_offset = os.path.getsize(log_path)
    committed = []
    errors = []
    stop = threading.Event()
    guard = threading.Lock()

    def writer(index: int):
        try:
            with admin_conn() as writer_conn:
                counter = 0
                while not stop.is_set():
                    doc_id = f"race{index}-{counter:05d}"
                    counter += 1
                    if writer_conn.save({"_id": doc_id, "pad": "x"}, coll=HEAL_COLL).get("status") == "OK":
                        with guard:
                            committed.append(doc_id)
        except Exception as exc:
            errors.append(f"writer {index}: {exc}")

    def reader():
        try:
            with admin_conn() as reader_conn:
                while not stop.is_set():
                    # Dirty, so it takes no collection lock and really overlaps the writers above.
                    reader_conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": HEAL_COLL,
                                      "dirtyRead": True, "aggregationSteps": [{"type": "COUNT"}]})
        except Exception as exc:
            errors.append(f"reader: {exc}")

    def corrupter():
        while not stop.is_set():
            try:
                append_torn_pk_line(work_dir, DB, HEAL_COLL)
            except OSError:
                pass
            time.sleep(0.005)

    threads = [threading.Thread(target=reader, daemon=True) for _ in range(2)]
    threads += [threading.Thread(target=writer, args=(n,), daemon=True) for n in range(3)]
    threads.append(threading.Thread(target=corrupter, daemon=True))
    for thread in threads:
        thread.start()
    time.sleep(RACE_SECONDS)
    stop.set()
    for thread in threads:
        thread.join(timeout=60)

    check("every racing client finished cleanly", not errors, "; ".join(errors))
    check("the race committed enough writes to be worth asserting on", len(committed) > 100,
          f"only {len(committed)} writes committed in {RACE_SECONDS}s")

    # Without this the suite could pass by never entering the window it is here to guard.
    with open(log_path, "rb") as fp:
        fp.seek(log_offset)
        heals = fp.read().decode(errors="replace").count(f"PK index entry in {HEAL_COLL}-pk")
    check("the writes really did race a self-heal", heals > 20,
          f"only {heals} self-heals fired, so the assertions below prove little")

    missing = sorted(set(committed) - pk_index_ids(index_file))
    check("no committed write was erased from the PK index by a self-heal", not missing,
          f"{len(missing)} of {len(committed)} committed ids are gone from the index for good "
          f"(REINDEX does not rebuild it); first: {missing[:5]}")

    pk = conn.count_via_pk(coll=HEAL_COLL)
    scan = conn.count_via_scan(coll=HEAL_COLL)
    check("a PK-index count and a full scan still agree", pk == scan,
          f"pk={pk} scan={scan}, expected {HEAL_SEEDED + len(committed)}")

    for doc_id in committed[-3:]:
        check_status(f"FIND_BY_ID still finds {doc_id}",
                     conn.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": HEAL_COLL,
                                "_id": doc_id}), "OK")


def main():
    bu.banner("storage consistency e2e tests", HOST, PORT)

    jar = os.path.join(REPO_ROOT, JAR)
    if not os.path.isfile(jar):
        print(f"\n[ERROR] Jar not found at {jar}. Build it first: mvn package -DskipTests\n")
        sys.exit(1)

    work_dir = tempfile.mkdtemp(prefix="lwnrdb-storage-")
    log_path = os.path.join(work_dir, "server.log")
    print(f"  Working dir: {work_dir}")

    proc = None
    try:
        write_config(work_dir)
        print(f"  Starting server on {HOST}:{PORT} ...")
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            seed(conn, work_dir)
        bu.stop_server(proc)
        proc = None

        print(f"\n  Restarting server on {HOST}:{PORT} ...")
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_scan_is_complete_after_restart(conn)
            test_scan_is_complete_after_the_first_write(conn)
            test_page_cap_is_enforced_after_restart(conn, work_dir)
            test_page_metadata_follows_an_admin_row_that_grows_on_update(conn, work_dir)
            test_drop_database_does_not_strand_a_collection_lock(conn)
            test_drop_and_recreate_a_database_does_not_serve_stale_documents(conn)
            test_writes_to_an_unknown_collection_are_refused_cleanly(conn)
            test_a_committed_transaction_survives_a_restart_whole(conn)
            test_an_unreadable_schema_refuses_the_write(conn, work_dir)
            test_index_operations_cannot_destroy_the_pk_index(conn, work_dir)
            test_a_filter_on_id_cannot_destroy_the_pk_index(conn, work_dir)
            test_a_filter_on_id_agrees_with_find_by_id_on_case(conn)
            test_a_filter_on_id_reads_only_the_matching_document(conn)

            check("a burst of indexed writes leaves an index-dirty marker on disk",
                  write_until_indexes_are_dirty(conn, work_dir),
                  "the background queue drained faster than the test could write")
        print("\n  Killing the server without a drain ...")
        proc.kill()
        proc.wait(timeout=30)
        log_offset = os.path.getsize(log_path)
        proc = None

        append_torn_pk_line(work_dir, DB, HEAL_COLL)
        write_config(work_dir, max_memory=CACHE_DISABLED)
        print(f"  Restarting server on {HOST}:{PORT} with the cache disabled ...")
        proc = bu.start_server(work_dir, log_path)
        test_an_unclean_stop_is_reported_at_the_next_startup(work_dir, log_path, log_offset)
        with admin_conn() as conn:
            test_the_committed_transaction_is_all_there_after_the_restart(conn)
            test_a_bulk_insert_leaves_no_document_the_pk_index_cannot_reach(conn)
            test_reindex_clears_the_marker_only_once_every_index_was_rebuilt(conn, work_dir)
            test_a_self_heal_never_erases_a_committed_write(conn, work_dir, log_path)
    finally:
        bu.stop_server(proc)

    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
