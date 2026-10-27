import json
import os
import random
import threading
import time
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
GROUP_WRITERS = 8
CONCURRENT_COLL = "group_writers"
CONCURRENT_SAVES = 40
DELETE_VACUUM_DOCS = 24
DELETE_CHECKPOINT_DOCS = 300
AUDIT_SOURCE = ("import db from 'db'; import args from 'args';"
                " db.save(db.name, '{audit}', {{ doc: args.id, event: args.event }}); return 1;")


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
    repairs_index: bool = False
    intents_while_paused: int = 0
    logged_while_paused: bool = True
    writers: int = 1
    triggered: bool = False
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


def update_own_doc(conn):
    save(conn, conn.coll, doc(conn.writer, "grouped"))


def acknowledged_or_grouped(case: KillCase) -> dict:
    return {f"k{i}": {f"acknowledged-{i}", f"grouped-{i}"} for i in range(case.seed_docs)}


def retype_k1(conn):
    save(conn, conn.coll, {"_id": "k1", "v": 1, "pad": PAD})


def with_k1_retyped(case: KillCase) -> dict:
    return {**all_acknowledged(case), "k1": 1}


def delete_k1(conn):
    conn.send({"type": "DELETE", "databaseName": DB, "collectionName": conn.coll, "_id": "k1"})


def bulk_update(conn):
    conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": conn.coll,
               "objects": [doc(i, "unacknowledged") for i in range(4)]})


def last_attempted_k1(case: KillCase) -> dict:
    return {**all_acknowledged(case), "k1": case.observed["attempted"][-1]}


def churn_k1_until_paused(case: KillCase):
    def write(conn):
        for round_number in range(1000):
            value = f"round{round_number}"
            case.observed["attempted"] = case.observed.get("attempted", []) + [f"{value}-1"]
            response = save(conn, conn.coll, doc(1, value))
            if response.get("status") != "OK":
                return
            case.acknowledged.append(f"{value}-1")
    return write


def delete_until_paused(case: KillCase):
    def write(conn):
        for i in range(case.seed_docs):
            case.observed["attempted"] = case.observed.get("attempted", []) + [f"k{i}"]
            response = conn.send({"type": "DELETE", "databaseName": DB, "collectionName": conn.coll, "_id": f"k{i}"})
            if response.get("status") != "OK":
                return
    return write


def without_attempted_deletes(case: KillCase) -> dict:
    gone = set(case.observed.get("attempted", []))
    return {key: value for key, value in all_acknowledged(case).items() if key not in gone}


def cases() -> list:
    vacuum = KillCase("intent_vacuum_renamed", "PAGE_VACUUM_RENAMED", 4, None, last_acknowledged_k1,
                      intents_while_paused=1, logged_while_paused=False)
    vacuum.write = churn_k1_until_paused(vacuum)
    checkpoint = KillCase("wal_checkpoint_forced", "WAL_CHECKPOINT_FORCED", 4, None, last_attempted_k1,
                          logged_while_paused=False)
    checkpoint.write = churn_k1_until_paused(checkpoint)
    delete_vacuum = KillCase("intent_delete_vacuum_renamed", "PAGE_VACUUM_RENAMED", DELETE_VACUUM_DOCS, None,
                             without_attempted_deletes, intents_while_paused=1, logged_while_paused=False)
    delete_vacuum.write = delete_until_paused(delete_vacuum)
    delete_checkpoint = KillCase("wal_delete_checkpoint_forced", "WAL_CHECKPOINT_FORCED", DELETE_CHECKPOINT_DOCS,
                                 None, without_attempted_deletes, logged_while_paused=False)
    delete_checkpoint.write = delete_until_paused(delete_checkpoint)
    rewrite_begun = KillCase("wal_rewrite_begun", "WAL_REWRITE_BEGUN", 4, None, last_acknowledged_k1,
                             logged_while_paused=False)
    rewrite_begun.write = churn_k1_until_paused(rewrite_begun)
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
         KillCase("wal_delete_appended", "WAL_APPENDED", KILLED_DOCS, delete_k1, without_k1, indexed=True),
         delete_vacuum],
        [KillCase("wal_index_repaired", "INDEX_BATCH_FLUSHED", KILLED_DOCS, retype_k1, with_k1_retyped,
                  indexed=True, repairs_index=True),
         delete_checkpoint, rewrite_begun],
        [KillCase("wal_group_appended", "WAL_GROUP_APPENDED", GROUP_WRITERS, update_own_doc, acknowledged_or_grouped,
                  indexed=True, writers=GROUP_WRITERS),
         KillCase("wal_group_forced", "WAL_GROUP_FORCED", GROUP_WRITERS, update_own_doc, acknowledged_or_grouped,
                  indexed=True, writers=GROUP_WRITERS)],
        [KillCase("wal_group_forced_triggered", "WAL_GROUP_FORCED", GROUP_WRITERS, update_own_doc,
                  acknowledged_or_grouped, indexed=True, writers=GROUP_WRITERS, triggered=True),
         KillCase("group_runs_staged", "GROUP_RUNS_STAGED", GROUP_WRITERS, update_own_doc, acknowledged_or_grouped,
                  indexed=True, writers=GROUP_WRITERS, triggered=True)],
    ]


