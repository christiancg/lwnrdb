import argparse
import base64
import bisect
import hashlib
import json
import os
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from collections.abc import Callable
from typing import Union

import base_utils as bu
from base_utils import (Conn, check, check_code, check_field, check_status, filter_step, holds_throughout, ids_of,
                        section, wait_until)

HOST = "127.0.0.1"
BASE_CLIENT_PORT = int(os.environ.get("CLUSTER_TEST_CLIENT_PORT", "8991"))
BASE_CLUSTER_PORT = int(os.environ.get("CLUSTER_TEST_CLUSTER_PORT", "9991"))
NODE_COUNT = 3

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD
CLUSTER_SECRET = "integration-cluster-secret-of-32-plus-chars"

GOSSIP_INTERVAL_S = 0.5
ANTI_ENTROPY_INTERVAL_S = 3.0

HAMMER_CONNECTIONS = 4
HAMMER_SECONDS = 5.0

bu.configure(host=HOST, port=BASE_CLIENT_PORT,
             username=ADMIN_USERNAME, password=ADMIN_PASSWORD)

nodes: list["Node"] = []


def authed(port: int) -> Conn:
    conn = Conn(port=port)
    r = conn.send({"type": "AUTHENTICATE",
                              "username": ADMIN_USERNAME, "password": ADMIN_PASSWORD})
    if r.get("status") != "OK":
        conn.close()
        raise RuntimeError(f"admin auth failed on port {port}: {r}")
    return conn


def op(port: int, payload: dict, timeout=None) -> dict:
    conn = authed(port)
    try:
        return conn.send(payload, timeout)
    finally:
        conn.close()


def as_user(port: int, username: str, password: str, payload=None) -> dict:
    conn = Conn(port=port)
    try:
        auth = conn.send({"type": "AUTHENTICATE", "username": username, "password": password})
        if payload is None or auth.get("status") != "OK":
            return auth
        return conn.send(payload)
    finally:
        conn.close()


def create_db(port, db):
    return op(port, {"type": "CREATE_DATABASE", "databaseName": db})


def drop_db(port, db):
    return op(port, {"type": "DROP_DATABASE", "databaseName": db})


def list_databases(port):
    return op(port, {"type": "LIST_DATABASES"})


def create_coll(port, db, coll):
    return op(port, {"type": "CREATE_COLLECTION", "databaseName": db, "collectionName": coll})


def list_collections(port, db):
    return op(port, {"type": "LIST_COLLECTIONS", "databaseName": db})


def save(port, db, coll, obj):
    return op(port, {"type": "SAVE", "databaseName": db, "collectionName": coll, "object": obj})


def bulk_save(port, db, coll, objs):
    return op(port, {"type": "BULK_SAVE", "databaseName": db, "collectionName": coll, "objects": objs})


def find_by_id(port, db, coll, _id):
    return op(port, {"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll, "_id": _id})


def delete(port, db, coll, _id):
    return op(port, {"type": "DELETE", "databaseName": db, "collectionName": coll, "_id": _id})


def aggregate(port, db, coll, steps):
    return op(port, {"type": "AGGREGATE", "databaseName": db, "collectionName": coll,
                     "aggregationSteps": steps})


def create_index(port, db, coll, field):
    return op(port, {"type": "CREATE_INDEX", "databaseName": db, "collectionName": coll, "fieldName": field})


def run_script(port, script, db=None, conn=None) -> dict:
    payload = {"type": "RUN_SCRIPT", "databaseName": db or DB, "script": script}
    if conn is not None:
        return conn.send(payload)
    return op(port, payload)


def burst(port, script, count: int) -> list:
    results: list = [None] * count
    def worker(index):
        results[index] = run_script(port, script)
    threads = [threading.Thread(target=worker, args=(i,)) for i in range(count)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return results


class BackgroundOp:

    def __init__(self, target: Union[Conn, int], payload: dict):
        self.response = None
        self._thread = threading.Thread(target=self._run, args=(target, payload), daemon=True)
        self._thread.start()

    def _run(self, target, payload):
        self.response = target.send(payload) if isinstance(target, Conn) else op(target, payload)

    def join(self, timeout_s: float = 60.0) -> bool:
        self._thread.join(timeout_s)
        return not self._thread.is_alive()


def list_scripts(port) -> list:
    return op(port, {"type": "LIST_SCRIPTS"}).get("scripts") or []


def cancel_script(port, run_id) -> dict:
    return op(port, {"type": "CANCEL_SCRIPT", "runId": run_id})


def until_stat_grows(port, key: str, attempt: Callable[[int], list], timeout_s: float = 25.0,
                     min_rounds: int = 1):
    deadline = time.time() + timeout_s
    round_no = 0
    while True:
        before = script_stats(port).get(key, 0)
        results = attempt(round_no)
        delta = script_stats(port).get(key, 0) - before
        round_no += 1
        if delta > 0 or (round_no >= min_rounds and time.time() >= deadline):
            return results, delta
        time.sleep(0.3)


def until_forwarded(port, attempt: Callable[[int], list], timeout_s: float = 25.0):
    return until_stat_grows(port, "forwarded", attempt, timeout_s, min_rounds=3)


def wait_until_forwarding_is_live(port, timeout_s: float = 60.0) -> bool:
    conn = authed(port)
    try:
        def _forwards_at_least_once():
            before = script_stats(port).get("forwarded", 0)
            for _ in range(15):
                run_script(None, "return 1 + 1;", conn=conn)
            return script_stats(port).get("forwarded", 0) > before

        return wait_until(_forwards_at_least_once, timeout_s=timeout_s, interval_s=0.5)
    finally:
        conn.close()


def script_stats(port) -> dict:
    r = op(port, {"type": "GET_DATABASE_STATS"})
    return (r.get("stats") or {}).get("scripts") or {}


def all_ports() -> list:
    return [n.client_port for n in nodes if n.alive]


def all_nodes_see(db, coll, _id, expected_value, field="v", ports=None, timeout_s=15.0) -> bool:
    ports = ports if ports is not None else all_ports()

    def _seen():
        for p in ports:
            r = find_by_id(p, db, coll, _id)
            if r.get("status") != "OK" or bu.dig(r, f"object.{field}") != expected_value:
                return False
        return True

    return wait_until(_seen, timeout_s)


def stays_converged(db, coll, expected: dict, seconds: float) -> set:
    diverged = set()

    def _sample():
        for doc_id, value in expected.items():
            if doc_id not in diverged and not all_nodes_see(db, coll, doc_id, value, ports=all_ports(),
                                                            timeout_s=1.0):
                diverged.add(doc_id)
        return True

    holds_throughout(_sample, seconds)
    return diverged


def _folder_contains(folder: str, token: str, suffixes: tuple) -> bool:
    if not os.path.isdir(folder):
        return False
    for name in sorted(os.listdir(folder)):
        if not name.endswith(suffixes):
            continue
        try:
            with open(os.path.join(folder, name), encoding="utf-8", errors="replace") as fp:
                if token in fp.read():
                    return True
        except OSError:
            pass
    return False


def holds_tx_record(node, tx_id, suffix="") -> bool:
    return _folder_contains(os.path.join(node.work_dir, "db", "admin", "transactions"),
                            f"{tx_id}|{suffix}", (".dat",))


def commit_in_background(conn: Conn, node, tx_id, marker, timeout_s, interval_s):
    pending = BackgroundOp(conn, {"type": "COMMIT_TRANSACTION"})
    return pending, wait_until(lambda: holds_tx_record(node, tx_id, marker), timeout_s=timeout_s, interval_s=interval_s)


def stored_documents(node, db, coll) -> dict:
    result = {}
    folder = os.path.join(node.work_dir, "db", db, coll)
    if not os.path.isdir(folder):
        return result
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".dat"):
            continue
        try:
            with open(os.path.join(folder, name), encoding="utf-8", errors="replace") as fp:
                for line in fp:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        document = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if isinstance(document, dict) and "_id" in document:
                        result[document["_id"]] = document
        except OSError:
            pass
    return result


def owned_by_every_node(prefix):
    owners = collections_by_owner(nodes[0].client_port, DB, prefix)
    if not check("found a collection owned by each node", all(i in owners for i in range(NODE_COUNT)),
                 f"owners={owners}"):
        return None
    return owners


def indexes_of(port, db, coll):
    r = op(port, {"type": "GET_DATABASE_STATS"})
    for d in bu.dig(r, "stats.databases") or []:
        if d.get("name") != db:
            continue
        for c in d.get("collections") or []:
            if c.get("name") == coll:
                return c.get("indexes") or []
    return None


def fresh_database(port, db, seed_value=1, label_prefix=""):
    check_status(f"{label_prefix}CREATE_DATABASE", op_with_retry(lambda: create_db(port, db)), "OK")
    check_status(f"{label_prefix}CREATE_COLLECTION", op_with_retry(lambda: create_coll(port, db, "docs")), "OK")
    if seed_value is not None:
        check_status(f"{label_prefix}SAVE", op_with_retry(
            lambda: save(port, db, "docs", {"_id": "d1", "v": seed_value})), "OK")


def owner_of(port, db, coll, attempts=20):
    for _ in range(attempts):
        conn = authed(port)
        try:
            tx_id = conn.send({"type": "START_TRANSACTION"}).get("transactionId")
            if tx_id is None:
                return None
            probe = conn.send({"type": "SAVE", "databaseName": db, "collectionName": coll,
                               "object": {"_id": "owner_probe", "v": 0}})
            if probe.get("status") == "OK":
                return next((n for n in nodes if n.alive and holds_tx_record(n, tx_id)), None)
        finally:
            conn.send({"type": "ROLLBACK_TRANSACTION"})
            conn.close()
        time.sleep(0.5)
    return None


def collections_by_owner(port, db, prefix, count=24) -> dict:
    names = [f"{prefix}{i}" for i in range(count)]

    def _created():
        return all(create_coll(port, db, coll_name).get("status") == "OK" for coll_name in names)

    if not wait_until(_created, timeout_s=30.0, interval_s=1.0):
        return {}
    grouped = {}
    for name in names:
        node = owner_of(port, db, name)
        if node is not None:
            grouped.setdefault(node.index, []).append(name)
    return grouped


CANARY_DB = "cluster_canary_db"
CANARY_COLL = "canary"


_CANARY_COUNTER = {"n": 0}


def _canary_write_converges() -> bool:
    live = next((n for n in nodes if n.alive), None)
    if live is None:
        return False
    _CANARY_COUNTER["n"] += 1
    v = _CANARY_COUNTER["n"]
    if save(live.client_port, CANARY_DB, CANARY_COLL, {"_id": "canary", "v": v}).get("status") != "OK":
        return False
    return all_nodes_see(CANARY_DB, CANARY_COLL, "canary", v, timeout_s=1.0)


def wait_for_cluster(timeout_s: float = 60.0):
    print(f"  Waiting for the {NODE_COUNT}-node cluster to converge ...")
    op_with_retry(lambda: op(nodes[0].client_port, {"type": "CREATE_DATABASE", "databaseName": CANARY_DB}),
                  timeout_s)
    op_with_retry(lambda: op(nodes[0].client_port, {"type": "CREATE_COLLECTION",
                                                    "databaseName": CANARY_DB, "collectionName": CANARY_COLL}),
                  timeout_s)
    if not wait_until(_canary_write_converges, timeout_s, interval_s=1.0):
        for n in nodes:
            n.dump_log()
        raise RuntimeError("cluster did not converge in time")
    print("  Cluster converged.")


def wait_until_cluster_is_healthy(timeout_s: float = 60.0) -> bool:
    return wait_until(_canary_write_converges, timeout_s, interval_s=1.0)


def seed_documents(port, db, coll, objs, timeout_s: float = 30.0) -> bool:
    for obj in objs:
        if not wait_until(lambda o=obj: save(port, db, coll, o).get("status") == "OK", timeout_s):
            return False
    return True


class Node:
    def __init__(self, index: int, base_dir: str):
        self.index = index
        self.client_port = BASE_CLIENT_PORT + index
        self.cluster_port = BASE_CLUSTER_PORT + index
        self.work_dir = os.path.join(base_dir, f"node-{index}")
        os.makedirs(self.work_dir, exist_ok=True)
        self.log_path = os.path.join(self.work_dir, "server.log")
        self.proc = None
        self.alive = False
        self._write_config()

    def _write_config(self):
        bu.write_config(
            self.work_dir, self.client_port,
            maxMemory="96mb",
            clusterEnabled=True,
            clusterPort=self.cluster_port,
            clusterBindAddress="127.0.0.1",
            clusterAdvertisedAddress="127.0.0.1",
            clusterSeeds="" if self.index == 0 else f"{HOST}:{BASE_CLUSTER_PORT}",
            clusterExpectedSize=3,
            clusterSecret=CLUSTER_SECRET,
            gossipIntervalMs=int(GOSSIP_INTERVAL_S * 1000),
            suspectTimeoutMs=2000,
            deadTimeoutMs=4000,
            deadProbeIntervalMs=1000,
            replicationAckTimeoutMs=5000,
            virtualNodesPerNode=128,
            readFallbackToLocal=True,
            antiEntropyIntervalMs=int(ANTI_ENTROPY_INTERVAL_S * 1000),
            tombstoneRetentionMs=86400000,
            scriptsEnabled=True,
            scriptTimeoutMs=20000,
            scriptRoutingEnabled=self.index != 2,
            scriptLocalityWeight=100 if self.index == 0 else 0,
            maxConcurrentScriptsPerUser=0,
            maxConcurrentScriptsPerDatabase=0,
            triggersEnabled=True,
            beforeHookInstructionBudget=200000,
            beforeHookTimeoutMs=2000,
            schedulesEnabled=True,
            scheduleTickMs=200,
            scheduleRefreshMs=2000,
            scheduleTimeoutMs=10000,
            faultInjectionEnabled=True,
        )

    def start(self):
        log = open(self.log_path, "ab")
        self.proc = subprocess.Popen(
            ["java", "-Xmx1g", "-jar", bu.JAR_PATH],
            stdout=log, stderr=log, cwd=self.work_dir)
        deadline = time.time() + 60.0
        while time.time() < deadline:
            if bu.port_open(HOST, self.client_port):
                self.alive = True
                return
            if self.proc.poll() is not None:
                break
            time.sleep(0.2)
        self.dump_log()
        self.proc.kill()
        raise RuntimeError(f"node-{self.index} did not come up in time")

    def kill(self):
        if self.proc is not None:
            self.proc.kill()
            try:
                self.proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                pass
        self.alive = False

    def wait_for_exit(self, timeout_s: float) -> bool:
        try:
            self.proc.wait(timeout=timeout_s)
        except subprocess.TimeoutExpired:
            return False
        self.alive = False
        return True

    def suspend(self):
        if self.proc is not None:
            self.proc.send_signal(signal.SIGSTOP)

    def resume(self):
        if self.proc is not None:
            self.proc.send_signal(signal.SIGCONT)

    def stop(self):
        if self.proc is not None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.alive = False

    def dump_log(self, tail_bytes: int = 4000):
        try:
            with open(self.log_path, "rb") as fp:
                tail = fp.read()[-tail_bytes:].decode(errors="replace")
            print(f"--- node-{self.index} log tail ---\n{tail}\n--- end ---", file=sys.stderr)
        except OSError:
            pass


def dump_all_logs(tail_bytes: int = 20000):
    for n in nodes:
        n.dump_log(tail_bytes)


DB = "cluster_test_db"
LOCALITY_DB = "locality_db"


def test_formation_and_quorum_writes():
    section("Cluster formation + synchronous quorum writes")

    p0 = nodes[0].client_port
    check_status("CREATE_DATABASE on the seed node commits under quorum",
                 op_with_retry(lambda: create_db(p0, DB)), "OK")
    check_status("CREATE_COLLECTION commits under quorum",
                 op_with_retry(lambda: create_coll(p0, DB, "docs")), "OK")

    check_status("SAVE reaches the replication quorum (not 503-2/503-3)",
                 op_with_retry(lambda: save(p0, DB, "docs", {"_id": "q1", "v": 1})), "OK")


RETRYABLE_CODES = {"503-2", "503-5", "503-9", "503-10"}


def op_with_retry(fn: Callable[[], dict], timeout_s=30.0, interval_s=1.0):
    deadline = time.time() + timeout_s
    response = fn()
    while response.get("errorCode") in RETRYABLE_CODES and time.time() < deadline:
        time.sleep(interval_s)
        response = fn()
    return response


def test_ddl_replication():
    section("DDL replication — CREATE/DROP propagate to every node")

    check_status("CREATE_DATABASE via node-1",
                 op_with_retry(lambda: create_db(nodes[1].client_port, "ddl_repl_db")), "OK")
    check_status("CREATE_COLLECTION via node-1",
                 op_with_retry(lambda: create_coll(nodes[1].client_port, "ddl_repl_db", "widgets")), "OK")

    seen_db = wait_until(
        lambda: all("ddl_repl_db" in (list_databases(p).get("databases") or []) for p in all_ports()),
        timeout_s=15.0)
    check("new database is listed on every node", seen_db)

    seen_coll = wait_until(
        lambda: all("widgets" in (list_collections(p, "ddl_repl_db").get("collections") or [])
                    for p in all_ports()),
        timeout_s=15.0)
    check("new collection is listed on every node", seen_coll)

    check_code("CREATE_DATABASE cluster is refused: it is every node's cluster state folder",
               op_with_retry(lambda: create_db(nodes[1].client_port, "cluster")), "ERROR", "400-1")
    check("every node still holds its persisted node id",
          all(os.path.isfile(os.path.join(node.work_dir, "db", "cluster", "node.id")) for node in nodes))

    check_code("duplicate CREATE_DATABASE via node-2 conflicts (409-2)",
               op_with_retry(lambda: create_db(nodes[2].client_port, "ddl_repl_db")), "ERROR", "409-2")

    check_status("DROP_DATABASE via node-2",
                 op_with_retry(lambda: drop_db(nodes[2].client_port, "ddl_repl_db")), "OK")
    dropped = wait_until(
        lambda: all("ddl_repl_db" not in (list_databases(p).get("databases") or []) for p in all_ports()),
        timeout_s=15.0)
    check("dropped database disappears from every node", dropped)


def test_write_replication_and_read_routing():
    section("Document write replication + read routing (write anywhere, read everywhere)")

    create_coll(nodes[0].client_port, DB, "routed")

    for i in range(NODE_COUNT):
        _id = f"w{i}"
        r = save(nodes[i].client_port, DB, "routed", {"_id": _id, "v": i * 10})
        check_status(f"SAVE via node-{i} commits", r, "OK")
        check(f"doc written via node-{i} is visible on every node",
                   all_nodes_see(DB, "routed", _id, i * 10),
                   detail=f"_id={_id}")

    r = aggregate(nodes[2].client_port, DB, "routed", [{"type": "COUNT"}])
    check_field("COUNT via node-2 reflects all cross-node writes", r, "results.0.count", NODE_COUNT)


def test_bulk_delete_and_upsert():
    section("BULK_SAVE / DELETE / upsert replication")

    create_coll(nodes[0].client_port, DB, "bulk")

    r = bulk_save(nodes[1].client_port, DB, "bulk",
                  [{"_id": "b1", "v": 1}, {"_id": "b2", "v": 2}, {"_id": "b3", "v": 3}])
    check_status("BULK_SAVE via node-1 commits", r, "OK")
    check("all bulk docs are visible on every node",
               all_nodes_see(DB, "bulk", "b1", 1) and all_nodes_see(DB, "bulk", "b2", 2)
               and all_nodes_see(DB, "bulk", "b3", 3))

    check_status("upsert b1 via node-2", save(nodes[2].client_port, DB, "bulk", {"_id": "b1", "v": 99}), "OK")
    check("upserted value is visible on every node", all_nodes_see(DB, "bulk", "b1", 99))

    check_status("bulk-update every doc via node-1",
                 bulk_save(nodes[1].client_port, DB, "bulk",
                           [{"_id": "b1", "v": 111}, {"_id": "b3", "v": 333}]), "OK")
    check("the bulk-updated values are visible on every node",
          all_nodes_see(DB, "bulk", "b1", 111) and all_nodes_see(DB, "bulk", "b3", 333))
    reverted = stays_converged(DB, "bulk", {"b1": 111, "b3": 333}, seconds=5.0)
    check("the bulk-updated values survive an anti-entropy sweep rather than reverting", not reverted,
          f"reverted: {sorted(reverted)}")

    check_status("DELETE b2 via node-0", delete(nodes[0].client_port, DB, "bulk", "b2"), "OK")

    def _gone():
        return all(find_by_id(p, DB, "bulk", "b2").get("status") == "NOT_FOUND" for p in all_ports())

    check("deleted doc is NOT_FOUND on every node", wait_until(_gone, timeout_s=15.0))

    check_code("DELETE of an id that never existed via node-0",
               delete(nodes[0].client_port, DB, "bulk", "never-there"), "NOT_FOUND", "404-2")
    check("only the deleted id is tombstoned, on every node",
          wait_until(lambda: all(tombstone_ids(n, DB, "bulk") == {"b2"} for n in nodes), timeout_s=15.0),
          f"tombstones: {[sorted(tombstone_ids(n, DB, 'bulk')) for n in nodes]}")


def test_index_replication():
    section("Index replication (CREATE_INDEX is DDL — applied on every node)")

    create_coll(nodes[0].client_port, DB, "indexed")
    bulk_save(nodes[0].client_port, DB, "indexed",
              [{"_id": "e1", "email": "a@x.io"}, {"_id": "e2", "email": "b@x.io"}])
    check_status("CREATE_INDEX via node-1", create_index(nodes[1].client_port, DB, "indexed", "email"), "OK")

    def _indexed_everywhere():
        return all("email" in (indexes_of(p, DB, "indexed") or []) for p in all_ports())

    check("email index is registered on every node", wait_until(_indexed_everywhere, timeout_s=20.0))

    def _query_ok():
        return all(ids_of(aggregate(p, DB, "indexed", [filter_step("email", "EQUALS", "a@x.io")])) == ["e1"]
                   for p in all_ports())

    check("index-backed query is correct on every node", wait_until(_query_ok, timeout_s=15.0))


INDEX_TOKEN_SEPARATOR = "\x1f"


def _index_folder(node, coll) -> str:
    return os.path.join(node.work_dir, "db", DB, coll)


def _index_lines(node, coll, field) -> list:
    folder = _index_folder(node, coll)
    names = sorted(n for n in os.listdir(folder) if n.startswith(f"{coll}-{field}-"))
    entries = {}
    for name in [n for n in names if n.endswith(".idx")] + [n for n in names if n.endswith(".idxlog")]:
        with open(os.path.join(folder, name), encoding="utf-8") as fp:
            for line in fp.read().split("\n")[:-1]:
                tokens = line.split(INDEX_TOKEN_SEPARATOR)
                if name.endswith(".idx"):
                    entries.setdefault(tokens[0], set()).update(tokens[1:])
                elif tokens[0] == "+":
                    entries.setdefault(tokens[1], set()).update(tokens[2:])
                elif tokens[0] == "-":
                    entries.get(tokens[1], set()).difference_update(tokens[2:])
    return sorted(INDEX_TOKEN_SEPARATOR.join([key] + sorted(ids)) for key, ids in entries.items() if ids)


def _damage_string_index(node, coll, field, kept: list):
    folder = _index_folder(node, coll)
    with open(os.path.join(folder, f"{coll}-{field}-String.idx"), "w", encoding="utf-8") as fp:
        fp.write("".join(line + "\n" for line in kept))
    log = os.path.join(folder, f"{coll}-{field}-String.idxlog")
    if os.path.exists(log):
        os.remove(log)


def test_reindex_rebuilds_the_index_on_every_node():
    section("REINDEX is cluster-wide: it rebuilds a damaged index on a node that did not receive it")

    victim = nodes[2]
    check("the replicated index holds both values on node-2",
          wait_until(lambda: len(_index_lines(victim, "indexed", "email")) == 2, timeout_s=20.0),
          detail=str(_index_lines(victim, "indexed", "email")))
    lines = _index_lines(victim, "indexed", "email")
    _damage_string_index(victim, "indexed", "email", [line for line in lines if not line.startswith("a@x.io")])
    check("node-2's index file lost the a@x.io line", len(_index_lines(victim, "indexed", "email")) == 1)

    check_status("REINDEX via node-1", op_with_retry(lambda: op(nodes[1].client_port, {
        "type": "REINDEX", "databaseName": DB, "collectionName": "indexed"})), "OK")

    check("node-2 rebuilt the line it lost",
          wait_until(lambda: sorted(_index_lines(victim, "indexed", "email")) == sorted(lines), timeout_s=20.0),
          detail=str(_index_lines(victim, "indexed", "email")))


def _pk_path(node, coll) -> str:
    return os.path.join(node.work_dir, "db", DB, coll, f"{coll}-pk.idx")


def _rows_read_their_own_record(node, coll) -> bool:
    folder = os.path.join(node.work_dir, "db", DB, coll)
    for doc_id, position, length, page, _version in bu.read_pk_rows(_pk_path(node, coll)):
        with open(os.path.join(folder, f"{coll}-{page}.dat"), "rb") as fp:
            fp.seek(position)
            if not fp.read(length).startswith(f'{{"_id":"{doc_id}"'.encode("utf-8")):
                return False
    return True


def test_reindex_primary_key_rebuilds_the_pk_index_on_every_node():
    section("REINDEX primaryKey is cluster-wide: it re-addresses a damaged pk.idx on a node that did not receive it")

    victim = nodes[2]
    rows = bu.read_pk_rows(_pk_path(victim, "indexed"))
    check("node-2's pk.idx names both documents", len(rows) == 2, detail=str(rows))
    rows[0][1:3], rows[1][1:3] = rows[1][1:3], rows[0][1:3]
    bu.write_pk_rows(_pk_path(victim, "indexed"), rows)
    check("node-2's pk.idx now addresses the wrong records", not _rows_read_their_own_record(victim, "indexed"))

    check_status("REINDEX primaryKey via node-1", op_with_retry(lambda: op(nodes[1].client_port, {
        "type": "REINDEX", "databaseName": DB, "collectionName": "indexed", "primaryKey": True})), "OK")

    check("node-2 rebuilt its pk.idx from its pages",
          wait_until(lambda: _rows_read_their_own_record(victim, "indexed"), timeout_s=20.0))
    for doc_id in ("e1", "e2"):
        check(f"{doc_id} reads back on every node",
              all(find_by_id(p, DB, "indexed", doc_id).get("status") == "OK" for p in all_ports()))


def test_user_and_permission_replication():
    section("User + permission replication (record-shipped — auth works on every node)")

    r = op(nodes[0].client_port, {
        "type": "CREATE_USER", "username": "cluster_reader", "password": "cluster_reader1234",
        "admin": False, "globalPermissions": [], "databasePermissions": {DB: "READ"},
        "collectionPermissions": {}})
    check_status("CREATE_USER via node-0", r, "OK")

    def _auth_everywhere():
        return all(_authenticates(p, "cluster_reader", "cluster_reader1234") for p in all_ports())

    check("replicated user authenticates on every node", wait_until(_auth_everywhere, timeout_s=15.0))

    for i in range(NODE_COUNT):
        c = Conn(port=nodes[i].client_port)
        try:
            c.send({"type": "AUTHENTICATE",
                            "username": "cluster_reader", "password": "cluster_reader1234"})
            w = c.send({"type": "SAVE", "databaseName": DB, "collectionName": "routed",
                                "object": {"_id": "nope", "v": 0}})
            check_code(f"read-only user SAVE via node-{i} is forbidden (403-1)", w, "FORBIDDEN", "403-1")
            rd = c.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": "routed",
                                 "aggregationSteps": [{"type": "COUNT"}]})
            check_status(f"read-only user can still read via node-{i}", rd, "OK")
        finally:
            c.close()

    op(nodes[0].client_port, {"type": "DELETE_USER", "username": "cluster_reader"})


