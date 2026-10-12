import json
import os
import shutil
import tempfile
import threading
import time

import base_utils as bu
from base_utils import check, check_code, check_field, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("STORAGE_CONSISTENCY_TEST_PORT", "8996"))
ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

DB = "storage_db"
COLL = "docs"
UNWRITTEN_COLL = "never_written"
UNWRITTEN_SCANS = 300
LOCK_DB = "lock_db"
RECREATE_DB = "recreate_db"
SCHEMA_COLL = "schema_guarded"
LOCK_COLL = "items"
DIRTY_COLL = "dirty_index_docs"
HEAL_COLL = "heal_race_docs"
SCRIPT_COLL = "script_written"
NON_FINITE_COLL = "non_finite_numbers"
LOST_ROWS_COLL = "lost_page_rows"
LOST_ROWS_DOCS = 12
TORN_COLL = "torn_tail_docs"
TORN_DOCS = 5
TORN_BYTES = '{"_id":"torn","pad":"interrupted mid-wri'
LINE_END_COLL = "lost_line_end_docs"
LINE_END_DOCS = 4
HALF_BUILT_COLL = "half_built_index_docs"
HALF_BUILT_FIELD = "k"
ORPHAN_COLL = "unindexed_record_docs"
ORPHAN_DOCS = 4
ORPHAN_FIELD = "k"
ORPHAN_ID = "orphan"
DROPPED_DBS = 6
KILLED_DROP_COLL = "drop_killed_docs"
KILLED_DROP_DB = "drop_killed_db"
LEFTOVER_ROWS_COLL = "leftover_row_docs"
LEFTOVER_ROWS_COPY = "leftover_rows_copy"
BULK_GROWTH_COLL = "bulk_growth_docs"
BULK_GROWTH_DOCS = 10
DANGLING_COLL = "dangling_pk_docs"
OLD_GRAMMAR_COLL = "old_grammar_pk_docs"
OLD_GRAMMAR_PK = "first|0|20|0|0\nsecond|20|20|0|0\n"
REBUILD_UNREADABLE_COLL = "rebuild_unreadable_pk_docs"
REBUILD_SWAPPED_COLL = "rebuild_swapped_offset_docs"
REBUILD_LOST_ROW_COLL = "rebuild_lost_row_docs"
REBUILD_DUPLICATE_COLL = "rebuild_duplicate_docs"
REBUILD_IDS = ("alpha", "bravo", "charlie")
UNLISTED_DB = "unlisted_db"
UNLISTED_COLL = "unlisted_docs"
UNLISTED_GHOST = "unlisted_docX"
DROPPED_DB_COLLECTIONS = 4
ADMIN_PAGES_DB = "admin_pages_spill_db"
ADMIN_PAGES_COLLECTIONS = 80
SYMLINK_DB = "symlink_db"
SYMLINK_COLL = "linked_docs"
SYMLINK_TARGET = "outside_symlink_target"
SYMLINK_PRECIOUS = "precious.txt"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=ADMIN_PASSWORD)

MAX_PAGE_SIZE = "4kb"
MAX_PAGE_BYTES = 4096
MAX_ENTRY_SIZE = "1kb"
SEEDED_DOCS = 40
PAD = "p" * 300

CACHE_DISABLED = "-1"

HEAL_SEEDED = 1000
RACE_SECONDS = 3.0

PERM_USER_PREFIX = "page_grow_user_"
PERM_FILL_PREFIX = "page_fill_user_"
PERM_USERS = 6
PERM_ROUNDS = 10


FULL_SCAN = [{"type": "SKIP", "skip": 0}]
NO_DOCUMENT_HAS_THIS = bu.filter_step("pad", "NOT_EQUALS", "__no_document_has_this__")


class Conn(bu.Conn):
    def save(self, doc, db=DB, coll=COLL) -> dict:
        return self.send({"type": "SAVE", "databaseName": db, "collectionName": coll, "object": doc})

    def find(self, doc_id, db=DB, coll=COLL) -> dict:
        return self.send({"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": doc_id})

    def create_coll(self, coll, db=DB) -> dict:
        return self.send({"type": "CREATE_COLLECTION", "databaseName": db, "collectionName": coll})

    def create_index(self, field, coll=COLL, db=DB) -> dict:
        return self.send({"type": "CREATE_INDEX", "databaseName": db, "collectionName": coll, "fieldName": field})

    def aggregate(self, coll, steps, db=DB, **extra) -> dict:
        return self.send({"type": "AGGREGATE", "databaseName": db, "collectionName": coll,
                          "aggregationSteps": steps, **extra})

    def ids(self, coll, steps, db=DB) -> list:
        return bu.ids_of(self.aggregate(coll, steps, db))

    def count(self, coll, steps, db=DB):
        return bu.count_of(self.aggregate(coll, steps + [{"type": "COUNT"}], db))

    def count_via_pk(self, db=DB, coll=COLL) -> int:
        return self.count(coll, [], db)

    def count_via_scan(self, db=DB, coll=COLL) -> int:
        return self.count(coll, [NO_DOCUMENT_HAS_THIS], db)

    def run(self, script: str, db=DB) -> dict:
        return self.send({"type": "RUN_SCRIPT", "databaseName": db, "script": script})


admin_conn = Conn.authenticated


def write_config(work_dir: str, max_memory: str = "256mb"):
    bu.write_config(work_dir, PORT, maxMemory=max_memory, maxPageSize=MAX_PAGE_SIZE,
                    maxEntrySize=MAX_ENTRY_SIZE, scriptsEnabled=True)


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
    folder = os.path.join(work_dir, "db", "admin", "pages", f"{db}_{coll}")
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


def occupancy_drift(recorded: dict, actual: dict, entries: bool = True) -> list:
    def drifted(page) -> bool:
        row = recorded.get(page)
        return row is None or row["size"] != actual[page][0] or (entries and row["entryCount"] != actual[page][1])

    def describe(page) -> str:
        size, count = actual[page]
        on_disk = f"on disk {size}B/{count} entries" if entries else f"on disk {size}B"
        row = recorded.get(page)
        if row is None:
            return f"page {page}: no recorded row, {on_disk}"
        if entries:
            return f"page {page}: recorded {row['size']}B/{row['entryCount']} entries, {on_disk}"
        return f"page {page}: recorded {row['size']}B, {on_disk}"

    return [describe(page) for page in sorted(actual) if drifted(page)]


def oversized_pages(work_dir: str, coll: str, limit: int, db=DB) -> dict:
    folder = os.path.join(work_dir, "db", db, coll)
    sizes = {name: os.path.getsize(os.path.join(folder, name)) for name in page_files(work_dir, db, coll)}
    return {name: size for name, size in sizes.items() if size > limit}


def unterminated_pages(folder: str, names: list) -> list:
    torn = []
    for name in names:
        with open(os.path.join(folder, name), "rb") as page:
            content = page.read()
        if content and content[-1:] != b"\n":
            torn.append(name)
    return torn


def last_page(names: list) -> str:
    return max(names, key=lambda name: int(name.rsplit("-", 1)[1][:-len(".dat")]))


def pk_index_file(work_dir: str, db=DB, coll=COLL) -> str:
    return os.path.join(work_dir, "db", db, coll, f"{coll}-pk.idx")


def pk_index_ids(index_file: str) -> set:
    ids = set()
    with open(index_file, "r", encoding="utf-8", errors="replace") as fp:
        for line in fp:
            line = line.strip()
            fields = line.split("\x1f")
            if len(fields) == 5:
                ids.add(bu.unescape_index_token(fields[0]))
    return ids


def append_torn_pk_line(work_dir: str, db=DB, coll=COLL) -> None:
    with open(pk_index_file(work_dir, db, coll), "a", encoding="utf-8") as fp:
        fp.write("torn pk index line\n")


def seed(conn: Conn, work_dir: str):
    section("Seeding a collection that spans several pages")
    check_status("create the database", conn.send({"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
    check_status("create the collection",
                 conn.create_coll(COLL), "OK")
    for i in range(SEEDED_DOCS):
        conn.save({"_id": f"id{i:03d}", "pad": PAD})

    check_status("create the drop/recreate database",
                 conn.send({"type": "CREATE_DATABASE", "databaseName": RECREATE_DB}), "OK")
    check_status("create its collection",
                 conn.create_coll(COLL, db=RECREATE_DB), "OK")
    check_status("seed it", conn.save({"_id": "shared", "pad": "before"}, db=RECREATE_DB), "OK")

    check_status("create the schema-guarded collection",
                 conn.create_coll(SCHEMA_COLL), "OK")
    check_status("give it a schema", conn.send({
        "type": "SAVE_SCHEMA", "databaseName": DB, "collectionName": SCHEMA_COLL,
        "schema": {"type": "object", "required": ["name"], "properties": {"name": {"type": "string"}}}}), "OK")

    check_status("create the collection whose PK index gets corrupted",
                 conn.create_coll(HEAL_COLL), "OK")
    for start in range(0, HEAL_SEEDED, 200):
        conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": HEAL_COLL,
                   "objects": [{"_id": f"seed{i:05d}", "pad": "x"}
                               for i in range(start, min(start + 200, HEAL_SEEDED))]})
    check("it holds every seeded document", conn.count_via_pk(coll=HEAL_COLL) == HEAL_SEEDED,
          f"got {conn.count_via_pk(coll=HEAL_COLL)} of {HEAL_SEEDED}")

    seed_script_written_documents(conn)
    seed_collections_spanning_several_admin_pages(conn, work_dir)

    files = page_files(work_dir)
    check("the collection really spans several pages", len(files) > 1, f"got {files}")
    check("page metadata is persisted for a user collection", page_metadata_bytes(work_dir) > 0,
          "admin/pages/<db>_<coll> is empty, so a restart would lose the page list")
    check("the full scan sees every seeded document", conn.count_via_scan() == SEEDED_DOCS,
          f"got {conn.count_via_scan()} of {SEEDED_DOCS}")


def admin_pages_collection_name(index: int) -> str:
    return f"collection_registered_before_the_restart_{index:03d}"


def seed_collections_spanning_several_admin_pages(conn: Conn, work_dir: str):
    section("Seeding enough collections that admin/collections spans several pages")
    check_status("create their database", conn.send({"type": "CREATE_DATABASE", "databaseName": ADMIN_PAGES_DB}),
                 "OK")
    refused = [response for response in (
        conn.create_coll(admin_pages_collection_name(i), db=ADMIN_PAGES_DB) for i in range(ADMIN_PAGES_COLLECTIONS))
        if response.get("status") != "OK"]
    check(f"{ADMIN_PAGES_COLLECTIONS} collections were registered", not refused,
          f"{len(refused)} requests failed; first: {refused[:1]}")
    check("admin/collections spans several pages", len(actual_pages(work_dir, "admin", "collections")) > 1,
          f"pages: {sorted(actual_pages(work_dir, 'admin', 'collections'))}")


SCRIPT_WRITTEN = {
    "sw_geo": '{ _id: "sw_geo", loc: "#geo(3.0,4.0)", pad: "x" }',
    "sw_time": '{ _id: "sw_time", loc: "#time(08:00:00)", pad: "x" }',
    "sw_datetime": '{ _id: "sw_datetime", loc: "#datetime(2024-07-12T12:30:00)", pad: "x" }',
    "sw_vector": '{ _id: "sw_vector", loc: "#vector(1.0,2.0,3.0)", pad: "x" }',
    "sw_plain": '{ _id: "sw_plain", loc: "ordinary", pad: "x", n: 1.5, flag: true }',
}
WIRE_WRITTEN = {"ww_geo": "#geo(1.0,2.0)", "ww_plain": "ordinary"}


def seed_script_written_documents(conn: Conn):
    section("Seeding a collection written by both the wire and a script")
    check_status("create the script-written collection",
                 conn.create_coll(SCRIPT_COLL), "OK")
    for doc_id, loc in WIRE_WRITTEN.items():
        check_status(f"wire-write {doc_id}",
                     conn.save({"_id": doc_id, "loc": loc, "pad": "x"}, coll=SCRIPT_COLL), "OK")
    for doc_id, literal in SCRIPT_WRITTEN.items():
        check_status(f"script-write {doc_id}",
                     conn.run('import db from "db";\n'
                              f'db.save(db.name, "{SCRIPT_COLL}", {literal});'), "OK")
    check_status("index the field both sides wrote",
                 conn.create_index("loc", coll=SCRIPT_COLL), "OK")


def test_a_script_written_document_survives_a_restart(conn: Conn):
    section("A script-written document reads back after a restart")
    expected = sorted(list(SCRIPT_WRITTEN) + list(WIRE_WRITTEN))
    for doc_id in expected:
        response = conn.find(doc_id, coll=SCRIPT_COLL)
        check_status(f"FIND_BY_ID reaches {doc_id}", response, "OK")
    scanned = conn.ids(SCRIPT_COLL, [])
    check("a full scan returns every document", scanned == expected, f"expected {expected} got {scanned}")
    pk = conn.count_via_pk(coll=SCRIPT_COLL)
    check("the index-only COUNT agrees with the scan", pk == len(expected), f"count={pk} scan={len(scanned)}")


def test_a_script_written_custom_value_is_found_by_an_index_backed_filter(conn: Conn):
    section("A script-written custom value lands in its own index family")

    def ids_for(operand, force_scan):
        return conn.ids(SCRIPT_COLL, (FULL_SCAN if force_scan else []) + [bu.filter_step("loc", "EQUALS", operand)])

    for value, expected in (("#geo(3.0,4.0)", ["sw_geo"]), ("#geo(1.0,2.0)", ["ww_geo"]),
                            ("#time(08:00:00)", ["sw_time"]), ("ordinary", ["sw_plain", "ww_plain"])):
        indexed = ids_for(value, force_scan=False)
        scanned = ids_for(value, force_scan=True)
        check(f"the index and the scan agree on {value}", indexed == scanned == expected,
              f"index={indexed} scan={scanned} expected={expected}")


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
    for i in range(0, SEEDED_DOCS, 3):
        conn.send({"type": "DELETE", "databaseName": DB, "collectionName": COLL, "_id": f"id{i:03d}"})
    for i in range(SEEDED_DOCS):
        conn.save({"_id": f"post{i:03d}", "pad": PAD})
    oversized = oversized_pages(work_dir, COLL, 4096 * 2)
    check("no page grew far past maxPageSize after the restart", not oversized, f"oversized: {oversized}")

    pk = conn.count_via_pk()
    scan = conn.count_via_scan()
    check("the collection is still fully scannable", pk == scan, f"pk={pk} scan={scan}")


GROWTH_COLL = "grown_in_a_txn"
GROWTH_DOCS = 20
GROWTH_PAD = "g" * 700


def test_a_transaction_of_in_place_growths_respects_max_page_size(conn: Conn, work_dir: str):
    section("A burst of in-place growths still relocates")
    check_status("create the collection",
                 conn.create_coll(GROWTH_COLL), "OK")
    for i in range(GROWTH_DOCS):
        check_status(f"seed a small document {i}", conn.save({"_id": f"grow{i:02d}", "pad": "s"},
                                                             coll=GROWTH_COLL), "OK")

    check_status("START_TRANSACTION", conn.send({"type": "START_TRANSACTION"}), "OK")
    refused = [conn.save({"_id": f"grow{i:02d}", "pad": GROWTH_PAD}, coll=GROWTH_COLL)
               for i in range(GROWTH_DOCS)]
    refused = [r for r in refused if r.get("status") != "OK"]
    check("every growth was buffered", not refused, f"first refusal: {refused[:1]}")
    check_status("COMMIT_TRANSACTION", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")

    oversized = oversized_pages(work_dir, GROWTH_COLL, MAX_PAGE_BYTES)
    check("no page grew past maxPageSize", not oversized, f"oversized: {oversized}")

    drifted = occupancy_drift(recorded_pages(work_dir, DB, GROWTH_COLL), actual_pages(work_dir, DB, GROWTH_COLL))
    check("the recorded page occupancy still matches the pages on disk", not drifted, "; ".join(drifted))

    pk = conn.count_via_pk(coll=GROWTH_COLL)
    check("every grown document is still there", pk == GROWTH_DOCS, f"pk={pk}")


def test_a_bulk_save_does_not_place_inserts_against_a_stale_page_size(conn: Conn, work_dir: str):
    section("A bulk save's inserts are placed against the size its own updates just grew to")
    check_status("create the collection",
                 conn.create_coll(BULK_GROWTH_COLL), "OK")
    for i in range(BULK_GROWTH_DOCS):
        check_status(f"seed bg{i}", conn.save({"_id": f"bg{i}", "pad": "s" * 250}, coll=BULK_GROWTH_COLL), "OK")
    seeded_pages = {row[3] for row in bu.read_pk_rows(pk_index_file(work_dir, DB, BULK_GROWTH_COLL))}
    check("the seed fills a single page", seeded_pages == {0}, f"pages: {seeded_pages}")

    grown = [{"_id": f"bg{i}", "pad": "g" * 350} for i in range(BULK_GROWTH_DOCS)]
    check_status("one BULK_SAVE grows every seeded document and inserts one more",
                 conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": BULK_GROWTH_COLL,
                            "objects": grown + [{"_id": "bg_fresh", "pad": "f" * 600}]}), "OK")

    rows = {row[0]: row for row in bu.read_pk_rows(pk_index_file(work_dir, DB, BULK_GROWTH_COLL))}
    check("the insert did not land on the page its own updates grew past the cap",
          "bg_fresh" in rows and rows["bg_fresh"][3] != 0, f"bg_fresh row: {rows.get('bg_fresh')}")

    drifted = ["unchecked"]

    def settled() -> bool:
        drifted[:] = occupancy_drift(recorded_pages(work_dir, DB, BULK_GROWTH_COLL),
                                     actual_pages(work_dir, DB, BULK_GROWTH_COLL))
        return not drifted

    bu.wait_until(settled, timeout_s=15, interval_s=0.2)
    check("the recorded page occupancy matches the pages on disk", not drifted, f"drifted pages: {drifted}")