def collection_folder(work_dir: str, coll: str) -> str:
    return os.path.join(work_dir, "db", DB, coll)


def index_ids_by_key(path: str) -> dict:
    ids = {}
    for suffix in ("", "log"):
        file = path + suffix
        if not os.path.exists(file):
            continue
        with open(file, "rb") as fp:
            lines = fp.read().decode("utf-8", errors="replace").split("\n")
        for line in (line for line in lines if line):
            fields = line.split(bu.PK_FIELD_SEPARATOR)
            if suffix == "":
                ids.setdefault(fields[0], set()).update(fields[1:])
            elif fields[0] == "+":
                ids.setdefault(fields[1], set()).update(fields[2:])
            else:
                ids.get(fields[1], set()).difference_update(fields[2:])
    return ids


def indexed_types_of(work_dir: str, coll: str, field: str, doc_id: str) -> list:
    folder = collection_folder(work_dir, coll)
    prefix = f"{coll}-{field}-"
    types = sorted({name[len(prefix):].split(".")[0] for name in os.listdir(folder)
                    if name.startswith(prefix) and name.endswith((".idx", ".idxlog"))})
    return [kind for kind in types
            if any(doc_id in held for held in index_ids_by_key(os.path.join(folder, f"{prefix}{kind}.idx")).values())]


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
        if case.triggered:
            install_audit_trigger(conn, case.coll)


def audit_coll(coll: str) -> str:
    return f"{coll}_audit"


def install_audit_trigger(conn, coll: str):
    check_status(f"create {audit_coll(coll)}", conn.send({"type": "CREATE_COLLECTION", "databaseName": DB,
                                                          "collectionName": audit_coll(coll)}), "OK")
    check_status(f"install the audit procedure of {coll}",
                 conn.send({"type": "SAVE_PROCEDURE", "databaseName": DB, "name": f"{coll}_auditor",
                            "script": AUDIT_SOURCE.format(audit=audit_coll(coll))}), "OK")
    check_status(f"install the after trigger on {coll}",
                 conn.send({"type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": coll, "name": "audit",
                            "events": ["CREATED", "UPDATED"], "procedureName": f"{coll}_auditor"}), "OK")


def run_writer(conn_factory, case: KillCase):
    for writer in range(case.writers):
        threading.Thread(target=write_once, args=(conn_factory, case, writer), daemon=True).start()


def write_once(conn_factory, case: KillCase, writer: int):
    try:
        conn = conn_factory()
        conn.coll = case.coll
        conn.writer = writer
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


def index_agrees(conn, coll: str, found: dict, allowed: dict) -> bool:
    for doc_id, value in found.items():
        if indexed_count(conn, coll, value) != 1:
            return False
        if any(indexed_count(conn, coll, other) != 0 for other in allowed[doc_id] - {value}):
            return False
    return True


def test_a_killed_group_recovers_whole_documents(conn, case: KillCase):
    section(f"{case.writers} writers grouped behind one force in {case.coll}, killed at {case.point}, leave every"
            f" document whole")
    allowed = case.expected(case)
    found = values_by_id(conn, case.coll, sorted(allowed))
    check("every document is its acknowledged version or its grouped one, never missing or torn",
          set(found) == set(allowed) and all(found[doc_id] in allowed[doc_id] for doc_id in allowed), f"found {found}")
    check("a full scan lists every document once", scanned_ids(conn, case.coll) == sorted(allowed),
          f"scan={scanned_ids(conn, case.coll)}")
    check("the index-only COUNT agrees", pk_count(conn, case.coll) == len(allowed))
    check("the paused group was already in the write-ahead log", case.observed.get("logged", 0) >= 1,
          f"saw {case.observed.get('logged')}")
    bu.wait_until(lambda: index_agrees(conn, case.coll, found, allowed), timeout_s=15, interval_s=0.2)
    check("the index holds every document under the value it kept, and nothing under the other",
          index_agrees(conn, case.coll, found, allowed))


def audited_docs(conn, coll: str) -> list:
    response = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": audit_coll(coll),
                          "aggregationSteps": FULL_SCAN})
    return sorted((row.get("doc") for row in response.get("results") or []), key=str)