def test_grants_are_pruned_on_every_node():
    section("A dropped database takes its permission grants with it on every node")

    grant_db = "cluster_grant_db"
    port = nodes[0].client_port
    fresh_database(port, grant_db)
    op(port, {"type": "DELETE_USER", "username": "cluster_grantee"})
    check_status("CREATE_USER with a grant on the database", op(port, {
        "type": "CREATE_USER", "username": "cluster_grantee", "password": "cluster_grantee1234",
        "admin": False, "globalPermissions": [], "databasePermissions": {grant_db: "READ"},
        "collectionPermissions": {}}), "OK")

    def read_as_grantee(p):
        return as_user(p, "cluster_grantee", "cluster_grantee1234",
                       {"type": "FIND_BY_ID", "databaseName": grant_db, "collectionName": "docs", "_id": "d1"})

    check("the grantee can read on every node before the drop",
          wait_until(lambda: all(read_as_grantee(p).get("status") == "OK" for p in all_ports()), timeout_s=15.0))

    check_status("DROP_DATABASE via node-0", op_with_retry(lambda: drop_db(port, grant_db)), "OK")
    fresh_database(port, grant_db, seed_value=2, label_prefix="re-")

    def refused_everywhere():
        return all(read_as_grantee(p).get("status") == "FORBIDDEN" for p in all_ports())

    check("the old grant is refused on every node after the re-create",
          wait_until(refused_everywhere, timeout_s=15.0))

    op(port, {"type": "DELETE_USER", "username": "cluster_grantee"})
    op_with_retry(lambda: drop_db(port, grant_db))


def test_password_change_deauthenticates_sessions_on_every_node():
    section("A password change signs the user out on every node, not only on the coordinator")

    username = "cluster_pw_rotator"
    old_password = "cluster_pw_old1234"
    port = nodes[0].client_port
    op(port, {"type": "DELETE_USER", "username": username})
    check_status("CREATE_USER via node-0", op(port, {
        "type": "CREATE_USER", "username": username, "password": old_password, "admin": False,
        "globalPermissions": [], "databasePermissions": {}, "collectionPermissions": {}}), "OK")
    check("the user authenticates on every node",
          wait_until(lambda: all(_authenticates(p, username, old_password) for p in all_ports()), timeout_s=15.0))

    probe = {"type": "LIST_DATABASES"}
    sessions = [Conn(port=nodes[i].client_port) for i in (1, 2)]
    try:
        for i, session in zip((1, 2), sessions):
            check_status(f"session on node-{i} authenticates",
                         session.send({"type": "AUTHENTICATE", "username": username, "password": old_password}),
                         "OK")
            check_status(f"session on node-{i} is served", session.send(probe), "OK")
        check_status("admin changes the password through node-0", op(port, {
            "type": "SET_PASSWORD", "username": username, "newPassword": "cluster_pw_new1234"}), "OK")
        for i, session in zip((1, 2), sessions):
            check(f"session on node-{i} must authenticate again",
                  wait_until(lambda s=session: s.send(probe).get("errorCode") == "401-1", timeout_s=15.0))
    finally:
        for session in sessions:
            session.close()
    op(port, {"type": "DELETE_USER", "username": username})


def test_a_deleted_users_ownership_is_pruned_on_every_node():
    section("A deleted user stops owning databases on every node, so a recreated name inherits nothing")

    owner_db = "cluster_owner_db"
    owner = "cluster_ghost_owner"
    port = nodes[0].client_port
    fresh_database(port, owner_db)
    op(port, {"type": "DELETE_USER", "username": owner})
    create_user = {"type": "CREATE_USER", "username": owner, "password": "cluster_owner1234", "admin": False,
                   "globalPermissions": [], "databasePermissions": {}, "collectionPermissions": {}}
    check_status("CREATE_USER with no grants", op(port, create_user), "OK")
    check_status("SET_DATABASE_OWNERS makes it the owner", op(port, {
        "type": "SET_DATABASE_OWNERS", "databaseName": owner_db, "owners": [owner]}), "OK")

    def read_as_owner(p):
        return as_user(p, owner, "cluster_owner1234",
                       {"type": "FIND_BY_ID", "databaseName": owner_db, "collectionName": "docs", "_id": "d1"})

    check("the owner can read on every node",
          wait_until(lambda: all(read_as_owner(p).get("status") == "OK" for p in all_ports()), timeout_s=15.0))

    check_status("DELETE_USER via node-0", op(port, {"type": "DELETE_USER", "username": owner}), "OK")
    check_status("CREATE_USER under the same name", op(port, create_user), "OK")

    check("the recreated user owns nothing on any node",
          wait_until(lambda: all(read_as_owner(p).get("status") == "FORBIDDEN" for p in all_ports()),
                     timeout_s=15.0))

    op(port, {"type": "DELETE_USER", "username": owner})
    op_with_retry(lambda: drop_db(port, owner_db))


def test_a_client_cannot_advance_the_write_clock_through_an_incarnation():
    section("A client-supplied collection incarnation is ignored")

    forged_db = "cluster_incarnation_db"
    port = nodes[0].client_port
    check_status("CREATE_DATABASE", op_with_retry(lambda: create_db(port, forged_db)), "OK")
    check_status("CREATE_COLLECTION carrying a forged incarnation", op_with_retry(lambda: op(port, {
        "type": "CREATE_COLLECTION", "databaseName": forged_db, "collectionName": "docs",
        "incarnationText": "9223372036854775805"})), "OK")
    for value in (1, 2, 3):
        check_status(f"SAVE v={value}", op_with_retry(
            lambda v=value: save(port, forged_db, "docs", {"_id": "d1", "v": v})), "OK")
    check("the last write wins on every node, so versions kept increasing",
          all_nodes_see(forged_db, "docs", "d1", 3))

    def stored_incarnations():
        found = []
        for node in nodes:
            entry = stored_documents(node, "admin", "collections").get(f"{forged_db}|docs")
            found.append(None if entry is None else entry.get("incarnation"))
        return found

    check("every node registered the collection", wait_until(
        lambda: all(incarnation is not None for incarnation in stored_incarnations()), timeout_s=15.0),
        f"got {stored_incarnations()}")
    incarnations = stored_incarnations()
    check("no node holds the forged incarnation", "9223372036854775805" not in incarnations,
          f"got {incarnations}")
    check("the coordinator minted one and every node agrees on it",
          len(set(incarnations)) == 1 and incarnations[0] not in (None, "0"), f"got {incarnations}")
    op_with_retry(lambda: drop_db(port, forged_db))


def test_a_forged_stamp_is_ignored_and_a_real_one_still_replicates():
    section("A script definition keeps the saving user as definer on every node")

    stamp_db = "cluster_stamp_db"
    manager = "cluster_stamp_manager"
    password = "cluster_stamp1234"
    port = nodes[0].client_port
    fresh_database(port, stamp_db, seed_value=None)
    op(port, {"type": "DELETE_USER", "username": manager})
    check_status("CREATE_USER with MANAGE on the database", op(port, {
        "type": "CREATE_USER", "username": manager, "password": password, "admin": False,
        "databasePermissions": {stamp_db: "READ_WRITE"}, "scriptPermissions": {stamp_db: "MANAGE"}}), "OK")
    check_status("SAVE_PROCEDURE", op_with_retry(lambda: op(port, {
        "type": "SAVE_PROCEDURE", "databaseName": stamp_db, "name": "noop", "script": "return 1;"})), "OK")

    def as_manager(p, payload):
        return as_user(p, manager, password, payload)

    forged = {"stampedVersion": 7, "stampedDefiner": ADMIN_USERNAME, "stampedUpdatedBy": ADMIN_USERNAME,
              "stampedUpdatedAt": 1, "stampedCreatedAt": 1}
    edge = nodes[1].client_port
    check("the manager can authenticate on the edge node", wait_until(
        lambda: as_manager(edge, {"type": "LIST_DATABASES"}).get("status") == "OK", timeout_s=15.0))
    check_status("SAVE_TRIGGER carrying a forged stamp, through a non-coordinator node", as_manager(edge, {
        "type": "SAVE_TRIGGER", "databaseName": stamp_db, "collectionName": "docs", "name": "stamped",
        "events": ["CREATED"], "procedureName": "noop", **forged}), "OK")
    check_status("SAVE_SCHEDULE carrying a forged stamp, through a non-coordinator node", as_manager(edge, {
        "type": "SAVE_SCHEDULE", "databaseName": stamp_db, "name": "stamped", "procedureName": "noop",
        "cron": "0 3 * * *", **forged}), "OK")

    def trigger_on(p):
        listed = op(p, {"type": "LIST_TRIGGERS", "databaseName": stamp_db, "collectionName": "docs"})
        return next((t for t in (listed.get("triggers") or []) if t.get("name") == "stamped"), None)

    def schedule_on(p):
        listed = op(p, {"type": "LIST_SCHEDULES", "databaseName": stamp_db})
        return next((t for t in (listed.get("schedules") or []) if t.get("name") == "stamped"), None)

    check("the trigger reaches every node", wait_until(
        lambda: all(trigger_on(p) is not None for p in all_ports()), timeout_s=15.0))
    check("the schedule reaches every node", wait_until(
        lambda: all(schedule_on(p) is not None for p in all_ports()), timeout_s=15.0))
    triggers = [trigger_on(p) for p in all_ports()]
    schedules = [schedule_on(p) for p in all_ports()]
    check("every node holds the trigger with the saving user as definer",
          all(t and t.get("definer") == manager and t.get("updatedBy") == manager for t in triggers),
          f"got {triggers}")
    check("every node holds the schedule with the saving user as definer",
          all(t and t.get("definer") == manager and t.get("updatedBy") == manager for t in schedules),
          f"got {schedules}")
    check("every node agrees on the trigger version, assigned by the coordinator",
          len({t.get("version") for t in triggers}) == 1 and triggers[0].get("version") == 1, f"got {triggers}")
    check("every node agrees on the schedule version, assigned by the coordinator",
          len({t.get("version") for t in schedules}) == 1 and schedules[0].get("version") == 1,
          f"got {schedules}")
    check("and on when each was created", len({t.get("createdAt") for t in triggers}) == 1
          and len({t.get("createdAt") for t in schedules}) == 1, f"got {triggers} {schedules}")

    op(port, {"type": "DELETE_USER", "username": manager})
    op_with_retry(lambda: drop_db(port, stamp_db))


class PeerSession:
    def __init__(self, node, secret=CLUSTER_SECRET, hello=True):
        self.sock = socket.create_connection((HOST, node.cluster_port), timeout=15)
        self.reader = self.sock.makefile("r", encoding="utf-8")
        self.counter = 0
        if hello:
            self.write_line(json.dumps({"type": "HELLO", "secret": secret}))

    def write_line(self, text):
        self.sock.sendall((text + "\n").encode("utf-8"))

    def frame(self, message: dict) -> dict:
        self.counter += 1
        self.write_line(json.dumps({"correlationId": f"forged-{self.counter}", "secret": CLUSTER_SECRET,
                                    **message}))
        line = self.reader.readline()
        return json.loads(line) if line else {}

    def closed_without_reply(self) -> bool:
        try:
            return self.sock.recv(1) == b""
        except (ConnectionResetError, socket.timeout):
            return True

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def _clock_version_now() -> str:
    return _clock_version_ahead(0)


def _clock_version_ahead(seconds: int) -> str:
    return str(int((time.time() + seconds) * 1000) << 16)


def _escape_marker_anywhere(marker) -> list:
    found = []
    for node in nodes:
        root = os.path.dirname(node.work_dir)
        for folder, _, files in os.walk(root):
            for name in files + [os.path.basename(folder)]:
                if name.startswith(marker):
                    found.append(os.path.join(folder, name))
    return found


def _forwarded(session: PeerSession, acting_user, body: dict) -> dict:
    message = {"type": "FORWARD_REQUEST",
               "forwardBody": base64.b64encode(json.dumps(body).encode("utf-8")).decode("ascii")}
    if acting_user is not None:
        message["actingUser"] = acting_user
    reply = session.frame(message)
    if reply.get("type") != "FORWARD_RESPONSE":
        return reply
    return json.loads(base64.b64decode(reply["forwardBody"]).decode("utf-8"))