def seed_a_collection_whose_page_rows_will_be_lost(conn: Conn, work_dir: str):
    section("Seed a collection whose page-occupancy rows a crash will lose")
    check_status("create the collection",
                 conn.create_coll(LOST_ROWS_COLL), "OK")
    for i in range(LOST_ROWS_DOCS):
        conn.save({"_id": f"lost{i:02d}", "pad": "s"}, coll=LOST_ROWS_COLL)
    bu.wait_until(lambda: recorded_pages(work_dir, DB, LOST_ROWS_COLL), timeout_s=15, interval_s=0.2)
    check("its page rows reached disk", bool(recorded_pages(work_dir, DB, LOST_ROWS_COLL)))


def lose_the_page_rows(work_dir: str):
    folder = os.path.join(work_dir, "db", "admin", "pages", f"{DB}_{LOST_ROWS_COLL}")
    for name in os.listdir(folder):
        os.remove(os.path.join(folder, name))


def test_lost_page_rows_are_rebuilt_at_startup(conn: Conn, work_dir: str):
    section("Page-occupancy rows lost to a crash are rebuilt at the next startup")
    recorded = recorded_pages(work_dir, DB, LOST_ROWS_COLL)
    actual = actual_pages(work_dir, DB, LOST_ROWS_COLL)
    drifted = occupancy_drift(recorded, actual)
    check("every page on disk has a row that matches it again", actual and not drifted,
          f"recorded={recorded} actual={actual}")

    for i in range(LOST_ROWS_DOCS):
        conn.save({"_id": f"lost{i:02d}", "pad": GROWTH_PAD}, coll=LOST_ROWS_COLL)
    oversized = oversized_pages(work_dir, LOST_ROWS_COLL, MAX_PAGE_BYTES)
    check("growing every document afterwards keeps each page under maxPageSize", not oversized,
          f"oversized: {oversized}")
    pk = conn.count_via_pk(coll=LOST_ROWS_COLL)
    check("every grown document is still there", pk == LOST_ROWS_DOCS, f"pk={pk}")


def seed_a_collection_whose_page_tail_will_tear(conn: Conn):
    section("Seed a collection whose last page a crash will tear")
    check_status("create the collection",
                 conn.create_coll(TORN_COLL), "OK")
    for i in range(TORN_DOCS):
        check_status(f"save torn{i}", conn.save({"_id": f"torn{i}", "pad": "s"}, coll=TORN_COLL), "OK")


def tear_the_page_tail(work_dir: str):
    folder = os.path.join(work_dir, "db", DB, TORN_COLL)
    last = last_page(page_files(work_dir, DB, TORN_COLL))
    with open(os.path.join(folder, last), "ab") as page:
        page.write(TORN_BYTES.encode("utf-8"))


def test_a_torn_page_tail_is_healed_at_startup(conn: Conn, work_dir: str):
    section("A torn page tail is healed at startup, so the next write is not glued onto it")
    unterminated = unterminated_pages(os.path.join(work_dir, "db", DB, TORN_COLL), page_files(work_dir, DB, TORN_COLL))
    check("every page ends at a record boundary after the restart", not unterminated, f"torn: {unterminated}")
    check_status("save a document after the restart", conn.save({"_id": "after_tear", "pad": "s"}, coll=TORN_COLL),
                 "OK")
    scanned = conn.ids(TORN_COLL, FULL_SCAN)
    expected = sorted([f"torn{i}" for i in range(TORN_DOCS)] + ["after_tear"])
    check("a full scan sees the document written after the tear", scanned == expected,
          f"expected {expected} got {scanned}")
    pk = conn.count_via_pk(coll=TORN_COLL)
    check("the index-only COUNT agrees with the scan", pk == len(expected), f"count={pk} scan={len(scanned)}")


def seed_collections_for_a_lost_record_and_an_unrecognised_pk_index(conn: Conn):
    section("Seed a collection whose last record a crash will lose, and one whose pk.idx will be unreadable")
    for coll in (DANGLING_COLL, OLD_GRAMMAR_COLL):
        check_status(f"create {coll}",
                     conn.create_coll(coll), "OK")
        for doc_id in ("first", "second"):
            check_status(f"save {doc_id} into {coll}", conn.save({"_id": doc_id, "pad": doc_id}, coll=coll), "OK")


def lose_the_last_record(work_dir: str):
    folder = os.path.join(work_dir, "db", DB, DANGLING_COLL)
    path = os.path.join(folder, page_files(work_dir, DB, DANGLING_COLL)[0])
    with open(path, "rb+") as page:
        content = page.read()
        page.truncate(content.rstrip(b"\n").rfind(b"\n") + 1)


def old_grammar_pk_index(work_dir: str) -> str:
    return pk_index_file(work_dir, DB, OLD_GRAMMAR_COLL)


def plant_an_old_grammar_pk_index(work_dir: str):
    shutil.copyfile(old_grammar_pk_index(work_dir), old_grammar_pk_index(work_dir) + ".original")
    with open(old_grammar_pk_index(work_dir), "w") as index:
        index.write(OLD_GRAMMAR_PK)


def restore_the_old_grammar_pk_index(work_dir: str):
    shutil.move(old_grammar_pk_index(work_dir) + ".original", old_grammar_pk_index(work_dir))


def test_a_pk_entry_past_its_page_is_retired_at_startup(conn: Conn):
    section("A pk.idx entry whose record a crash lost is retired at startup, so no write lands on its neighbour")
    check_status("FIND_BY_ID of the lost record answers NOT_FOUND",
                 conn.find("second", coll=DANGLING_COLL), "NOT_FOUND")
    check_status("its neighbour still reads back",
                 conn.find("first", coll=DANGLING_COLL), "OK")
    check_status("the lost id can be saved again", conn.save({"_id": "second", "pad": "again"}, coll=DANGLING_COLL),
                 "OK")
    for doc_id in ("first", "second"):
        check_status(f"{doc_id} reads back after the save",
                     conn.find(doc_id, coll=DANGLING_COLL), "OK")
    pk = conn.count_via_pk(coll=DANGLING_COLL)
    scan = conn.count_via_scan(coll=DANGLING_COLL)
    check("the index-only COUNT agrees with the scan", pk == scan == 2, f"pk={pk} scan={scan}")


def test_an_unrecognised_pk_index_is_never_overwritten(conn: Conn, work_dir: str):
    section("A pk.idx in which no line parses fails loudly and is never overwritten by a write")
    check("a SAVE into the collection is refused",
          conn.save({"_id": "third", "pad": "third"}, coll=OLD_GRAMMAR_COLL).get("status") != "OK")
    check_status("a FIND_BY_ID answers an error, not NOT_FOUND",
                 conn.find("first", coll=OLD_GRAMMAR_COLL), "ERROR")
    with open(old_grammar_pk_index(work_dir)) as index:
        check("the unreadable pk.idx is byte-identical to the planted one", index.read() == OLD_GRAMMAR_PK)


def test_a_restored_pk_index_serves_every_document(conn: Conn):
    section("Once the pk.idx is restored, every document it named reads back")
    for doc_id in ("first", "second"):
        check_status(f"{doc_id} reads back",
                     conn.find(doc_id, coll=OLD_GRAMMAR_COLL), "OK")
    pk = conn.count_via_pk(coll=OLD_GRAMMAR_COLL)
    scan = conn.count_via_scan(coll=OLD_GRAMMAR_COLL)
    check("no write landed while it was unreadable", pk == scan == 2, f"pk={pk} scan={scan}")


def reindex_primary_key(conn: Conn, coll: str) -> dict:
    return conn.send({"type": "REINDEX", "databaseName": DB, "collectionName": coll, "primaryKey": True})


def seed_collections_for_primary_key_rebuilds(conn: Conn):
    section("Seed collections whose pk.idx will be unreadable, misaddressed, short a row, or face a duplicated record")
    for coll in (REBUILD_UNREADABLE_COLL, REBUILD_SWAPPED_COLL, REBUILD_LOST_ROW_COLL, REBUILD_DUPLICATE_COLL):
        check_status(f"create {coll}", conn.create_coll(coll), "OK")
        check_status(f"index pad on {coll}", conn.create_index("pad", coll=coll), "OK")
        for doc_id in REBUILD_IDS:
            check_status(f"save {doc_id} into {coll}", conn.save({"_id": doc_id, "pad": doc_id}, coll=coll), "OK")