def test_a_killed_triggered_group_audits_each_landed_write_once(conn, case: KillCase):
    section(f"A triggered group in {case.coll} killed at {case.point} fires exactly one trigger per landed write")
    found = values_by_id(conn, case.coll, sorted(case.expected(case)))
    landed = sorted(doc_id for doc_id, value in found.items() if value.startswith("grouped-"))
    bu.wait_until(lambda: audited_docs(conn, case.coll) == landed, timeout_s=30, interval_s=0.25)
    time.sleep(1.0)
    check("every landed write has exactly one audit row and no other write has one",
          audited_docs(conn, case.coll) == landed, f"landed {landed} audited {audited_docs(conn, case.coll)}")
    bu.wait_until(lambda: not [run for run in conn.send({"type": "LIST_TRIGGER_RUNS"}).get("runs") or []
                               if case.coll in json.dumps(run)], timeout_s=30, interval_s=0.25)
    left = [run for run in conn.send({"type": "LIST_TRIGGER_RUNS"}).get("runs") or [] if case.coll in json.dumps(run)]
    check("no staged run of the group is left behind", not left, f"left {left}")


def test_every_killed_write_recovers_to_what_its_log_committed(conn, work_dir: str, rounds: list):
    for case in (case for kill_round in rounds for case in kill_round):
        if case.writers > 1:
            test_a_killed_group_recovers_whole_documents(conn, case)
            if case.triggered:
                test_a_killed_triggered_group_audits_each_landed_write_once(conn, case)
            continue
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
        if case.indexed:
            bu.wait_until(lambda: indexed_count(conn, case.coll, "acknowledged-1") == 0, timeout_s=15, interval_s=0.2)
            check("the old value left no field-index entry", indexed_count(conn, case.coll, "acknowledged-1") == 0)
        if case.repairs_index:
            bu.wait_until(lambda: indexed_types_of(work_dir, case.coll, "v", "k1") == ["Number"], timeout_s=15,
                          interval_s=0.2)
            check("startup re-queued the replayed write's index work, so the index files hold k1 only as a Number",
                  indexed_types_of(work_dir, case.coll, "v", "k1") == ["Number"],
                  f"held under {indexed_types_of(work_dir, case.coll, 'v', 'k1')}")


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


def write_concurrently(conn_factory, generation: int) -> dict:
    section(f"{GROUP_WRITERS} writers save and delete concurrently in {CONCURRENT_COLL} (pass {generation})")
    if generation == 1:
        with conn_factory() as setup_conn:
            check_status("create the concurrent-writers collection", setup_conn.send(
                {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": CONCURRENT_COLL}), "OK")
            check_status("index v", setup_conn.send({"type": "CREATE_INDEX", "databaseName": DB,
                                                      "collectionName": CONCURRENT_COLL, "fieldName": "v"}), "OK")
    acknowledged = {}
    guard = threading.Lock()

    def writer(number: int):
        with conn_factory() as writer_conn:
            for n in range(CONCURRENT_SAVES):
                doc_id = f"w{number}-{n % 10}"
                if n % 7 == 6:
                    response = writer_conn.send({"type": "DELETE", "databaseName": DB,
                                                 "collectionName": CONCURRENT_COLL, "_id": doc_id})
                    value = None
                else:
                    value = f"g{generation}-w{number}-n{n}"
                    response = save(writer_conn, CONCURRENT_COLL, {"_id": doc_id, "v": value, "pad": PAD})
                if response.get("status") == "OK":
                    with guard:
                        acknowledged[doc_id] = value

    threads = [threading.Thread(target=writer, args=(number,)) for number in range(GROUP_WRITERS)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=120)
    check("every concurrent write was answered", len(acknowledged) == GROUP_WRITERS * 10, f"{len(acknowledged)}")
    return acknowledged


def test_every_concurrent_write_landed(conn, acknowledged: dict):
    section(f"Every write the {GROUP_WRITERS} concurrent writers were told succeeded is there after a restart")
    expected = {doc_id: value for doc_id, value in acknowledged.items() if value is not None}
    found = values_by_id(conn, CONCURRENT_COLL, sorted(acknowledged))
    check("FIND_BY_ID answers exactly the acknowledged values", found == expected,
          f"missing or wrong: {sorted(set(expected.items()) ^ set(found.items()))[:6]}")
    check("a full scan lists exactly the acknowledged documents", scanned_ids(conn, CONCURRENT_COLL) == sorted(expected))
    check("the index-only COUNT agrees", pk_count(conn, CONCURRENT_COLL) == len(expected))
    bu.wait_until(lambda: all(indexed_count(conn, CONCURRENT_COLL, value) == 1 for value in expected.values()),
                  timeout_s=30, interval_s=0.5)
    check("the index finds every acknowledged value once",
          all(indexed_count(conn, CONCURRENT_COLL, value) == 1 for value in expected.values()))

