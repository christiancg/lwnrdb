import json
import os
import random
import threading
from dataclasses import dataclass, field
from typing import Callable

import base_utils as bu
from base_utils import check, check_status, section

DB = "storage_db"
PAD = "p" * 300
GROWN = "g" * 900
KILLED_DOCS = 6
FULL_SCAN = [{"type": "SKIP", "skip": 0}]
CHURN_COLL = "intent_churn"
CHURN_DOCS = 50
CHURN_SAVES = 2000
PK_LOG_MIN_DEAD_ROWS = 1024


def doc(i: int, value: str, pad: str = PAD) -> dict:
    return {"_id": f"k{i}", "v": f"{value}-{i}", "pad": pad}


@dataclass
class KillCase:
    coll: str
    point: str
    seed_docs: int
    write: Callable[[object], None]
    expected: Callable[["KillCase"], dict]
    indexed: bool = False
    intents_while_paused: int = 0
    logged_while_paused: bool = True
    observed: dict = field(default_factory=dict)
    acknowledged: list = field(default_factory=list)


def save(conn, coll: str, document: dict) -> dict:
    return conn.send({"type": "SAVE", "databaseName": DB, "collectionName": coll, "object": document})


def all_acknowledged(case: KillCase) -> dict:
    return {f"k{i}": f"acknowledged-{i}" for i in range(case.seed_docs)}


def with_k1(value: str):
    return lambda case: {**all_acknowledged(case), "k1": f"{value}-1"}


def without_k1(case: KillCase) -> dict:
    return {key: value for key, value in all_acknowledged(case).items() if key != "k1"}


def with_bulk(value: str):
    return lambda case: {**all_acknowledged(case), **{f"k{i}": f"{value}-{i}" for i in range(4)}}


def last_acknowledged_k1(case: KillCase) -> dict:
    return {**all_acknowledged(case), "k1": case.acknowledged[-1] if case.acknowledged else "acknowledged-1"}


def update_k1(value: str, pad: str = PAD):
    return lambda conn: save(conn, conn.coll, doc(1, value, pad))


def insert_new(case_docs: int):
    return lambda conn: save(conn, conn.coll, doc(case_docs, "unacknowledged"))


def with_new(value: str):
    return lambda case: {**all_acknowledged(case), f"k{case.seed_docs}": f"{value}-{case.seed_docs}"}


def delete_k1(conn):
    conn.send({"type": "DELETE", "databaseName": DB, "collectionName": conn.coll, "_id": "k1"})


def bulk_update(conn):
    conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": conn.coll,
               "objects": [doc(i, "unacknowledged") for i in range(4)]})


def churn_k1_until_paused(case: KillCase):
    def write(conn):
        for round_number in range(1000):
            value = f"round{round_number}"
            response = save(conn, conn.coll, doc(1, value))
            if response.get("status") != "OK":
                return
            case.acknowledged.append(f"{value}-1")
    return write


def cases() -> list:
    vacuum = KillCase("intent_vacuum_renamed", "PAGE_VACUUM_RENAMED", 4, None, last_acknowledged_k1,
                      intents_while_paused=1, logged_while_paused=False)
    vacuum.write = churn_k1_until_paused(vacuum)
    checkpoint = KillCase("wal_checkpoint_forced", "WAL_CHECKPOINT_FORCED", 4, None, last_acknowledged_k1,
                          logged_while_paused=False)
    checkpoint.write = churn_k1_until_paused(checkpoint)
    return [
        [KillCase("wal_update_logged", "PAGE_COPY_APPENDED", KILLED_DOCS, update_k1("unacknowledged"),
                  with_k1("unacknowledged")),
         KillCase("wal_delete_applied", "PK_ROW_COMMITTED", KILLED_DOCS, delete_k1, without_k1, indexed=True),
         vacuum],
        [KillCase("wal_relocate_logged", "PAGE_COPY_APPENDED", 11, update_k1("unacknowledged", GROWN),
                  with_k1("unacknowledged")),
         KillCase("wal_update_applied", "PK_ROW_COMMITTED", KILLED_DOCS, update_k1("unacknowledged"),
                  with_k1("unacknowledged")),
         checkpoint],
        [KillCase("wal_bulk_logged", "PAGE_COPY_APPENDED", KILLED_DOCS, bulk_update, with_bulk("unacknowledged")),
         KillCase("wal_relocate_applied", "PK_ROW_COMMITTED", 11, update_k1("unacknowledged", GROWN),
                  with_k1("unacknowledged")),
         KillCase("wal_insert_appended", "WAL_APPENDED", KILLED_DOCS, insert_new(KILLED_DOCS),
                  with_new("unacknowledged"))],
        [KillCase("wal_bulk_applied", "PK_ROW_COMMITTED", KILLED_DOCS, bulk_update, with_bulk("unacknowledged")),
         KillCase("wal_delete_appended", "WAL_APPENDED", KILLED_DOCS, delete_k1, without_k1, indexed=True)],
    ]