def damage_the_pk_indexes_to_rebuild(work_dir: str):
    with open(pk_index_file(work_dir, DB, REBUILD_UNREADABLE_COLL), "w") as index:
        index.write(OLD_GRAMMAR_PK)
    swapped_path = pk_index_file(work_dir, DB, REBUILD_SWAPPED_COLL)
    rows = bu.read_pk_rows(swapped_path)
    first, second = rows[0], rows[1]
    first[1:3], second[1:3] = second[1:3], first[1:3]
    bu.write_pk_rows(swapped_path, rows)
    lost_row_path = pk_index_file(work_dir, DB, REBUILD_LOST_ROW_COLL)
    bu.write_pk_rows(lost_row_path, [row for row in bu.read_pk_rows(lost_row_path) if row[0] != "bravo"])
    page = os.path.join(work_dir, "db", DB, REBUILD_DUPLICATE_COLL, page_files(work_dir, DB, REBUILD_DUPLICATE_COLL)[0])
    with open(page, "rb") as fp:
        first_record = fp.read().split(b"\n")[0] + b"\n"
    with open(page, "ab") as fp:
        fp.write(first_record)


def pad_of(response: dict):
    return (response.get("object") or {}).get("pad")


def test_reindex_primary_key_rebuilds_an_unrecognised_pk_index(conn: Conn):
    section("REINDEX primaryKey rebuilds an unreadable pk.idx from the pages, at version 0")
    coll = REBUILD_UNREADABLE_COLL
    check_status("before the rebuild a FIND_BY_ID answers an error", conn.find("alpha", coll=coll), "ERROR")
    rebuilt = reindex_primary_key(conn, coll)
    check_status("REINDEX primaryKey answers OK", rebuilt, "OK")
    check("every record became an entry at version 0",
          rebuilt.get("primaryKeyEntries") == 3 and rebuilt.get("unversionedEntries") == 3, f"response={rebuilt}")
    check("with nothing to compare against, every field index was rebuilt", rebuilt.get("rebuiltFields") == ["pad"],
          f"rebuiltFields={rebuilt.get('rebuiltFields')}")
    for doc_id in REBUILD_IDS:
        check_status(f"{doc_id} reads back", conn.find(doc_id, coll=coll), "OK")
    check_status("a SAVE now lands", conn.save({"_id": "delta", "pad": "delta"}, coll=coll), "OK")
    pk = conn.count_via_pk(coll=coll)
    scan = conn.count_via_scan(coll=coll)
    check("the index-only COUNT agrees with the scan", pk == scan == 4, f"pk={pk} scan={scan}")


def test_reindex_primary_key_repairs_swapped_offsets(conn: Conn):
    section("REINDEX primaryKey re-addresses rows whose offsets were swapped, keeping their versions")
    coll = REBUILD_SWAPPED_COLL
    rebuilt = reindex_primary_key(conn, coll)
    check_status("REINDEX primaryKey answers OK", rebuilt, "OK")
    check("every version survived and no field index was rebuilt",
          rebuilt.get("unversionedEntries") == 0 and rebuilt.get("rebuiltFields") == [], f"response={rebuilt}")
    for doc_id in REBUILD_IDS:
        found = conn.find(doc_id, coll=coll)
        check(f"{doc_id} reads back its own document", found.get("status") == "OK" and pad_of(found) == doc_id,
              f"response={found}")


def test_reindex_primary_key_restores_a_record_pk_idx_lost(conn: Conn):
    section("REINDEX primaryKey indexes a record pk.idx lost from the middle of a page")
    coll = REBUILD_LOST_ROW_COLL
    check_status("before the rebuild the middle record is unreachable by id", conn.find("bravo", coll=coll),
                 "NOT_FOUND")
    rebuilt = reindex_primary_key(conn, coll)
    check_status("REINDEX primaryKey answers OK", rebuilt, "OK")
    check("only the lost record is at version 0 and no field index was rebuilt",
          rebuilt.get("unversionedEntries") == 1 and rebuilt.get("rebuiltFields") == [], f"response={rebuilt}")
    check_status("bravo reads back", conn.find("bravo", coll=coll), "OK")
    by_index = bu.wait_until(lambda: conn.ids(coll, [bu.filter_step("pad", "EQUALS", "bravo")]) == ["bravo"], 10)
    check("an index-backed FILTER finds bravo", bool(by_index))
    pk = conn.count_via_pk(coll=coll)
    scan = conn.count_via_scan(coll=coll)
    check("the index-only COUNT agrees with the scan", pk == scan == 3, f"pk={pk} scan={scan}")


def test_reindex_primary_key_refuses_a_duplicated_record(conn: Conn, work_dir: str):
    section("REINDEX primaryKey refuses a page holding the same _id twice and leaves pk.idx untouched")
    coll = REBUILD_DUPLICATE_COLL
    with open(pk_index_file(work_dir, DB, coll), "rb") as index:
        before = index.read()
    refused = reindex_primary_key(conn, coll)
    check_code("REINDEX primaryKey answers 409-15", refused, "ERROR", "409-15")
    with open(pk_index_file(work_dir, DB, coll), "rb") as index:
        check("pk.idx is byte-identical", index.read() == before)
    check_status("the collection still reads as before", conn.find("alpha", coll=coll), "OK")


def seed_collections_for_a_lost_line_end_and_a_half_built_index(conn: Conn):
    section("Seed a collection whose last record will lose its line end, and one whose index build will not finish")
    for coll in (LINE_END_COLL, HALF_BUILT_COLL):
        check_status(f"create {coll}",
                     conn.create_coll(coll), "OK")
    for i in range(LINE_END_DOCS):
        check_status(f"save line{i}", conn.save({"_id": f"line{i}", "pad": "s"}, coll=LINE_END_COLL), "OK")
    check_status("create the index that will be left half-built",
                 conn.create_index(HALF_BUILT_FIELD, coll=HALF_BUILT_COLL), "OK")
    for i in range(LINE_END_DOCS):
        check_status(f"save half{i}", conn.save({"_id": f"half{i}", HALF_BUILT_FIELD: "same"}, coll=HALF_BUILT_COLL),
                     "OK")


def lose_the_last_line_end(work_dir: str):
    folder = os.path.join(work_dir, "db", DB, LINE_END_COLL)
    last = last_page(page_files(work_dir, DB, LINE_END_COLL))
    path = os.path.join(folder, last)
    with open(path, "rb+") as page:
        page.seek(-1, os.SEEK_END)
        if page.read(1) == b"\n":
            page.seek(-1, os.SEEK_END)
            page.truncate()


def leave_the_index_half_built(work_dir: str):
    folder = os.path.join(work_dir, "db", DB, HALF_BUILT_COLL)
    prefix = f"{HALF_BUILT_COLL}-{HALF_BUILT_FIELD}-"
    for name in os.listdir(folder):
        if name.startswith(prefix) and name.endswith(".idx"):
            os.remove(os.path.join(folder, name))
    with open(os.path.join(folder, f"{HALF_BUILT_COLL}-{HALF_BUILT_FIELD}.building"), "w") as marker:
        marker.write("0")


def test_a_lost_line_end_is_restored_at_startup(conn: Conn, work_dir: str):
    section("A record that lost only its line end is restored at startup, so the next write is not glued onto it")
    unterminated = unterminated_pages(os.path.join(work_dir, "db", DB, LINE_END_COLL),
                                      page_files(work_dir, DB, LINE_END_COLL))
    check("every page ends at a record boundary after the restart", not unterminated, f"torn: {unterminated}")
    check_status("save a document after the restart",
                 conn.save({"_id": "after_line_end", "pad": "s"}, coll=LINE_END_COLL), "OK")
    expected = sorted([f"line{i}" for i in range(LINE_END_DOCS)] + ["after_line_end"])
    scanned = conn.ids(LINE_END_COLL, FULL_SCAN)
    check("a full scan sees the record that lost its line end and the one written after it", scanned == expected,
          f"expected {expected} got {scanned}")
    for doc_id in ("line%d" % (LINE_END_DOCS - 1), "after_line_end"):
        check_status(f"FIND_BY_ID {doc_id}", conn.find(doc_id, coll=LINE_END_COLL), "OK")


def seed_a_collection_that_will_hold_an_unindexed_record(conn: Conn):
    section("Seed an indexed collection whose last page will hold a record its PK index never named")
    check_status("create the collection",
                 conn.create_coll(ORPHAN_COLL), "OK")
    check_status("index the field the unindexed record will carry",
                 conn.create_index(ORPHAN_FIELD, coll=ORPHAN_COLL), "OK")
    for i in range(ORPHAN_DOCS):
        check_status(f"save kept{i}", conn.save({"_id": f"kept{i}", ORPHAN_FIELD: "same"}, coll=ORPHAN_COLL), "OK")


def leave_an_unindexed_record(work_dir: str):
    folder = os.path.join(work_dir, "db", DB, ORPHAN_COLL)
    last = last_page(page_files(work_dir, DB, ORPHAN_COLL))
    record = json.dumps({"_id": ORPHAN_ID, ORPHAN_FIELD: "same"}, separators=(",", ":")) + "\n"
    with open(os.path.join(folder, last), "ab") as page:
        page.write(record.encode("utf-8"))


KILLED_UPDATE_COLL = "killed_update_docs"
KILLED_DELETE_COLL = "killed_delete_docs"
KILLED_RELOCATE_COLL = "killed_relocate_docs"
KILLED_DOCS = 5
KILLED_ID = "killed1"


def killed_doc(i: int, value: str = "acknowledged") -> dict:
    return {"_id": f"killed{i}", "v": f"{value}-{i}"}


def seed_collections_whose_compactions_a_kill_will_interrupt(conn: Conn):
    section("Seed collections whose in-place update, delete and relocation a kill will interrupt")
    for coll in (KILLED_UPDATE_COLL, KILLED_DELETE_COLL, KILLED_RELOCATE_COLL):
        check_status(f"create {coll}",
                     conn.create_coll(coll), "OK")
        for i in range(KILLED_DOCS):
            check_status(f"save killed{i} into {coll}", conn.save(killed_doc(i), coll=coll), "OK")
    check_status(f"index v in {KILLED_DELETE_COLL}",
                 conn.create_index("v", coll=KILLED_DELETE_COLL), "OK")


def interrupt_a_compaction(work_dir: str, coll: str, kind: str):
    folder = os.path.join(work_dir, "db", DB, coll)
    pk_path = pk_index_file(work_dir, DB, coll)
    rows = bu.read_pk_rows(pk_path)
    row = next(r for r in rows if r[0] == KILLED_ID)
    _, position, length, page, _ = row
    page_path = os.path.join(folder, f"{coll}-{page}.dat")
    with open(page_path, "rb") as fp:
        page_bytes = fp.read()
    bu.write_compaction_marker(folder, kind, DB, coll, row, page_bytes)
    shifted = page_bytes[:position] + page_bytes[position + length:]
    if kind == "UPDATE":
        shifted += (json.dumps(killed_doc(1, "unacknowledged"), separators=(",", ":")) + "\n").encode("utf-8")
    with open(page_path, "wb") as fp:
        fp.write(shifted)
    if kind == "RELOCATE":
        kept = [r for r in rows if r[0] != KILLED_ID]
        for r in kept:
            if r[3] == page and r[1] > position:
                r[1] -= length
        bu.write_pk_rows(pk_path, kept)


def interrupt_the_compactions(work_dir: str):
    interrupt_a_compaction(work_dir, KILLED_UPDATE_COLL, "UPDATE")
    interrupt_a_compaction(work_dir, KILLED_DELETE_COLL, "DELETE")
    interrupt_a_compaction(work_dir, KILLED_RELOCATE_COLL, "RELOCATE")


def found_value(conn: Conn, coll: str, doc_id: str):
    response = conn.find(doc_id, coll=coll)
    return (response.get("object") or {}).get("v") if response.get("status") == "OK" else None


def compaction_markers_left(work_dir: str, coll: str) -> list:
    folder = os.path.join(work_dir, "db", DB, coll)
    return [name for name in os.listdir(folder) if name.endswith(".compacting")]


def check_every_document_is_acknowledged(conn: Conn, work_dir: str, coll: str, expected_ids: list):
    values = {f"killed{i}": found_value(conn, coll, f"killed{i}") for i in range(KILLED_DOCS)}
    expected = {f"killed{i}": (f"acknowledged-{i}" if f"killed{i}" in expected_ids else None)
                for i in range(KILLED_DOCS)}
    check("FIND_BY_ID answers every document with its last acknowledged value", values == expected,
          f"expected {expected} got {values}")
    scanned = conn.ids(coll, FULL_SCAN)
    check("a full scan lists exactly the same documents", scanned == sorted(expected_ids),
          f"expected {sorted(expected_ids)} got {scanned}")
    pk = conn.count_via_pk(coll=coll)
    check("the index-only COUNT agrees with the scan", pk == len(expected_ids), f"count={pk}")
    check("the compaction marker is gone", not compaction_markers_left(work_dir, coll),
          f"left: {compaction_markers_left(work_dir, coll)}")


