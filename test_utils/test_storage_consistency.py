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
"""

import os
import sys
import tempfile
import threading
import time

import base_utils as bu
from base_utils import check, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("STORAGE_CONSISTENCY_TEST_PORT", "8996"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"

DB = "storage_db"
COLL = "docs"
LOCK_DB = "lock_db"
RECREATE_DB = "recreate_db"
LOCK_COLL = "items"

JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"
REPO_ROOT = bu.REPO_ROOT

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)

# Small enough that a few dozen small documents span several pages, so a partial page list is
# observable at all. maxEntrySize must stay strictly below it.
MAX_PAGE_SIZE = "4kb"
MAX_ENTRY_SIZE = "1kb"
SEEDED_DOCS = 40
PAD = "p" * 300


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


def write_config(work_dir: str):
    cfg = (
        f"port={PORT}\n"
        "filePath=db\n"
        "logPath=logs\n"
        "maxMemory=256mb\n"
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
            test_drop_database_does_not_strand_a_collection_lock(conn)
            test_drop_and_recreate_a_database_does_not_serve_stale_documents(conn)
            test_writes_to_an_unknown_collection_are_refused_cleanly(conn)
    finally:
        bu.stop_server(proc)

    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