def collection_folder(work_dir: str, coll: str) -> str:
    return os.path.join(work_dir, "db", DB, coll)


def intents_left(work_dir: str, coll: str) -> list:
    return bu.open_intents(collection_folder(work_dir, coll))


def seed(conn, rounds: list):
    section("Seed the collections whose writes a kill will interrupt at each write-intent fault point")
    for case in (case for kill_round in rounds for case in kill_round):
        check_status(f"create {case.coll}",
                     conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": case.coll}), "OK")
        if case.indexed:
            check_status(f"index v in {case.coll}", conn.send({"type": "CREATE_INDEX", "databaseName": DB,
                                                               "collectionName": case.coll, "fieldName": "v"}), "OK")
        for i in range(case.seed_docs):
            check_status(f"save k{i} into {case.coll}", save(conn, case.coll, doc(i, "acknowledged")), "OK")


def run_writer(conn_factory, case: KillCase):
    try:
        conn = conn_factory()
        conn.coll = case.coll
        case.write(conn)
    except Exception:
        pass


def kill_mid_write(proc, conn_factory, work_dir: str, kill_round: list):
    with conn_factory() as conn:
        for case in kill_round:
            check_status(f"arm {case.point} PAUSE for {case.coll}",
                         conn.send({"type": "ARM_FAULT", "point": case.point, "action": "PAUSE",
                                    "match": f"{DB}|{case.coll}"}), "OK")
        for case in kill_round:
            threading.Thread(target=run_writer, args=(conn_factory, case), daemon=True).start()
            check_status(f"the write to {case.coll} reached {case.point}",
                         conn.send({"type": "AWAIT_FAULT", "point": case.point, "timeoutMs": 30000}), "OK")
            case.observed["intents"] = len(intents_left(work_dir, case.coll))
            case.observed["logged"] = bu.logged_writes(collection_folder(work_dir, case.coll))
    proc.kill()
    proc.wait(timeout=30)


def run(work_dir: str, log_path: str, conn_factory, write_config: Callable[[], None]):
    rounds = cases()
    write_config()
    proc = bu.start_server(work_dir, log_path)
    with conn_factory() as conn:
        seed(conn, rounds)
    for number, kill_round in enumerate(rounds, start=1):
        print(f"\n  Killing the server mid-write, round {number} of {len(rounds)} ...")
        kill_mid_write(proc, conn_factory, work_dir, kill_round)
        proc = bu.start_server(work_dir, log_path)
    return proc, rounds


def values_by_id(conn, coll: str, ids: list) -> dict:
    values = {}
    for doc_id in ids:
        response = conn.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": coll, "_id": doc_id})
        if response.get("status") == "OK":
            values[doc_id] = (response.get("object") or {}).get("v")
    return values


def scanned_ids(conn, coll: str) -> list:
    return bu.ids_of(conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                                "aggregationSteps": FULL_SCAN}))


def pk_count(conn, coll: str):
    return bu.count_of(conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                                  "aggregationSteps": [{"type": "COUNT"}]}))


def indexed_count(conn, coll: str, value: str):
    return bu.count_of(conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                                  "aggregationSteps": [bu.filter_step("v", "EQUALS", value), {"type": "COUNT"}]}))