def test_an_update_killed_mid_compaction_is_undone_at_startup(conn: Conn, work_dir: str):
    section("An in-place update a kill interrupted mid-compaction is undone at startup")
    check_every_document_is_acknowledged(conn, work_dir, KILLED_UPDATE_COLL,
                                         [f"killed{i}" for i in range(KILLED_DOCS)])


def test_a_delete_killed_mid_compaction_is_completed_at_startup(conn: Conn, work_dir: str):
    section("A delete a kill interrupted mid-compaction is completed at startup")
    check_every_document_is_acknowledged(conn, work_dir, KILLED_DELETE_COLL,
                                         [f"killed{i}" for i in range(KILLED_DOCS) if f"killed{i}" != KILLED_ID])


def killed_value_count(conn: Conn, prefix: list) -> int:
    return conn.count(KILLED_DELETE_COLL, prefix + [bu.filter_step("v", "EQUALS", killed_doc(1)["v"])]) or 0


def test_a_delete_killed_mid_compaction_leaves_no_index_entry(conn: Conn):
    section("A delete a kill interrupted mid-compaction leaves no field-index entry behind")
    scanned = killed_value_count(conn, FULL_SCAN)
    bu.wait_until(lambda: killed_value_count(conn, []) == 0, timeout_s=15, interval_s=0.2)
    indexed = killed_value_count(conn, [])
    check("the scan no longer finds the deleted document", scanned == 0, f"scan={scanned}")
    check("the index-only COUNT agrees with the scan once startup removed the deleted id",
          indexed == scanned, f"indexed={indexed} scan={scanned}")


def test_a_relocation_killed_between_delete_and_insert_keeps_the_document(conn: Conn, work_dir: str):
    section("A grow-relocation a kill interrupted between its delete and its insert keeps the document")
    check_every_document_is_acknowledged(conn, work_dir, KILLED_RELOCATE_COLL,
                                         [f"killed{i}" for i in range(KILLED_DOCS)])


def leftover_rows_folder(work_dir: str) -> str:
    return os.path.join(work_dir, "db", "admin", "pages", f"{DB}_{LEFTOVER_ROWS_COLL}")


def page_rows_mid_compaction(work_dir: str, coll: str) -> bool:
    folder = os.path.join(work_dir, "db", "admin", "pages", f"{DB}_{coll}")
    return os.path.isdir(folder) and any(name.endswith(".compacting") for name in os.listdir(folder))


def wait_for_settled_page_rows(work_dir: str, coll: str, timeout_s=15.0):
    seen = {"recorded": {}, "actual": {}}

    def settled() -> bool:
        recorded = seen["recorded"] = recorded_pages(work_dir, DB, coll)
        actual = seen["actual"] = actual_pages(work_dir, DB, coll)
        return bool(recorded) and not page_rows_mid_compaction(work_dir, coll) and {
            page: (row["size"], row["entryCount"]) for page, row in recorded.items() if page in actual} == actual

    ok = bool(bu.wait_until(settled, timeout_s=timeout_s, interval_s=0.2))
    return ok, seen["recorded"], seen["actual"]


def seed_drops_a_kill_will_interrupt(conn: Conn, work_dir: str):
    section("Seed a collection and a database whose drop a kill will interrupt, and a drop that leaves page rows")
    check_status("create the collection whose drop will be interrupted",
                 conn.create_coll(KILLED_DROP_COLL), "OK")
    check_status("write into it", conn.save({"_id": "doomed", "pad": "s"}, coll=KILLED_DROP_COLL), "OK")
    check_status("create the database whose drop will be interrupted",
                 conn.send({"type": "CREATE_DATABASE", "databaseName": KILLED_DROP_DB}), "OK")
    check_status("create a collection in it",
                 conn.create_coll(COLL, db=KILLED_DROP_DB),
                 "OK")
    check_status("create the collection whose page rows a kill will leave behind",
                 conn.create_coll(LEFTOVER_ROWS_COLL), "OK")
    for i in range(3):
        conn.save({"_id": f"old{i}", "pad": "s" * 200}, coll=LEFTOVER_ROWS_COLL)
    settled, recorded, actual = wait_for_settled_page_rows(work_dir, LEFTOVER_ROWS_COLL)
    check("its page rows reached disk", settled, f"recorded {recorded} actual {actual}")
    copy = os.path.join(work_dir, LEFTOVER_ROWS_COPY)
    shutil.rmtree(copy, ignore_errors=True)
    shutil.copytree(leftover_rows_folder(work_dir), copy, ignore=shutil.ignore_patterns("*.compacting"))
    check_status("drop it", conn.send({"type": "DROP_COLLECTION", "databaseName": DB,
                                       "collectionName": LEFTOVER_ROWS_COLL}), "OK")


def interrupt_the_drops(work_dir: str):
    shutil.rmtree(os.path.join(work_dir, "db", DB, KILLED_DROP_COLL))
    shutil.rmtree(os.path.join(work_dir, "db", KILLED_DROP_DB))
    shutil.copytree(os.path.join(work_dir, LEFTOVER_ROWS_COPY), leftover_rows_folder(work_dir))


def collection_names(conn: Conn, db: str) -> list:
    return conn.send({"type": "LIST_COLLECTIONS", "databaseName": db}).get("collections") or []


