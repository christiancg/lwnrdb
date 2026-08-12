"""End-to-end tests for multi-node clustering.

Like the TLS suite, this script is **self-contained**: it starts its own
three-node LWNRDB cluster (each node in its own working directory, on its own
client/cluster ports) and drives it entirely over the normal client protocol.
Nothing outside this script needs to be running, so it uses
``start_server: false`` in the CI matrix and manages the whole cluster lifecycle
itself (tracked subprocess handles, never pgrep).

Topology: node-0 is the seed (empty ``clusterSeeds``); node-1 and node-2 join via
node-0. ``clusterExpectedSize=3`` so the write-quorum majority is 2. Failure-
detection and anti-entropy timers are turned down aggressively so the failure /
rejoin cases converge in a few seconds rather than the production defaults.

What it verifies (all through a client connected to an arbitrary node — the whole
point of clustering is that any node serves any request transparently):

  * cluster formation + synchronous quorum writes;
  * DDL replication (CREATE/DROP database, collection, index) to every node;
  * document write replication + read routing (write via any node, read from all);
  * BULK_SAVE / DELETE / upsert replication and cross-node consistency;
  * user + permission replication (record-shipped, so the password hash is
    byte-identical — a user created on one node authenticates on the others);
  * transactions under clustering: single-node commit/rollback, a multi-collection
    transaction committed atomically and visible cluster-wide, read-your-writes;
  * admin transaction ops (LIST_TRANSACTIONS, in-doubt count in GET_DATABASE_STATS);
  * node failure with quorum maintained (kill a node, writes + reads keep working);
  * node rejoin (restart it, the cluster serves consistent data again).
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import time

HOST = "127.0.0.1"
BASE_CLIENT_PORT = int(os.environ.get("CLUSTER_TEST_CLIENT_PORT", "8991"))
BASE_CLUSTER_PORT = int(os.environ.get("CLUSTER_TEST_CLUSTER_PORT", "9991"))
NODE_COUNT = 3

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "administrator"
CLUSTER_SECRET = "integration-cluster-secret"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

JAR = "target/lwnrdb-1.0-SNAPSHOT.jar"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

failures = 0
nodes: list["Node"] = []


# ── reporting helpers (mirror the other suites) ──────────────────────────────

def section(title: str):
    print(f"\n{'─' * 60}")
    print(f"  {title}")
    print(f"{'─' * 60}")


def check(label: str, response: dict, expected_status: str):
    global failures
    actual = response.get("status")
    ok = actual == expected_status
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         expected={expected_status}  got={actual}  msg={response.get('message', '')!r}")
    if not ok:
        failures += 1


def check_true(label: str, ok: bool, detail: str = ""):
    global failures
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    if detail:
        print(f"         {detail}")
    if not ok:
        failures += 1


def check_code(label: str, response: dict, expected_status: str, expected_code: str):
    global failures
    actual_status = response.get("status")
    actual_code = response.get("errorCode")
    ok = actual_status == expected_status and actual_code == expected_code
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         expected={expected_status}/{expected_code}  "
          f"got={actual_status}/{actual_code}  msg={response.get('message', '')!r}")
    if not ok:
        failures += 1


def _dig(obj, path: str):
    cur = obj
    for part in path.split("."):
        if cur is None:
            return None
        if part.isdigit() and isinstance(cur, list):
            idx = int(part)
            cur = cur[idx] if 0 <= idx < len(cur) else None
        elif isinstance(cur, dict):
            cur = cur.get(part)
        else:
            return None
    return cur


def check_field(label: str, response: dict, path: str, expected):
    global failures
    actual = _dig(response, path)
    ok = actual == expected
    icon = PASS if ok else FAIL
    print(f"  [{icon}] {label}")
    print(f"         path={path}  expected={expected!r}  got={actual!r}")
    if not ok:
        failures += 1


# ── protocol helper ──────────────────────────────────────────────────────────

def send(s, f, payload: dict) -> dict:
    try:
        s.sendall((json.dumps(payload) + "\n").encode())
    except (BrokenPipeError, OSError):
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    try:
        raw = f.readline().decode().strip()
    except (OSError, ConnectionError):
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    if not raw:
        return {"status": "ERROR", "message": "Server closed connection unexpectedly"}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"status": "ERROR", "message": raw}


class Conn:
    """A single client connection to a node (by client port)."""

    def __init__(self, port: int):
        self.s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.s.settimeout(20)
        self.s.connect((HOST, port))
        self.f = self.s.makefile("rb")

    def __enter__(self):
        return self.s, self.f

    def __exit__(self, *_):
        self.close()

    def close(self):
        try:
            self.f.close()
        except OSError:
            pass
        try:
            self.s.close()
        except OSError:
            pass


def authed(port: int) -> Conn:
    """Open a connection to the given client port and authenticate as admin."""
    conn = Conn(port)
    r = send(conn.s, conn.f, {"type": "AUTHENTICATE",
                              "username": ADMIN_USERNAME, "password": ADMIN_PASSWORD})
    if r.get("status") != "OK":
        conn.close()
        raise RuntimeError(f"admin auth failed on port {port}: {r}")
    return conn


# ── operation wrappers (take a client port) ──────────────────────────────────

def op(port: int, payload: dict) -> dict:
    conn = authed(port)
    try:
        return send(conn.s, conn.f, payload)
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


def filter_step(field, fop, value):
    return {"type": "FILTER", "operator": {"fieldOperatorType": fop, "field": field, "value": value}}


def ids_of(response) -> list:
    return sorted(d.get("_id") for d in (response.get("results") or []))


# ── convergence helpers ──────────────────────────────────────────────────────

def all_ports() -> list:
    return [n.client_port for n in nodes if n.alive]


def wait_until(predicate, timeout_s: float, interval_s: float = 0.4) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            if predicate():
                return True
        except (OSError, RuntimeError):
            pass
        time.sleep(interval_s)
    try:
        return bool(predicate())
    except (OSError, RuntimeError):
        return False


def all_nodes_see(db, coll, _id, expected_value, field="v", ports=None, timeout_s=15.0) -> bool:
    """Every listed node's FIND_BY_ID must return the expected field value.

    Reads route to the owner, so consistency is normally immediate; the small
    poll absorbs transient routing during membership flux / anti-entropy."""
    ports = ports if ports is not None else all_ports()

    def _seen():
        for p in ports:
            r = find_by_id(p, db, coll, _id)
            if r.get("status") != "OK" or _dig(r, f"object.{field}") != expected_value:
                return False
        return True

    return wait_until(_seen, timeout_s)


CANARY_DB = "cluster_canary_db"
CANARY_COLL = "canary"


def wait_for_cluster(timeout_s: float = 60.0):
    """Block until all nodes have joined and route/replicate consistently.

    Quorum needs 2 of 3 nodes, so the canary SAVE fails (503-2) until enough
    nodes are up; once a SAVE commits AND every node reads back the current
    value, the ring has converged on all nodes and routing works everywhere."""
    print(f"  Waiting for the {NODE_COUNT}-node cluster to converge ...")
    op(nodes[0].client_port, {"type": "CREATE_DATABASE", "databaseName": CANARY_DB})
    op(nodes[0].client_port, {"type": "CREATE_COLLECTION",
                              "databaseName": CANARY_DB, "collectionName": CANARY_COLL})
    counter = {"n": 0}

    def _converged():
        counter["n"] += 1
        v = counter["n"]
        r = save(nodes[0].client_port, CANARY_DB, CANARY_COLL, {"_id": "canary", "v": v})
        if r.get("status") != "OK":
            return False
        return all_nodes_see(CANARY_DB, CANARY_COLL, "canary", v, timeout_s=1.0)

    if not wait_until(_converged, timeout_s, interval_s=1.0):
        for n in nodes:
            n.dump_log()
        raise RuntimeError("cluster did not converge in time")
    print("  Cluster converged.")


# ── node lifecycle ───────────────────────────────────────────────────────────

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
        seeds = "" if self.index == 0 else f"{HOST}:{BASE_CLUSTER_PORT}"
        cfg = (
            f"port={self.client_port}\n"
            "filePath=db\n"
            "logPath=logs\n"
            "maxMemory=256mb\n"
            f"defaultAdminUsername={ADMIN_USERNAME}\n"
            f"defaultAdminPassword={ADMIN_PASSWORD}\n"
            "clusterEnabled=true\n"
            f"clusterPort={self.cluster_port}\n"
            "clusterBindAddress=127.0.0.1\n"
            "clusterAdvertisedAddress=127.0.0.1\n"
            f"clusterSeeds={seeds}\n"
            "clusterExpectedSize=3\n"
            f"clusterSecret={CLUSTER_SECRET}\n"
            "gossipIntervalMs=500\n"
            "suspectTimeoutMs=2000\n"
            "deadTimeoutMs=4000\n"
            "replicationAckTimeoutMs=5000\n"
            "virtualNodesPerNode=128\n"
            "readFallbackToLocal=true\n"
            "antiEntropyIntervalMs=3000\n"
            "tombstoneRetentionMs=86400000\n"
        )
        with open(os.path.join(self.work_dir, "lwnrdb.cfg"), "w") as fp:
            fp.write(cfg)

    def start(self):
        jar = os.path.join(REPO_ROOT, JAR)
        log = open(self.log_path, "ab")
        self.proc = subprocess.Popen(
            ["java", "-Xmx512m", "-jar", jar],
            stdout=log, stderr=log, cwd=self.work_dir)
        deadline = time.time() + 60.0
        while time.time() < deadline:
            if self._client_port_open():
                self.alive = True
                return
            if self.proc.poll() is not None:
                break
            time.sleep(0.2)
        self.dump_log()
        self.proc.kill()
        raise RuntimeError(f"node-{self.index} did not come up in time")

    def _client_port_open(self) -> bool:
        try:
            with socket.create_connection((HOST, self.client_port), timeout=0.5):
                return True
        except OSError:
            return False

    def kill(self):
        """Hard crash (SIGKILL) — simulate an abrupt node failure."""
        if self.proc is not None:
            self.proc.kill()
            try:
                self.proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                pass
        self.alive = False

    def stop(self):
        if self.proc is not None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.alive = False

    def dump_log(self):
        try:
            with open(self.log_path, "rb") as fp:
                tail = fp.read()[-4000:].decode(errors="replace")
            print(f"--- node-{self.index} log tail ---\n{tail}\n--- end ---", file=sys.stderr)
        except OSError:
            pass


# ══════════════════════════════════════════════════════════════════════════
# Tests
# ══════════════════════════════════════════════════════════════════════════

DB = "cluster_test_db"


def test_formation_and_quorum_writes():
    section("Cluster formation + synchronous quorum writes")

    p0 = nodes[0].client_port
    check("CREATE_DATABASE on the seed node commits under quorum", create_db(p0, DB), "OK")
    check("CREATE_COLLECTION commits under quorum", create_coll(p0, DB, "docs"), "OK")
    r = save(p0, DB, "docs", {"_id": "q1", "v": 1})
    check("SAVE reaches the replication quorum (not 503-2/503-3)", r, "OK")


def test_ddl_replication():
    section("DDL replication — CREATE/DROP propagate to every node")

    # Create a database + collection on node-1; it must appear on all nodes.
    check("CREATE_DATABASE via node-1", create_db(nodes[1].client_port, "ddl_repl_db"), "OK")
    check("CREATE_COLLECTION via node-1", create_coll(nodes[1].client_port, "ddl_repl_db", "widgets"), "OK")

    seen_db = wait_until(
        lambda: all("ddl_repl_db" in (list_databases(p).get("databases") or []) for p in all_ports()),
        timeout_s=15.0)
    check_true("new database is listed on every node", seen_db)

    seen_coll = wait_until(
        lambda: all("widgets" in (list_collections(p, "ddl_repl_db").get("collections") or [])
                    for p in all_ports()),
        timeout_s=15.0)
    check_true("new collection is listed on every node", seen_coll)

    # A duplicate CREATE routed through a different node still conflicts (shared metadata).
    check_code("duplicate CREATE_DATABASE via node-2 conflicts (409-2)",
               create_db(nodes[2].client_port, "ddl_repl_db"), "ERROR", "409-2")

    # DROP replicates too.
    check("DROP_DATABASE via node-2", drop_db(nodes[2].client_port, "ddl_repl_db"), "OK")
    dropped = wait_until(
        lambda: all("ddl_repl_db" not in (list_databases(p).get("databases") or []) for p in all_ports()),
        timeout_s=15.0)
    check_true("dropped database disappears from every node", dropped)


def test_write_replication_and_read_routing():
    section("Document write replication + read routing (write anywhere, read everywhere)")

    create_coll(nodes[0].client_port, DB, "routed")

    # Write the same collection via each node in turn; every write must be visible on all nodes.
    for i in range(NODE_COUNT):
        _id = f"w{i}"
        r = save(nodes[i].client_port, DB, "routed", {"_id": _id, "v": i * 10})
        check(f"SAVE via node-{i} commits", r, "OK")
        check_true(f"doc written via node-{i} is visible on every node",
                   all_nodes_see(DB, "routed", _id, i * 10),
                   detail=f"_id={_id}")

    # AGGREGATE (COUNT) from a non-writing node reflects all replicated writes.
    r = aggregate(nodes[2].client_port, DB, "routed", [{"type": "COUNT"}])
    check_field("COUNT via node-2 reflects all cross-node writes", r, "results.0.count", NODE_COUNT)


def test_bulk_delete_and_upsert():
    section("BULK_SAVE / DELETE / upsert replication")

    create_coll(nodes[0].client_port, DB, "bulk")

    r = bulk_save(nodes[1].client_port, DB, "bulk",
                  [{"_id": "b1", "v": 1}, {"_id": "b2", "v": 2}, {"_id": "b3", "v": 3}])
    check("BULK_SAVE via node-1 commits", r, "OK")
    check_true("all bulk docs are visible on every node",
               all_nodes_see(DB, "bulk", "b1", 1) and all_nodes_see(DB, "bulk", "b2", 2)
               and all_nodes_see(DB, "bulk", "b3", 3))

    # Upsert (same _id) via a different node overwrites, and the new value replicates.
    check("upsert b1 via node-2", save(nodes[2].client_port, DB, "bulk", {"_id": "b1", "v": 99}), "OK")
    check_true("upserted value is visible on every node", all_nodes_see(DB, "bulk", "b1", 99))

    # DELETE via yet another node removes it everywhere (tombstone → no resurrection).
    check("DELETE b2 via node-0", delete(nodes[0].client_port, DB, "bulk", "b2"), "OK")

    def _gone():
        return all(find_by_id(p, DB, "bulk", "b2").get("status") == "NOT_FOUND" for p in all_ports())

    check_true("deleted doc is NOT_FOUND on every node", wait_until(_gone, timeout_s=15.0))


def test_index_replication():
    section("Index replication (CREATE_INDEX is DDL — applied on every node)")

    create_coll(nodes[0].client_port, DB, "indexed")
    bulk_save(nodes[0].client_port, DB, "indexed",
              [{"_id": "e1", "email": "a@x.io"}, {"_id": "e2", "email": "b@x.io"}])
    check("CREATE_INDEX via node-1", create_index(nodes[1].client_port, DB, "indexed", "email"), "OK")

    # The index shows up in GET_DATABASE_STATS on every node.
    def _indexed_everywhere():
        for p in all_ports():
            r = op(p, {"type": "GET_DATABASE_STATS"})
            found = False
            for d in _dig(r, "stats.databases") or []:
                if d.get("name") != DB:
                    continue
                for c in d.get("collections") or []:
                    if c.get("name") == "indexed" and "email" in (c.get("indexes") or []):
                        found = True
            if not found:
                return False
        return True

    check_true("email index is registered on every node", wait_until(_indexed_everywhere, timeout_s=20.0))

    # Index-backed query returns the right result from every node.
    def _query_ok():
        return all(ids_of(aggregate(p, DB, "indexed", [filter_step("email", "EQUALS", "a@x.io")])) == ["e1"]
                   for p in all_ports())

    check_true("index-backed query is correct on every node", wait_until(_query_ok, timeout_s=15.0))


def test_user_and_permission_replication():
    section("User + permission replication (record-shipped — auth works on every node)")

    # A read-only user on the shared DB, created via node-0.
    r = op(nodes[0].client_port, {
        "type": "CREATE_USER", "username": "cluster_reader", "password": "cluster_reader1234",
        "admin": False, "globalPermissions": [], "databasePermissions": {DB: "READ"},
        "collectionPermissions": {}})
    check("CREATE_USER via node-0", r, "OK")

    # The user (same salted hash on every node) authenticates on all nodes.
    def _auth_everywhere():
        for p in all_ports():
            c = Conn(p)
            try:
                a = send(c.s, c.f, {"type": "AUTHENTICATE",
                                    "username": "cluster_reader", "password": "cluster_reader1234"})
                if a.get("status") != "OK":
                    return False
            finally:
                c.close()
        return True

    check_true("replicated user authenticates on every node", wait_until(_auth_everywhere, timeout_s=15.0))

    # Permissions replicate too: the read-only user cannot write on any node.
    for i in range(NODE_COUNT):
        c = Conn(nodes[i].client_port)
        try:
            send(c.s, c.f, {"type": "AUTHENTICATE",
                            "username": "cluster_reader", "password": "cluster_reader1234"})
            w = send(c.s, c.f, {"type": "SAVE", "databaseName": DB, "collectionName": "routed",
                                "object": {"_id": "nope", "v": 0}})
            check_code(f"read-only user SAVE via node-{i} is forbidden (403-1)", w, "FORBIDDEN", "403-1")
            rd = send(c.s, c.f, {"type": "AGGREGATE", "databaseName": DB, "collectionName": "routed",
                                 "aggregationSteps": [{"type": "COUNT"}]})
            check(f"read-only user can still read via node-{i}", rd, "OK")
        finally:
            c.close()

    op(nodes[0].client_port, {"type": "DELETE_USER", "username": "cluster_reader"})


def test_single_node_transaction():
    section("Transaction under clustering — commit + rollback + read-your-writes (5a)")

    create_coll(nodes[0].client_port, DB, "txn")

    # Commit path via node-1, with read-your-writes inside the transaction.
    conn = authed(nodes[1].client_port)
    try:
        check("START_TRANSACTION via node-1", send(conn.s, conn.f, {"type": "START_TRANSACTION"}), "OK")
        check("buffered SAVE inside txn", send(conn.s, conn.f, {
            "type": "SAVE", "databaseName": DB, "collectionName": "txn",
            "object": {"_id": "t1", "v": 7}}), "OK")
        ryw = send(conn.s, conn.f, {"type": "FIND_BY_ID", "databaseName": DB,
                                    "collectionName": "txn", "_id": "t1"})
        check_field("read-your-writes sees the buffered doc", ryw, "object.v", 7)
        check("COMMIT_TRANSACTION", send(conn.s, conn.f, {"type": "COMMIT_TRANSACTION"}), "OK")
    finally:
        conn.close()

    check_true("committed txn doc is visible on every node", all_nodes_see(DB, "txn", "t1", 7))

    # Rollback path via node-2 discards the buffered write cluster-wide.
    conn = authed(nodes[2].client_port)
    try:
        check("START_TRANSACTION via node-2", send(conn.s, conn.f, {"type": "START_TRANSACTION"}), "OK")
        check("buffered SAVE inside txn", send(conn.s, conn.f, {
            "type": "SAVE", "databaseName": DB, "collectionName": "txn",
            "object": {"_id": "t2", "v": 8}}), "OK")
        check("ROLLBACK_TRANSACTION", send(conn.s, conn.f, {"type": "ROLLBACK_TRANSACTION"}), "OK")
    finally:
        conn.close()

    def _rolled_back():
        return all(find_by_id(p, DB, "txn", "t2").get("status") == "NOT_FOUND" for p in all_ports())

    check_true("rolled-back doc never appears on any node", wait_until(_rolled_back, timeout_s=10.0))


def test_multi_collection_transaction():
    section("Multi-collection transaction — atomic commit visible cluster-wide (5a/5b)")

    # Several collections: with 3 nodes these very likely hash to different owners,
    # exercising the cross-owner 2PC path (and the single-owner fast path otherwise).
    mc = ["mc_a", "mc_b", "mc_c"]
    for c in mc:
        create_coll(nodes[0].client_port, DB, c)

    conn = authed(nodes[0].client_port)
    try:
        check("START_TRANSACTION (multi-collection)", send(conn.s, conn.f, {"type": "START_TRANSACTION"}), "OK")
        for c in mc:
            r = send(conn.s, conn.f, {"type": "SAVE", "databaseName": DB, "collectionName": c,
                                      "object": {"_id": "x", "v": 5}})
            check(f"buffered SAVE into {c}", r, "OK")
        check("COMMIT spans all involved owners atomically",
              send(conn.s, conn.f, {"type": "COMMIT_TRANSACTION"}), "OK")
    finally:
        conn.close()

    for c in mc:
        check_true(f"committed doc in {c} is visible on every node",
                   all_nodes_see(DB, c, "x", 5), detail=f"collection={c}")

    # Atomic rollback across all collections.
    conn = authed(nodes[0].client_port)
    try:
        check("START_TRANSACTION (rollback)", send(conn.s, conn.f, {"type": "START_TRANSACTION"}), "OK")
        for c in mc:
            send(conn.s, conn.f, {"type": "SAVE", "databaseName": DB, "collectionName": c,
                                  "object": {"_id": "y", "v": 6}})
        check("ROLLBACK_TRANSACTION", send(conn.s, conn.f, {"type": "ROLLBACK_TRANSACTION"}), "OK")
    finally:
        conn.close()

    def _none_have_y():
        for c in mc:
            for p in all_ports():
                if find_by_id(p, DB, c, "y").get("status") != "NOT_FOUND":
                    return False
        return True

    check_true("rolled-back multi-collection write appears nowhere", wait_until(_none_have_y, timeout_s=10.0))


def test_admin_transaction_ops():
    section("Admin transaction ops — LIST_TRANSACTIONS + in-doubt count (steady state)")

    for i in range(NODE_COUNT):
        r = op(nodes[i].client_port, {"type": "LIST_TRANSACTIONS"})
        check(f"LIST_TRANSACTIONS via node-{i} returns OK", r, "OK")
        check_true(f"no in-doubt transactions in steady state (node-{i})",
                   (r.get("transactions") or []) == [], detail=f"transactions={r.get('transactions')!r}")

    r = op(nodes[0].client_port, {"type": "GET_DATABASE_STATS"})
    check("GET_DATABASE_STATS returns OK", r, "OK")
    check_field("in-doubt transaction count is zero", r, "stats.inDoubtTransactions.count", 0)


def test_node_failure_quorum_maintained():
    section("Node failure — quorum (2 of 3) maintained, writes + reads keep working")

    victim = nodes[2]
    print(f"  Killing node-{victim.index} (hard crash) ...")
    victim.kill()

    # Wait for the survivors to detect the death and reassign ownership, then a
    # write+read cycle over all collections must succeed with just 2 nodes.
    # CREATE_COLLECTION is a coordinated admin op: right after the kill, the
    # admin-coordinator hash-ring slot may still resolve to the dead node until
    # SUSPECT->DEAD is detected, so retry it (idempotent) instead of firing once.
    def _write_read_ok():
        if create_coll(nodes[0].client_port, DB, "after_fail").get("status") != "OK":
            return False
        r = save(nodes[0].client_port, DB, "after_fail", {"_id": "f1", "v": 111})
        if r.get("status") != "OK":
            return False
        return all_nodes_see(DB, "after_fail", "f1", 111, ports=all_ports(), timeout_s=1.0)

    ok = wait_until(_write_read_ok, timeout_s=30.0, interval_s=1.0)
    check_true("with one node down the surviving majority still commits + serves reads", ok)

    # A previously-written doc is still readable from the survivors.
    check_true("pre-failure data still readable from the survivors",
               all_nodes_see(DB, "routed", "w0", 0, ports=all_ports(), timeout_s=15.0))


def test_node_rejoin():
    section("Node rejoin — restarted node reconverges and the cluster is consistent")

    rejoiner = nodes[2]
    print(f"  Restarting node-{rejoiner.index} ...")
    rejoiner.start()

    # Once it rejoins the ring, routing works through it again: a fresh write is
    # visible when read via the rejoined node (and all others).
    def _rejoined():
        v = int(time.time()) % 100000
        r = save(nodes[0].client_port, DB, "routed", {"_id": "rejoin", "v": v})
        if r.get("status") != "OK":
            return False
        return all_nodes_see(DB, "routed", "rejoin", v, ports=all_ports(), timeout_s=2.0)

    check_true("rejoined node serves consistent reads and the cluster is healthy",
               wait_until(_rejoined, timeout_s=45.0, interval_s=1.0))


# ══════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════

def main():
    print("\n" + "═" * 60)
    print("  LWNRDB — Clustering (multi-node) integration suite")
    print("═" * 60)

    jar = os.path.join(REPO_ROOT, JAR)
    if not os.path.isfile(jar):
        print(f"\n[ERROR] Jar not found at {jar}. Build it first: mvn package -DskipTests\n")
        sys.exit(1)

    base_dir = tempfile.mkdtemp(prefix="lwnrdb-cluster-")
    print(f"  Base working dir: {base_dir}")
    for i in range(NODE_COUNT):
        nodes.append(Node(i, base_dir))

    try:
        # Start the seed first, then the joiners.
        print("  Starting nodes ...")
        for n in nodes:
            n.start()
            print(f"    node-{n.index}: client={n.client_port} cluster={n.cluster_port}")

        wait_for_cluster()

        test_formation_and_quorum_writes()
        test_ddl_replication()
        test_write_replication_and_read_routing()
        test_bulk_delete_and_upsert()
        test_index_replication()
        test_user_and_permission_replication()
        test_single_node_transaction()
        test_multi_collection_transaction()
        test_admin_transaction_ops()
        # Failure / rejoin last: they degrade then restore the cluster.
        test_node_failure_quorum_maintained()
        test_node_rejoin()

        # Cleanup (best-effort).
        drop_db(nodes[0].client_port, DB)
        drop_db(nodes[0].client_port, CANARY_DB)
    finally:
        for n in nodes:
            n.stop()

    print("\n" + "═" * 60)
    if failures == 0:
        print("  \033[92mAll checks passed.\033[0m")
    else:
        print(f"  \033[91m{failures} check(s) FAILED.\033[0m")
        for n in nodes:
            n.dump_log()
    print("═" * 60 + "\n")

    sys.exit(0 if failures == 0 else 1)


if __name__ == "__main__":
    main()
