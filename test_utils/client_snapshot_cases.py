import json
import threading

import base_utils as bu
from base_utils import Conn, check, check_code, check_status, section

SNAP = "client_snap"
LEDGER = "ledger"
EVENTS = "events"
FILLERS = 20000
CHURN = 600
PRESEEDED_DELETES = 400
MAX_ROUNDS = 290
FILLER_BODY = "f" * 160
GROWN_BODY = "g" * 3000
ABORT_ROWS = 400
ROUND_PAUSE_S = 0.02
SMALL_PAGE_REPLY_BYTES = 64 * 1024
MIN_ROUNDS_DURING_DUMP = 5


def _send_all(conn: Conn, label: str, requests: list):
    for request in requests:
        check_status(label, conn.send(request), "OK")


def _bulk(db: str, coll: str, objects: list) -> dict:
    return {"type": "BULK_SAVE", "databaseName": db, "collectionName": coll, "objects": objects}


def _seed(port: int):
    with Conn.authenticated(port=port) as c:
        check_status(f"create {SNAP}", c.send({"type": "CREATE_DATABASE", "databaseName": SNAP}), "OK")
        for coll in (LEDGER, EVENTS):
            check_status(f"create {SNAP}.{coll}", c.send({"type": "CREATE_COLLECTION", "databaseName": SNAP,
                                                         "collectionName": coll}), "OK")
        _send_all(c, "seed the churn documents first, so they sit before the fillers",
                  [_bulk(SNAP, LEDGER, [{"_id": f"churn-{i:04d}", "body": "c"} for i in range(start, start + 200)])
                   for start in range(0, CHURN, 200)])
        _send_all(c, "seed the clock", [_bulk(SNAP, LEDGER, [{"_id": "clock", "n": 0}])])
        _send_all(c, "seed the fillers",
                  [_bulk(SNAP, LEDGER, [{"_id": f"filler-{i:05d}", "body": FILLER_BODY, "i": i}
                                        for i in range(start, start + 2000)])
                   for start in range(0, FILLERS, 2000)])
        _send_all(c, "seed the documents the writer deletes",
                  [_bulk(SNAP, EVENTS, [{"_id": f"del-{i:04d}"} for i in range(1, PRESEEDED_DELETES + 1)])])


def _transaction_round(conn: Conn, k: int) -> bool:
    requests = [
        {"type": "START_TRANSACTION"},
        {"type": "SAVE", "databaseName": SNAP, "collectionName": LEDGER, "object": {"_id": "clock", "n": k}},
        {"type": "SAVE", "databaseName": SNAP, "collectionName": EVENTS, "object": {"_id": f"ins-{k:04d}"}},
        {"type": "DELETE", "databaseName": SNAP, "collectionName": EVENTS, "_id": f"del-{k:04d}"},
        {"type": "DELETE", "databaseName": SNAP, "collectionName": LEDGER, "_id": f"churn-{k:04d}"},
        {"type": "SAVE", "databaseName": SNAP, "collectionName": LEDGER,
         "object": {"_id": f"churn-{k + 300:04d}", "body": GROWN_BODY}},
        {"type": "COMMIT_TRANSACTION"},
    ]
    for request in requests:
        if conn.send(request).get("status") != "OK":
            conn.send({"type": "ROLLBACK_TRANSACTION"})
            return False
    return True


class _Writer(threading.Thread):
    def __init__(self, port: int):
        super().__init__(daemon=True)
        self.port = port
        self.rounds = 0
        self.failures = 0
        self.stop = threading.Event()

    def run(self):
        with Conn.authenticated(port=self.port) as conn:
            k = 1
            while not self.stop.is_set() and k <= MAX_ROUNDS:
                if _transaction_round(conn, k):
                    self.rounds = k
                    k += 1
                else:
                    self.failures += 1
                self.stop.wait(ROUND_PAUSE_S)