def test_a_drop_interrupted_after_its_folder_was_deleted_can_be_retried(conn: Conn):
    section("A drop a kill interrupted after its folder was deleted can be retried")
    check("the interrupted collection is still registered", KILLED_DROP_COLL in collection_names(conn, DB),
          f"collections: {collection_names(conn, DB)}")
    check_status("DROP_COLLECTION of it succeeds",
                 conn.send({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": KILLED_DROP_COLL}),
                 "OK")
    check("it is no longer listed", KILLED_DROP_COLL not in collection_names(conn, DB),
          f"collections: {collection_names(conn, DB)}")
    check_status("CREATE_COLLECTION of the name succeeds",
                 conn.create_coll(KILLED_DROP_COLL),
                 "OK")
    old = conn.find("doomed", coll=KILLED_DROP_COLL)
    check("the re-created collection is empty", old.get("status") != "OK", f"response: {old}")
    check_status("DROP_DATABASE of the interrupted database succeeds",
                 conn.send({"type": "DROP_DATABASE", "databaseName": KILLED_DROP_DB}), "OK")
    check_status("CREATE_DATABASE of the name succeeds",
                 conn.send({"type": "CREATE_DATABASE", "databaseName": KILLED_DROP_DB}), "OK")


def test_a_recreated_collection_does_not_inherit_leftover_page_rows(conn: Conn, work_dir: str):
    section("A re-created collection starts from empty page rows")
    check("the kill left the dropped collection's page rows on disk", os.path.isdir(leftover_rows_folder(work_dir)))
    check_status("re-create the collection",
                 conn.create_coll(LEFTOVER_ROWS_COLL), "OK")
    for i in range(2):
        check_status(f"save new{i}", conn.save({"_id": f"new{i}", "pad": "n"}, coll=LEFTOVER_ROWS_COLL), "OK")
    settled, recorded, actual = wait_for_settled_page_rows(work_dir, LEFTOVER_ROWS_COLL)
    pages_coll = f"{DB}_{LEFTOVER_ROWS_COLL}"
    row_ids = [row[0] for row in bu.read_pk_rows(os.path.join(leftover_rows_folder(work_dir), f"{pages_coll}-pk.idx"))]
    check("the page-row index names every row once", len(row_ids) == len(set(row_ids)), f"row ids: {row_ids}")
    check("the recorded page occupancy matches the pages on disk", settled, f"recorded {recorded} actual {actual}")


def test_an_unindexed_record_is_adopted_at_startup(conn: Conn):
    section("A record a crash left on a page but out of the PK index is adopted at startup")
    expected = sorted([f"kept{i}" for i in range(ORPHAN_DOCS)] + [ORPHAN_ID])
    check_status("FIND_BY_ID reaches the adopted record",
                 conn.find(ORPHAN_ID, coll=ORPHAN_COLL), "OK")
    scanned = conn.ids(ORPHAN_COLL, FULL_SCAN)
    check("a full scan lists every record once", scanned == expected, f"expected {expected} got {scanned}")
    pk = conn.count_via_pk(coll=ORPHAN_COLL)
    check("the index-only COUNT agrees with the scan", pk == len(expected), f"count={pk} scan={len(scanned)}")
    indexed = conn.ids(ORPHAN_COLL, [bu.filter_step(ORPHAN_FIELD, "EQUALS", "same")])
    check("an index-backed FILTER finds the adopted record", indexed == expected, f"got {indexed}")

    check_status("save the adopted record again, as a client retrying its write would",
                 conn.save({"_id": ORPHAN_ID, ORPHAN_FIELD: "same", "retried": True}, coll=ORPHAN_COLL), "OK")
    rescanned = conn.ids(ORPHAN_COLL, FULL_SCAN)
    check("the retry updates the adopted record instead of adding a second row with its id",
          rescanned == expected, f"expected {expected} got {rescanned}")
    pk = conn.count_via_pk(coll=ORPHAN_COLL)
    check("the index-only COUNT still agrees after the retry", pk == len(expected), f"count={pk}")


def test_a_half_built_index_is_answered_by_a_scan_until_rebuilt(conn: Conn, work_dir: str, log_path: str,
                                                                log_offset: int):
    section("A half-built index is declined until REINDEX, instead of answering from a partial file")
    log = bu.read_log(log_path)[log_offset:]
    check("startup names the half-built index", f"{DB}|{HALF_BUILT_COLL}|{HALF_BUILT_FIELD}" in log
          and "half-built" in log, "no startup warning about the half-built index")
    check_status("save a document after the restart",
                 conn.save({"_id": "half_after", HALF_BUILT_FIELD: "same"}, coll=HALF_BUILT_COLL), "OK")
    expected = sorted([f"half{i}" for i in range(LINE_END_DOCS)] + ["half_after"])
    found = conn.ids(HALF_BUILT_COLL, [bu.filter_step(HALF_BUILT_FIELD, "EQUALS", "same")])
    check("FILTER EQUALS on the half-built field still finds every older document", found == expected,
          f"expected {expected} got {found}")
    check_status("REINDEX the half-built field",
                 conn.send({"type": "REINDEX", "databaseName": DB, "collectionName": HALF_BUILT_COLL,
                            "fieldNames": [HALF_BUILT_FIELD]}), "OK")
    marker = os.path.join(work_dir, "db", DB, HALF_BUILT_COLL, f"{HALF_BUILT_COLL}-{HALF_BUILT_FIELD}.building")
    check("REINDEX clears the half-built marker", not os.path.exists(marker), marker)
    found = conn.ids(HALF_BUILT_COLL, [bu.filter_step(HALF_BUILT_FIELD, "EQUALS", "same")])
    check("the rebuilt index answers every document", found == expected, f"expected {expected} got {found}")


ADMIN_HEAL_DB = "heal_admin_db"


def admin_databases_folder(work_dir: str) -> str:
    return os.path.join(work_dir, "db", "admin", "databases")


def tear_an_admin_page_tail(work_dir: str):
    folder = admin_databases_folder(work_dir)
    last = last_page([name for name in os.listdir(folder) if name.endswith(".dat")])
    with open(os.path.join(folder, last), "ab") as page:
        page.write(TORN_BYTES.encode("utf-8"))


def seed_a_collection_its_database_will_stop_listing(conn: Conn):
    section("Seed a collection whose database row a kill will leave without it")
    check_status("create its database", conn.send({"type": "CREATE_DATABASE", "databaseName": UNLISTED_DB}), "OK")
    check_status("create the collection", conn.create_coll(UNLISTED_COLL, db=UNLISTED_DB), "OK")
    check_status("write into it", conn.save({"_id": "kept"}, db=UNLISTED_DB, coll=UNLISTED_COLL), "OK")


def database_row_lines(work_dir: str, db: str) -> list:
    folder = admin_databases_folder(work_dir)
    marker = f'"{db}"'.encode("utf-8")
    found = []
    for name in sorted(os.listdir(folder)):
        if name.endswith(".dat"):
            with open(os.path.join(folder, name), "rb") as page:
                found.extend(line for line in page.read().split(b"\n") if marker in line)
    return found


def unlist_the_collection(work_dir: str):
    listed, unlisted = f'"{UNLISTED_COLL}"'.encode("utf-8"), f'"{UNLISTED_GHOST}"'.encode("utf-8")
    folder = admin_databases_folder(work_dir)
    rewritten = 0
    for name in os.listdir(folder):
        if not name.endswith(".dat"):
            continue
        path = os.path.join(folder, name)
        with open(path, "rb") as page:
            lines = page.read().split(b"\n")
        changed = [line.replace(listed, unlisted) if f'"{UNLISTED_DB}"'.encode("utf-8") in line else line
                   for line in lines]
        if changed != lines:
            rewritten += 1
            with open(path, "wb") as page:
                page.write(b"\n".join(changed))
    check("the database row no longer lists its collection, as a kill mid-CREATE_COLLECTION leaves it",
          rewritten == 1, f"rewrote {rewritten} page(s)")


def test_an_unlisted_collection_is_relisted_at_startup(conn: Conn, work_dir: str):
    section("A registered collection its database row does not list is re-listed at startup")
    rows = database_row_lines(work_dir, UNLISTED_DB)
    check("the database row lists the collection again", any(f'"{UNLISTED_COLL}"'.encode("utf-8") in row
                                                              for row in rows), f"rows: {rows}")
    check_status("its document is still served", conn.find("kept", db=UNLISTED_DB, coll=UNLISTED_COLL), "OK")
    check_status("drop the database", conn.send({"type": "DROP_DATABASE", "databaseName": UNLISTED_DB}), "OK")
    check_status("re-create it", conn.send({"type": "CREATE_DATABASE", "databaseName": UNLISTED_DB}), "OK")
    leftover = collection_names(conn, UNLISTED_DB)
    check("the re-created database inherits no collection from the dropped one", not leftover,
          f"collections: {leftover}")


def test_an_admin_write_after_a_torn_admin_tail_lands_on_its_own_line(conn: Conn, work_dir: str):
    section("A torn admin page tail is healed at startup, so the next admin write is not glued onto it")
    folder = admin_databases_folder(work_dir)
    unterminated = unterminated_pages(folder, [name for name in os.listdir(folder) if name.endswith(".dat")])
    check("every admin/databases page ends at a record boundary after the restart", not unterminated,
          f"torn: {unterminated}")
    check_status("CREATE_DATABASE after the restart",
                 conn.send({"type": "CREATE_DATABASE", "databaseName": ADMIN_HEAL_DB}), "OK")


def test_the_admin_write_after_the_tear_is_still_there(conn: Conn):
    section("The admin record written after a torn admin tail survives the next restart")
    databases = conn.send({"type": "LIST_DATABASES"}).get("databases") or []
    check("LIST_DATABASES still lists the database created after the tear", ADMIN_HEAL_DB in databases,
          f"databases={databases}")


EMPTY_IDX_COLL = "emptied_index_docs"


def index_files(work_dir: str, db: str, coll: str) -> dict:
    folder = os.path.join(work_dir, "db", db, coll)
    if not os.path.isdir(folder):
        return {}
    sizes = {}
    for f in os.listdir(folder):
        if f.endswith(".idx"):
            try:
                sizes[f] = os.path.getsize(os.path.join(folder, f))
            except FileNotFoundError:
                continue
    return sizes


def await_index_file(work_dir: str, coll: str, name: str, present: bool,
                     timeout: float = 15.0) -> bool:
    return bool(bu.wait_until(lambda: (name in index_files(work_dir, DB, coll)) == present, timeout, 0.2))


def test_an_index_with_no_entries_left_is_removed(conn: Conn, work_dir: str):
    section("An index file whose last line was removed is deleted, not left empty")
    boolean_index = f"{EMPTY_IDX_COLL}-mixed-Boolean.idx"
    check_status("create the collection",
                 conn.create_coll(EMPTY_IDX_COLL), "OK")
    check_status("index the field",
                 conn.create_index("mixed", coll=EMPTY_IDX_COLL), "OK")
    check_status("write a boolean-valued document",
                 conn.save({"_id": "bool1", "mixed": True}, coll=EMPTY_IDX_COLL), "OK")
    for i in range(3):
        check_status(f"write a string-valued document {i}",
                     conn.save({"_id": f"str{i}", "mixed": f"value{i}"}, coll=EMPTY_IDX_COLL), "OK")
    check("the boolean index file exists while a boolean value does",
          await_index_file(work_dir, EMPTY_IDX_COLL, boolean_index, True),
          f"index files: {sorted(index_files(work_dir, DB, EMPTY_IDX_COLL))}")

    check_status("rewrite the only boolean document with a string value",
                 conn.save({"_id": "bool1", "mixed": "value9"}, coll=EMPTY_IDX_COLL), "OK")

    check("the now-entryless boolean index file is gone rather than empty",
          await_index_file(work_dir, EMPTY_IDX_COLL, boolean_index, False),
          f"index files: {index_files(work_dir, DB, EMPTY_IDX_COLL)}")

    contains = bu.filter_step("mixed", "CONTAINS", "value")
    scanned_ids = conn.ids(EMPTY_IDX_COLL, [bu.filter_step("_id", "NOT_EQUALS", "__none__"), contains])
    indexed_ids = conn.ids(EMPTY_IDX_COLL, [contains])
    check("CONTAINS still answers exactly what a scan answers",
          scanned_ids == indexed_ids == ["bool1", "str0", "str1", "str2"],
          f"scan={scanned_ids} indexed={indexed_ids}")


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

    drifted = occupancy_drift(recorded, actual)
    check("the recorded page occupancy still matches the page on disk", not drifted,
          "first-fit is packing rows into a page it believes has room — " + "; ".join(drifted) if drifted else "")


def test_page_metadata_follows_dropped_databases(conn: Conn, work_dir: str):
    section("Page occupancy for admin/databases after dropping databases that hold collections")
    refused = []
    for db_index in range(DROPPED_DBS):
        db_name = f"dropped_db_{db_index:02d}"
        refused.append(conn.send({"type": "CREATE_DATABASE", "databaseName": db_name}))
        for coll_index in range(DROPPED_DB_COLLECTIONS):
            refused.append(conn.create_coll(f"collection_with_a_long_name_{coll_index:02d}", db=db_name))
        refused.append(conn.send({"type": "DROP_DATABASE", "databaseName": db_name}))
    refused = [response for response in refused if response.get("status") != "OK"]
    check(f"{DROPPED_DBS} databases with collections were created and dropped", not refused,
          f"{len(refused)} requests failed; first: {refused[:1]}")

    drifted = occupancy_drift(recorded_pages(work_dir, "admin", "databases"),
                              actual_pages(work_dir, "admin", "databases"), entries=False)
    check("the recorded admin/databases page size still matches the page on disk", not drifted,
          "; ".join(drifted))


def test_page_metadata_follows_collections_dropped_after_a_restart(conn: Conn, work_dir: str):
    section("Page occupancy for admin/collections after dropping collections registered before the restart")
    refused = [response for response in (
        conn.send({"type": "DROP_COLLECTION", "databaseName": ADMIN_PAGES_DB,
                   "collectionName": admin_pages_collection_name(i)}) for i in range(ADMIN_PAGES_COLLECTIONS))
        if response.get("status") != "OK"]
    check(f"{ADMIN_PAGES_COLLECTIONS} collections loaded at startup were dropped", not refused,
          f"{len(refused)} requests failed; first: {refused[:1]}")

    drifted = occupancy_drift(recorded_pages(work_dir, "admin", "collections"),
                              actual_pages(work_dir, "admin", "collections"))
    check("the recorded admin/collections occupancy still matches every page on disk", not drifted,
          "a dropped row's delta landed on another page — " + "; ".join(drifted) if drifted else "")


COLLIDING_DB_LONG, COLLIDING_COLL_SHORT = "pgfold_one", "two"
COLLIDING_DB_SHORT, COLLIDING_COLL_LONG = "pgfold", "one_two"
COLLIDING_DOCS = 30


def rows_agree_with_pages(work_dir: str, db: str, coll: str) -> bool:
    recorded = recorded_pages(work_dir, db, coll)
    actual = actual_pages(work_dir, db, coll)
    return bool(actual) and all(page in recorded and recorded[page]["size"] == actual[page][0] for page in actual)


def test_dropping_a_colliding_collection_keeps_the_others_page_rows(conn: Conn, work_dir: str):
    section("Dropping a collection whose page-metadata folder another collection shares")
    for db, coll in ((COLLIDING_DB_LONG, COLLIDING_COLL_SHORT), (COLLIDING_DB_SHORT, COLLIDING_COLL_LONG)):
        conn.send({"type": "CREATE_DATABASE", "databaseName": db})
        check_status(f"create {db}|{coll}",
                     conn.create_coll(coll, db=db), "OK")
    check_status("write the collection that is dropped",
                 conn.save({"_id": "gone", "pad": PAD}, db=COLLIDING_DB_LONG, coll=COLLIDING_COLL_SHORT), "OK")
    for i in range(COLLIDING_DOCS):
        conn.save({"_id": f"kept{i:02d}", "pad": PAD}, db=COLLIDING_DB_SHORT, coll=COLLIDING_COLL_LONG)
    bu.wait_until(lambda: rows_agree_with_pages(work_dir, COLLIDING_DB_SHORT, COLLIDING_COLL_LONG),
                  timeout_s=10, interval_s=0.1)
    check("the surviving collection's rows reached the shared folder",
          rows_agree_with_pages(work_dir, COLLIDING_DB_SHORT, COLLIDING_COLL_LONG),
          f"recorded={recorded_pages(work_dir, COLLIDING_DB_SHORT, COLLIDING_COLL_LONG)}")

    check_status("drop the colliding collection",
                 conn.send({"type": "DROP_COLLECTION", "databaseName": COLLIDING_DB_LONG,
                            "collectionName": COLLIDING_COLL_SHORT}), "OK")
    check("the surviving collection still has a row matching every page on disk",
          rows_agree_with_pages(work_dir, COLLIDING_DB_SHORT, COLLIDING_COLL_LONG),
          f"recorded={recorded_pages(work_dir, COLLIDING_DB_SHORT, COLLIDING_COLL_LONG)} "
          f"actual={actual_pages(work_dir, COLLIDING_DB_SHORT, COLLIDING_COLL_LONG)}")
    check("the dropped collection left no rows behind",
          not recorded_pages(work_dir, COLLIDING_DB_LONG, COLLIDING_COLL_SHORT),
          f"got {recorded_pages(work_dir, COLLIDING_DB_LONG, COLLIDING_COLL_SHORT)}")
    pk = conn.count_via_pk(db=COLLIDING_DB_SHORT, coll=COLLIDING_COLL_LONG)
    check("every surviving document is still there", pk == COLLIDING_DOCS, f"pk={pk}")


def test_drop_database_does_not_strand_a_collection_lock(conn: Conn):
    section("DROP_DATABASE against concurrent writers")
    check_status("create the database", conn.send({"type": "CREATE_DATABASE", "databaseName": LOCK_DB}), "OK")
    check_status("create the collection",
                 conn.create_coll(LOCK_COLL, db=LOCK_DB),
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
                 conn.create_coll(LOCK_COLL, db=LOCK_DB),
                 "OK")
    check_status("a write after the re-create succeeds",
                 conn.save({"_id": "after-drop", "pad": "x"}, db=LOCK_DB, coll=LOCK_COLL), "OK")


def test_drop_and_recreate_a_database_does_not_serve_stale_documents(conn: Conn):
    section("DROP_DATABASE then CREATE_DATABASE with the same ids")
    conn.count_via_pk(db=RECREATE_DB)

    check_status("drop the database", conn.send({"type": "DROP_DATABASE", "databaseName": RECREATE_DB}), "OK")
    check_status("create it again", conn.send({"type": "CREATE_DATABASE", "databaseName": RECREATE_DB}), "OK")
    check_status("create the collection again",
                 conn.create_coll(COLL, db=RECREATE_DB), "OK")
    check_status("write the same id again", conn.save({"_id": "shared", "pad": "after"}, db=RECREATE_DB), "OK")

    found = conn.find("shared", db=RECREATE_DB)
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


def test_a_bulk_insert_leaves_no_document_the_pk_index_cannot_reach(conn: Conn):
    section("bulk insert: scan and the pk index agree")
    conn.create_coll(BULK_COLL)
    objects = [{"_id": f"bulk_{i:04d}", "pad": PAD} for i in range(60)]
    check_status("bulk insert across several pages",
                 conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": BULK_COLL,
                            "objects": objects}), "OK")

    scan_ids = conn.ids(BULK_COLL, [])
    missing = [i for i in scan_ids
               if conn.find(i, coll=BULK_COLL).get("status") != "OK"]
    check("every bulk-inserted document a scan returns is reachable by FIND_BY_ID",
          len(scan_ids) == len(objects) and not missing,
          f"scanned={len(scan_ids)} expected={len(objects)} unreachable={missing[:5]}")
    check("no id appears twice on the page", len(scan_ids) == len(set(scan_ids)),
          f"scanned={len(scan_ids)} distinct={len(set(scan_ids))}")

    counted_rows = conn.count(BULK_COLL, [])
    check("the pk index describes no row the pages cannot produce",
          counted_rows == len(scan_ids),
          f"index-only COUNT={counted_rows} scan={len(scan_ids)}")


