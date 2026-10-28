import os
import shutil
import tempfile
import threading
from typing import Callable

import base_utils as bu
from base_utils import check, check_status, section

DB = "tx_kill_db"
COLL = "tx_kill"
POINT = "WAL_APPENDED"
SUBJECT = "admin|transactions"
FAULT_HALT_STATUS = 137


def live_transaction_records(work_dir: str) -> set:
    path = os.path.join(work_dir, "db", "admin", "transactions", "transactions-pk.idx")
    if not os.path.exists(path):
        return set()
    with open(path, "rb") as fp:
        lines = fp.read().decode("utf-8", errors="replace").split("\n")
    live = {}
    for line in (line for line in lines if line):
        fields = line.split(bu.PK_FIELD_SEPARATOR)
        if len(fields) == 2 and fields[1] == "-":
            live.pop(fields[0], None)
        elif len(fields) == 5:
            live[fields[0]] = True
    return set(live)


def records_of(work_dir: str, tx_id: str) -> list:
    return sorted(record for record in live_transaction_records(work_dir) if record.startswith(f"{tx_id}|"))


def send(conn, payload: dict) -> dict:
    return conn.send(payload)


def save(conn, doc_id: str) -> dict:
    return send(conn, {"type": "SAVE", "databaseName": DB, "collectionName": COLL, "object": {"_id": doc_id, "v": 1}})


def find(conn, doc_id: str) -> dict:
    return send(conn, {"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": doc_id})


def commit_in_background(conn):
    thread = threading.Thread(target=lambda: _quietly(conn, {"type": "COMMIT_TRANSACTION"}), daemon=True)
    thread.start()
    return thread


def _quietly(conn, payload: dict):
    try:
        conn.send(payload)
    except Exception:
        pass


def open_transaction_with_one_save(conn_factory, doc_id: str):
    conn = conn_factory()
    tx_id = send(conn, {"type": "START_TRANSACTION"}).get("transactionId")
    check_status(f"buffer {doc_id}", save(conn, doc_id), "OK")
    return conn, tx_id


def halt_and_restart(proc, work_dir: str, log_path: str):
    try:
        returncode = proc.wait(timeout=30)
    except Exception:
        proc.kill()
        returncode = None
    check("the server halted at the fault", returncode == FAULT_HALT_STATUS, f"returncode={returncode}")
    return bu.start_server(work_dir, log_path)


def test_a_commit_halted_at_its_begin_record_is_finished_at_startup(proc, conn_factory, work_dir, log_path):
    section("A commit halted right after its begin record ([+ops, +|localcommit]) is finished at the next startup")
    conn, tx_id = open_transaction_with_one_save(conn_factory, "halted_at_begin")
    with conn_factory() as control:
        check_status("ARM_FAULT halts at the first admin|transactions record",
                     send(control, {"type": "ARM_FAULT", "point": POINT, "action": "HALT", "match": SUBJECT}), "OK")
    commit_in_background(conn)
    proc = halt_and_restart(proc, work_dir, log_path)
    conn.close()
    with conn_factory() as after:
        check_status("the decided document is present after the restart", find(after, "halted_at_begin"), "OK")
    check("the startup finish left no record of the transaction", records_of(work_dir, tx_id) == [],
          f"left: {records_of(work_dir, tx_id)}")
    return proc


def test_a_commit_halted_at_its_finish_record_keeps_its_document(proc, conn_factory, work_dir, log_path):
    section("A commit halted right after its finish record ([-|localcommit, -ops]) keeps its document and no record")
    conn, tx_id = open_transaction_with_one_save(conn_factory, "halted_at_finish")
    with conn_factory() as control:
        check_status("ARM_FAULT pauses at the begin record",
                     send(control, {"type": "ARM_FAULT", "point": POINT, "action": "PAUSE", "match": SUBJECT}), "OK")
        commit_in_background(conn)
        check_status("the commit reached its begin record",
                     send(control, {"type": "AWAIT_FAULT", "point": POINT, "timeoutMs": 20000}), "OK")
        check_status("ARM_FAULT halts at the next record, the finish",
                     send(control, {"type": "ARM_FAULT", "point": POINT, "action": "HALT", "match": SUBJECT}), "OK")
    proc = halt_and_restart(proc, work_dir, log_path)
    conn.close()
    with conn_factory() as after:
        check_status("the committed document is present after the restart", find(after, "halted_at_finish"), "OK")
    check("the replayed finish record left nothing", records_of(work_dir, tx_id) == [],
          f"left: {records_of(work_dir, tx_id)}")
    return proc


def test_a_kill_after_a_buffered_save_leaves_nothing(proc, conn_factory, work_dir, log_path):
    section("A SIGKILL after a buffered SAVE leaves nothing: buffered ops live in memory until the commit")
    conn, tx_id = open_transaction_with_one_save(conn_factory, "buffered_only")
    check("the buffered op never reached admin/transactions", records_of(work_dir, tx_id) == [],
          f"records={records_of(work_dir, tx_id)}")
    proc.kill()
    proc.wait(timeout=30)
    conn.close()
    proc = bu.start_server(work_dir, log_path)
    with conn_factory() as after:
        check_status("the uncommitted document is absent after the restart", find(after, "buffered_only"),
                     "NOT_FOUND")
    check("and nothing of the transaction is left", records_of(work_dir, tx_id) == [])
    return proc