def test_every_killed_write_recovers_to_what_its_log_committed(conn, work_dir: str, rounds: list):
    for case in (case for kill_round in rounds for case in kill_round):
        section(f"A write killed at {case.point} in {case.coll} recovers to what its write-ahead log committed")
        expected = case.expected(case)
        ids = sorted({f"k{i}" for i in range(case.seed_docs)} | set(expected))
        found = values_by_id(conn, case.coll, ids)
        check("FIND_BY_ID answers exactly the committed values", found == expected,
              f"expected {expected} got {found}")
        check("a full scan lists exactly the same documents", scanned_ids(conn, case.coll) == sorted(expected),
              f"scan={scanned_ids(conn, case.coll)}")
        check("the index-only COUNT agrees", pk_count(conn, case.coll) == len(expected))
        check("no write intent is left", not intents_left(work_dir, case.coll),
              f"left: {intents_left(work_dir, case.coll)}")
        check(f"the paused write had {case.intents_while_paused} intent(s) on disk",
              case.observed.get("intents") == case.intents_while_paused, f"saw {case.observed.get('intents')}")
        if case.logged_while_paused:
            check("the paused write was already in the write-ahead log", case.observed.get("logged", 0) >= 1,
                  f"saw {case.observed.get('logged')}")
        check("startup replayed the log and left it a bare checkpoint",
              bu.logged_writes(collection_folder(work_dir, case.coll)) == 0)
        if case.indexed:
            bu.wait_until(lambda: indexed_count(conn, case.coll, "acknowledged-1") == 0, timeout_s=15, interval_s=0.2)
            check("the deleted id left no field-index entry", indexed_count(conn, case.coll, "acknowledged-1") == 0)


def churn(conn) -> dict:
    section(f"{CHURN_SAVES} SAVEs churn {CHURN_DOCS} documents through append-and-blank updates")
    check_status("create the churn collection",
                 conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": CHURN_COLL}), "OK")
    rng = random.Random(2026)
    latest = {}
    refused = []
    for round_number in range(CHURN_SAVES):
        i = round_number % CHURN_DOCS if round_number < CHURN_DOCS else rng.randrange(CHURN_DOCS)
        document = doc(i, f"r{round_number}", "p" * rng.randrange(150, 450))
        if save(conn, CHURN_COLL, document).get("status") == "OK":
            latest[document["_id"]] = document["v"]
        else:
            refused.append(document["_id"])
    check("every churn write was acknowledged", not refused, f"refused: {refused[:5]}")
    return latest


def pk_path(work_dir: str, coll: str) -> str:
    return os.path.join(work_dir, "db", DB, coll, f"{coll}-pk.idx")


def page_lengths(work_dir: str, coll: str) -> dict:
    folder = os.path.join(work_dir, "db", DB, coll)
    return {int(name[len(coll) + 1:-4]): os.path.getsize(os.path.join(folder, name))
            for name in os.listdir(folder) if name.endswith(".dat")}


def test_update_churn_keeps_every_page_within_a_quarter_of_dead_space(conn, work_dir: str, latest: dict,
                                                                     max_page_bytes: int):
    section("Update churn keeps every page within a quarter page of dead space, and scan agrees with FIND_BY_ID")
    lengths = page_lengths(work_dir, CHURN_COLL)
    bound = max_page_bytes + max_page_bytes // 4
    check(f"no page file grew past {bound} bytes", all(length <= bound for length in lengths.values()),
          f"pages={lengths}")
    found = values_by_id(conn, CHURN_COLL, sorted(latest))
    check("FIND_BY_ID answers the last value of every document", found == latest)
    check("a full scan lists every document once", scanned_ids(conn, CHURN_COLL) == sorted(latest))


def test_pk_idx_compacts_under_update_churn(work_dir: str, latest: dict):
    section("pk.idx compacts itself under update churn")
    rows = bu.read_pk_rows(pk_path(work_dir, CHURN_COLL))
    check("the folded rows name exactly the live documents", [row[0] for row in rows] == sorted(latest))
    lines = len(bu.read_pk_log(pk_path(work_dir, CHURN_COLL)))
    check(f"the log keeps at most 2 x live + {PK_LOG_MIN_DEAD_ROWS} rows",
          lines <= 2 * len(latest) + PK_LOG_MIN_DEAD_ROWS, f"{lines} rows for {len(latest)} live documents")


def test_page_rows_equal_live_bytes_after_a_restart(work_dir: str, recorded_pages: Callable):
    section("After a restart the page rows record the live bytes the pk index names, not the file lengths")
    live = {}
    for _, _, length, page, _ in bu.read_pk_rows(pk_path(work_dir, CHURN_COLL)):
        live[page] = live.get(page, 0) + length
    rows = {page: int(row.get("size", -1)) for page, row in recorded_pages(work_dir, DB, CHURN_COLL).items()}
    check("every page row equals its page's live bytes", all(rows.get(page) == size for page, size in live.items()),
          f"rows={rows} live={live}")
    holes = sum(page_lengths(work_dir, CHURN_COLL).values()) - sum(live.values())
    check("the churn left holes for the rows to tell apart from the file lengths", holes > 0,
          json.dumps(page_lengths(work_dir, CHURN_COLL)))