def test_a_bulk_update_of_many_entries_leaves_every_document_consistent(conn: Conn):
    section("bulk update of many entries: scan, FIND_BY_ID and the field index all agree")
    objects = [{"_id": f"bulk_{i:04d}", "pad": "updated-" + PAD} for i in range(60)]
    check_status("bulk update across several pages",
                 conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": BULK_COLL,
                            "objects": objects}), "OK")

    scan_by_id = {d.get("_id"): d.get("pad") for d in (conn.aggregate(BULK_COLL, []).get("results") or [])}
    check("every entry the scan returns reflects the bulk update",
          len(scan_by_id) == len(objects) and all(v == "updated-" + PAD for v in scan_by_id.values()),
          f"scanned={len(scan_by_id)} expected={len(objects)}"
          f" stale={[k for k, v in scan_by_id.items() if v != 'updated-' + PAD][:5]}")

    stale_by_find = [i for i in scan_by_id
                     if (conn.find(i, coll=BULK_COLL).get("object") or {}).get("pad") != "updated-" + PAD]
    check("FIND_BY_ID answers the same updated value the scan does, for every entry",
          not stale_by_find, f"stale under FIND_BY_ID: {stale_by_find[:5]}")

    check_status("CREATE_INDEX on the updated field",
                 conn.create_index("pad", coll=BULK_COLL), "OK")
    indexed_ids = conn.ids(BULK_COLL, [bu.filter_step("pad", "EQUALS", "updated-" + PAD)])
    check("an index-backed FILTER on the updated field finds every updated entry",
          indexed_ids == sorted(scan_by_id.keys()),
          f"indexed={len(indexed_ids)} scanned={len(scan_by_id)}")


REPLAY_COLL = "replayed_repeated_ids"
REPLAY_FENCE_COLL = "replay_fence"


def replay_fence_folder(work_dir: str) -> str:
    return os.path.join(work_dir, "db", DB, REPLAY_FENCE_COLL)


def arrange_a_killed_commit_with_repeated_ids(conn: Conn, work_dir: str):
    section("A commit with several ops on one id stops past its commit point")
    for coll in (REPLAY_COLL, REPLAY_FENCE_COLL):
        check_status(f"create {coll}", conn.create_coll(coll), "OK")
    check_status("seed r1 before the transaction", conn.save({"_id": "r1", "v": 0}, coll=REPLAY_COLL), "OK")
    check_status("start the transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    for value in (1, 2, 3):
        check_status(f"buffer r1 v={value}", conn.save({"_id": "r1", "v": value}, coll=REPLAY_COLL), "OK")
    check_status("buffer r2", conn.save({"_id": "r2", "v": 1}, coll=REPLAY_COLL), "OK")
    check_status("buffer the delete of r2", conn.send(
        {"type": "DELETE", "databaseName": DB, "collectionName": REPLAY_COLL, "_id": "r2"}), "OK")
    check_status("buffer the op that will not apply", conn.save({"_id": "blocker", "v": 1}, coll=REPLAY_FENCE_COLL), "OK")
    os.chmod(replay_fence_folder(work_dir), 0o000)
    check_code("the commit fences past its commit point",
               conn.send({"type": "COMMIT_TRANSACTION"}), "ERROR", "500-33")


def release_the_replay_fence(work_dir: str):
    os.chmod(replay_fence_folder(work_dir), 0o755)
    check("the commit marker outlived the kill", bu.local_commit_markers(work_dir) == 1,
          "with no marker the restart has nothing to replay, so the checks after it would be vacuous")


def test_a_killed_commit_with_repeated_ids_replays_to_its_last_value(conn: Conn, work_dir: str):
    r1 = conn.find("r1", coll=REPLAY_COLL)
    check_field("an id saved three times ends at its last value", r1, "object.v", 3)
    check_status("an id saved then deleted ends deleted", conn.find("r2", coll=REPLAY_COLL), "NOT_FOUND")
    check_status("the op that could not apply before the kill landed", conn.find("blocker", coll=REPLAY_FENCE_COLL), "OK")
    check("the replay retired its commit marker", bu.local_commit_markers(work_dir) == 0,
          f"markers left: {bu.local_commit_markers(work_dir)}")


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


def test_reads_of_an_unknown_collection_are_refused_cleanly(conn: Conn):
    section("reads naming a collection that does not exist")
    mis_cased = COLL.upper() if COLL != COLL.upper() else COLL.lower()
    for label, request in (
        ("FIND_BY_ID", {"type": "FIND_BY_ID", "databaseName": DB, "collectionName": "ghost", "_id": "a"}),
        ("AGGREGATE", {"type": "AGGREGATE", "databaseName": DB, "collectionName": "ghost",
                       "aggregationSteps": [{"type": "COUNT"}]}),
        ("AGGREGATE with a JOIN into it", {"type": "AGGREGATE", "databaseName": DB, "collectionName": COLL,
                                           "aggregationSteps": [{"type": "JOIN", "joinCollection": "ghost",
                                                                 "localField": "_id", "remoteField": "_id",
                                                                 "asField": "joined"}]}),
        ("LISTEN", {"type": "LISTEN", "databaseName": DB, "collectionName": "ghost",
                    "aggregationSteps": [{"type": "COUNT"}]}),
        ("FIND_BY_ID under a spelling that differs only in case",
         {"type": "FIND_BY_ID", "databaseName": DB, "collectionName": mis_cased, "_id": "a"}),
        ("AGGREGATE under a spelling that differs only in case",
         {"type": "AGGREGATE", "databaseName": DB, "collectionName": mis_cased,
          "aggregationSteps": [{"type": "COUNT"}]}),
    ):
        bu.check_code(f"{label} answers 404-11 instead of reading files no registered collection owns",
                      conn.send(request), "NOT_FOUND", "404-11")
    check_status("the registered spelling still reads", conn.aggregate(COLL, [{"type": "COUNT"}]), "OK")


def test_blocking_steps_over_an_unwritten_collection_do_not_exhaust_descriptors(conn: Conn):
    section("repeated blocking aggregations over a collection with no page metadata")
    check_status("the collection is created",
                 conn.create_coll(UNWRITTEN_COLL), "OK")
    blocking_pipelines = (
        ("SORT", [{"type": "SORT", "fieldName": "score", "ascending": True}]),
        ("GROUP_BY", [{"type": "GROUP_BY", "fieldName": "score"}]),
        ("JOIN", [{"type": "JOIN", "joinCollection": COLL, "localField": "score",
                   "remoteField": "score", "asField": "joined"}]),
    )
    for label, steps in blocking_pipelines:
        for _ in range(UNWRITTEN_SCANS):
            conn.aggregate(UNWRITTEN_COLL, steps)
        check(f"the server still answers after {UNWRITTEN_SCANS} {label} scans of an unwritten collection",
              conn.send({"type": "LIST_COLLECTIONS", "databaseName": DB}).get("status") == "OK")

    with admin_conn() as fresh:
        check_status("a new connection is still accepted afterwards",
                     fresh.save({"_id": "after_scans", "score": 1}, coll=UNWRITTEN_COLL), "OK")
        check_field("the document written after the scans reads back",
                    fresh.find("after_scans", coll=UNWRITTEN_COLL),
                    "object.score", 1)


def test_index_operations_cannot_destroy_the_pk_index(conn: Conn, work_dir: str):
    section("CREATE_INDEX / DROP_INDEX never take the pk index or the tombstones with them")
    coll = "user-data"
    bu.check_status("create a hyphenated collection",
                    conn.create_coll(coll), "OK")
    for i in range(3):
        conn.save({"_id": f"d{i}", "data": i}, coll=coll)

    bu.check_code("DROP_INDEX on _id is refused",
                  conn.send({"type": "DROP_INDEX", "databaseName": DB, "collectionName": coll,
                             "fieldName": "_id"}), "ERROR", "400-1")
    bu.check_code("CREATE_INDEX on _id is refused",
                  conn.create_index("_id", coll=coll), "ERROR", "400-1")

    bu.check_status("CREATE_INDEX on a field whose name is a token of the collection name",
                    conn.create_index("data", coll=coll), "OK")

    pk_file = pk_index_file(work_dir, DB, coll)
    bu.check("the pk index file still exists", os.path.isfile(pk_file), detail=pk_file)
    for i in range(3):
        bu.check_status(f"FIND_BY_ID still resolves d{i}",
                        conn.find(f"d{i}", coll=coll), "OK")

    conn.save({"_id": "d0", "data": 99}, coll=coll)
    ids = conn.ids(coll, [])
    bu.check("a re-save does not duplicate the _id", len(ids) == len(set(ids)) == 3, detail=f"ids={ids}")


def test_a_filter_on_id_cannot_destroy_the_pk_index(conn: Conn, work_dir: str):
    section("A FILTER on _id is answered by the pk index and never rewrites it")
    coll = "id_filter"
    bu.check_status("create the collection",
                    conn.create_coll(coll), "OK")
    for i in range(3):
        conn.save({"_id": f"d{i}", "n": i}, coll=coll)

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
        ("IN", ["D0"], []),
        ("NOT_IN", ["D0"], ["d0", "d1", "d2"]),
    ]
    for op_type, value, expected in cases:
        got = conn.ids(coll, [bu.filter_step("_id", op_type, value)])
        bu.check(f"FILTER _id {op_type} answers correctly", got == expected,
                 detail=f"expected {expected}, got {got}")

    bu.check("the pk index file was not resized", os.path.getsize(pk_file) == size_before,
             detail=f"{size_before} -> {os.path.getsize(pk_file)}")
    bu.check("the pk index still holds every id", pk_index_ids(pk_file) == ids_before,
             detail=f"{sorted(ids_before)} -> {sorted(pk_index_ids(pk_file))}")
    for i in range(3):
        bu.check_status(f"FIND_BY_ID still resolves d{i}",
                        conn.find(f"d{i}", coll=coll), "OK")
    conn.save({"_id": "d0", "n": 99}, coll=coll)
    scanned = conn.ids(coll, [])
    bu.check("a re-save after the filter does not duplicate the _id",
             len(scanned) == len(set(scanned)) == 3, detail=f"ids={scanned}")


def test_a_filter_on_id_agrees_with_find_by_id_on_case(conn: Conn):
    section("FILTER _id and FIND_BY_ID name the same document")
    coll = "id_case"
    bu.check_status("create the collection",
                    conn.create_coll(coll), "OK")
    for doc_id in ("Case1", "case1"):
        conn.save({"_id": doc_id, "which": doc_id}, coll=coll)

    for doc_id in ("Case1", "case1"):
        filtered = conn.ids(coll, [bu.filter_step("_id", "EQUALS", doc_id)])
        found = conn.find(doc_id, coll=coll)
        bu.check(f"FILTER _id EQUALS {doc_id} names exactly that document", filtered == [doc_id],
                 detail=f"got {filtered}")
        bu.check(f"FIND_BY_ID {doc_id} agrees with the filter",
                 (found.get("object") or {}).get("_id") == doc_id, detail=str(found)[:200])


def test_a_filter_on_id_reads_only_the_matching_document(conn: Conn):
    section("A FILTER on _id is resolved by the pk index, not by a collection scan")
    coll = "id_analyze"
    bu.check_status("create the collection",
                    conn.create_coll(coll), "OK")
    for i in range(25):
        conn.save({"_id": f"a{i:03d}", "n": i}, coll=coll)

    resp = conn.aggregate(coll, [bu.filter_step("_id", "EQUALS", "a007")], analyze=True)
    analysis = resp.get("analyzeResult") or {}
    bu.check("the filter still answers correctly",
             [d.get("_id") for d in (resp.get("results") or [])] == ["a007"], detail=str(resp)[:200])
    bu.check("the pk index is reported as used", "_id" in (analysis.get("indexesUsed") or []),
             detail=str(analysis)[:300])
    bu.check("only the matching document was read", analysis.get("documentsScanned") == 1,
             detail=f"documentsScanned={analysis.get('documentsScanned')} of 25")


def test_a_count_after_an_id_filter_matches_the_rows_it_returns(conn: Conn):
    section("An index-only COUNT agrees with the rows the same FILTER returns")
    coll = "id_count"
    bu.check_status("create the collection",
                    conn.create_coll(coll), "OK")
    for doc_id in ("k1", "k2", "k3"):
        conn.save({"_id": doc_id, "n": 1}, coll=coll)

    for label, value, expected in (("a number", 5, []), ("an object", {}, []), ("an array", [], []),
                                   ("null", None, ["k1", "k2", "k3"])):
        steps = [bu.filter_step("_id", "NOT_EQUALS", value)]
        rows = conn.ids(coll, steps)
        counted = conn.count(coll, steps)
        bu.check(f"NOT_EQUALS _id against {label} names the right documents", rows == expected,
                 detail=f"got {rows}, expected {expected}")
        bu.check(f"COUNT after NOT_EQUALS _id against {label} agrees with the rows",
                 counted == len(rows), detail=f"count={counted} rows={len(rows)}")

    present = [bu.filter_step("_id", "NOT_EQUALS", "k2")]
    bu.check("NOT_EQUALS _id against a present id still answers from the pk index",
             conn.ids(coll, present) == ["k1", "k3"], detail=str(conn.ids(coll, present)))
    bu.check("COUNT after it agrees", conn.count(coll, present) == 2,
             detail=f"count={conn.count(coll, present)}")

    not_in = [bu.filter_step("_id", "NOT_IN", [5, None])]
    bu.check("NOT_IN _id against a non-string list still returns every document",
             conn.ids(coll, not_in) == ["k1", "k2", "k3"], detail=str(conn.ids(coll, not_in)))
    bu.check("COUNT after it agrees", conn.count(coll, not_in) == 3,
             detail=f"count={conn.count(coll, not_in)}")