def test_forged_peer_frames_are_refused():
    section("Forged peer frames are refused: handshake, names, versions and forwarded identity")

    forged_db = "cluster_forged_db"
    reader = "cluster_forged_reader"
    reader_password = "cluster_forged1234"
    marker = "peer_escape_marker"
    port = nodes[0].client_port
    fresh_database(port, forged_db, seed_value=None)
    op(port, {"type": "DELETE_USER", "username": reader})
    check_status("CREATE_USER with READ only", op(port, {
        "type": "CREATE_USER", "username": reader, "password": reader_password, "admin": False,
        "databasePermissions": {forged_db: "READ"}}), "OK")
    target = nodes[1]
    check("the reader reaches the target node", wait_until(
        lambda: as_user(target.client_port, reader, reader_password, {"type": "LIST_DATABASES"}).get("status")
        == "OK", timeout_s=15.0))

    wrong = PeerSession(target, secret="not-the-cluster-secret-but-long-enough")
    check("a first line with a wrong secret is closed with no reply", wrong.closed_without_reply())
    wrong.close()

    oversized = PeerSession(target, hello=False)
    try:
        oversized.sock.sendall(b"a" * 5000)
    except OSError:
        pass
    check("a first line over 4 KB is closed", oversized.closed_without_reply())
    oversized.close()

    session = PeerSession(target)
    try:
        traversal = f"../{marker}"
        reply = session.frame({"type": "REPLICATE_ADMIN", "adminSnapshot": {"records": [{
            "key": f"database|{traversal}|{traversal}", "version": _clock_version_now(),
            "body": {"_id": traversal, "collections": [], "owners": []}}]}})
        check_field("REPLICATE_ADMIN with a traversal database is skipped, not retried", reply, "type",
                    "REPLICATE_ADMIN_ACK")
        check("and creates no folder outside the data root", not _escape_marker_anywhere(marker),
              f"found {_escape_marker_anywhere(marker)}")
        check("and registers no database", all(
            traversal not in stored_documents(node, "admin", "databases") for node in nodes))

        reply = session.frame({"type": "REPLICATE_ADMIN", "adminSnapshot": {"records": [{
            "key": f"procedure|{forged_db}|../../{marker}", "version": _clock_version_now(),
            "body": {"name": f"../../{marker}", "source": "return 1;"}}]}})
        check_field("REPLICATE_ADMIN with a traversal procedure is skipped, not retried", reply, "type",
                    "REPLICATE_ADMIN_ACK")
        check("and writes no procedure file", not _escape_marker_anywhere(marker),
              f"found {_escape_marker_anywhere(marker)}")

        reply = session.frame({"type": "REPLICATE_ADMIN", "adminSnapshot": {"records": [{
            "key": f"collection|{forged_db}|idx_forged", "version": _clock_version_now(),
            "body": {"_id": f"{forged_db}|idx_forged", "indexes": [f"../{marker}"]}}]}})
        check_field("REPLICATE_ADMIN naming an invalid index field is skipped, not retried", reply, "type",
                    "REPLICATE_ADMIN_ACK")
        check("and writes no index marker", not _escape_marker_anywhere(marker),
              f"found {_escape_marker_anywhere(marker)}")

        skewed_db = "cluster_skewed_db"
        reply = session.frame({"type": "REPLICATE_ADMIN", "adminSnapshot": {"records": [{
            "key": f"database|{skewed_db}|{skewed_db}", "version": _clock_version_ahead(7200),
            "body": {"_id": skewed_db, "collections": [], "owners": []}}]}})
        check_field("REPLICATE_ADMIN with a version beyond the skew is not acknowledged", reply, "type", "ERROR")
        check("and the deferred database is registered nowhere", all(
            skewed_db not in stored_documents(node, "admin", "databases") for node in nodes))

        reply = session.frame({"type": "REPLICATE", "replication": {
            "dbName": traversal, "collName": "docs", "op": "UPSERT", "documents": [{"_id": "x1"}]}})
        check_field("REPLICATE into a traversal database", reply, "type", "ERROR")
        check("and creates nothing", not _escape_marker_anywhere(marker))

        reply = session.frame({"type": "REPLICATE", "replication": {
            "dbName": forged_db, "collName": "docs", "op": "UPSERT", "documents": [{"_id": "poison"}],
            "versions": ["9223372036854775000"]}})
        check_field("REPLICATE with a version far beyond the clock", reply, "type", "ERROR")
        check("the poisoned document is stored nowhere",
              all("poison" not in stored_documents(node, forged_db, "docs") for node in nodes))
        check_status("an ordinary SAVE afterwards", op_with_retry(
            lambda: save(port, forged_db, "docs", {"_id": "after", "v": 1})), "OK")
        check_status("and a newer one", op_with_retry(
            lambda: save(port, forged_db, "docs", {"_id": "after", "v": 2})), "OK")
        check("still replicates in order, so the clock was not poisoned",
              all_nodes_see(forged_db, "docs", "after", 2))

        save_body = {"type": "SAVE", "databaseName": forged_db, "collectionName": "docs",
                     "object": {"_id": "forged", "v": 1}}
        denied = _forwarded(session, reader, save_body)
        check_code("a forwarded SAVE as a READ-only user answers 403-1", denied, "FORBIDDEN", "403-1")
        check("and is stored on no node",
              all("forged" not in stored_documents(node, forged_db, "docs") for node in nodes))
        traversal_body = dict(save_body, databaseName=traversal)
        check_code("a forwarded SAVE into a traversal database answers 400-1",
                   _forwarded(session, ADMIN_USERNAME, traversal_body), "ERROR", "400-1")
        check_code("a forwarded SAVE with no acting user answers 401-1", _forwarded(session, None, save_body),
                   "UNAUTHENTICATED", "401-1")
    finally:
        session.close()

    op(port, {"type": "DELETE_USER", "username": reader})
    op_with_retry(lambda: drop_db(port, forged_db))