def _documents(port: int, coll: str) -> dict:
    with Conn.authenticated(port=port) as c:
        reply = c.send({"type": "AGGREGATE", "databaseName": SNAP, "collectionName": coll, "aggregationSteps": []})
    return {doc["_id"]: doc for doc in reply.get("results") or []}


def _check_cut(port: int, writer_rounds: int):
    ledger = _documents(port, LEDGER)
    events = _documents(port, EVENTS)
    clock = ledger.get("clock", {}).get("n")
    check("the restored clock is a value the writer committed", isinstance(clock, int) and 0 <= clock <= writer_rounds,
          f"clock={clock}, rounds={writer_rounds}")
    if not isinstance(clock, int):
        return
    check("the dump cut through the writes, and writes kept landing while it read",
          0 < clock and writer_rounds - clock >= MIN_ROUNDS_DURING_DUMP, f"clock={clock}, rounds={writer_rounds}")
    missing_inserts = [k for k in range(1, clock + 1) if f"ins-{k:04d}" not in events]
    late_inserts = [k for k in range(clock + 1, writer_rounds + 1) if f"ins-{k:04d}" in events]
    check("every insert committed before the cut is there", not missing_inserts, str(missing_inserts[:10]))
    check("no insert committed after the cut is there", not late_inserts, str(late_inserts[:10]))
    early_deletes = [k for k in range(1, clock + 1) if f"del-{k:04d}" in events]
    lost_survivors = [k for k in range(clock + 1, PRESEEDED_DELETES + 1) if f"del-{k:04d}" not in events]
    check("every delete committed before the cut is applied", not early_deletes, str(early_deletes[:10]))
    check("no delete committed after the cut is applied", not lost_survivors, str(lost_survivors[:10]))
    churn_wrong = [k for k in range(1, clock + 1) if f"churn-{k:04d}" in ledger]
    churn_kept = [k for k in range(clock + 1, MAX_ROUNDS + 1) if f"churn-{k:04d}" not in ledger]
    check("the other collection of each transaction agrees with the clock", not churn_wrong and not churn_kept,
          f"present though deleted: {churn_wrong[:5]}, missing: {churn_kept[:5]}")
    grown_wrong = [k for k in range(1, MAX_ROUNDS + 1)
                   if (ledger.get(f"churn-{k + 300:04d}", {}).get("body") == GROWN_BODY) != (k <= clock)]
    check("a document grown (relocated) by the writer is in its version at the cut", not grown_wrong,
          str(grown_wrong[:10]))
    fillers = [f"filler-{i:05d}" for i in range(FILLERS)]
    lost_fillers = [name for name in fillers if name not in ledger]
    changed_fillers = [name for name in fillers if name in ledger and ledger[name].get("body") != FILLER_BODY]
    check(f"every untouched document is there ({FILLERS})", not lost_fillers,
          f"{len(lost_fillers)} lost, first {lost_fillers[:5]}")
    check("and unchanged", not changed_fillers, str(changed_fillers[:5]))


def test_dump_is_a_point_in_time_cut_under_writes(cwd: str, client, source_port: int, target_port: int):
    section("--dump is one consistent cut of a database while transactions keep writing")
    _seed(source_port)
    writer = _Writer(source_port)
    writer.start()
    try:
        check("the writer is committing before the dump starts",
              bool(bu.wait_until(lambda: writer.rounds >= 10, 30.0)), f"rounds={writer.rounds}")
        dumped = client(cwd, "--dump", "--databases", SNAP, "--max-reply-bytes", str(SMALL_PAGE_REPLY_BYTES))
    finally:
        writer.stop.set()
        writer.join(60)
    check("--dump under writes exits 0", dumped.returncode == 0, dumped.stderr.decode())
    check("the writer never failed", writer.failures == 0, f"{writer.failures} failed rounds")
    restored = client(cwd, "--restore", port=target_port, stdin=dumped.stdout)
    check("--restore of that dump exits 0", restored.returncode == 0, restored.stderr.decode())
    _check_cut(target_port, writer.rounds)
    for port in (source_port, target_port):
        with Conn.authenticated(port=port) as c:
            c.send({"type": "DROP_DATABASE", "databaseName": SNAP})