def test_not_equals_null_returns_the_documents_that_are_not_null(conn: Conn):
    section("A null operand respects the operator it was given")
    coll = "null_operand"
    bu.check_status("create the collection",
                    conn.create_coll(coll), "OK")
    conn.save({"_id": "u_null", "v": None}, coll=coll)
    conn.save({"_id": "u_str", "v": "open"}, coll=coll)
    conn.save({"_id": "u_num", "v": 7}, coll=coll)
    conn.save({"_id": "u_absent"}, coll=coll)

    def against_null(operator_type: str) -> list:
        return conn.ids(coll, [bu.filter_step("v", operator_type, None)])

    for stage in ("unindexed", "indexed"):
        if stage == "indexed":
            bu.check_status("index the field",
                            conn.create_index("v", coll=coll), "OK")
        bu.check(f"EQUALS null names only the null-valued document ({stage})",
                 against_null("EQUALS") == ["u_null"], detail=str(against_null("EQUALS")))
        bu.check(f"NOT_EQUALS null names the documents that hold a value ({stage})",
                 against_null("NOT_EQUALS") == ["u_num", "u_str"], detail=str(against_null("NOT_EQUALS")))
        for operator in ("GREATER_THAN", "GREATER_THAN_EQUALS", "SMALLER_THAN", "SMALLER_THAN_EQUALS", "CONTAINS"):
            bu.check(f"{operator} against null names nothing ({stage})",
                     against_null(operator) == [], detail=str(against_null(operator)))


def test_in_and_not_in_treat_null_as_a_member(conn: Conn):
    section("IN and NOT_IN treat a null in the list as a member")
    coll = "null_membership"
    bu.check_status("create the collection",
                    conn.create_coll(coll), "OK")
    conn.save({"_id": "m_null", "x": None}, coll=coll)
    conn.save({"_id": "m_one", "x": 1}, coll=coll)
    conn.save({"_id": "m_absent"}, coll=coll)

    def ids(operator_type: str, operand) -> list:
        return conn.ids(coll, [bu.filter_step("x", operator_type, operand)])

    expected = [("IN", [None], ["m_null"]), ("IN", [1, None], ["m_null", "m_one"]),
                ("NOT_IN", [None], ["m_one"]), ("NOT_IN", [1, None], []), ("NOT_IN", [1], ["m_null"])]
    for stage in ("unindexed", "indexed"):
        if stage == "indexed":
            bu.check_status("index the field",
                            conn.create_index("x", coll=coll), "OK")
        for operator, value, want in expected:
            got = ids(operator, value)
            bu.check(f"{operator} {value} names {want} ({stage})", got == want, detail=str(got))
        bu.check(f"IN [null] agrees with EQUALS null ({stage})", ids("IN", [None]) == ids("EQUALS", None))
        bu.check(f"NOT_IN [null] agrees with NOT_EQUALS null ({stage})",
                 ids("NOT_IN", [None]) == ids("NOT_EQUALS", None))


def dirty_markers(work_dir: str, db=DB, coll=DIRTY_COLL):
    folder = os.path.join(work_dir, "db", db, coll)
    if not os.path.isdir(folder):
        return []
    return sorted(f for f in os.listdir(folder) if f.endswith(("-indexes.dirty", "-indexes.unclean")))


def kill_while_the_indexes_are_dirty(conn: Conn, work_dir: str, proc) -> bool:
    check_status("create the collection whose indexes will be left dirty",
                 conn.create_coll(DIRTY_COLL), "OK")
    check_status("index a field on it",
                 conn.create_index("n", coll=DIRTY_COLL), "OK")
    stop = threading.Event()

    def writer():
        with admin_conn() as writer_conn:
            index = 0
            while not stop.is_set():
                writer_conn.save({"_id": f"d{index:05d}", "n": index, "pad": PAD}, coll=DIRTY_COLL)
                index += 1

    thread = threading.Thread(target=writer, daemon=True)
    thread.start()
    try:
        deadline = time.time() + 30.0
        while time.time() < deadline:
            if dirty_markers(work_dir):
                proc.kill()
                return bool(dirty_markers(work_dir))
        return False
    finally:
        stop.set()
        thread.join(timeout=5.0)


def test_an_unclean_stop_is_reported_at_the_next_startup(log_path: str, log_offset: int):
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
                 conn.create_index("pad", coll=DIRTY_COLL), "OK")

    check_status("rebuild only one of the two indexes",
                 conn.send({"type": "REINDEX", "databaseName": DB, "collectionName": DIRTY_COLL,
                            "fieldNames": ["n"]}), "OK")
    check("a partial rebuild leaves the marker in place", dirty_markers(work_dir),
          "the fields that run skipped were never re-derived, so the collection is still suspect")

    check_status("rebuild every index",
                 conn.send({"type": "REINDEX", "databaseName": DB, "collectionName": DIRTY_COLL}), "OK")
    check("a whole-collection rebuild retires the marker", not dirty_markers(work_dir),
          "REINDEX is the remedy the startup warning names, so the warning must not outlive it")


def conjunction(kind: str, leaves: list) -> dict:
    return {"type": "FILTER", "operator": {"conjunctionType": kind, "operators": leaves}}


def test_a_conjunction_counts_exactly_the_rows_it_returns(conn: Conn):
    section("An index-backed conjunction and the rows it claims to count")

    coll = "conj_consistency"
    check_status("create the conjunction collection",
                 conn.create_coll(coll), "OK")
    docs = [{"_id": f"c{i}", "n": i, "tag": "even" if i % 2 == 0 else "odd"} for i in range(20)]
    check_status("load the conjunction corpus",
                 conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll, "objects": docs}), "OK")
    for field in ("n", "tag"):
        check_status(f"index {field}",
                     conn.create_index(field, coll=coll), "OK")

    leaves = [{"fieldOperatorType": "GREATER_THAN", "field": "n", "value": 5},
              {"fieldOperatorType": "EQUALS", "field": "tag", "value": "even"}]
    for kind in ("AND", "OR", "XOR", "NOR", "NAND"):
        indexed = conn.ids(coll, [conjunction(kind, leaves)])
        scanned = conn.ids(coll, FULL_SCAN + [conjunction(kind, leaves)])
        check(f"{kind} answers the same rows through the index and a scan", indexed == scanned,
              f"index={indexed}, scan={scanned}")
        counted = conn.count(coll, [conjunction(kind, leaves)])
        check(f"{kind} counts exactly the rows it returns", counted == len(indexed),
              f"count={counted}, rows={len(indexed)}")

    repeated = [leaves[0], dict(leaves[0])]
    for kind in ("AND", "XOR"):
        indexed = conn.ids(coll, [conjunction(kind, repeated)])
        scanned = conn.ids(coll, FULL_SCAN + [conjunction(kind, repeated)])
        check(f"{kind} over a duplicated child agrees between the index and a scan", indexed == scanned,
              f"index={indexed}, scan={scanned}")


def test_a_buffered_write_to_a_missing_collection_is_refused_at_buffer_time(conn: Conn):
    section("A transactional write names a collection that was never created")

    check_status("start the transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    refusal = conn.save({"_id": "nope"}, coll="never_created_coll")
    check("the buffered save is refused where the standalone save would be",
          refusal.get("errorCode") in ("404-11", "503-10"),
          f"got {refusal.get('errorCode')!r}, expected the collection-readiness refusal rather than a commit failure")
    rollback = conn.send({"type": "ROLLBACK_TRANSACTION"})
    check("the transaction rolls back cleanly afterwards", rollback.get("status") == "OK",
          f"got {rollback.get('status')!r}")


def test_a_script_predicate_returning_infinity_keeps_its_document(conn: Conn):
    section("A FILTER script predicate is decided by JS truthiness")

    coll = "script_truthiness"
    check_status("create the script collection",
                 conn.create_coll(coll), "OK")
    check_status("load one document",
                 conn.save({"_id": "s1", "n": 1}, coll=coll), "OK")

    for source, expected, why in (
            ("export default () => 1 / 0;", ["s1"], "Infinity is truthy in JavaScript"),
            ("export default () => -1 / 0;", ["s1"], "-Infinity is truthy in JavaScript"),
            ("export default () => 0 / 0;", [], "NaN is falsy in JavaScript"),
            ("export default () => 0;", [], "zero is falsy"),
            ("export default () => 'x';", ["s1"], "a non-empty string is truthy")):
        steps = [{"type": "FILTER", "operator": {"script": source}}]
        got = conn.ids(coll, steps)
        check(f"a predicate returning {source.split('=> ')[1][:-1]} selects {expected}", got == expected, why)
        scanned = conn.ids(coll, FULL_SCAN + steps)
        check("the same predicate answers identically down the scan path", got == scanned,
              f"index={got}, scan={scanned}")


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
                    race_id = f"race{index}-{counter:05d}"
                    counter += 1
                    if writer_conn.save({"_id": race_id, "pad": "x"}, coll=HEAL_COLL).get("status") == "OK":
                        with guard:
                            committed.append(race_id)
        except Exception as exc:
            errors.append(f"writer {index}: {exc}")

    def reader():
        try:
            with admin_conn() as reader_conn:
                while not stop.is_set():
                    reader_conn.aggregate(HEAL_COLL, [{"type": "COUNT"}], dirtyRead=True)
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
                     conn.find(doc_id, coll=HEAL_COLL), "OK")


def save_raw_number(conn: Conn, coll: str, doc_id: str, literal: str) -> dict:
    return conn.send_raw('{"type":"SAVE","databaseName":"%s","collectionName":"%s",'
                         '"object":{"_id":"%s","v":%s}}' % (DB, coll, doc_id, literal), strict=True)


def test_non_finite_numbers_are_refused(conn: Conn):
    section("A number the serializer cannot spell is refused instead of stored as null")
    coll = NON_FINITE_COLL
    bu.check_status("create the collection",
                    conn.create_coll(coll), "OK")

    for label, literal in (("1e400", "1e400"), ("-1e400", "-1e400"), ("a 401-digit integer", "1" + "0" * 400)):
        response = save_raw_number(conn, coll, "refused", literal)
        bu.check(f"SAVE of {label} is refused", response.get("status") != "OK", detail=str(response)[:160])

    bu.check_status("SAVE of the largest representable double is accepted",
                    save_raw_number(conn, coll, "big", "1.7976931348623157e308"), "OK")
    bu.check_status("SAVE of an ordinary number is accepted", save_raw_number(conn, coll, "small", "5"), "OK")

    bu.check_status("CREATE_INDEX on the field",
                    conn.create_index("v", coll=coll), "OK")
    time.sleep(1.5)
    assert_count_agrees_with_rows(conn, coll, "warm")


def assert_count_agrees_with_rows(conn: Conn, coll: str, phase: str):
    gt = bu.filter_step("v", "GREATER_THAN", 1)
    rows = conn.ids(coll, [gt])
    counted = conn.count(coll, [gt])
    bu.check(f"index-only COUNT equals the rows the same FILTER returns ({phase})", counted == len(rows),
             detail=f"count={counted} rows={rows}")


def test_non_finite_numbers_stay_refused_after_a_restart(conn: Conn):
    section("The non-finite refusal and its index survive a restart")
    coll = NON_FINITE_COLL
    stored = conn.find("big", coll=coll)
    bu.check("the largest representable double reads back unchanged",
             (stored.get("object") or {}).get("v") == 1.7976931348623157e308, detail=str(stored)[:200])
    bu.check("the refused document was never stored",
             conn.find("refused", coll=coll).get("status") != "OK")
    assert_count_agrees_with_rows(conn, coll, "cold")
    bu.check_status("REINDEX the collection",
                    conn.send({"type": "REINDEX", "databaseName": DB, "collectionName": coll}), "OK")
    assert_count_agrees_with_rows(conn, coll, "after REINDEX")


REGISTRATION_DB = "registration_db"
REGISTRATION_COLLS = ["reg_a", "reg_b", "reg_c"]
REGISTRATION_DROPPED = "reg_b"


def registration_collections(conn: Conn) -> list:
    return sorted(conn.send({"type": "LIST_COLLECTIONS", "databaseName": REGISTRATION_DB}).get("collections") or [])


def seed_a_database_with_a_dropped_collection(conn: Conn):
    conn.send({"type": "DROP_DATABASE", "databaseName": REGISTRATION_DB})
    check_status("create the registration database", conn.send(
        {"type": "CREATE_DATABASE", "databaseName": REGISTRATION_DB}), "OK")
    for coll in REGISTRATION_COLLS:
        check_status(f"create {coll}", conn.create_coll(coll, db=REGISTRATION_DB), "OK")
        check_status(f"save into {coll}", conn.save({"_id": f"{coll}-1", "n": 1}, db=REGISTRATION_DB, coll=coll),
                     "OK")
    check_status(f"drop {REGISTRATION_DROPPED}", conn.send({
        "type": "DROP_COLLECTION", "databaseName": REGISTRATION_DB, "collectionName": REGISTRATION_DROPPED}), "OK")


def admin_collection_rows(work_dir: str) -> str:
    folder = os.path.join(work_dir, "db", "admin", "collections")
    text = []
    for name in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
        with open(os.path.join(folder, name), encoding="utf-8", errors="replace") as fp:
            text.append(fp.read())
    return "\n".join(text)