def test_single_node_transaction():
    section("Transaction under clustering — commit + rollback + read-your-writes (5a)")

    create_coll(nodes[0].client_port, DB, "txn")

    conn = authed(nodes[1].client_port)
    try:
        check_status("START_TRANSACTION via node-1", conn.send({"type": "START_TRANSACTION"}), "OK")
        check_status("buffered SAVE inside txn", conn.send({
            "type": "SAVE", "databaseName": DB, "collectionName": "txn",
            "object": {"_id": "t1", "v": 7}}), "OK")
        ryw = conn.send({"type": "FIND_BY_ID", "databaseName": DB,
                                    "collectionName": "txn", "_id": "t1"})
        check_field("read-your-writes sees the buffered doc", ryw, "object.v", 7)
        check_status("COMMIT_TRANSACTION", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    finally:
        conn.close()

    check("committed txn doc is visible on every node", all_nodes_see(DB, "txn", "t1", 7))

    conn = authed(nodes[2].client_port)
    try:
        check_status("START_TRANSACTION via node-2", conn.send({"type": "START_TRANSACTION"}), "OK")
        check_status("buffered SAVE inside txn", conn.send({
            "type": "SAVE", "databaseName": DB, "collectionName": "txn",
            "object": {"_id": "t2", "v": 8}}), "OK")
        check_status("ROLLBACK_TRANSACTION", conn.send({"type": "ROLLBACK_TRANSACTION"}), "OK")
    finally:
        conn.close()

    def _rolled_back():
        return all(find_by_id(p, DB, "txn", "t2").get("status") == "NOT_FOUND" for p in all_ports())

    check("rolled-back doc never appears on any node", wait_until(_rolled_back, timeout_s=10.0))


def test_multi_collection_transaction():
    section("Multi-collection transaction — atomic commit visible cluster-wide (5a/5b)")

    mc = ["mc_a", "mc_b", "mc_c"]
    for c in mc:
        create_coll(nodes[0].client_port, DB, c)

    conn = authed(nodes[0].client_port)
    try:
        check_status("START_TRANSACTION (multi-collection)", conn.send({"type": "START_TRANSACTION"}), "OK")
        for c in mc:
            r = conn.send({"type": "SAVE", "databaseName": DB, "collectionName": c,
                                      "object": {"_id": "x", "v": 5}})
            check_status(f"buffered SAVE into {c}", r, "OK")
        check_status("COMMIT spans all involved owners atomically",
              conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    finally:
        conn.close()

    for c in mc:
        check(f"committed doc in {c} is visible on every node",
                   all_nodes_see(DB, c, "x", 5), detail=f"collection={c}")

    conn = authed(nodes[0].client_port)
    try:
        check_status("START_TRANSACTION (rollback)", conn.send({"type": "START_TRANSACTION"}), "OK")
        for c in mc:
            conn.send({"type": "SAVE", "databaseName": DB, "collectionName": c,
                                  "object": {"_id": "y", "v": 6}})
        check_status("ROLLBACK_TRANSACTION", conn.send({"type": "ROLLBACK_TRANSACTION"}), "OK")
        check_status("START_TRANSACTION again on the same connection after the rollback",
                     conn.send({"type": "START_TRANSACTION"}), "OK")
        for c in mc:
            check_status(f"next transaction's SAVE into {c} reuses the connection's participant sessions",
                         conn.send({"type": "SAVE", "databaseName": DB, "collectionName": c,
                                    "object": {"_id": "z", "v": 7}}), "OK")
        check_status("COMMIT of the next transaction on the same connection",
                     conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    finally:
        conn.close()

    for collection in mc:
        check(f"the next transaction's commit is visible in {collection} on every node",
              all_nodes_see(DB, collection, "z", 7), detail=f"collection={collection}")

    def _none_have_y():
        for rolled_back in mc:
            for p in all_ports():
                if find_by_id(p, DB, rolled_back, "y").get("status") != "NOT_FOUND":
                    return False
        return True

    check("rolled-back multi-collection write appears nowhere", wait_until(_none_have_y, timeout_s=10.0))


def _edge_and_remote_collections(prefix):
    owners = collections_by_owner(nodes[0].client_port, DB, prefix)
    remote = [index for index in owners if index != 0]
    if not check("found a collection owned by the edge and one owned by another node",
                 0 in owners and bool(remote), f"owners={owners}"):
        return None, None
    return owners[0][0], owners[remote[0]][0]


def _joined_ids(response):
    rows = response.get("results") or []
    return sorted(doc.get("_id") for row in rows for doc in (row.get("j") or []))


def test_a_transactional_join_sees_a_participants_buffered_writes():
    section("A transactional JOIN reads the join target's buffered writes from the node holding them")
    local, remote = _edge_and_remote_collections("txjoin")
    if local is None:
        return
    check_status("seed the primary row", save(nodes[0].client_port, DB, local, {"_id": "a1", "ref": 1, "v": 1}),
                 "OK")
    check_status("seed a committed join row", save(nodes[0].client_port, DB, remote, {"_id": "b_old", "k": 1, "v": 1}),
                 "OK")
    check("the seeds reached every node",
          all_nodes_see(DB, local, "a1", 1) and all_nodes_see(DB, remote, "b_old", 1))
    join = [{"type": "JOIN", "joinCollection": remote, "localField": "ref", "remoteField": "k", "asField": "j"}]

    conn = authed(nodes[0].client_port)
    try:
        check_status("START_TRANSACTION", conn.send({"type": "START_TRANSACTION"}), "OK")
        check_status("buffer a new join row on its owner", conn.send({
            "type": "SAVE", "databaseName": DB, "collectionName": remote,
            "object": {"_id": "b_new", "k": 1, "v": 2}}), "OK")
        check_status("buffer the delete of the committed join row", conn.send({
            "type": "DELETE", "databaseName": DB, "collectionName": remote, "_id": "b_old"}), "OK")
        inside = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": local,
                            "aggregationSteps": join})
        check("the join lists the buffered row and not the buffered delete",
              _joined_ids(inside) == ["b_new"], f"got {inside}")
        check_status("ROLLBACK_TRANSACTION", conn.send({"type": "ROLLBACK_TRANSACTION"}), "OK")
    finally:
        conn.close()

    outside = aggregate(nodes[0].client_port, DB, local, join)
    check("after the rollback the join sees only the committed row", _joined_ids(outside) == ["b_old"],
          f"got {outside}")


def test_a_transactional_join_across_two_written_nodes_is_refused():
    section("A transactional JOIN over writes buffered on two nodes is refused rather than answered partially")
    local, remote = _edge_and_remote_collections("txjoinsplit")
    if local is None:
        return
    join = [{"type": "JOIN", "joinCollection": remote, "localField": "ref", "remoteField": "k", "asField": "j"}]

    conn = authed(nodes[0].client_port)
    try:
        check_status("START_TRANSACTION", conn.send({"type": "START_TRANSACTION"}), "OK")
        check_status("buffer a primary row on the edge", conn.send({
            "type": "SAVE", "databaseName": DB, "collectionName": local,
            "object": {"_id": "a2", "ref": 2, "v": 2}}), "OK")
        check_status("buffer a join row on its owner", conn.send({
            "type": "SAVE", "databaseName": DB, "collectionName": remote,
            "object": {"_id": "b2", "k": 2, "v": 2}}), "OK")
        refused = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": local,
                             "aggregationSteps": join})
        check_code("the join is refused with 421-3", refused, "ERROR", "421-3")
        check_status("the transaction still commits", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
    finally:
        conn.close()

    check("both committed rows reached every node",
          all_nodes_see(DB, local, "a2", 2) and all_nodes_see(DB, remote, "b2", 2))


def test_a_replica_listener_never_sees_a_partial_transaction():
    section("A listener attached to a replica is notified once the whole transaction applied (5a/5b)")

    coll = "listen_repl"
    create_coll(nodes[0].client_port, DB, coll)
    owner = owner_of(nodes[0].client_port, DB, coll)
    check("the collection has an owner", owner is not None, "cannot tell owner from replica without one")
    if owner is None:
        return

    replicas = [n for n in nodes if n.alive and n is not owner]
    check("there is a replica to listen on", bool(replicas))
    if not replicas:
        return

    listener = authed(replicas[0].client_port)
    writer = authed(nodes[0].client_port)
    try:
        steps = [{"type": "FILTER",
                  "operator": {"fieldOperatorType": "EQUALS", "field": "kind", "value": "repl-txn"}}]
        registered = listener.send({"type": "LISTEN", "databaseName": DB, "collectionName": coll,
                                    "aggregationSteps": steps})
        check_status("LISTEN registered on a replica", registered, "OK")

        expected = {f"repl-{i}" for i in range(1, 6)}
        check_status("START_TRANSACTION", writer.send({"type": "START_TRANSACTION"}), "OK")
        for id_ in sorted(expected):
            writer.send({"type": "SAVE", "databaseName": DB, "collectionName": coll,
                         "object": {"_id": id_, "kind": "repl-txn"}})
        check_status("COMMIT_TRANSACTION", writer.send({"type": "COMMIT_TRANSACTION"}), "OK")

        frames = []
        frame = listener.recv(timeout=10.0)
        while frame is not None:
            frames.append({d.get("_id") for d in (frame.get("results") or [])})
            frame = listener.recv(timeout=2.0)

        check("the replica's listener was notified", bool(frames), "no push reached the replica")
        check("no frame holds part of the transaction", all(ids == expected for ids in frames),
              f"partial frames: {[sorted(ids) for ids in frames if ids != expected]}")

        listener.send({"type": "STOP_LISTEN", "listenId": registered.get("listenId")})
    finally:
        listener.close()
        writer.close()


def test_lock_timeout_aborts_the_whole_transaction():
    section("A participant that timed out on a collection lock votes no (5b)")

    owners = collections_by_owner(nodes[0].client_port, DB, "locktimeout")
    remote = [index for index in owners if index != 0]
    if not check("found collections owned by two nodes other than the edge", len(remote) >= 2, f"owners={owners}"):
        return
    blocked, spared = owners[remote[0]][0], owners[remote[1]][0]

    holder = authed(nodes[0].client_port)
    contender = authed(nodes[0].client_port)
    try:
        check_status("the first transaction takes the collection's write lock",
                     holder.send({"type": "START_TRANSACTION"}), "OK")
        check_status("its buffered write is accepted",
                     holder.send({"type": "SAVE", "databaseName": DB, "collectionName": blocked,
                                  "object": {"_id": "holder", "v": 1}}), "OK")

        check_status("the second transaction starts", contender.send({"type": "START_TRANSACTION"}), "OK")
        check_status("its write to the free collection is buffered",
                     contender.send({"type": "SAVE", "databaseName": DB, "collectionName": spared,
                                     "object": {"_id": "contender", "v": 1}}), "OK")
        timed_out = contender.send({"type": "SAVE", "databaseName": DB, "collectionName": blocked,
                                    "object": {"_id": "contender", "v": 1}}, timeout=30.0)
        check("its write to the locked collection does not succeed",
              timed_out.get("errorCode") in ("409-5", "503-8"), f"got {timed_out}")

        commit = contender.send({"type": "COMMIT_TRANSACTION"}, timeout=30.0)
        check("the client is told the transaction failed rather than committed",
              commit.get("status") != "OK", f"got {commit}")
    finally:
        contender.close()
        holder.send({"type": "ROLLBACK_TRANSACTION"})
        holder.close()

    for coll, _id in ((spared, "contender"), (blocked, "contender"), (blocked, "holder")):
        present = [n.index for n in nodes if n.alive and _id in stored_documents(n, DB, coll)]
        check(f"no node applied {_id} in {coll}", not present, f"node(s) {present} hold it")


def test_admin_transaction_ops():
    section("Admin transaction ops — LIST_TRANSACTIONS + in-doubt count (steady state)")

    for i in range(NODE_COUNT):
        r = op(nodes[i].client_port, {"type": "LIST_TRANSACTIONS"})
        check_status(f"LIST_TRANSACTIONS via node-{i} returns OK", r, "OK")
        check(f"no in-doubt transactions in steady state (node-{i})",
                   (r.get("transactions") or []) == [], detail=f"transactions={r.get('transactions')!r}")

    r = op(nodes[0].client_port, {"type": "GET_DATABASE_STATS"})
    check_status("GET_DATABASE_STATS returns OK", r, "OK")
    check_field("in-doubt transaction count is zero", r, "stats.inDoubtTransactions.count", 0)


def test_script_placement_forwards_runs():
    section("Script node selection — a run is placed on a node chosen by script load")

    for i in (0, 1):
        check(f"node-{i} reports script routing enabled",
                   script_stats(nodes[i].client_port).get("routingEnabled") is True,
                   detail=f"scripts={script_stats(nodes[i].client_port)!r}")

    before = script_stats(nodes[0].client_port)

    def _batch(_round):
        conn = authed(nodes[0].client_port)
        try:
            return [run_script(None, "return 1 + 1;", conn=conn) for _ in range(40)]
        finally:
            conn.close()

    results, forwarded = until_forwarded(nodes[0].client_port, _batch)
    after = script_stats(nodes[0].client_port)

    check("every placed run returned OK", all(r.get("status") == "OK" for r in results),
               detail=f"statuses={sorted({r.get('status') for r in results})}")
    check("every placed run returned the script's value",
               all(r.get("result") == 2 for r in results),
               detail=f"results={sorted({repr(r.get('result')) for r in results})}")
    check("placement moved runs off the receiving node", forwarded > 0,
               detail=f"forwarded delta={forwarded}")
    fallbacks = after.get("forwardFallbacks", 0) - before.get("forwardFallbacks", 0)
    check("no forward failed while every node was up", fallbacks == 0,
               detail=f"forwardFallbacks delta={fallbacks}")
    check("the edge is idle again once the runs are done",
               after.get("running") == 0, detail=f"running={after.get('running')!r}")


def test_script_run_history_still_reaches_the_collection_owner():
    section("Script-run history recorded on any node reaches the owner of script_runs")

    hist_db = "cluster_history_db"
    port = nodes[0].client_port
    fresh_database(port, hist_db, seed_value=None)
    check_status("SAVE_PROCEDURE", op_with_retry(lambda: op(port, {
        "type": "SAVE_PROCEDURE", "databaseName": hist_db, "name": "histproc", "script": "return 1;"})), "OK")

    calls = []

    def _batch(_round):
        conn = authed(port)
        try:
            batch = [conn.send({"type": "CALL_PROCEDURE", "databaseName": hist_db, "procedureName": "histproc"})
                     for _ in range(20)]
        finally:
            conn.close()
        calls.extend(batch)
        return batch

    def history_rows():
        reply = op(port, {"type": "AGGREGATE", "databaseName": hist_db, "collectionName": "script_runs",
                          "aggregationSteps": [filter_step("name", "EQUALS", "histproc")]})
        return reply.get("results") or []

    def runners():
        return {row.get("node") for row in history_rows()}

    deadline = time.time() + 40.0
    while time.time() < deadline and len(runners()) < 2:
        until_forwarded(port, _batch)
    check("every call answered OK", calls and all(c.get("status") == "OK" for c in calls),
          f"statuses={sorted({c.get('status') for c in calls})}")
    check("every call left a history row", wait_until(lambda: len(history_rows()) >= len(calls), timeout_s=60.0),
          f"rows={len(history_rows())} calls={len(calls)}")
    lost_marker = f"Could not record the run of CALL_PROCEDURE 'histproc' in {hist_db}"
    lost = {node.index: bu.read_log(node.log_path).count(lost_marker) for node in nodes}
    check("no node dropped a history row", not any(lost.values()), f"dropped per node={lost}")
    check("rows were recorded by more than one node, so at least one came from a non-owner",
          len(runners()) >= 2, f"runners={runners()}")
    op_with_retry(lambda: drop_db(port, hist_db))


def test_forwarded_script_reads_and_writes():
    section("Script node selection — a forwarded script still reads and writes correctly")

    check_status("CREATE_COLLECTION for the script data", create_coll(nodes[0].client_port, DB, "scripted"), "OK")
    script = ('import db from "db";\n'
              'import args from "args";\n'
              'db.save(db.name, "scripted", { _id: args.id, v: 7 });\n'
              'return db.findById(db.name, "scripted", args.id).v;')

    written = []

    def _batch(round_no):
        written.clear()
        conn = authed(nodes[0].client_port)
        try:
            out = []
            for i in range(8):
                run_id = f"scripted-{round_no}-{i}"
                out.append(conn.send({"type": "RUN_SCRIPT", "databaseName": DB,
                                                 "script": script, "args": {"id": run_id}}))
                written.append(run_id)
            return out
        finally:
            conn.close()

    results, forwarded = until_forwarded(nodes[0].client_port, _batch)

    for doc_id, r in zip(written, results):
        check_status(f"a placed script writes and reads back {doc_id}", r, "OK")
        check(f"{doc_id} read back its own write", r.get("result") == 7,
                   detail=f"result={r.get('result')!r}")
    check("at least one of those runs was forwarded", forwarded > 0)
    for doc_id in written:
        check(f"{doc_id} is visible from every node",
                   all_nodes_see(DB, "scripted", doc_id, 7, ports=all_ports(), timeout_s=15.0))


def test_forwarded_script_relays_its_stack():
    section("Script node selection — a forwarded failure relays its stack")

    script = ("function inner() {\n"
              "  throw new Error('forwarded boom');\n"
              "}\n"
              "function outer() {\n"
              "  inner();\n"
              "}\n"
              "outer();")

    def _batch(_round):
        conn = authed(nodes[0].client_port)
        try:
            return [conn.send({"type": "RUN_SCRIPT", "databaseName": DB, "script": script})
                    for _ in range(8)]
        finally:
            conn.close()

    results, forwarded = until_forwarded(nodes[0].client_port, _batch)
    check("at least one failing run was forwarded", forwarded > 0)
    for response in results:
        stack = response.get("stack")
        check("the failure carries its frames", isinstance(stack, list) and len(stack) >= 3,
                   detail=f"stack={stack!r}")
        check("the innermost frame survived the relay",
                   bool(stack) and stack[0].startswith("inner ("), detail=f"stack={stack!r}")


def test_forwarded_script_preserves_the_acting_user():
    section("Script node selection — the acting user survives the forward")

    granted = {"type": "CREATE_USER", "username": "cluster_script_user", "password": "cluster_script1234",
               "admin": False, "globalPermissions": [], "databasePermissions": {DB: "READ_WRITE"},
               "collectionPermissions": {}, "scriptPermissions": {DB: True}}
    denied = {"type": "CREATE_USER", "username": "cluster_script_denied", "password": "cluster_script1234",
              "admin": False, "globalPermissions": [], "databasePermissions": {DB: "READ_WRITE"},
              "collectionPermissions": {}, "scriptPermissions": {}}
    check_status("CREATE_USER with a per-database RUN grant", op(nodes[0].client_port, granted), "OK")
    check_status("CREATE_USER without a grant", op(nodes[0].client_port, denied), "OK")

    def _granted_can_run():
        return as_user(nodes[0].client_port, "cluster_script_user", "cluster_script1234",
                       {"type": "RUN_SCRIPT", "databaseName": DB, "script": "return 1;"}).get("status") == "OK"

    check("the granted user's script runs (the record replicated)",
               wait_until(_granted_can_run, timeout_s=15.0))

    script = ('import db from "db";\n'
              'db.save(db.name, "scripted", { _id: "as-user", v: 7 });\n'
              'return db.findById(db.name, "scripted", "as-user").v;')

    def _batch(_round):
        user_conn = Conn(port=nodes[0].client_port)
        try:
            user_conn.send({"type": "AUTHENTICATE", "username": "cluster_script_user",
                            "password": "cluster_script1234"})
            return [user_conn.send({"type": "RUN_SCRIPT", "databaseName": DB, "script": script})
                    for _ in range(12)]
        finally:
            user_conn.close()

    results, forwarded = until_forwarded(nodes[0].client_port, _batch)

    check("a non-admin's forwarded script runs with its own authority",
               all(r.get("status") == "OK" and r.get("result") == 7 for r in results),
               detail=f"responses={[(r.get('status'), r.get('result'), r.get('message')) for r in results][:3]}")
    check("at least one of those runs was forwarded", forwarded > 0)

    before = script_stats(nodes[0].client_port)
    c = Conn(port=nodes[0].client_port)
    try:
        c.send({"type": "AUTHENTICATE", "username": "cluster_script_denied",
                        "password": "cluster_script1234"})
        for _ in range(5):
            check_code("a caller without the grant is refused",
                       c.send({"type": "RUN_SCRIPT", "databaseName": DB, "script": "return 1;"}),
                       "FORBIDDEN", "403-1")
    finally:
        c.close()
    after = script_stats(nodes[0].client_port)
    check("a refused call is never forwarded",
               after.get("forwarded", 0) == before.get("forwarded", 0),
               detail=f"forwarded {before.get('forwarded')} -> {after.get('forwarded')}")

    op(nodes[0].client_port, {"type": "DELETE_USER", "username": "cluster_script_user"})
    op(nodes[0].client_port, {"type": "DELETE_USER", "username": "cluster_script_denied"})


def test_long_forwarded_script_is_not_cut_off():
    section("Script node selection — a long forwarded script is not cut off by the ack timeout")

    driver = nodes[1].client_port
    script = 'export default new Promise(r => setTimeout(() => r("slept"), 6000));'

    def _batch(_round):
        out = burst(driver, script, 9)
        check("every long script returned its result",
                   all(r.get("status") == "OK" and r.get("result") == "slept" for r in out),
                   detail=f"responses={[(r.get('status'), r.get('result'), r.get('message')) for r in out][:3]}")
        return out

    check("forwarding is live before the long runs",
               wait_until_forwarding_is_live(driver, timeout_s=60.0))

    _, forwarded = until_forwarded(driver, _batch, timeout_s=45.0)
    check("a long script outliving the ack timeout was forwarded and still answered",
               forwarded > 0, detail="no concurrent long run was forwarded")


def test_script_control_is_cluster_wide():
    section("Script control — a run is listed and cancelled from a node that is not running it")

    runner_node = nodes[2]
    observer_ports = [nodes[0].client_port, nodes[1].client_port]
    check("no script is running anywhere before the test",
               wait_until(lambda: all(not list_scripts(p) for p in all_ports()), timeout_s=30.0),
               detail=f"listing={list_scripts(nodes[0].client_port)!r}")
    background = BackgroundOp(runner_node.client_port, {
        "type": "RUN_SCRIPT", "databaseName": DB,
        "script": 'export default new Promise(r => setTimeout(() => r(1), 15000));'})

    expected_node = f"{HOST}:{runner_node.cluster_port}"

    def _row_on_the_runner(client_port):
        for candidate in list_scripts(client_port):
            if candidate.get("node") == expected_node and candidate.get("kind") == "RUN_SCRIPT":
                return candidate
        return None

    row = wait_until(lambda: _row_on_the_runner(nodes[0].client_port) is not None, timeout_s=15.0) \
        and _row_on_the_runner(nodes[0].client_port)
    check("node-0 sees a run it is not executing", bool(row),
               detail=f"node-0 listing={list_scripts(nodes[0].client_port)!r}")
    if not row:
        background.join(30.0)
        return
    check("the row names the node actually executing the run",
               row.get("node") == expected_node,
               detail=f"expected node={expected_node!r} got={row.get('node')!r}")
    check("and it is not the node answering the listing",
               row.get("node") != f"{HOST}:{nodes[0].cluster_port}")
    check("the row carries the run's kind and database",
               row.get("kind") == "RUN_SCRIPT" and row.get("database") == DB,
               detail=f"row={row!r}")
    for port in observer_ports:
        rows = list_scripts(port)
        check(f"the run is visible from the node on port {port}",
                   any(r.get("runId") == row["runId"] and r.get("node") == expected_node for r in rows),
                   detail=f"got {rows!r}")

    response = cancel_script(nodes[0].client_port, row["runId"])
    check_status("CANCEL_SCRIPT from another node succeeds", response, "OK")
    check_field("it reports the run as cancelled", response, "cancelled", True)

    check("the cancelled run returned to its caller", background.join(30.0))
    check_code("the caller on the executing node receives 408-2",
               background.response or {}, "ERROR", "408-2")
    check_field("the caller's runId is the one the other node listed",
                background.response or {}, "runId", row["runId"])

    for port in all_ports():
        check(f"the cancelled run leaves the listing on port {port}",
                   wait_until(lambda p=port: not any(r.get("runId") == row["runId"]
                                                     for r in list_scripts(p)), timeout_s=30.0),
                   detail=f"still listed: {list_scripts(port)!r}")

    unknown = cancel_script(nodes[1].client_port, "00000000-0000-0000-0000-000000000000")
    check_status("cancelling an unknown run anywhere is still OK", unknown, "OK")
    check_field("and reports cancelled:false", unknown, "cancelled", False)

    check("every node reports itself idle again",
               wait_until(lambda: all(script_stats(p).get("running") == 0 for p in all_ports()),
                          timeout_s=30.0))


def test_script_placement_falls_back_when_the_target_dies():
    section("Script node selection — an unreachable target falls back to local execution")

    victim = nodes[2]
    driver = nodes[1].client_port

    check("forwarding is live before the crash",
               wait_until_forwarding_is_live(driver, timeout_s=60.0))

    def _victim_idle():
        return script_stats(victim.client_port).get("running", 0) == 0

    before = script_stats(driver).get("forwardFallbacks", 0)
    check("the victim is idle before the crash", wait_until(_victim_idle, timeout_s=15.0))
    time.sleep(2 * GOSSIP_INTERVAL_S)
    check("the victim stayed idle for a full gossip round", _victim_idle())

    print(f"  Killing node-{victim.index} (hard crash) ...")
    victim.kill()

    stop = threading.Event()
    statuses = set()

    def hammer():
        conn = authed(driver)
        try:
            while not stop.is_set():
                statuses.add(run_script(None, "return 1 + 1;", conn=conn).get("status"))
                stop.wait(0.05)
        finally:
            conn.close()

    workers = [threading.Thread(target=hammer, daemon=True) for _ in range(HAMMER_CONNECTIONS)]
    for worker in workers:
        worker.start()

    fell_back = wait_until(
        lambda: script_stats(driver).get("forwardFallbacks", 0) > before,
        timeout_s=HAMMER_SECONDS)
    stop.set()
    for worker in workers:
        worker.join(60.0)
    if not fell_back:
        fell_back = wait_until(
            lambda: script_stats(driver).get("forwardFallbacks", 0) > before,
            timeout_s=30.0)
    fallbacks = script_stats(driver).get("forwardFallbacks", 0) - before

    check("scripts keep succeeding while a placement target is down", statuses == {"OK"},
               detail=f"statuses={sorted(statuses)}")
    check("a failed forward is counted and the run stays local", fell_back,
               detail=f"forwardFallbacks delta={fallbacks}")

    def _all_ok():
        return all(run_script(nodes[0].client_port, "return 1 + 1;").get("status") == "OK"
                   for _ in range(5))

    check("scripts still succeed once the dead node leaves the view",
               wait_until(_all_ok, timeout_s=120.0, interval_s=1.0))

    print(f"  Restarting node-{victim.index} ...")
    victim.start()
    wait_for_cluster()


def test_script_routing_disabled_stays_local():
    section("Script node selection — routing off keeps every run on the receiving node")

    stats = script_stats(nodes[2].client_port)
    check("node-2 reports script routing disabled", stats.get("routingEnabled") is False,
               detail=f"scripts={stats!r}")

    before = script_stats(nodes[2].client_port)
    conn = authed(nodes[2].client_port)
    try:
        results = [run_script(None, "return 1 + 1;", conn=conn) for _ in range(20)]
    finally:
        conn.close()
    after = script_stats(nodes[2].client_port)

    check("every run on the routing-disabled node returned OK",
               all(r.get("status") == "OK" and r.get("result") == 2 for r in results),
               detail=f"statuses={sorted({r.get('status') for r in results})}")
    check("nothing was forwarded", after.get("forwarded", 0) == before.get("forwarded", 0),
               detail=f"forwarded {before.get('forwarded')} -> {after.get('forwarded')}")
    check("nothing fell back either (no forward was attempted)",
               after.get("forwardFallbacks", 0) == before.get("forwardFallbacks", 0))


def _locality_db_ready():
    check_status("CREATE_DATABASE for the locality database", create_db(nodes[0].client_port, LOCALITY_DB), "OK")
    check_status("CREATE_COLLECTION for the locality database",
          create_coll(nodes[0].client_port, LOCALITY_DB, "only"), "OK")
    check_status("SAVE the locality document",
          save(nodes[0].client_port, LOCALITY_DB, "only", {"_id": "l1", "v": 9}), "OK")


def test_script_placement_prefers_the_collection_owner():
    section("Script node selection — placement prefers a node owning the scoped database")

    _locality_db_ready()
    stats = script_stats(nodes[0].client_port)
    check("node-0 reports the locality weight it was configured with",
               stats.get("localityWeight") == 100, detail=f"scripts={stats!r}")

    before = script_stats(nodes[0].client_port)
    script = ('import db from "db";\n'
              'import args from "args";\n'
              'return db.findById(db.name, "only", "l1").v;')

    def _batch(_round):
        conn = authed(nodes[0].client_port)
        try:
            return [run_script(None, script, db=LOCALITY_DB, conn=conn) for _ in range(40)]
        finally:
            conn.close()

    results, preferred = until_stat_grows(nodes[0].client_port, "localityPreferred", _batch)
    after = script_stats(nodes[0].client_port)

    check("every placed run returned OK", all(r.get("status") == "OK" for r in results),
               detail=f"statuses={sorted({r.get('status') for r in results})}")
    check("every placed run returned the script's value", all(r.get("result") == 9 for r in results),
               detail=f"results={sorted({repr(r.get('result')) for r in results})}")
    check("the ownership share changed placement decisions", preferred > 0,
               detail=f"localityPreferred delta={preferred}")
    fallbacks = after.get("forwardFallbacks", 0) - before.get("forwardFallbacks", 0)
    check("no forward failed while every node was up", fallbacks == 0,
               detail=f"forwardFallbacks delta={fallbacks}")


def test_script_placement_locality_weight_zero_is_load_only():
    section("Script node selection — scriptLocalityWeight=0 places purely by load")

    stats = script_stats(nodes[1].client_port)
    check("node-1 reports locality weighting turned off",
               stats.get("localityWeight") == 0, detail=f"scripts={stats!r}")

    before = script_stats(nodes[1].client_port)
    script = ('import db from "db";\n'
              'return db.findById(db.name, "only", "l1").v;')

    def _batch(_round):
        conn = authed(nodes[1].client_port)
        try:
            return [run_script(None, script, db=LOCALITY_DB, conn=conn) for _ in range(40)]
        finally:
            conn.close()

    results, forwarded = until_forwarded(nodes[1].client_port, _batch)
    after = script_stats(nodes[1].client_port)

    check("every placed run returned OK", all(r.get("status") == "OK" for r in results),
               detail=f"statuses={sorted({r.get('status') for r in results})}")
    check("every placed run returned the script's value", all(r.get("result") == 9 for r in results),
               detail=f"results={sorted({repr(r.get('result')) for r in results})}")
    check("routing is still fully active at weight 0", forwarded > 0,
               detail=f"forwarded delta={forwarded}")
    preferred = after.get("localityPreferred", 0) - before.get("localityPreferred", 0)
    check("locality changed nothing at weight 0", preferred == 0,
               detail=f"localityPreferred delta={preferred}")


def test_node_failure_quorum_maintained():
    section("Node failure — quorum (2 of 3) maintained, writes + reads keep working")

    victim = nodes[2]
    print(f"  Killing node-{victim.index} (hard crash) ...")
    victim.kill()

    def _write_read_ok():
        if create_coll(nodes[0].client_port, DB, "after_fail").get("status") != "OK":
            return False
        r = save(nodes[0].client_port, DB, "after_fail", {"_id": "f1", "v": 111})
        if r.get("status") != "OK":
            return False
        return all_nodes_see(DB, "after_fail", "f1", 111, ports=all_ports(), timeout_s=1.0)

    ok = wait_until(_write_read_ok, timeout_s=30.0, interval_s=1.0)
    check("with one node down the surviving majority still commits + serves reads", ok)

    check("pre-failure data still readable from the survivors",
               all_nodes_see(DB, "routed", "w0", 0, ports=all_ports(), timeout_s=15.0))


def test_node_rejoin():
    section("Node rejoin — restarted node reconverges and the cluster is consistent")

    rejoiner = nodes[2]
    print(f"  Restarting node-{rejoiner.index} ...")
    rejoiner.start()

    def _rejoined():
        v = int(time.time()) % 100000
        r = save(nodes[0].client_port, DB, "routed", {"_id": "rejoin", "v": v})
        if r.get("status") != "OK":
            return False
        return all_nodes_see(DB, "routed", "rejoin", v, ports=all_ports(), timeout_s=2.0)

    check("rejoined node serves consistent reads and the cluster is healthy",
               wait_until(_rejoined, timeout_s=45.0, interval_s=1.0))


DOWN_COLL = "changed_while_down"
DOWN_PROC = "deleted_while_down"
DOWN_USER = "changed_while_down_user"
STALE_GRANT_COLL = "regranted_while_down"
STALE_GRANTEE = "stale_grantee"
STALE_GRANT_KEY = f"{DB}|{STALE_GRANT_COLL}"


def _collection_grants_on(node, username):
    users = op(node.client_port, {"type": "LIST_USERS"}).get("users") or []
    entry = next((user for user in users if user.get("_id") == username), None)
    return None if entry is None else (entry.get("collectionPermissions") or {})


def _admin(payload: dict) -> dict:
    return op_with_retry(lambda: op(nodes[0].client_port, payload))


def arrange_admin_changes_for_a_downed_node():
    section("Admin records that will change while node-2 is down")

    check_status("CREATE_COLLECTION", _admin({"type": "CREATE_COLLECTION", "databaseName": DB,
                                                "collectionName": DOWN_COLL}), "OK")
    check_status("CREATE_INDEX", _admin({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": DOWN_COLL,
                                          "fieldName": "f"}), "OK")
    check_status("SAVE_PROCEDURE", _admin({"type": "SAVE_PROCEDURE", "databaseName": DB, "name": DOWN_PROC,
                                            "script": "return 1;"}), "OK")
    _admin({"type": "DELETE_USER", "username": DOWN_USER})
    check_status("CREATE_USER", _admin({"type": "CREATE_USER", "username": DOWN_USER,
                                         "password": "before_the_outage1", "admin": False, "globalPermissions": [],
                                         "databasePermissions": {DB: "READ"}, "collectionPermissions": {}}), "OK")
    check_status("CREATE_COLLECTION for the collection-only grant",
                 _admin({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": STALE_GRANT_COLL}), "OK")
    _admin({"type": "DELETE_USER", "username": STALE_GRANTEE})
    check_status("CREATE_USER holding only a grant on that collection",
                 _admin({"type": "CREATE_USER", "username": STALE_GRANTEE, "password": "stale_grantee1",
                         "admin": False, "globalPermissions": [], "databasePermissions": {},
                         "collectionPermissions": {STALE_GRANT_KEY: "READ"}}), "OK")
    check("node-2 holds every record before the outage",
          wait_until(lambda: os.path.isfile(procedure_file(nodes[2], DOWN_PROC))
                     and _authenticates(nodes[2].client_port, DOWN_USER, "before_the_outage1")
                     and STALE_GRANT_KEY in (_collection_grants_on(nodes[2], STALE_GRANTEE) or {}), timeout_s=20.0))


def make_admin_changes_while_a_node_is_down():
    section("Admin changes made while node-2 is down")

    check("node-2 is down", not nodes[2].alive)
    check_status("SAVE_SCHEMA", _admin({"type": "SAVE_SCHEMA", "databaseName": DB, "collectionName": DOWN_COLL,
                                         "schema": {"type": "object", "required": ["f"]}}), "OK")
    check_status("DELETE_PROCEDURE", _admin({"type": "DELETE_PROCEDURE", "databaseName": DB,
                                              "name": DOWN_PROC}), "OK")
    check_status("SET_PASSWORD", _admin({"type": "SET_PASSWORD", "username": DOWN_USER,
                                          "newPassword": "after_the_outage1"}), "OK")
    check_status("CHANGE_PERMISSIONS", _admin({"type": "CHANGE_PERMISSIONS", "username": DOWN_USER,
                                                "admin": False, "globalPermissions": [],
                                                "databasePermissions": {DB: "READ_WRITE"},
                                                "collectionPermissions": {}}), "OK")
    check_status("DROP_INDEX", _admin({"type": "DROP_INDEX", "databaseName": DB, "collectionName": DOWN_COLL,
                                        "fieldName": "f"}), "OK")
    check_status("DROP_COLLECTION of the granted collection",
                 _admin({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": STALE_GRANT_COLL}), "OK")
    check_status("CREATE_COLLECTION re-creates it under the same name",
                 _admin({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": STALE_GRANT_COLL}), "OK")


def _schema_file(node) -> str:
    return os.path.join(node.work_dir, "db", DB, DOWN_COLL, f"{DOWN_COLL}-schema.json")


def test_a_schema_saved_while_a_node_is_down_reaches_it():
    section("A schema saved while a node was down reaches it once it rejoins")

    def _has_schema():
        if not os.path.isfile(_schema_file(nodes[2])):
            return False
        with open(_schema_file(nodes[2]), encoding="utf-8") as fp:
            content = fp.read()
        return '"required"' in content

    check("node-2 holds the schema saved during its outage", wait_until(_has_schema, timeout_s=60.0))


def test_a_procedure_deleted_while_a_node_is_down_stays_deleted():
    section("A procedure deleted while a node was down stays deleted once it rejoins")

    check("node-2 deleted the procedure removed during its outage",
          wait_until(lambda: not os.path.isfile(procedure_file(nodes[2], DOWN_PROC)), timeout_s=60.0))
    check("no node brought it back after several admin sweeps",
          holds_throughout(lambda: all(not os.path.isfile(procedure_file(node, DOWN_PROC))
                                       for node in nodes if node.alive), seconds=3 * ANTI_ENTROPY_INTERVAL_S))


def test_a_password_changed_while_a_node_is_down_reaches_it():
    section("A password changed while a node was down reaches it once it rejoins")

    check("node-2 accepts the new password",
          wait_until(lambda: _authenticates(nodes[2].client_port, DOWN_USER, "after_the_outage1"), timeout_s=60.0))
    check("node-2 refuses the old password",
          not _authenticates(nodes[2].client_port, DOWN_USER, "before_the_outage1"))


def test_permission_changes_and_dropped_indexes_replicate():
    section("Permission changes and a dropped index made while a node was down reach it")

    def _writes_through_node_2():
        return as_user(nodes[2].client_port, DOWN_USER, "after_the_outage1",
                       {"type": "SAVE", "databaseName": DB, "collectionName": DOWN_COLL,
                        "object": {"_id": "w1", "f": 1}}).get("status") == "OK"

    check("node-2 lets the user write with the permission granted during its outage",
          wait_until(_writes_through_node_2, timeout_s=60.0))

    def _index_dropped_on_node_2():
        indexes = indexes_of(nodes[2].client_port, DB, DOWN_COLL)
        return indexes is not None and "f" not in indexes

    check("node-2 dropped the index removed during its outage", wait_until(_index_dropped_on_node_2, timeout_s=60.0))
    _admin({"type": "DELETE_USER", "username": DOWN_USER})


def test_a_grant_on_a_collection_dropped_while_a_node_is_down_is_pruned_there():
    section("A grant on a collection dropped and re-created while a node was down is pruned there too")

    def _pruned_on(node):
        grants = _collection_grants_on(node, STALE_GRANTEE)
        return grants is not None and STALE_GRANT_KEY not in grants

    check("the coordinator pruned the grant", _pruned_on(nodes[0]))
    check("node-2 pruned the grant it held before its outage", wait_until(lambda: _pruned_on(nodes[2]),
                                                                           timeout_s=60.0))
    check("no node brings it back over several admin sweeps",
          holds_throughout(lambda: all(_pruned_on(node) for node in nodes if node.alive),
                           seconds=2 * ANTI_ENTROPY_INTERVAL_S))
    _admin({"type": "DELETE_USER", "username": STALE_GRANTEE})
    _admin({"type": "DROP_COLLECTION", "databaseName": DB, "collectionName": STALE_GRANT_COLL})


def test_a_restarted_participant_does_not_commit_half_a_transaction():
    section("A participant that restarted mid-transaction refuses to continue the slice it lost")

    owners = collections_by_owner(nodes[0].client_port, DB, "slicelost")
    participant = nodes[1]
    if not check(f"found a collection owned by node-{participant.index}", participant.index in owners,
                 f"owners={owners}"):
        return
    coll = owners[participant.index][0]
    if not check("the ring is healthy before the transaction starts", wait_until_cluster_is_healthy()):
        return

    conn = authed(nodes[0].client_port)
    try:
        check_status("START_TRANSACTION", conn.send({"type": "START_TRANSACTION"}), "OK")
        check_status(f"buffered SAVE into {coll} on node-{participant.index}",
                     conn.send({"type": "SAVE", "databaseName": DB, "collectionName": coll,
                                "object": {"_id": "lost_before", "v": 1}}), "OK")

        print(f"  Restarting node-{participant.index} mid-transaction ...")
        participant.stop()
        participant.start()
        if not check("the ring is healthy after the restart", wait_until_cluster_is_healthy()):
            return

        after = conn.send({"type": "SAVE", "databaseName": DB, "collectionName": coll,
                           "object": {"_id": "lost_after", "v": 1}})
        check("the next write to the restarted participant is refused with 409-13",
              after.get("errorCode") == "409-13", f"response={after}")
        commit = conn.send({"type": "COMMIT_TRANSACTION"})
        check("COMMIT does not report half a transaction as committed", commit.get("status") != "OK",
              f"response={commit}")
        conn.send({"type": "ROLLBACK_TRANSACTION"})
    finally:
        conn.close()

    for doc_id in ("lost_before", "lost_after"):
        check(f"{doc_id} is on no node",
              all(find_by_id(port, DB, coll, doc_id).get("status") == "NOT_FOUND" for port in all_ports()))


def test_restart_does_not_regress_write_versions():
    section("Write versions do not regress across a restart")

    coll = "version_regression"

    def _seeded():
        if create_coll(nodes[0].client_port, DB, coll).get("status") != "OK":
            return False
        if save(nodes[0].client_port, DB, coll, {"_id": "r1", "v": 1}).get("status") != "OK":
            return False
        return all_nodes_see(DB, coll, "r1", 1, ports=all_ports(), timeout_s=2.0)

    check("seed is committed and visible everywhere before the restart",
          wait_until(_seeded, timeout_s=30.0, interval_s=1.0))

    restarted = nodes[1]
    print(f"  Restarting node-{restarted.index} ...")
    restarted.stop()
    restarted.start()

    def _back():
        return save(nodes[0].client_port, DB, coll, {"_id": "r1", "v": 2}).get("status") == "OK"

    check("the cluster accepts a write after the restart", wait_until(_back, timeout_s=45.0, interval_s=1.0))
    check("the newer write survives anti-entropy rather than being pulled back",
          all_nodes_see(DB, coll, "r1", 2, ports=all_ports(), timeout_s=30.0))

    burst_ids = [f"burst{i}" for i in range(12)]
    for index, doc_id in enumerate(burst_ids):
        check_status(f"burst write {doc_id}", save(nodes[0].client_port, DB, coll, {"_id": doc_id, "v": index}), "OK")
    for index, doc_id in enumerate(burst_ids):
        check(f"every node agrees on {doc_id} after a same-millisecond burst",
              all_nodes_see(DB, coll, doc_id, index, ports=all_ports(), timeout_s=30.0))
    diverged = stays_converged(DB, coll, {doc_id: index for index, doc_id in enumerate(burst_ids)}, seconds=5.0)
    for doc_id in burst_ids:
        check(f"{doc_id} still agrees after an anti-entropy sweep", doc_id not in diverged)

    check_a_mid_commit_restart_keeps_the_newer_values()


MID_COMMIT_FILLER_OPS = 2000
MID_COMMIT_FILLER_SIZE = 2


def pad_the_commit(conn, coll, prefix):
    refused = []
    for filler in range(MID_COMMIT_FILLER_OPS):
        response = conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll,
                              "objects": [{"_id": f"{prefix}{filler}_{n}", "v": filler}
                                          for n in range(MID_COMMIT_FILLER_SIZE)]})
        if response.get("status") != "OK":
            refused.append(response)
    check("the commit's padding was buffered", not refused,
          f"{len(refused)} of {MID_COMMIT_FILLER_OPS} filler ops were refused, first={refused[0] if refused else None}")


def _commit_and_kill_mid_apply(victim, coll, ids):
    conn = authed(nodes[0].client_port)
    try:
        tx_id = conn.send({"type": "START_TRANSACTION"}).get("transactionId")
        if tx_id is None:
            return None
        check_status("the ids the replay must skip were buffered", conn.send({
            "type": "BULK_SAVE", "databaseName": DB, "collectionName": coll,
            "objects": [{"_id": doc_id, "v": 2} for doc_id in ids]}), "OK")
        pad_the_commit(conn, coll, "filler")
        pending, caught = commit_in_background(conn, victim, tx_id, "localcommit", timeout_s=60.0, interval_s=0.02)
        if not caught:
            pending.join(60.0)
            return None
        time.sleep(0.1)
        victim.kill()
        pending.join(30.0)
        return tx_id
    finally:
        conn.close()


def check_a_mid_commit_restart_keeps_the_newer_values():
    section("A commit interrupted mid-apply does not replay over newer writes")

    owners = collections_by_owner(nodes[0].client_port, DB, "midcommit")
    victim = next((nodes[i] for i in (1, 2) if i in owners), None)
    if not check("found a collection owned by a node that can rejoin", victim is not None, f"owners={owners}"):
        return
    coll = owners[victim.index][0]
    ids = [f"mc{i}" for i in range(8)]

    print(f"  Killing node-{victim.index} inside its commit of {coll} ...")
    tx_id = _commit_and_kill_mid_apply(victim, coll, ids)
    if not check("the node was killed while its commit was still applying", tx_id is not None,
                 "no local-commit marker was ever observed, so the commit outran the harness"):
        return
    check("it kept the local-commit marker for the restart to finish",
          holds_tx_record(victim, tx_id, "localcommit"), f"transaction={tx_id}")
    held = stored_documents(victim, DB, coll)
    check("and it had already applied the ids the replay must later skip",
          all(held.get(doc_id, {}).get("v") == 2 for doc_id in ids),
          f"stored={[held.get(doc_id) for doc_id in ids]}")

    def _rewritten():
        for doc_id in ids:
            if save(nodes[0].client_port, DB, coll, {"_id": doc_id, "v": 3}).get("status") != "OK":
                return False
        return all(all_nodes_see(DB, coll, doc_id, 3, ports=all_ports(), timeout_s=2.0) for doc_id in ids)

    check("the new owner accepts newer values while the node is down",
          wait_until(_rewritten, timeout_s=60.0, interval_s=1.0))

    print(f"  Restarting node-{victim.index} ...")
    victim.start()

    for doc_id in ids:
        check(f"{doc_id} keeps the value written while the node was down",
              all_nodes_see(DB, coll, doc_id, 3, ports=all_ports(), timeout_s=45.0))
    diverged = stays_converged(DB, coll, {doc_id: 3 for doc_id in ids}, seconds=12.0)
    for doc_id in ids:
        check(f"{doc_id} still does after several anti-entropy sweeps", doc_id not in diverged)


def test_a_participant_killed_mid_commit_finishes_without_its_coordinator():
    section("A participant killed while applying a decided 2PC slice finishes it on restart")

    owners = owned_by_every_node("midtwopc")
    if owners is None:
        return
    edge, victim = nodes[2], nodes[1]
    slow, quick = owners[victim.index][0], owners[edge.index][0]
    if not check("the ring is healthy before the transaction starts", wait_until_cluster_is_healthy()):
        return

    conn = authed(edge.client_port)
    try:
        tx_id = conn.send({"type": "START_TRANSACTION"}).get("transactionId")
        check_status("a write to the coordinator's own collection", conn.send({
            "type": "SAVE", "databaseName": DB, "collectionName": quick, "object": {"_id": "mt_edge", "v": 1}}), "OK")
        check_status("a write the participant applies first", conn.send({
            "type": "SAVE", "databaseName": DB, "collectionName": slow, "object": {"_id": "mt_first", "v": 1}}), "OK")
        pad_the_commit(conn, slow, "mtpad")
        pending, caught = commit_in_background(conn, victim, tx_id, "localcommit", timeout_s=120.0, interval_s=0.02)
        if caught:
            print(f"  Killing participant node-{victim.index} while it applies the decided slice ...")
            victim.kill()
        pending.join(180.0)
    finally:
        conn.close()
    if not check("the participant was killed while applying the decided slice", caught,
                 "no decided marker was ever observed on the participant, so the commit outran the harness"):
        return
    check("it kept its decided marker", holds_tx_record(victim, tx_id, "localcommit"), f"transaction={tx_id}")

    print(f"  Killing coordinator node-{edge.index} so nothing can re-drive the commit ...")
    edge.kill()
    print(f"  Restarting node-{victim.index} ...")
    victim.start()

    def _finished():
        return (stored_documents(victim, DB, slow).get("mt_first", {}).get("v") == 1
                and not holds_tx_record(victim, tx_id, "localcommit"))

    check("the restarted participant finished the slice on its own", wait_until(_finished, timeout_s=90.0),
          f"transaction={tx_id}")
    held = stored_documents(victim, DB, slow)
    missing = [f"mtpad{f}_{n}" for f in range(MID_COMMIT_FILLER_OPS) for n in range(MID_COMMIT_FILLER_SIZE)
               if f"mtpad{f}_{n}" not in held]
    check("including every op it had not applied before the kill", not missing, f"{len(missing)} missing")
    check("and lists nothing in doubt", in_doubt_row(victim.client_port, tx_id) is None, f"transaction={tx_id}")

    print(f"  Restarting node-{edge.index} ...")
    edge.start()
    check("every node sees the participant's slice", all_nodes_see(DB, slow, "mt_first", 1, ports=all_ports(),
                                                                   timeout_s=60.0))
    check("and the coordinator's own slice", all_nodes_see(DB, quick, "mt_edge", 1, ports=all_ports(),
                                                         timeout_s=60.0))


def tombstone_ids(node, db, coll):
    path = os.path.join(node.work_dir, "db", db, coll, f"{coll}-tombstones.idx")
    if not os.path.isfile(path):
        return set()
    ids = set()
    with open(path, encoding="utf-8") as fp:
        for line in fp:
            line = line.strip()
            if line and "\x1f" in line:
                ids.add(bu.unescape_index_token(line.split("\x1f", 1)[0]))
    return ids


def test_a_fresh_tombstone_survives_anti_entropy_sweeps():
    section("Tombstones inside the retention window are not collected")

    coll = "tombstone_retention"

    def _written():
        if create_coll(nodes[0].client_port, DB, coll).get("status") != "OK":
            return False
        if save(nodes[0].client_port, DB, coll, {"_id": "gone", "v": 1}).get("status") != "OK":
            return False
        return all_nodes_see(DB, coll, "gone", 1, ports=all_ports(), timeout_s=2.0)

    check("the document is committed on every node", wait_until(_written, timeout_s=30.0, interval_s=1.0))

    check_status("delete it", delete(nodes[0].client_port, DB, coll, "gone"), "OK")

    owner = next((n for n in nodes if "gone" in tombstone_ids(n, DB, coll)), None)
    check("the delete wrote a tombstone", owner is not None,
          "no node recorded a tombstone for the deleted document")

    time.sleep(10)

    if owner is not None:
        check("the tombstone is still there after several sweeps", "gone" in tombstone_ids(owner, DB, coll),
              "a tombstone inside tombstoneRetentionMs was garbage collected")
    for node in nodes:
        r = find_by_id(node.client_port, DB, coll, "gone")
        check(f"node-{node.index} still reports the document deleted", r.get("status") == "NOT_FOUND",
              f"got {r}")


def test_delete_of_an_unreplicated_id_does_not_destroy_it():
    section("A DELETE for an id this node does not hold writes no tombstone")

    coll = "spurious_tombstone"
    candidates = [f"{coll}_w{i}" for i in range(6)]

    def _created():
        for name in [coll] + candidates:
            if create_coll(nodes[0].client_port, DB, name).get("status") != "OK":
                return False
        return True

    check("the collections exist on every node", wait_until(_created, timeout_s=30.0, interval_s=1.0))

    for node in nodes:
        r = delete(node.client_port, DB, coll, "never-existed")
        check(f"node-{node.index} answers NOT_FOUND for an id nothing holds", r.get("status") == "NOT_FOUND",
              f"got {r}")

    holders = [n.index for n in nodes if "never-existed" in tombstone_ids(n, DB, coll)]
    check("a refused DELETE wrote no tombstone anywhere", not holders,
          f"node(s) {holders} buried an id they never held")

    absent = nodes[2]
    print(f"  Stopping node-{absent.index} ...")
    absent.stop()
    written = next((name for name in candidates
                    if save(nodes[0].client_port, DB, name, {"_id": "while-down", "v": 1}).get("status") == "OK"),
                   None)
    check("a write commits while one node is down", written is not None,
          "no candidate collection had a live owner")
    print(f"  Restarting node-{absent.index} ...")
    absent.start()

    if written is not None:
        r = delete(absent.client_port, DB, written, "absent-during-rejoin")
        check("the rejoining node refuses an id nothing holds rather than burying it",
              r.get("status") != "OK", f"got {r}")
        holders = [n.index for n in nodes if "absent-during-rejoin" in tombstone_ids(n, DB, written)]
        check("the rejoining node wrote no tombstone for it", not holders,
              f"node(s) {holders} buried an id nothing ever held")
        check("the document written while that node was down is still readable everywhere",
              all_nodes_see(DB, written, "while-down", 1, ports=all_ports(), timeout_s=45.0))


def test_a_rejoining_node_repairs_one_change_among_many_documents():
    section("Anti-entropy repairs one missed write among many identical documents")

    coll = "pull_batches"
    total = 1200
    check("the collection exists on every node",
          wait_until(lambda: create_coll(nodes[0].client_port, DB, coll).get("status") == "OK", timeout_s=30.0))
    documents = [{"_id": f"d{i}", "v": 1} for i in range(total)]
    check("the documents are committed",
          wait_until(lambda: bulk_save(nodes[0].client_port, DB, coll, documents).get("status") == "OK",
                     timeout_s=30.0, interval_s=1.0))
    check("every node holds every document", wait_until(
        lambda: all(len(stored_documents(n, DB, coll)) == total for n in nodes), timeout_s=60.0, interval_s=1.0))

    owner = owner_of(nodes[0].client_port, DB, coll)
    absent = next((n for n in nodes if n is not owner), None)
    if not check("the collection has a live owner", owner is not None and absent is not None):
        return
    writer = next(n for n in nodes if n is not absent)
    print(f"  Stopping node-{absent.index} ...")
    absent.stop()
    check("a change commits while that node is down",
          wait_until(lambda: save(writer.client_port, DB, coll, {"_id": "d0", "v": 2}).get("status") == "OK",
                     timeout_s=30.0, interval_s=1.0))
    print(f"  Restarting node-{absent.index} ...")
    absent.start()

    check("the rejoined node repairs the document it missed", wait_until(
        lambda: stored_documents(absent, DB, coll).get("d0", {}).get("v") == 2, timeout_s=60.0, interval_s=1.0))
    held = stored_documents(absent, DB, coll)
    check("and still holds every other document unchanged",
          len(held) == total and all(held[f"d{i}"].get("v") == 1 for i in range(1, total)),
          f"holds {len(held)} documents")


def drive_to_prepared(edge, watched, frozen, buffered, victim, timeout_s=20.0):
    conn = authed(edge.client_port)
    try:
        tx_id = conn.send({"type": "START_TRANSACTION"}).get("transactionId")
        if tx_id is None:
            return None
        buffered(conn)
        frozen.suspend()
        pending, prepared = commit_in_background(conn, watched, tx_id, "part", timeout_s=timeout_s, interval_s=0.05)
        if prepared:
            print(f"  Killing node-{victim.index} with node-{watched.index} prepared ...")
            victim.kill()
        frozen.resume()
        pending.join(60.0)
        if not prepared:
            conn.send({"type": "ROLLBACK_TRANSACTION"})
        wait_until_cluster_is_healthy()
        return tx_id if prepared else None
    finally:
        frozen.resume()
        conn.close()


def in_doubt_row(port, tx_id):
    rows = op(port, {"type": "LIST_TRANSACTIONS"}).get("transactions") or []
    return next((row for row in rows if row.get("dtxId") == tx_id), None)


def test_a_prepared_participant_resolves_without_its_coordinator():
    section("A prepared participant resolves after its coordinator dies")

    owners = owned_by_every_node("deadcoord")
    if owners is None:
        return
    coordinator, watched, frozen = nodes[1], nodes[0], nodes[2]
    slices = [owners[watched.index][0], owners[frozen.index][0]]

    if not check("the ring is healthy before the transaction starts", wait_until_cluster_is_healthy()):
        return

    def _buffer(conn):
        for slice_coll in slices:
            check_status(f"buffered SAVE into {slice_coll}", conn.send({
                "type": "SAVE", "databaseName": DB, "collectionName": slice_coll,
                "object": {"_id": "dc", "v": 1}}), "OK")

    tx_id = drive_to_prepared(coordinator, watched, frozen, _buffer, coordinator)
    if not check(f"node-{watched.index} durably prepared before the coordinator was killed", tx_id is not None,
                 "no participant marker was ever observed, so the commit round outran the harness"):
        return

    check("the cluster reports it in-doubt with an unreachable coordinator",
          wait_until(lambda: (in_doubt_row(watched.client_port, tx_id) or {}).get("coordinatorReachable") is False,
                     timeout_s=30.0, interval_s=1.0),
          detail=f"row={in_doubt_row(watched.client_port, tx_id)}")

    resolved = op(watched.client_port, {"type": "RESOLVE_TRANSACTION", "dtxId": tx_id, "decision": "commit"},
                  timeout=60.0)
    check_status("RESOLVE_TRANSACTION answers rather than wedging on the prepared session's locks",
                 resolved, "OK")

    for coll in slices:
        check(f"the resolved slice is readable in {coll}",
              all_nodes_see(DB, coll, "dc", 1, ports=all_ports(), timeout_s=45.0))
        check(f"{coll} is writable again, so its write locks were released",
              wait_until(lambda name=coll: save(nodes[0].client_port, DB, name,
                                                {"_id": "after_dc", "v": 1}).get("status") == "OK",
                         timeout_s=45.0, interval_s=1.0))

    print(f"  Restarting node-{coordinator.index} ...")
    coordinator.start()
    check("the cluster serves writes again with every node back",
          wait_until(lambda: save(nodes[0].client_port, DB, slices[0], {"_id": "dc_done", "v": 1})
                     .get("status") == "OK", timeout_s=60.0, interval_s=1.0))


def prepare_then_kill_behind_a_slow_commit(edge, victim, buffered, padding, timeout_s=60.0):
    conn = authed(edge.client_port)
    try:
        tx_id = conn.send({"type": "START_TRANSACTION"}).get("transactionId")
        if tx_id is None:
            return None
        buffered(conn)
        pad_the_commit(conn, padding, "pad")
        pending, prepared = commit_in_background(conn, victim, tx_id, "part", timeout_s=timeout_s, interval_s=0.02)
        if prepared:
            print(f"  Killing prepared participant node-{victim.index} before the commit reaches it ...")
            victim.kill()
        pending.join(180.0)
        wait_until_cluster_is_healthy()
        return tx_id if prepared else None
    finally:
        conn.close()


def test_a_post_prepare_write_survives_2pc_recovery():
    section("A 2PC replay reserves no tombstone for a delete it skips")

    owners = owned_by_every_node("postprepare")
    if owners is None:
        return
    edge, victim, survivor = nodes[2], nodes[1], nodes[0]
    coll = owners[victim.index][0]
    padding = owners[edge.index][0]
    ids = [f"pp{i}" for i in range(4)]

    if not check("the ring is healthy before the transaction starts", wait_until_cluster_is_healthy()):
        return

    def _seeded():
        for doc_id in ids:
            if save(survivor.client_port, DB, coll, {"_id": doc_id, "v": 1}).get("status") != "OK":
                return False
        return all(all_nodes_see(DB, coll, doc_id, 1, ports=all_ports(), timeout_s=2.0) for doc_id in ids)

    check("the documents are committed on every node", wait_until(_seeded, timeout_s=60.0, interval_s=1.0))

    def _buffer(conn):
        for doc_id in ids:
            check_status(f"buffered DELETE of {doc_id}", conn.send({
                "type": "DELETE", "databaseName": DB, "collectionName": coll, "_id": doc_id}), "OK")

    tx_id = prepare_then_kill_behind_a_slow_commit(edge, victim, _buffer, padding)
    if not check(f"node-{victim.index} durably prepared its delete slice", tx_id is not None,
                 "no participant marker was ever observed, so the commit outran the harness"):
        return
    check("the killed participant kept its prepared marker", holds_tx_record(victim, tx_id, "part"),
          f"transaction={tx_id}")

    def _rewritten():
        for doc_id in ids:
            if save(survivor.client_port, DB, coll, {"_id": doc_id, "v": 2}).get("status") != "OK":
                return False
        return all(all_nodes_see(DB, coll, doc_id, 2, ports=all_ports(), timeout_s=2.0) for doc_id in ids)

    check("the surviving nodes rewrite the ids while the participant is down",
          wait_until(_rewritten, timeout_s=90.0, interval_s=1.0))

    print(f"  Killing coordinator node-{edge.index} so the participant stays in-doubt ...")
    edge.kill()
    print(f"  Restarting node-{victim.index} ...")
    victim.start()

    def _caught_up():
        held = stored_documents(victim, DB, coll)
        return all(held.get(doc_id, {}).get("v") == 2 for doc_id in ids)

    if not check("the restarted participant caught up with the newer values",
                 wait_until(_caught_up, timeout_s=90.0),
                 f"stored={[stored_documents(victim, DB, coll).get(doc_id) for doc_id in ids]}"):
        return
    check("and is still in-doubt, so the replay under test has not run yet",
          in_doubt_row(victim.client_port, tx_id) is not None, f"transaction={tx_id}")

    resolved = op(victim.client_port, {"type": "RESOLVE_TRANSACTION", "dtxId": tx_id, "decision": "commit"},
                  timeout=60.0)
    check_status("the in-doubt slice is force-committed", resolved, "OK")

    buried = [n.index for n in nodes if n.alive and set(ids) & tombstone_ids(n, DB, coll)]
    check("the skipped deletes buried no tombstone", not buried, f"node(s) {buried} tombstoned a rewritten id")

    print(f"  Restarting node-{edge.index} ...")
    edge.start()
    for doc_id in ids:
        check(f"{doc_id} still holds the value written after the prepare",
              all_nodes_see(DB, coll, doc_id, 2, ports=all_ports(), timeout_s=60.0))
    diverged = stays_converged(DB, coll, {doc_id: 2 for doc_id in ids}, seconds=12.0)
    for doc_id in ids:
        check(f"{doc_id} survived several anti-entropy sweeps", doc_id not in diverged)


def install_marking_trigger(host, watched, marks, name, procedure_label, trigger_label):
    conn = authed(host.client_port)
    try:
        check_status(procedure_label, conn.send({
            "type": "SAVE_PROCEDURE", "databaseName": DB, "name": f"{name}_mark",
            "script": "import db from 'db'; import args from 'args';"
                      f" db.save(db.name, '{marks}', {{ _id: crypto.randomUUID(), src: args.id }});"
                      " return 1;"}), "OK")
        trigger_result = {}

        def _trigger_installed():
            nonlocal trigger_result
            trigger_result = conn.send({
                "type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": watched, "name": name,
                "events": ["CREATED"], "procedureName": f"{name}_mark"})
            return trigger_result.get("status") == "OK"

        wait_until(_trigger_installed, timeout_s=30.0, interval_s=1.0)
        check_status(trigger_label, trigger_result, "OK")
    finally:
        conn.close()


def runs_marked(host, marks, src) -> int:
    return len(ids_of(aggregate(host.client_port, DB, marks, [filter_step("src", "EQUALS", src)])))


def test_a_trigger_fires_once_despite_a_replication_timeout():
    section("A trigger runs exactly once when its commit's replication quorum times out")

    owners = collections_by_owner(nodes[0].client_port, DB, "repltimeout")
    host_index = next((index for index, names in owners.items() if len(names) >= 2), None)
    if not check("found two collections owned by one node", host_index is not None, f"owners={owners}"):
        return
    host = nodes[host_index]
    watched, marks = owners[host_index][0], owners[host_index][1]

    install_marking_trigger(host, watched, marks, "repltimeout", "store the marking procedure",
                            "install the after trigger")

    frozen = [node for node in nodes if node is not host]

    def _write_with_the_peers_frozen(write_id):
        for node in frozen:
            node.suspend()
        try:
            return save(host.client_port, DB, watched, {"_id": write_id, "v": 1})
        finally:
            for node in frozen:
                node.resume()
            wait_until_cluster_is_healthy()

    fired_id = None
    for attempt in range(5):
        doc_id = f"rt{attempt}"
        if _write_with_the_peers_frozen(doc_id).get("errorCode") == "503-3":
            fired_id = doc_id
            break
    if not check("a write's replication quorum timed out, so the case under test was reached",
                 fired_id is not None, "the peers kept acknowledging within replicationAckTimeoutMs"):
        return
    if not check("the frozen peers rejoined, so the trigger's own write can commit",
                 wait_until_cluster_is_healthy()):
        return

    check(f"the trigger for {fired_id} ran", wait_until(lambda: runs_marked(host, marks, fired_id) >= 1,
                                                        timeout_s=60.0))
    time.sleep(15)
    ran = runs_marked(host, marks, fired_id)
    check("and exactly once, rather than being retried after a commit that had stood", ran == 1,
          f"{ran} run(s) recorded")


STARTUP_REPLAY_SETTLE_S = 10


CLUSTER_RESET_FLOOD = 500
CLUSTER_LOG_RATE_PER_SECOND = 20


def reset_connection(port: int):
    try:
        sock = socket.create_connection((HOST, port), timeout=5)
    except OSError:
        return
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
    sock.close()


def test_cluster_port_reset_flood_is_rate_limited():
    section(f"{CLUSTER_RESET_FLOOD} resets on the cluster port are rate-limited to ~{CLUSTER_LOG_RATE_PER_SECOND}/s")
    victim = nodes[0]
    marker = "Cluster connection error"
    before = bu.read_log(victim.log_path).count(marker)
    start = time.time()
    for _ in range(CLUSTER_RESET_FLOOD):
        reset_connection(victim.cluster_port)
    time.sleep(2.0)
    elapsed = time.time() - start
    logged = bu.read_log(victim.log_path).count(marker) - before
    ceiling = CLUSTER_LOG_RATE_PER_SECOND * (int(elapsed) + 2)
    check(f"the reset flood was rate-limited, not one line per reset",
          logged < ceiling and logged < CLUSTER_RESET_FLOOD,
          f"{logged} lines for {CLUSTER_RESET_FLOOD} resets in {elapsed:.1f}s (ceiling {ceiling})")
    with Conn(port=victim.client_port) as conn:
        check_status("the node still serves clients", conn.authenticate(), "OK")


def test_a_trigger_writing_only_to_another_owner_consumes_its_run():
    section("A trigger whose body writes only to another node's collection consumes its run")

    owners = collections_by_owner(nodes[0].client_port, DB, "remotebody")
    pair = next(((host, other) for host in owners for other in owners if host != other), None)
    if not check("found collections owned by two different nodes", pair is not None, f"owners={owners}"):
        return
    host = nodes[pair[0]]
    watched, marks = owners[pair[0]][0], owners[pair[1]][0]

    install_marking_trigger(host, watched, marks, "remotebody", "store the remote-marking procedure",
                            "install the after trigger on the host's own collection")

    check_status("write the watched document on its owner",
                 save(host.client_port, DB, watched, {"_id": "fires_remotely", "v": 1}), "OK")

    if not check("the trigger's remote write landed",
                 wait_until(lambda: runs_marked(host, marks, "fires_remotely") >= 1, timeout_s=60.0)):
        return

    def _pending_runs_of_the_trigger():
        runs = op(host.client_port, {"type": "LIST_TRIGGER_RUNS", "status": "PENDING"}).get("runs") or []
        return [run for run in runs if run.get("trigger") == "remotebody"]

    check("the firing node holds no pending run for the trigger once it succeeded",
          wait_until(lambda: not _pending_runs_of_the_trigger(), timeout_s=15.0),
          f"pending: {_pending_runs_of_the_trigger()} - the consume op sat in a local slice the commit skipped")

    host.stop()
    host.start()
    if not check("the restarted firing node rejoined", wait_until_cluster_is_healthy()):
        return
    time.sleep(STARTUP_REPLAY_SETTLE_S)
    ran = runs_marked(host, marks, "fires_remotely")
    check("the restart did not replay the body", ran == 1,
          f"{ran} run(s) recorded - startup recovery re-submitted a run whose effects had committed")


def test_forwarded_write_ignores_a_client_supplied_trigger_depth():
    section("A client-supplied triggerDepth does not survive forwarding")

    coll = "depth_guard"

    def _all_accepted():
        if create_coll(nodes[0].client_port, DB, coll).get("status") != "OK":
            return False
        for node in nodes:
            response = op(node.client_port, {"type": "SAVE", "databaseName": DB, "collectionName": coll,
                                             "triggerDepth": -2000000000,
                                             "object": {"_id": f"d{node.index}", "v": node.index}})
            if response.get("status") != "OK":
                return False
        return all_nodes_see(DB, coll, "d0", 0, ports=all_ports(), timeout_s=2.0)

    check("a client-supplied triggerDepth is ignored and every write commits",
          wait_until(_all_accepted, timeout_s=30.0, interval_s=1.0))


def test_an_escaped_trigger_depth_does_not_suppress_the_owners_trigger():
    section("An escaped triggerDepth key does not survive forwarding to suppress the owner's trigger")

    owners = collections_by_owner(nodes[0].client_port, DB, "escdepth")
    host_index = next((index for index, names in owners.items() if len(names) >= 2), None)
    if not check("found two collections owned by one node", host_index is not None, f"owners={owners}"):
        return
    host = nodes[host_index]
    watched, marks = owners[host_index][0], owners[host_index][1]
    install_marking_trigger(host, watched, marks, "escdepth", "store the marking procedure",
                            "install the after trigger")

    for node in (n for n in nodes if n is not host):
        doc_id = f"esc{node.index}"
        line = ('{"type":"SAVE","databaseName":"' + DB + '","collectionName":"' + watched
                + '","trigger\\u0044epth":99,"object":{"_id":"' + doc_id + '","v":1}}')
        conn = authed(node.client_port)
        try:
            check_status(f"SAVE {doc_id} with an escaped triggerDepth through node-{node.index}",
                         conn.send_raw(line), "OK")
        finally:
            conn.close()
        check(f"the owner's trigger still ran for {doc_id}",
              wait_until(lambda: runs_marked(host, marks, doc_id) >= 1, timeout_s=30.0))


SCHEDULE_DB = DB
SCHEDULE_PROC = "sched_marker"
SCHEDULE_SOURCE = (
    "import db from 'db'; import args from 'args';"
    " db.save(db.name, args.coll, { _id: crypto.randomUUID(), at: Date.now() });"
    " return 1;"
)


def save_schedule(port, name, coll, interval_ms=1000, db=SCHEDULE_DB):
    return op(port, {"type": "SAVE_SCHEDULE", "databaseName": db, "name": name,
                     "procedureName": SCHEDULE_PROC, "intervalMs": interval_ms,
                     "args": {"coll": coll}})


def delete_schedule(port, name, db=SCHEDULE_DB):
    return op(port, {"type": "DELETE_SCHEDULE", "databaseName": db, "name": name})


def list_schedules(port, db=SCHEDULE_DB):
    return op(port, {"type": "LIST_SCHEDULES", "databaseName": db})


def schedule_names(port, db=SCHEDULE_DB) -> list:
    return sorted(s.get("name") for s in (list_schedules(port, db).get("schedules") or []))


def fire_count(port, coll, db=SCHEDULE_DB) -> int:
    r = aggregate(port, db, coll, [{"type": "COUNT"}])
    value = bu.dig(r, "results.0.count")
    return int(value) if value is not None else 0


def admin_coordinator_node():
    alive = [node for node in nodes if node.alive]
    winner = _ring_owner([node_id_of(node) for node in alive], ADMIN_COORDINATOR_KEY)
    return next(node for node in alive if node_id_of(node) == winner)


def procedure_version_on(port, db, name):
    listed = op(port, {"type": "LIST_PROCEDURES", "databaseName": db, "includeSource": True})
    stored = next((p for p in (listed.get("procedures") or []) if p.get("name") == name), {})
    return stored.get("version"), stored.get("source")


def test_a_procedure_bound_by_another_definer_is_protected_through_any_node():
    section("A procedure bound by another definer is protected through any node")

    definer, intruder, password = "cluster_bound_definer", "cluster_bound_intruder", "cluster_bound1234"
    port = nodes[0].client_port
    for username in (definer, intruder):
        op(port, {"type": "DELETE_USER", "username": username})
        check_status(f"CREATE_USER {username} with MANAGE", op(port, {
            "type": "CREATE_USER", "username": username, "password": password, "admin": False,
            "databasePermissions": {DB: "READ_WRITE"}, "scriptPermissions": {DB: "MANAGE"}}), "OK")
    coordinator = admin_coordinator_node()
    edges = [node for node in nodes if node is not coordinator]
    check("both users authenticate on every node", wait_until(lambda: all(
        as_user(n.client_port, u, password).get("status") == "OK" for n in nodes for u in (definer, intruder)),
        timeout_s=15.0))

    check_status("the definer saves the procedure through an edge node", as_user(edges[0].client_port, definer,
                 password, {"type": "SAVE_PROCEDURE", "databaseName": DB, "name": "bound_proc",
                            "script": "return 1;"}), "OK")
    check_status("and binds a trigger to it through an edge node", as_user(edges[0].client_port, definer, password, {
        "type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": "docs", "name": "bound_trigger",
        "events": ["CREATED"], "procedureName": "bound_proc"}), "OK")
    overwrite = {"type": "SAVE_PROCEDURE", "databaseName": DB, "name": "bound_proc", "script": "return 666;"}
    for label, node in (("a non-coordinator node", edges[1]), ("the admin coordinator", coordinator)):
        check_code(f"the foreign overwrite through {label} is refused",
                   as_user(node.client_port, intruder, password, overwrite), "FORBIDDEN", "403-4")

    saved = as_user(edges[1].client_port, definer, password, {
        "type": "SAVE_PROCEDURE", "databaseName": DB, "name": "bound_proc", "script": "return 2;"})
    check_status("the definer's overwrite through a non-coordinator node succeeds", saved, "OK")
    expected = (saved.get("version"), "return 2;")
    check("and replicates to every node", wait_until(
        lambda: all(procedure_version_on(n.client_port, DB, "bound_proc") == expected for n in nodes),
        timeout_s=15.0), f"expected {expected}")

    op(port, {"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": "docs", "name": "bound_trigger"})
    op(port, {"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "bound_proc"})
    for username in (definer, intruder):
        op(port, {"type": "DELETE_USER", "username": username})


def trigger_listed_on(port, coll, name):
    listed = op(port, {"type": "LIST_TRIGGERS", "databaseName": DB, "collectionName": coll})
    return next((t for t in (listed.get("triggers") or []) if t.get("name") == name), {})


def collection_owned_off_the_coordinator(coordinator, prefix):
    for i in range(8):
        coll = f"{prefix}_{i}"
        check_status(f"CREATE_COLLECTION {coll}", create_coll(coordinator.client_port, DB, coll), "OK")
        owner = owner_of(coordinator.client_port, DB, coll)
        if owner is not None and owner is not coordinator:
            return coll, owner
    return None, None


def test_a_revoked_definer_is_disabled_on_every_node():
    section("A trigger whose definer loses READ is disabled on every node, not only where it fires")

    definer, password = "cluster_revoked_definer", "cluster_revoked1234"
    coordinator = admin_coordinator_node()
    port = coordinator.client_port
    source, owner = collection_owned_off_the_coordinator(coordinator, "revoke_src")
    if source is None:
        check("a collection owned by a node other than the admin coordinator exists", False,
              "eight collections all landed on the coordinator")
        return
    check_status("CREATE_COLLECTION for the audit rows", create_coll(port, DB, "revoke_audit"), "OK")

    def grants(source_access):
        return {"type": "CHANGE_PERMISSIONS", "username": definer,
                "databasePermissions": {DB: "READ_WRITE"} if source_access else {},
                "collectionPermissions": {f"{DB}|revoke_audit": "READ_WRITE"}, "scriptPermissions": {DB: "MANAGE"}}

    op(port, {"type": "DELETE_USER", "username": definer})
    check_status("CREATE_USER the definer", op(port, {
        "type": "CREATE_USER", "username": definer, "password": password, "admin": False,
        "databasePermissions": {DB: "READ_WRITE"}, "collectionPermissions": {f"{DB}|revoke_audit": "READ_WRITE"},
        "scriptPermissions": {DB: "MANAGE"}}), "OK")
    check("the definer authenticates on every node", wait_until(lambda: all(
        as_user(n.client_port, definer, password).get("status") == "OK" for n in nodes), timeout_s=15.0))
    check_status("the definer saves an auditing procedure", as_user(port, definer, password, {
        "type": "SAVE_PROCEDURE", "databaseName": DB, "name": "revoke_audit_proc",
        "script": "import db from 'db'; import args from 'args';"
                  " db.save(db.name, 'revoke_audit', { _id: 'audit-' + args.id }); return 1;"}), "OK")
    check_status("and binds it to a collection the coordinator does not own", as_user(port, definer, password, {
        "type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": source, "name": "revocable",
        "events": ["CREATED"], "procedureName": "revoke_audit_proc"}), "OK")
    check_status("a write fires it on the owner", save(owner.client_port, DB, source, {"_id": "r1"}), "OK")
    check("while the definer holds READ the trigger runs", wait_until(
        lambda: find_by_id(owner.client_port, DB, "revoke_audit", "audit-r1").get("status") == "OK",
        timeout_s=15.0))

    check_status("the admin narrows the definer", op(port, grants(False)), "OK")
    check("every node lists the trigger disabled", wait_until(
        lambda: all(trigger_listed_on(n.client_port, source, "revocable").get("enabled") is False for n in nodes),
        timeout_s=15.0), f"got {[trigger_listed_on(n.client_port, source, 'revocable') for n in nodes]}")
    check("with the reason recorded", f"READ on collection '{source}'" in str(
        trigger_listed_on(owner.client_port, source, "revocable").get("disabledReason")))
    check_status("another write on the owner", save(owner.client_port, DB, source, {"_id": "r2"}), "OK")

    check_status("the admin gives the grant back", op(port, grants(True)), "OK")
    check_status("a write after the re-grant", save(owner.client_port, DB, source, {"_id": "r3"}), "OK")
    time.sleep(2.0)
    for doc_id in ("audit-r2", "audit-r3"):
        check(f"{doc_id} was never written", find_by_id(owner.client_port, DB, "revoke_audit", doc_id).get("status")
              == "NOT_FOUND", "a revoked definer's trigger ran on the collection owner")
    check("re-granting alone does not revive it on any node",
          all(trigger_listed_on(n.client_port, source, "revocable").get("enabled") is False for n in nodes))

    check_status("the admin re-saves the trigger", op(port, {
        "type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": source, "name": "revocable",
        "events": ["CREATED"], "procedureName": "revoke_audit_proc"}), "OK")
    check("which re-enables it everywhere", wait_until(
        lambda: all(trigger_listed_on(n.client_port, source, "revocable").get("enabled") is True for n in nodes),
        timeout_s=15.0))
    check_status("a write after the re-save", save(owner.client_port, DB, source, {"_id": "r4"}), "OK")
    check("the trigger runs again", wait_until(
        lambda: find_by_id(owner.client_port, DB, "revoke_audit", "audit-r4").get("status") == "OK",
        timeout_s=15.0))

    op(port, {"type": "DELETE_TRIGGER", "databaseName": DB, "collectionName": source, "name": "revocable"})
    op(port, {"type": "DELETE_PROCEDURE", "databaseName": DB, "name": "revoke_audit_proc"})
    op(port, {"type": "DELETE_USER", "username": definer})


def test_before_hook_runs_on_the_owner():
    section("Before hooks — a write forwarded to the owner still runs the hook")

    check_status("CREATE_COLLECTION for the hooked data", create_coll(nodes[0].client_port, DB, "hooked"), "OK")

    conn = authed(nodes[0].client_port)
    try:
        check_status("install the hook procedure", conn.send({
            "type": "SAVE_PROCEDURE", "databaseName": DB, "name": "hookcalc",
            "script": "export default (doc) => ({ ...doc, v: doc.v * 2, hooked: true });"}), "OK")
        check_status("install the before trigger", conn.send({
            "type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": "hooked", "name": "double",
            "events": ["CREATED", "UPDATED"], "procedureName": "hookcalc", "timing": "before"}), "OK")
    finally:
        conn.close()

    for i in range(NODE_COUNT):
        _id = f"h{i}"
        r = save(nodes[i].client_port, DB, "hooked", {"_id": _id, "v": 5})
        check_status(f"SAVE via node-{i} commits", r, "OK")
        check(f"the hook ran for a write entering at node-{i}",
                   all_nodes_see(DB, "hooked", _id, 10),
                   detail=f"_id={_id} should have been doubled by the owner's before hook")

    conn = authed(nodes[0].client_port)
    try:
        check_status("install a vetoing hook", conn.send({
            "type": "SAVE_PROCEDURE", "databaseName": DB, "name": "hookveto",
            "script": "export default (doc) => { throw new Error('refused by the owner'); };"}), "OK")
        check_status("point the trigger at it", conn.send({
            "type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": "hooked", "name": "double",
            "events": ["CREATED", "UPDATED"], "procedureName": "hookveto", "timing": "before"}), "OK")
    finally:
        conn.close()

    for i in range(NODE_COUNT):
        r = save(nodes[i].client_port, DB, "hooked", {"_id": f"hv{i}", "v": 1})
        check(f"a veto reaches the client that wrote via node-{i}",
                   r.get("errorCode") == "400-21",
                   detail=f"got {r}")

    conn = authed(nodes[0].client_port)
    try:
        check_status("install a hook that stamps the acting user", conn.send({
            "type": "SAVE_PROCEDURE", "databaseName": DB, "name": "hookwho",
            "script": "export default (doc, ctx) => ({ ...doc, by: ctx.actingUser });"}), "OK")
        check_status("point the trigger at it", conn.send({
            "type": "SAVE_TRIGGER", "databaseName": DB, "collectionName": "hooked", "name": "double",
            "events": ["CREATED", "UPDATED"], "procedureName": "hookwho", "timing": "before"}), "OK")
    finally:
        conn.close()

    for i in range(NODE_COUNT):
        _id = f"hw{i}"
        check_status(f"SAVE via node-{i} commits", save(nodes[i].client_port, DB, "hooked", {"_id": _id, "v": 1}), "OK")
        check(f"the owner's hook saw the real acting user for a write entering at node-{i}",
              all_nodes_see(DB, "hooked", _id, ADMIN_USERNAME, field="by"),
              detail=f"_id={_id} should carry the authenticated username, not the empty string a "
                     f"forward used to leave behind")

    conn = authed(nodes[0].client_port)
    try:
        conn.send({"type": "DELETE_TRIGGER", "databaseName": DB,
                              "collectionName": "hooked", "name": "double"})
    finally:
        conn.close()


def test_admin_ops_replicate_in_commit_order():
    section("Admin ops — a burst on one record converges on its last write everywhere")

    conn = authed(nodes[0].client_port)
    try:
        for i in range(5):
            check_status(f"SAVE_PROCEDURE burst #{i}", conn.send({
                "type": "SAVE_PROCEDURE", "databaseName": DB, "name": "burst",
                "script": f"export default () => {i};"}), "OK")
    finally:
        conn.close()

    def _last_write_everywhere():
        for node in nodes:
            if not node.alive:
                continue
            listed = op(node.client_port, {"type": "LIST_PROCEDURES", "databaseName": DB, "includeSource": True})
            row = next((p for p in listed.get("procedures", []) if p.get("name") == "burst"), None)
            if row is None or row.get("version") != 5 or "=> 4;" not in (row.get("source") or ""):
                return False
        return True

    check("every node holds the burst's last version and body", wait_until(_last_write_everywhere, timeout_s=30.0))


def node_id_of(node) -> str:
    with open(os.path.join(node.work_dir, "db", "cluster", "node.id")) as fp:
        content = fp.read()
    return content.strip()


def procedure_file(node, name: str) -> str:
    return os.path.join(node.work_dir, "db", DB, ".procedures", f"{name}.json")


def test_an_unreadable_definition_is_not_deleted_on_peers():
    section("Admin anti-entropy — a definition the winning node cannot read is not deleted elsewhere")

    name = "unreadable_probe"
    check_status("SAVE_PROCEDURE", op_with_retry(lambda: op(nodes[0].client_port, {
        "type": "SAVE_PROCEDURE", "databaseName": DB, "name": name, "script": "return 1;"})), "OK")
    alive = [node for node in nodes if node.alive]
    check("the procedure reaches every node",
          wait_until(lambda: all(os.path.isfile(procedure_file(node, name)) for node in alive), timeout_s=30.0))

    winner = max(alive, key=node_id_of)
    peers = [node for node in alive if node is not winner]
    with open(procedure_file(peers[0], name)) as fp:
        intact = fp.read()
    with open(procedure_file(winner, name), "w") as fp:
        fp.write("{\"name\": \"torn")

    time.sleep(4 * ANTI_ENTROPY_INTERVAL_S)

    for peer in peers:
        path = procedure_file(peer, name)
        still_there = os.path.isfile(path)
        check(f"node-{peer.index} still holds the procedure after several admin sweeps", still_there)
        if still_there:
            with open(path) as fp:
                check(f"node-{peer.index}'s copy is unchanged", fp.read() == intact)

    with open(procedure_file(winner, name), "w") as fp:
        fp.write(intact)
    op_with_retry(lambda: op(nodes[0].client_port, {"type": "DELETE_PROCEDURE", "databaseName": DB, "name": name}))


def test_schedule_replication_and_single_firing():
    section("Scheduled procedures — DDL replicates, and a schedule fires on exactly one node")

    check_status("store the marker procedure", op(nodes[0].client_port, {
        "type": "SAVE_PROCEDURE", "databaseName": SCHEDULE_DB, "name": SCHEDULE_PROC,
        "script": SCHEDULE_SOURCE}), "OK")
    create_coll(nodes[0].client_port, SCHEDULE_DB, "sched_marks")
    check_status("save a schedule via node-0", save_schedule(nodes[0].client_port, "clustered", "sched_marks"), "OK")

    check("the schedule is listed on every node",
               wait_until(lambda: all("clustered" in schedule_names(n.client_port) for n in nodes if n.alive),
                          timeout_s=20.0),
               detail=str([schedule_names(n.client_port) for n in nodes if n.alive]))

    check("the schedule starts firing",
               wait_until(lambda: fire_count(nodes[0].client_port, "sched_marks") >= 1, timeout_s=20.0))
    before = fire_count(nodes[0].client_port, "sched_marks")
    window = 6.0
    time.sleep(window)
    delta = fire_count(nodes[0].client_port, "sched_marks") - before
    expected = window / 1.0
    check("it fires at the single-owner rate, not once per node",
               1 <= delta <= expected * 1.7,
               detail=f"{delta} firings in {window}s (one owner would be about {expected:.0f}, "
                      f"three would be about {expected * 3:.0f})")

    check_status("delete the schedule", delete_schedule(nodes[0].client_port, "clustered"), "OK")
    check("the deletion replicates to every node",
               wait_until(lambda: all("clustered" not in schedule_names(n.client_port)
                                      for n in nodes if n.alive), timeout_s=20.0))


def test_schedule_failover():
    section("Scheduled procedures — a schedule owned by the dead node fails over")

    names = [f"failover{i}" for i in range(6)]
    for name in names:
        create_coll(nodes[0].client_port, SCHEDULE_DB, name)
        check_status(f"save {name}", save_schedule(nodes[0].client_port, name, name), "OK")

    def _all_firing():
        return all(fire_count(nodes[0].client_port, schedule_name) >= 1 for schedule_name in names)

    check("every schedule fires from a surviving node after the owner died",
               wait_until(_all_firing, timeout_s=60.0, interval_s=1.0),
               detail=str({name: fire_count(nodes[0].client_port, name) for name in names}))

    for name in names:
        delete_schedule(nodes[0].client_port, name)

    create_coll(nodes[0].client_port, SCHEDULE_DB, "while_down")
    check_status("save a schedule while one node is down",
          save_schedule(nodes[0].client_port, "whileDown", "while_down"), "OK")


def test_schedule_rejoin_catch_up():
    section("Scheduled procedures — a rejoining node picks up what it missed")

    rejoiner = nodes[2]
    check("the rejoined node knows the schedule saved while it was down",
               wait_until(lambda: "whileDown" in schedule_names(rejoiner.client_port), timeout_s=60.0,
                          interval_s=1.0),
               detail=str(schedule_names(rejoiner.client_port)))
    check_status("delete the schedule", delete_schedule(nodes[0].client_port, "whileDown"), "OK")
    check("the deletion reaches every node",
               wait_until(lambda: all("whileDown" not in schedule_names(n.client_port)
                                      for n in nodes if n.alive), timeout_s=20.0))


def _drop_recreate_scenario(db, coll, scope, rejoin_first):
    database = scope == "database"
    port = nodes[0].client_port
    live_ids = {"live0", "live1", "live2"}
    if database:
        check_status("create the database", create_db(port, db), "OK")
    check_status("create the collection", create_coll(port, db, coll), "OK")
    check("the pre-drop documents all committed",
          seed_documents(port, db, coll, [{"_id": f"pre{i}", "v": i} for i in range(5)]))

    def _every_node_holds_five():
        if database or rejoin_first:
            return all(len(stored_documents(n, db, coll)) == 5 for n in nodes if n.alive)
        return all(len(ids_of(aggregate(p, db, coll, []))) == 5 for p in all_ports())

    check("every node holds the pre-drop documents", wait_until(_every_node_holds_five, timeout_s=30.0))

    victim = nodes[2]
    print(f"  Killing node-{victim.index} so it misses the drop ...")
    victim.kill()

    def _dropped():
        if database:
            return drop_db(port, db).get("status") == "OK"
        return op(port, {"type": "DROP_COLLECTION", "databaseName": db,
                         "collectionName": coll}).get("status") == "OK"

    def _recreated():
        if database and create_db(port, db).get("status") != "OK":
            return False
        return create_coll(port, db, coll).get("status") == "OK"

    def _quarantined():
        if database:
            return f"Quarantined database {db}" in bu.read_log(victim.log_path)
        return _node_log_mentions_quarantine(victim, coll, db)

    check(f"the survivors drop the {scope}", wait_until(_dropped, timeout_s=30.0, interval_s=1.0))
    names = "names" if database else "name"
    if rejoin_first:
        print(f"  Restarting node-{victim.index} before the re-create ...")
        victim.start()
        missing = f"database {db}" if database else f"{db}|{coll}"
        check(f"the rejoined node unregisters the dropped {scope}", wait_until(_quarantined, timeout_s=60.0),
              f"node-{victim.index} logged no quarantine for {missing}")
        if not database:
            check("the rejoined node holds none of the dropped documents under the live name",
                  not stored_documents(victim, db, coll), str(sorted(stored_documents(victim, db, coll))))
        check(f"the cluster re-creates the same {names}", wait_until(_recreated, timeout_s=30.0, interval_s=1.0))
    else:
        check(f"the survivors re-create the same {names}", wait_until(_recreated, timeout_s=30.0, interval_s=1.0))
    check("the live incarnation's own documents commit",
          seed_documents(port, db, coll, [{"_id": f"live{i}", "v": i} for i in range(3)]))
    if not rejoin_first:
        print(f"  Restarting node-{victim.index} ...")
        victim.start()

    assert_no_resurrection(db, coll, live_ids, victim,
                           quarantined=_quarantined if database and rejoin_first else None)


def test_drop_and_recreate_does_not_resurrect_documents():
    section("DROP_COLLECTION + CREATE_COLLECTION while one node is down")
    coll = "incarnation_coll"
    _drop_recreate_scenario(DB, coll, "collection", rejoin_first=False)
    check_status("a write to the re-created collection succeeds",
                 save(nodes[0].client_port, DB, coll, {"_id": "after", "v": 1}), "OK")
    check("the write converges on every node",
          all_nodes_see(DB, coll, "after", 1, ports=all_ports(), timeout_s=30.0))


def assert_no_resurrection(db, coll, live_ids, victim, seconds: float = 25.0, quarantined=None):
    quarantined = quarantined or (lambda: _node_log_mentions_quarantine(victim, coll, db))
    check("the rejoined node recognised its documents as a dropped incarnation",
          wait_until(quarantined, timeout_s=60.0), f"node-{victim.index} logged no quarantine for {db}|{coll}")

    def _only_live_documents_on_disk():
        for node in nodes:
            if not node.alive:
                continue
            if set(stored_documents(node, db, coll)) - live_ids:
                return False
        return True

    held = holds_throughout(_only_live_documents_on_disk, seconds=seconds)
    per_node = {f"node-{n.index}": sorted(stored_documents(n, db, coll)) for n in nodes if n.alive}
    check("no node seeds the dropped incarnation's documents back", held, f"per-node: {per_node}")

    def _client_sees_only_live():
        for port in all_ports():
            r = aggregate(port, db, coll, [])
            if r.get("status") not in ("OK", "NOT_FOUND"):
                return False
            if set(ids_of(r)) - live_ids:
                return False
        return True

    served = {p: sorted(ids_of(aggregate(p, db, coll, []))) for p in all_ports()}
    check("a client read returns none of the dropped incarnation's documents",
          _client_sees_only_live(), f"per-port: {served}")

    check("the rejoined node still catches up on the live incarnation",
          wait_until(lambda: set(stored_documents(victim, db, coll)) == live_ids, timeout_s=60.0),
          f"node-{victim.index} holds {sorted(stored_documents(victim, db, coll))}, expected {sorted(live_ids)}")


def test_drop_database_and_recreate_does_not_resurrect_documents():
    section("DROP_DATABASE + CREATE_DATABASE while one node is down")
    _drop_recreate_scenario("incarnation_db", "dbinc_coll", "database", rejoin_first=False)


def test_drop_then_rejoin_then_recreate_does_not_resurrect_documents():
    section("DROP_COLLECTION, then the missed node rejoins, then CREATE_COLLECTION")
    _drop_recreate_scenario(DB, "rejoin_first_coll", "collection", rejoin_first=True)


def test_drop_database_then_rejoin_then_recreate_does_not_resurrect_documents():
    section("DROP_DATABASE, then the missed node rejoins, then CREATE_DATABASE")
    _drop_recreate_scenario("rejoin_first_db", "rejoin_first_dbcoll", "database", rejoin_first=True)


def test_a_node_that_missed_the_last_database_drop_converges():
    section("A node that missed the drop of the cluster's last database merges the database tombstones")
    victim = nodes[2]
    print(f"  Killing node-{victim.index} so it misses every drop ...")
    victim.kill()
    databases = list_databases(nodes[0].client_port).get("databases") or []
    check("the cluster still holds databases before the drop", bool(databases), "nothing left to drop")
    for db in databases:
        check(f"the survivors drop {db}",
              wait_until(lambda dropped=db: drop_db(nodes[0].client_port, dropped).get("status") == "OK",
                         timeout_s=30.0, interval_s=1.0))
    check("the survivors hold no database",
          wait_until(lambda: all(not (list_databases(p).get("databases") or []) for p in all_ports()),
                     timeout_s=30.0))

    print(f"  Restarting node-{victim.index} after the cluster emptied ...")
    victim.start()

    def _victim_holds_nothing():
        response = list_databases(victim.client_port)
        return response.get("status") == "OK" and not (response.get("databases") or [])

    check("the rejoined node merges the database tombstones instead of keeping the dropped databases",
          wait_until(_victim_holds_nothing, timeout_s=60.0, interval_s=1.0),
          f"node-{victim.index} still lists {list_databases(victim.client_port).get('databases')!r}")

    def _every_database_quarantined():
        log = bu.read_log(victim.log_path)
        return all(f"Quarantined database {dropped}" in log for dropped in databases)

    check("its data folders were moved aside, not deleted", wait_until(_every_database_quarantined, timeout_s=30.0),
          f"node-{victim.index} logged no quarantine for some of {databases!r}")


def _node_log_mentions_quarantine(node, coll, db: str = DB) -> bool:
    return f"Quarantined collection {db}|{coll}" in bu.read_log(node.log_path)


def test_rapid_collection_creates_are_not_quarantined():
    section("CREATE_COLLECTION bursts replicate and converge")
    colls = [f"burst_coll_{i}" for i in range(8)]
    unseeded = []
    for coll in colls:
        check_status(f"create {coll}", create_coll(nodes[0].client_port, DB, coll), "OK")
        if not seed_documents(nodes[0].client_port, DB, coll, [{"_id": "seed", "v": 1}]):
            unseeded.append(coll)
    check("every burst collection got its seed document", not unseeded, f"unseeded: {unseeded}")

    def _all_nodes_hold_every_burst_collection():
        for burst_coll in colls:
            for port in all_ports():
                if ids_of(aggregate(port, DB, burst_coll, [])) != ["seed"]:
                    return False
        return True

    ok = wait_until(_all_nodes_hold_every_burst_collection, timeout_s=90.0, interval_s=1.0)
    missing = {}
    if not ok:
        for coll in colls:
            per_node = {p: ids_of(aggregate(p, DB, coll, [])) for p in all_ports()}
            if any(v != ["seed"] for v in per_node.values()):
                missing[coll] = per_node
    check("every node keeps every collection created in the burst", ok, f"diverged: {missing}")

    quarantined = [f"node-{n.index}:{c}" for n in nodes for c in colls
                   if _node_log_mentions_quarantine(n, c)]
    check("no node quarantined a healthy collection", not quarantined, f"quarantines: {quarantined}")


def test_recreating_an_existing_collection_keeps_its_documents():
    section("CREATE_COLLECTION on a populated collection is a safe no-op")
    coll = "recreate_coll"
    check_status("create the collection", create_coll(nodes[0].client_port, DB, coll), "OK")
    check("the documents all committed",
          seed_documents(nodes[0].client_port, DB, coll, [{"_id": f"doc{i}", "v": i} for i in range(5)]))

    def _all_see_five():
        return all(len(ids_of(aggregate(p, DB, coll, []))) == 5 for p in all_ports())

    check("every node holds the documents", wait_until(_all_see_five, timeout_s=30.0))

    victim = nodes[2]
    print(f"  Killing node-{victim.index} so it misses the re-create ...")
    victim.kill()

    def _recreated():
        return create_coll(nodes[0].client_port, DB, coll).get("status") == "OK"

    check("re-creating an existing collection still answers OK",
          wait_until(_recreated, timeout_s=30.0, interval_s=1.0))

    print(f"  Restarting node-{victim.index} ...")
    victim.start()

    ok = wait_until(_all_see_five, timeout_s=90.0, interval_s=1.0)
    per_node = {p: sorted(ids_of(aggregate(p, DB, coll, []))) for p in all_ports()}
    check("no node loses the documents to a quarantine", ok, f"per-node: {per_node}")

    quarantined = [f"node-{n.index}" for n in nodes if _node_log_mentions_quarantine(n, coll)]
    check("no node quarantined the re-created collection", not quarantined, f"quarantines: {quarantined}")

    check_status("the collection is still writable",
                 save(nodes[0].client_port, DB, coll, {"_id": "after", "v": 9}), "OK")
    check("the later write converges", all_nodes_see(DB, coll, "after", 9, ports=all_ports(), timeout_s=30.0))


MIGRATION_NODE_OFFSET = 10
ADMIN_COORDINATOR_KEY = "admin|__admin_coordinator__"
VIRTUAL_NODES = 128


class MigrationNode(Node):
    def __init__(self, index: int, base_dir: str, node_id: str, seed_port: int = 0):
        self.node_id = node_id
        self.seed_port = seed_port
        self.clustered = False
        super().__init__(index, base_dir)

    def _write_config(self):
        super()._write_config()
        path = os.path.join(self.work_dir, "lwnrdb.cfg")
        with open(path) as fp:
            lines = fp.read().splitlines()
        seeds = f"{HOST}:{self.seed_port}" if self.seed_port else ""
        overrides = {
            "clusterEnabled": "true" if self.clustered else "false",
            "clusterSeeds": seeds,
            "clusterExpectedSize": "2",
        }
        rewritten = [f"{key}={overrides[key]}" if (key := line.split("=", 1)[0]) in overrides else line
                     for line in lines]
        rewritten.append(f"nodeId={self.node_id}")
        with open(path, "w") as fp:
            fp.write("\n".join(rewritten) + "\n")

    def cluster_it(self):
        self.clustered = True
        self._write_config()


def _ring_hash(value: str) -> int:
    return int.from_bytes(hashlib.sha256(value.encode()).digest()[:8], "big", signed=True)


def _ring_owner(node_ids: list, key: str) -> str:
    ring = sorted((_ring_hash(f"{node_id}#{i}"), node_id) for node_id in node_ids for i in range(VIRTUAL_NODES))
    position = bisect.bisect_left([point for point, _ in ring], _ring_hash(key))
    return ring[position % len(ring)][1]


def _ids_where_the_fresh_node_wins_and_coordinates() -> tuple:
    while True:
        populated, fresh = sorted([str(uuid.uuid4()), str(uuid.uuid4())])
        if _ring_owner([populated, fresh], ADMIN_COORDINATOR_KEY) == fresh:
            return populated, fresh


def _authenticates(port: int, username: str, password: str) -> bool:
    return as_user(port, username, password).get("status") == "OK"


def test_a_populated_standalone_node_keeps_its_data_when_a_fresh_node_joins():
    section("A standalone node switched to clustered keeps its data when a fresh node joins and coordinates")

    populated_id, fresh_id = _ids_where_the_fresh_node_wins_and_coordinates()
    base_dir = tempfile.mkdtemp(prefix="lwnrdb-migration-")
    populated = MigrationNode(MIGRATION_NODE_OFFSET, base_dir, populated_id)
    fresh = MigrationNode(MIGRATION_NODE_OFFSET + 1, base_dir, fresh_id, populated.cluster_port)
    db, coll, user, password = "standalone_db", "kept", "standalone_analyst", "standalone_analyst1"
    try:
        populated.start()
        check_status("CREATE_DATABASE while standalone", create_db(populated.client_port, db), "OK")
        check_status("CREATE_COLLECTION while standalone", create_coll(populated.client_port, db, coll), "OK")
        check_status("SAVE while standalone", save(populated.client_port, db, coll, {"_id": "d1", "v": 1}), "OK")
        check_status("CREATE_USER while standalone", op(populated.client_port, {
            "type": "CREATE_USER", "username": user, "password": password, "admin": False,
            "globalPermissions": [], "databasePermissions": {db: "READ"}, "collectionPermissions": {}}), "OK")
        populated.stop()

        populated.cluster_it()
        populated.start()
        check("a populated node's first clustered start keeps no admin epoch",
              not os.path.exists(os.path.join(populated.work_dir, "db", "cluster", "admin.epoch")))

        fresh.cluster_it()
        fresh.start()

        check("the fresh coordinator commits its first CREATE_DATABASE once it has synced its admin records",
              wait_until(lambda: create_db(fresh.client_port, "fresh_db").get("status") == "OK", timeout_s=60.0))
        ports = [populated.client_port, fresh.client_port]
        check("both nodes list the standalone database and the new one",
              wait_until(lambda: all({db, "fresh_db"} <= set(list_databases(p).get("databases") or [])
                                     for p in ports), timeout_s=30.0))
        check("the standalone document is readable through both nodes",
              wait_until(lambda: all(find_by_id(p, db, coll, "d1").get("status") == "OK" for p in ports),
                         timeout_s=30.0))
        check("the standalone user authenticates on both nodes",
              wait_until(lambda: all(_authenticates(p, user, password) for p in ports), timeout_s=30.0))
        quarantined = [name for name in os.listdir(os.path.join(populated.work_dir, "db"))
                       if ".quarantined-" in name]
        check("nothing on the populated node was moved aside", not quarantined, str(quarantined))
    finally:
        fresh.stop()
        populated.stop()


FAULT_HALT_STATUS = 137


def arm_fault(node, point, action, match=None) -> dict:
    payload = {"type": "ARM_FAULT", "point": point, "action": action}
    if match is not None:
        payload["match"] = match
    return op(node.client_port, payload)


def await_fault(node, point, timeout_ms=30000) -> dict:
    return op(node.client_port, {"type": "AWAIT_FAULT", "point": point, "timeoutMs": timeout_ms})


def release_fault(node, point=None) -> dict:
    payload = {"type": "RELEASE_FAULT"}
    if point is not None:
        payload["point"] = point
    return op(node.client_port, payload)


def collection_subject(coll) -> str:
    return f"{DB}|{coll}"


def nodes_agree_on(coll, _id) -> bool:
    held = [stored_documents(n, DB, coll).get(_id) for n in nodes if n.alive]
    answers = [find_by_id(n.client_port, DB, coll, _id).get("object") for n in nodes if n.alive]
    return all(h == held[0] for h in held) and all(a == answers[0] for a in answers)


def restore_after_fault_test():
    for n in nodes:
        if not n.alive:
            print(f"  Restarting node-{n.index} ...")
            n.start()
    for n in nodes:
        release_fault(n)
    wait_until_cluster_is_healthy()


def test_a_write_paused_while_its_ownership_moves_is_not_reported_unapplied():
    section("Fault seam: a write paused after its ownership re-check while the ownership moves away")

    owners = collections_by_owner(nodes[0].client_port, DB, "faultown")
    returning = nodes[1]
    if not check("found two collections owned by the node that will leave and return",
                 len(owners.get(returning.index, [])) >= 2, f"owners={owners}"):
        return
    coll, probe = owners[returning.index][0], owners[returning.index][1]
    point = "WRITE_OWNERSHIP_RECHECKED"
    try:
        print(f"  Killing node-{returning.index} so its collections move to a survivor ...")
        returning.kill()
        if not check("the ring settles without it", wait_until_cluster_is_healthy()):
            return
        interim = owner_of(nodes[0].client_port, DB, coll)
        if not check("a survivor took the collection over", interim is not None and interim is not returning):
            return
        if not check("the survivor's own view makes it the owner",
                     wait_until(lambda: owner_of(interim.client_port, DB, coll, attempts=1) is interim,
                                timeout_s=30.0, interval_s=0.5)):
            return
        check_status("ARM_FAULT pauses the write after its ownership re-check",
                     arm_fault(interim, point, "PAUSE", match=collection_subject(coll)), "OK")
        conn = authed(interim.client_port)
        try:
            pending = BackgroundOp(conn, {"type": "SAVE", "databaseName": DB, "collectionName": coll,
                                          "object": {"_id": "own1", "v": 1}})
            if not check_status("the write is parked between its re-check and its apply",
                                await_fault(interim, point), "OK"):
                return
            print(f"  Restarting node-{returning.index} while the write is parked ...")
            returning.start()
            check("the interim owner hands the collection back",
                  wait_until(lambda: owner_of(interim.client_port, DB, probe, attempts=1) is returning,
                             timeout_s=60.0, interval_s=1.0))
            check_status("RELEASE_FAULT lets the parked write go on", release_fault(interim, point), "OK")
            pending.join(60.0)
        finally:
            conn.close()
        answer = pending.response or {}
        check("the write that applied after losing ownership is not answered as refused",
              answer.get("errorCode") != "421-1", f"answer={answer}")
        check_code("it answers outcome unknown", answer, "ERROR", "503-8")
        check("every node converges on one value for the write",
              wait_until(lambda: nodes_agree_on(coll, "own1"), timeout_s=60.0, interval_s=1.0),
              f"held={[stored_documents(n, DB, coll).get('own1') for n in nodes if n.alive]}")
    finally:
        restore_after_fault_test()


def test_a_node_halted_between_a_tombstone_and_its_delete_converges():
    section("Fault seam: a node halted between a delete's tombstone and the delete itself")

    owners = collections_by_owner(nodes[0].client_port, DB, "faultdel")
    victim = nodes[2]
    if not check("found a collection owned by the victim", victim.index in owners, f"owners={owners}"):
        return
    coll = owners[victim.index][0]
    try:
        if not check("the document to delete is on every node",
                     seed_documents(nodes[0].client_port, DB, coll, [{"_id": "del1", "v": 1}])
                     and all_nodes_see(DB, coll, "del1", 1, ports=all_ports(), timeout_s=30.0)):
            return
        check_status("ARM_FAULT halts the owner right after the tombstone",
                     arm_fault(victim, "DELETE_TOMBSTONE_RESERVED", "HALT", match=collection_subject(coll)), "OK")
        answer = delete(victim.client_port, DB, coll, "del1")
        check("the delete is never answered", answer.get("message") == "Server closed connection unexpectedly",
              f"answer={answer}")
        check("the owner halted", victim.wait_for_exit(30.0))
        check("with the fault's exit status, not a crash", victim.proc.returncode == FAULT_HALT_STATUS,
              f"returncode={victim.proc.returncode}")
        check("its tombstone is on disk", "del1" in tombstone_ids(victim, DB, coll))
        check("and the document is still live on it", "del1" in stored_documents(victim, DB, coll))
        print(f"  Restarting node-{victim.index} ...")
        victim.start()
        check("the ring heals", wait_until_cluster_is_healthy())
        check("every node converges on the document",
              wait_until(lambda: nodes_agree_on(coll, "del1"), timeout_s=60.0, interval_s=1.0),
              f"held={[stored_documents(n, DB, coll).get('del1') for n in nodes if n.alive]}")
        resent = delete(nodes[0].client_port, DB, coll, "del1")
        check("a re-sent delete is answered without a server error",
              not str(resent.get("errorCode") or "").startswith("5"), f"answer={resent}")
    finally:
        restore_after_fault_test()


def test_a_dropped_commit_ack_is_redriven_and_the_marker_cleared():
    section("Fault seam: one dropped COMMIT_TX acknowledgement is re-driven by recovery")

    owners = owned_by_every_node("faultack")
    if owners is None:
        return
    edge, participant = nodes[2], nodes[1]
    local, remote = owners[edge.index][0], owners[participant.index][0]
    point = "PEER_REPLY"
    try:
        if not check("the ring is healthy before the transaction starts", wait_until_cluster_is_healthy()):
            return
        check_status("ARM_FAULT drops the participant's next COMMIT_TX acknowledgement",
                     arm_fault(participant, point, "DROP", match="COMMIT_TX"), "OK")
        conn = authed(edge.client_port)
        try:
            tx_id = conn.send({"type": "START_TRANSACTION"}).get("transactionId")
            check_status("a write to the coordinator's own collection", conn.send({
                "type": "SAVE", "databaseName": DB, "collectionName": local,
                "object": {"_id": "ack_edge", "v": 1}}), "OK")
            check_status("a write to the participant's collection", conn.send({
                "type": "SAVE", "databaseName": DB, "collectionName": remote,
                "object": {"_id": "ack_part", "v": 1}}), "OK")
            check_status("the commit is answered OK", conn.send({"type": "COMMIT_TRANSACTION"}), "OK")
        finally:
            conn.close()
        check_status("the acknowledgement was dropped", await_fault(participant, point, 5000), "OK")
        check("so the coordinator kept its marker for recovery",
              f"Not every participant acknowledged the commit of {tx_id}" in bu.read_log(edge.log_path),
              f"transaction={tx_id}")
        check("recovery re-drives the commit and clears the marker",
              wait_until(lambda: not holds_tx_record(edge, tx_id, "coord"), timeout_s=60.0, interval_s=1.0),
              f"transaction={tx_id}")
        check("the participant holds no prepared marker", not holds_tx_record(participant, tx_id, "part"),
              f"transaction={tx_id}")
        check("every node sees the participant's write",
              all_nodes_see(DB, remote, "ack_part", 1, ports=all_ports(), timeout_s=30.0))
        check("and the coordinator's own write",
              all_nodes_see(DB, local, "ack_edge", 1, ports=all_ports(), timeout_s=30.0))
    finally:
        restore_after_fault_test()


def test_a_participant_halted_between_its_commit_markers_is_not_left_in_doubt():
    section("Fault seam: a participant halted between its decided marker and dropping its prepared one")

    owners = owned_by_every_node("faultmark")
    if owners is None:
        return
    edge, victim = nodes[2], nodes[1]
    local, remote = owners[edge.index][0], owners[victim.index][0]
    point, subject = "WAL_APPENDED", "admin|transactions"
    try:
        if not check("the ring is healthy before the transaction starts", wait_until_cluster_is_healthy()):
            return
        conn = authed(edge.client_port)
        try:
            tx_id = conn.send({"type": "START_TRANSACTION"}).get("transactionId")
            check_status("a write to the coordinator's own collection", conn.send({
                "type": "SAVE", "databaseName": DB, "collectionName": local,
                "object": {"_id": "mark_edge", "v": 1}}), "OK")
            check_status("a write to the participant's collection", conn.send({
                "type": "SAVE", "databaseName": DB, "collectionName": remote,
                "object": {"_id": "mark_part", "v": 1}}), "OK")
            check_status("ARM_FAULT pauses the participant at its prepared marker",
                         arm_fault(victim, point, "PAUSE", match=subject), "OK")
            pending = BackgroundOp(conn, {"type": "COMMIT_TRANSACTION"})
            check_status("the participant paused while preparing", await_fault(victim, point, 20000), "OK")
            check_status("ARM_FAULT halts it at the next marker, the decided one",
                         arm_fault(victim, point, "HALT", match=subject), "OK")
            check("the participant halted", victim.wait_for_exit(30.0))
            check("with the fault's exit status, not a crash", victim.proc.returncode == FAULT_HALT_STATUS,
                  f"returncode={victim.proc.returncode}")
            pending.join(60.0)
        finally:
            conn.close()
        print(f"  Restarting node-{victim.index} ...")
        victim.start()
        check("the restarted participant finished the slice",
              wait_until(lambda: stored_documents(victim, DB, remote).get("mark_part", {}).get("v") == 1
                         and not holds_tx_record(victim, tx_id, "localcommit"), timeout_s=60.0),
              f"transaction={tx_id}")
        check("and dropped its prepared marker", not holds_tx_record(victim, tx_id, "part"), f"transaction={tx_id}")
        check("so nothing is listed in doubt", in_doubt_row(victim.client_port, tx_id) is None, f"transaction={tx_id}")
        check("every node sees the participant's write",
              all_nodes_see(DB, remote, "mark_part", 1, ports=all_ports(), timeout_s=30.0))
        check("and the coordinator's own write",
              all_nodes_see(DB, local, "mark_edge", 1, ports=all_ports(), timeout_s=30.0))
    finally:
        restore_after_fault_test()


def test_a_write_inside_an_anti_entropy_round_trip_is_not_undone():
    section("Fault seam: writes landing between an anti-entropy snapshot and its repair")

    owners = collections_by_owner(nodes[0].client_port, DB, "faultae")
    owner, replica = nodes[0], nodes[1]
    if not check("found a collection owned by node-0", owner.index in owners, f"owners={owners}"):
        return
    coll = owners[owner.index][0]
    point = "ANTI_ENTROPY_SNAPSHOTTED"
    try:
        if not check("the starting documents are on every node",
                     seed_documents(owner.client_port, DB, coll, [{"_id": "ae1", "v": 1}, {"_id": "ae2", "v": 1}])
                     and all_nodes_see(DB, coll, "ae2", 1, ports=all_ports(), timeout_s=30.0)):
            return
        check_status("ARM_FAULT pauses the replica's next anti-entropy round after its snapshot",
                     arm_fault(replica, point, "PAUSE", match=collection_subject(coll)), "OK")
        if not check_status("the replica's round is parked holding a stale snapshot",
                            await_fault(replica, point, 20000), "OK"):
            return
        check_status("an update lands inside the round", save(owner.client_port, DB, coll, {"_id": "ae1", "v": 2}),
                     "OK")
        check_status("a delete lands inside the round", delete(owner.client_port, DB, coll, "ae2"), "OK")
        check_status("a create lands inside the round", save(owner.client_port, DB, coll, {"_id": "ae3", "v": 1}),
                     "OK")

        def _replica_has_the_writes():
            held = stored_documents(replica, DB, coll)
            return held.get("ae1", {}).get("v") == 2 and "ae2" not in held and "ae3" in held

        check("replication reached the replica while its round was parked",
              wait_until(_replica_has_the_writes, timeout_s=15.0))
        check_status("RELEASE_FAULT lets the round repair from its stale snapshot", release_fault(replica, point),
                     "OK")

        def _every_node_has_the_writes():
            for n in nodes:
                held = stored_documents(n, DB, coll)
                if held.get("ae1", {}).get("v") != 2 or "ae2" in held or "ae3" not in held:
                    return False
            return True

        check("no write is undone across the next anti-entropy rounds",
              holds_throughout(_every_node_has_the_writes, seconds=ANTI_ENTROPY_INTERVAL_S * 3),
              f"held={[sorted(stored_documents(n, DB, coll)) for n in nodes]}")
    finally:
        restore_after_fault_test()


GROUPS = {
    "replication": [
        test_formation_and_quorum_writes,
        test_ddl_replication,
        test_write_replication_and_read_routing,
        test_bulk_delete_and_upsert,
        test_index_replication,
        test_reindex_rebuilds_the_index_on_every_node,
        test_reindex_primary_key_rebuilds_the_pk_index_on_every_node,
        test_user_and_permission_replication,
        test_grants_are_pruned_on_every_node,
        test_a_deleted_users_ownership_is_pruned_on_every_node,
        test_password_change_deauthenticates_sessions_on_every_node,
        test_a_client_cannot_advance_the_write_clock_through_an_incarnation,
        test_a_forged_stamp_is_ignored_and_a_real_one_still_replicates,
        test_forged_peer_frames_are_refused,
        test_single_node_transaction,
        test_multi_collection_transaction,
        test_a_transactional_join_sees_a_participants_buffered_writes,
        test_a_transactional_join_across_two_written_nodes_is_refused,
        test_a_replica_listener_never_sees_a_partial_transaction,
        test_admin_transaction_ops,
        test_lock_timeout_aborts_the_whole_transaction,
        test_admin_ops_replicate_in_commit_order,
        test_an_unreadable_definition_is_not_deleted_on_peers,
        test_forwarded_write_ignores_a_client_supplied_trigger_depth,
        test_an_escaped_trigger_depth_does_not_suppress_the_owners_trigger,
    ],
    "scripts": [
        test_formation_and_quorum_writes,
        test_a_procedure_bound_by_another_definer_is_protected_through_any_node,
        test_a_revoked_definer_is_disabled_on_every_node,
        test_script_placement_forwards_runs,
        test_script_run_history_still_reaches_the_collection_owner,
        test_forwarded_script_reads_and_writes,
        test_forwarded_script_relays_its_stack,
        test_forwarded_script_preserves_the_acting_user,
        test_long_forwarded_script_is_not_cut_off,
        test_script_routing_disabled_stays_local,
        test_script_placement_prefers_the_collection_owner,
        test_script_placement_locality_weight_zero_is_load_only,
        test_script_placement_falls_back_when_the_target_dies,
        test_before_hook_runs_on_the_owner,
        test_script_control_is_cluster_wide,
    ],
    "failover": [
        test_formation_and_quorum_writes,
        test_write_replication_and_read_routing,
        test_schedule_replication_and_single_firing,
        arrange_admin_changes_for_a_downed_node,
        test_node_failure_quorum_maintained,
        test_schedule_failover,
        make_admin_changes_while_a_node_is_down,
        test_node_rejoin,
        test_a_schema_saved_while_a_node_is_down_reaches_it,
        test_a_procedure_deleted_while_a_node_is_down_stays_deleted,
        test_a_password_changed_while_a_node_is_down_reaches_it,
        test_permission_changes_and_dropped_indexes_replicate,
        test_a_grant_on_a_collection_dropped_while_a_node_is_down_is_pruned_there,
        test_schedule_rejoin_catch_up,
        test_a_restarted_participant_does_not_commit_half_a_transaction,
        test_restart_does_not_regress_write_versions,
        test_a_fresh_tombstone_survives_anti_entropy_sweeps,
        test_delete_of_an_unreplicated_id_does_not_destroy_it,
        test_a_rejoining_node_repairs_one_change_among_many_documents,
    ],
    "faults": [
        test_formation_and_quorum_writes,
        test_a_write_paused_while_its_ownership_moves_is_not_reported_unapplied,
        test_a_node_halted_between_a_tombstone_and_its_delete_converges,
        test_a_dropped_commit_ack_is_redriven_and_the_marker_cleared,
        test_a_participant_halted_between_its_commit_markers_is_not_left_in_doubt,
        test_a_write_inside_an_anti_entropy_round_trip_is_not_undone,
        test_a_prepared_participant_resolves_without_its_coordinator,
        test_a_post_prepare_write_survives_2pc_recovery,
        test_a_participant_killed_mid_commit_finishes_without_its_coordinator,
        test_a_trigger_fires_once_despite_a_replication_timeout,
        test_a_trigger_writing_only_to_another_owner_consumes_its_run,
        test_cluster_port_reset_flood_is_rate_limited,
    ],
    "recreate": [
        test_formation_and_quorum_writes,
        test_drop_and_recreate_does_not_resurrect_documents,
        test_drop_database_and_recreate_does_not_resurrect_documents,
        test_drop_then_rejoin_then_recreate_does_not_resurrect_documents,
        test_drop_database_then_rejoin_then_recreate_does_not_resurrect_documents,
        test_rapid_collection_creates_are_not_quarantined,
        test_recreating_an_existing_collection_keeps_its_documents,
        test_a_node_that_missed_the_last_database_drop_converges,
        test_a_populated_standalone_node_keeps_its_data_when_a_fresh_node_joins,
    ],
}


def tests_for(group):
    if group is not None:
        return GROUPS[group]
    ordered = []
    for tests in GROUPS.values():
        ordered.extend(test for test in tests if test not in ordered)
    return ordered


def main():
    parser = argparse.ArgumentParser(description="Multi-node clustering integration suite")
    parser.add_argument("--group", choices=sorted(GROUPS),
                        help="run one group on its own cluster (CI runs each as a clustering-<group> leg); "
                             "omit to run every group in sequence on one cluster")
    group = parser.parse_args().group
    bu.banner("Clustering (multi-node) integration suite" + (f" — {group}" if group else ""))

    bu.require_jar()

    base_dir = tempfile.mkdtemp(prefix="lwnrdb-cluster-")
    print(f"  Base working dir: {base_dir}")
    for i in range(NODE_COUNT):
        nodes.append(Node(i, base_dir))

    try:
        print("  Starting nodes ...")
        for n in nodes:
            n.start()
            print(f"    node-{n.index}: client={n.client_port} cluster={n.cluster_port}")

        wait_for_cluster()

        for test in tests_for(group):
            test()
    except BaseException:
        print("\n[ERROR] the suite raised - dumping node logs", file=sys.stderr)
        dump_all_logs()
        raise
    finally:
        for n in nodes:
            n.stop()

    bu.summary(on_failure=dump_all_logs)


if __name__ == "__main__":
    main()