def _start(conn: Conn, db: str) -> dict:
    return conn.send({"type": "START_SNAPSHOT", "databaseName": db})


def _read(conn: Conn, db: str, coll: str, snapshot_id: str, limit: int = 10) -> dict:
    return conn.send({"type": "READ_SNAPSHOT", "databaseName": db, "collectionName": coll,
                      "snapshotId": snapshot_id, "limit": limit})


def test_a_snapshot_past_its_budget_is_aborted_and_the_writes_stand(port: int):
    section("A snapshot past snapshotMaxBytes is aborted; the write that passed it stands")
    with Conn.authenticated(port=port) as c:
        check_status(f"create {SNAP}", c.send({"type": "CREATE_DATABASE", "databaseName": SNAP}), "OK")
        check_status(f"create {SNAP}.{LEDGER}", c.send({"type": "CREATE_COLLECTION", "databaseName": SNAP,
                                                       "collectionName": LEDGER}), "OK")
        check_status("seed", c.send(_bulk(SNAP, LEDGER, [{"_id": f"r{i:04d}", "body": FILLER_BODY}
                                                         for i in range(ABORT_ROWS)])), "OK")
        started = _start(c, SNAP)
        check_status("START_SNAPSHOT", started, "OK")
        snapshot_id = started.get("snapshotId")
        with Conn.authenticated(port=port) as writer:
            check_status("a bulk update larger than the budget is accepted",
                         writer.send(_bulk(SNAP, LEDGER, [{"_id": f"r{i:04d}", "body": "updated"}
                                                          for i in range(ABORT_ROWS)])), "OK")
        aborted = _read(c, SNAP, LEDGER, snapshot_id)
        check_code("reading the snapshot answers 409-17", aborted, "ERROR", "409-17")
        check("the reason names snapshotMaxBytes", "snapshotMaxBytes" in (aborted.get("message") or ""),
              aborted.get("message") or "")
        current = c.send({"type": "FIND_BY_ID", "databaseName": SNAP, "collectionName": LEDGER, "_id": "r0000"})
        check("the update is in place", (current.get("object") or {}).get("body") == "updated", json.dumps(current))
        check_status("RELEASE_SNAPSHOT of an aborted snapshot",
                     c.send({"type": "RELEASE_SNAPSHOT", "databaseName": SNAP, "snapshotId": snapshot_id}), "OK")
        c.send({"type": "DROP_DATABASE", "databaseName": SNAP})


def test_snapshots_are_released_on_disconnect_and_when_idle(port: int):
    section("A snapshot is released when its connection closes, and when it is left unread")
    with Conn.authenticated(port=port) as c:
        check_status(f"create {SNAP}", c.send({"type": "CREATE_DATABASE", "databaseName": SNAP}), "OK")
    holder = Conn.authenticated(port=port)
    with Conn.authenticated(port=port) as other:
        check_status("the first START_SNAPSHOT", _start(holder, SNAP), "OK")
        check_code("a second one past snapshotMaxOpen answers 503-22", _start(other, SNAP), "ERROR", "503-22")
        holder.close()
        reopened = bu.wait_until(lambda: _start(other, SNAP).get("status") == "OK", 10.0)
        check("closing the holder's connection released its snapshot", bool(reopened))
        with Conn.authenticated(port=port) as idle:
            check_code("while the other connection holds one, 503-22 again", _start(idle, SNAP), "ERROR", "503-22")
        expired = bu.wait_until(lambda: _start(other, SNAP).get("status") == "OK", 15.0)
        check("an unread snapshot is released after snapshotIdleTimeoutMs", bool(expired))
    with Conn.authenticated(port=port) as c:
        c.send({"type": "DROP_DATABASE", "databaseName": SNAP})