def test_every_registered_collection_is_unregistered_when_its_database_is_dropped(conn: Conn, work_dir: str):
    section("A database whose collections were created and dropped before a restart drops cleanly")
    expected = sorted(c for c in REGISTRATION_COLLS if c != REGISTRATION_DROPPED)
    check("the database lists exactly the collections that were not dropped after the restart",
          registration_collections(conn) == expected, f"got {registration_collections(conn)}")
    db_folder = os.path.join(work_dir, "db", REGISTRATION_DB)
    check("the dropped collection's folder is gone", not os.path.exists(os.path.join(db_folder, REGISTRATION_DROPPED)))
    check("the surviving collections' folders are still there",
          all(os.path.isdir(os.path.join(db_folder, c)) for c in expected))
    check_status("DROP_DATABASE", conn.send({"type": "DROP_DATABASE", "databaseName": REGISTRATION_DB}), "OK")
    check("the database folder is gone", not os.path.exists(db_folder))
    check("no collection row of the dropped database is left in admin metadata",
          f"{REGISTRATION_DB}|" not in admin_collection_rows(work_dir),
          "a registered collection was never unregistered")
    check_status("re-create the database", conn.send(
        {"type": "CREATE_DATABASE", "databaseName": REGISTRATION_DB}), "OK")
    check("the re-created database lists no collection", registration_collections(conn) == [],
          f"got {registration_collections(conn)}")
    conn.send({"type": "DROP_DATABASE", "databaseName": REGISTRATION_DB})


SHUTDOWN_COLL = "shutdown_writes"
SHUTDOWN_TAG = "stopped"
SHUTDOWN_SHUTTING_DOWN = "503-13"


def hammer_until_refused(acknowledged: list):
    writer = admin_conn()
    try:
        position = 0
        while True:
            response = writer.save({"_id": f"w{position}", "tag": SHUTDOWN_TAG}, coll=SHUTDOWN_COLL)
            if response.get("status") == "OK":
                acknowledged.append(f"w{position}")
            elif response.get("errorCode") == SHUTDOWN_SHUTTING_DOWN or "closed" in (response.get("message") or ""):
                return
            position += 1
    finally:
        writer.close()


def stop_the_node_under_a_connected_writer(proc) -> list:
    with admin_conn() as conn:
        check_status("create the collection", conn.create_coll(SHUTDOWN_COLL), "OK")
        check_status("index the tag", conn.create_index("tag", coll=SHUTDOWN_COLL), "OK")
    acknowledged: list = []
    thread = threading.Thread(target=hammer_until_refused, args=(acknowledged,), daemon=True)
    thread.start()
    bu.wait_until(lambda: len(acknowledged) >= 50, timeout_s=30, interval_s=0.05)
    proc.terminate()
    thread.join(60)
    proc.wait(timeout=60)
    return list(acknowledged)


def test_every_acknowledged_write_is_indexed_after_a_stop_with_a_client_still_connected(conn: Conn,
                                                                                         acknowledged: list):
    section("A node stopped under a connected writer refuses writes rather than losing their index events")
    check("the writer got acknowledged writes in before the stop", len(acknowledged) >= 50,
          f"only {len(acknowledged)}")
    found = conn.ids(SHUTDOWN_COLL, [bu.filter_step("tag", "EQUALS", SHUTDOWN_TAG)])
    missing = sorted(set(acknowledged) - set(found))
    check("every acknowledged write is found by the index-backed filter", not missing,
          f"{len(missing)} acknowledged write(s) missing from the indexed answer, e.g. {missing[:5]}")
    scanned = conn.ids(SHUTDOWN_COLL, FULL_SCAN)
    check("and the indexed answer agrees with a scan", sorted(found) == sorted(scanned),
          f"indexed={len(found)} scanned={len(scanned)}")


def seed_a_database_whose_collection_will_be_a_symlink(conn: Conn):
    check_status("create the database whose collection folder becomes a symlink",
                 conn.send({"type": "CREATE_DATABASE", "databaseName": SYMLINK_DB}), "OK")
    check_status("create the collection that will be linked", conn.create_coll(SYMLINK_COLL, db=SYMLINK_DB), "OK")
    check_status("write a document into it",
                 conn.save({"_id": "linked", "v": 1}, db=SYMLINK_DB, coll=SYMLINK_COLL), "OK")


def symlink_target(work_dir: str) -> str:
    return os.path.join(work_dir, SYMLINK_TARGET)


def link_the_collection_folder_elsewhere(work_dir: str) -> bool:
    folder = os.path.join(work_dir, "db", SYMLINK_DB, SYMLINK_COLL)
    target = symlink_target(work_dir)
    shutil.move(folder, target)
    with open(os.path.join(target, SYMLINK_PRECIOUS), "w") as fp:
        fp.write("must survive the drop")
    try:
        os.symlink(target, folder, target_is_directory=True)
    except (OSError, NotImplementedError):
        shutil.move(target, folder)
        return False
    return True


def test_drop_database_does_not_follow_a_symlink(conn: Conn, work_dir: str, linked: bool):
    section("DROP_DATABASE unlinks a symlinked collection folder and keeps its target")
    if not linked:
        bu.warn("symbolic links are not available on this host; skipped")
        return
    check_status("the collection is served through the link",
                 conn.find("linked", db=SYMLINK_DB, coll=SYMLINK_COLL), "OK")
    check_status("DROP_DATABASE over a symlinked collection folder",
                 conn.send({"type": "DROP_DATABASE", "databaseName": SYMLINK_DB}), "OK")
    check("the database folder is gone", not os.path.lexists(os.path.join(work_dir, "db", SYMLINK_DB)))
    check("the file in the link's target survived the drop",
          os.path.isfile(os.path.join(symlink_target(work_dir), SYMLINK_PRECIOUS)))
    check("the link's target still holds the collection's page files",
          any(name.endswith(".dat") for name in os.listdir(symlink_target(work_dir))))


def main():
    bu.banner("storage consistency e2e tests", HOST, PORT)

    bu.require_jar()

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
            test_page_metadata_follows_dropped_databases(conn, work_dir)
            test_page_metadata_follows_collections_dropped_after_a_restart(conn, work_dir)
            test_dropping_a_colliding_collection_keeps_the_others_page_rows(conn, work_dir)
            test_a_transaction_of_in_place_growths_respects_max_page_size(conn, work_dir)
            test_a_bulk_save_does_not_place_inserts_against_a_stale_page_size(conn, work_dir)
            test_an_index_with_no_entries_left_is_removed(conn, work_dir)
            test_drop_database_does_not_strand_a_collection_lock(conn)
            test_drop_and_recreate_a_database_does_not_serve_stale_documents(conn)
            test_writes_to_an_unknown_collection_are_refused_cleanly(conn)
            test_reads_of_an_unknown_collection_are_refused_cleanly(conn)
            test_a_conjunction_counts_exactly_the_rows_it_returns(conn)
            test_a_buffered_write_to_a_missing_collection_is_refused_at_buffer_time(conn)
            test_a_script_predicate_returning_infinity_keeps_its_document(conn)
            test_an_unreadable_schema_refuses_the_write(conn, work_dir)
            test_index_operations_cannot_destroy_the_pk_index(conn, work_dir)
            test_a_filter_on_id_cannot_destroy_the_pk_index(conn, work_dir)
            test_a_filter_on_id_agrees_with_find_by_id_on_case(conn)
            test_a_filter_on_id_reads_only_the_matching_document(conn)
            test_a_count_after_an_id_filter_matches_the_rows_it_returns(conn)
            test_not_equals_null_returns_the_documents_that_are_not_null(conn)
            test_in_and_not_in_treat_null_as_a_member(conn)
            test_a_script_written_document_survives_a_restart(conn)
            test_a_script_written_custom_value_is_found_by_an_index_backed_filter(conn)
            test_blocking_steps_over_an_unwritten_collection_do_not_exhaust_descriptors(conn)
            test_non_finite_numbers_are_refused(conn)
            seed_a_collection_whose_page_rows_will_be_lost(conn, work_dir)
            seed_a_collection_whose_page_tail_will_tear(conn)
            seed_collections_for_a_lost_line_end_and_a_half_built_index(conn)
            seed_collections_for_a_lost_record_and_an_unrecognised_pk_index(conn)
            seed_collections_for_primary_key_rebuilds(conn)
            seed_a_collection_that_will_hold_an_unindexed_record(conn)
            seed_collections_whose_compactions_a_kill_will_interrupt(conn)
            seed_drops_a_kill_will_interrupt(conn, work_dir)
            seed_a_collection_its_database_will_stop_listing(conn)
            seed_a_database_whose_collection_will_be_a_symlink(conn)
            interrupted = admin_conn()
            arrange_a_killed_commit_with_repeated_ids(interrupted, work_dir)

            print("\n  Killing the server without a drain ...")
            check("the unclean stop left an index-dirty marker on disk",
                  kill_while_the_indexes_are_dirty(conn, work_dir, proc),
                  "the background queue drained before the kill landed")
        proc.wait(timeout=30)
        log_offset = os.path.getsize(log_path)
        proc = None
        interrupted.close()
        release_the_replay_fence(work_dir)

        append_torn_pk_line(work_dir, DB, HEAL_COLL)
        lose_the_page_rows(work_dir)
        tear_the_page_tail(work_dir)
        lose_the_last_line_end(work_dir)
        lose_the_last_record(work_dir)
        plant_an_old_grammar_pk_index(work_dir)
        damage_the_pk_indexes_to_rebuild(work_dir)
        leave_the_index_half_built(work_dir)
        leave_an_unindexed_record(work_dir)
        interrupt_the_compactions(work_dir)
        interrupt_the_drops(work_dir)
        unlist_the_collection(work_dir)
        tear_an_admin_page_tail(work_dir)
        linked = link_the_collection_folder_elsewhere(work_dir)
        write_config(work_dir, max_memory=CACHE_DISABLED)
        print(f"  Restarting server on {HOST}:{PORT} with the cache disabled ...")
        proc = bu.start_server(work_dir, log_path)
        test_an_unclean_stop_is_reported_at_the_next_startup(log_path, log_offset)
        with admin_conn() as conn:
            test_non_finite_numbers_stay_refused_after_a_restart(conn)
            test_lost_page_rows_are_rebuilt_at_startup(conn, work_dir)
            test_a_torn_page_tail_is_healed_at_startup(conn, work_dir)
            test_a_lost_line_end_is_restored_at_startup(conn, work_dir)
            test_a_pk_entry_past_its_page_is_retired_at_startup(conn)
            test_an_unrecognised_pk_index_is_never_overwritten(conn, work_dir)
            test_reindex_primary_key_rebuilds_an_unrecognised_pk_index(conn)
            test_reindex_primary_key_repairs_swapped_offsets(conn)
            test_reindex_primary_key_restores_a_record_pk_idx_lost(conn)
            test_reindex_primary_key_refuses_a_duplicated_record(conn, work_dir)
            test_an_unindexed_record_is_adopted_at_startup(conn)
            test_an_update_killed_mid_compaction_is_undone_at_startup(conn, work_dir)
            test_a_delete_killed_mid_compaction_is_completed_at_startup(conn, work_dir)
            test_a_delete_killed_mid_compaction_leaves_no_index_entry(conn)
            test_a_relocation_killed_between_delete_and_insert_keeps_the_document(conn, work_dir)
            test_a_drop_interrupted_after_its_folder_was_deleted_can_be_retried(conn)
            test_a_recreated_collection_does_not_inherit_leftover_page_rows(conn, work_dir)
            test_a_half_built_index_is_answered_by_a_scan_until_rebuilt(conn, work_dir, log_path, log_offset)
            test_a_bulk_insert_leaves_no_document_the_pk_index_cannot_reach(conn)
            test_a_bulk_update_of_many_entries_leaves_every_document_consistent(conn)
            test_reindex_clears_the_marker_only_once_every_index_was_rebuilt(conn, work_dir)
            test_a_self_heal_never_erases_a_committed_write(conn, work_dir, log_path)
            test_an_admin_write_after_a_torn_admin_tail_lands_on_its_own_line(conn, work_dir)
            test_an_unlisted_collection_is_relisted_at_startup(conn, work_dir)
            test_a_killed_commit_with_repeated_ids_replays_to_its_last_value(conn, work_dir)
            test_drop_database_does_not_follow_a_symlink(conn, work_dir, linked)
        bu.stop_server(proc)
        proc = None
        restore_the_old_grammar_pk_index(work_dir)

        print(f"  Restarting server on {HOST}:{PORT} ...")
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_the_admin_write_after_the_tear_is_still_there(conn)
            test_a_restored_pk_index_serves_every_document(conn)
            seed_a_database_with_a_dropped_collection(conn)
        acknowledged = stop_the_node_under_a_connected_writer(proc)
        proc = None

        print(f"  Restarting server on {HOST}:{PORT} ...")
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            test_every_acknowledged_write_is_indexed_after_a_stop_with_a_client_still_connected(conn, acknowledged)
            test_every_registered_collection_is_unregistered_when_its_database_is_dropped(conn, work_dir)
    finally:
        bu.stop_server(proc)

    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