GROUP_SUBJECT = f"{DB}|{COLL}"


def live_copies(work_dir: str, doc_id: str) -> int:
    folder = os.path.join(work_dir, "db", DB, COLL)
    needle = f'"_id":"{doc_id}"'
    copies = 0
    for name in os.listdir(folder):
        if name.endswith(".dat"):
            with open(os.path.join(folder, name), "rb") as fp:
                copies += sum(1 for line in fp.read().decode("utf-8", errors="replace").split("\n") if needle in line)
    return copies


def open_transaction_with_saves(conn_factory, doc_ids: list, value: int):
    conn = conn_factory()
    tx_id = send(conn, {"type": "START_TRANSACTION"}).get("transactionId")
    for doc_id in doc_ids:
        check_status(f"buffer {doc_id}", send(conn, {"type": "SAVE", "databaseName": DB, "collectionName": COLL,
                                                      "object": {"_id": doc_id, "v": value}}), "OK")
    return conn, tx_id


def test_a_grouped_commit_halted_before_its_force_is_finished_at_startup(proc, conn_factory, work_dir, log_path):
    section("A commit whose writes share one log force, halted before that force, is finished at the next startup")
    doc_ids = ["grouped_before_a", "grouped_before_b", "grouped_before_c"]
    conn, tx_id = open_transaction_with_saves(conn_factory, doc_ids, 1)
    with conn_factory() as control:
        check_status("ARM_FAULT halts before the commit's group force",
                     send(control, {"type": "ARM_FAULT", "point": "WAL_GROUP_APPENDED", "action": "HALT",
                                    "match": GROUP_SUBJECT}), "OK")
    commit_in_background(conn)
    proc = halt_and_restart(proc, work_dir, log_path)
    conn.close()
    with conn_factory() as after:
        for doc_id in doc_ids:
            check_status(f"{doc_id} is present after the restart", find(after, doc_id), "OK")
    check("the startup finish left no record of the transaction", records_of(work_dir, tx_id) == [],
          f"left: {records_of(work_dir, tx_id)}")
    return proc


def test_a_grouped_commit_halted_after_its_force_keeps_one_copy_of_each_document(proc, conn_factory, work_dir,
                                                                                 log_path):
    section("A grouped commit halted after its force, before its blanks, keeps one copy of each document")
    doc_ids = ["grouped_after_a", "grouped_after_b"]
    with conn_factory() as seed:
        for doc_id in doc_ids:
            check_status(f"commit {doc_id} outside a transaction", save(seed, doc_id), "OK")
    conn, tx_id = open_transaction_with_saves(conn_factory, doc_ids + doc_ids, 2)
    with conn_factory() as control:
        check_status("ARM_FAULT halts right after the commit's group force",
                     send(control, {"type": "ARM_FAULT", "point": "WAL_GROUP_FORCED", "action": "HALT",
                                    "match": GROUP_SUBJECT}), "OK")
    commit_in_background(conn)
    proc = halt_and_restart(proc, work_dir, log_path)
    conn.close()
    with conn_factory() as after:
        for doc_id in doc_ids:
            found = find(after, doc_id)
            check(f"{doc_id} holds the committed value", (found.get("object") or {}).get("v") == 2, f"got {found}")
    for doc_id in doc_ids:
        check(f"{doc_id} has exactly one live copy on disk", live_copies(work_dir, doc_id) == 1,
              f"copies={live_copies(work_dir, doc_id)}")
    check("the startup finish left no record of the transaction", records_of(work_dir, tx_id) == [],
          f"left: {records_of(work_dir, tx_id)}")
    return proc


def run(conn_factory, write_config: Callable[[str], None]):
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-txkill-")
    log_path = os.path.join(work_dir, "server.log")
    write_config(work_dir)
    print("\n  Starting a server for the transaction-record kill cases ...")
    proc = bu.start_server(work_dir, log_path)
    try:
        with conn_factory() as conn:
            check_status("create the database", send(conn, {"type": "CREATE_DATABASE", "databaseName": DB}), "OK")
            check_status("create the collection", send(conn, {"type": "CREATE_COLLECTION", "databaseName": DB,
                                                              "collectionName": COLL}), "OK")
        proc = test_a_commit_halted_at_its_begin_record_is_finished_at_startup(proc, conn_factory, work_dir, log_path)
        proc = test_a_commit_halted_at_its_finish_record_keeps_its_document(proc, conn_factory, work_dir, log_path)
        proc = test_a_kill_after_a_buffered_save_leaves_nothing(proc, conn_factory, work_dir, log_path)
        proc = test_a_grouped_commit_halted_before_its_force_is_finished_at_startup(proc, conn_factory, work_dir,
                                                                                    log_path)
        proc = test_a_grouped_commit_halted_after_its_force_keeps_one_copy_of_each_document(proc, conn_factory,
                                                                                            work_dir, log_path)
    finally:
        bu.stop_server(proc)
    shutil.rmtree(work_dir, ignore_errors=True)
