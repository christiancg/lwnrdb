#!/usr/bin/env python3
from __future__ import annotations

import argparse
import collections
import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import base_utils as b

REPO = pathlib.Path(__file__).resolve().parents[2]
JAR = REPO / "target" / "lwnrdb-1.0-SNAPSHOT.jar"
DEFAULT_JAVA_HOME = "/Users/christiancoello/Library/Java/JavaVirtualMachines/openjdk-27/Contents/Home"
DB, COLL, REFS = "prof", "docs", "refs"
DOCS = 50000
BULK = 5000

BUCKETS = (
    ("field-index full reload", "FieldIndexLoader.load"),
    ("field-index line rewrite", "IndexLine"),
    ("field-index batch", "FieldIndexBatch"),
    ("pk.idx rewrite", "PkIndexStore"),
    ("page in-place update", "PageCompactor"),
    ("request parse", "JsonReader"),
    ("response serialisation", "EJson.toJson"),
    ("page read", "DocumentPageStore"),
)

TARGETS = {
    "save_update": ("MB per op", 15.0),
    "bulk_insert": ("seconds", 60.0),
    "insert_noindex": ("KB per doc", 5.0),
    "cold_scan": ("KB per doc", 3.0),
}


def java_bin() -> pathlib.Path:
    return pathlib.Path(os.environ.get("JAVA_HOME", DEFAULT_JAVA_HOME)) / "bin"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    return port


class Server:
    def __init__(self, max_memory: str):
        self.dir = pathlib.Path(tempfile.mkdtemp(prefix="lwnrdb-profile-"))
        self.port = free_port()
        cfg = (REPO / "lwnrdb.cfg").read_text().replace("port=8989", f"port={self.port}")
        cfg += f"\nmaxMemory={max_memory}\ndefaultAdminPassword={b.PASSWORD}\n"
        (self.dir / "lwnrdb.cfg").write_text(cfg)
        log = open(self.dir / "server.out", "w")
        self.proc = subprocess.Popen([str(java_bin() / "java"), "-Xmx1g", "-jar", str(JAR)], cwd=self.dir,
                                     stdout=log, stderr=subprocess.STDOUT)
        deadline = time.time() + 60
        while time.time() < deadline:
            try:
                socket.create_connection(("127.0.0.1", self.port), timeout=1).close()
                break
            except OSError:
                time.sleep(0.5)
        b.configure(port=self.port)

    def jcmd(self, *args: str) -> str:
        return subprocess.run([str(java_bin() / "jcmd"), str(self.proc.pid), *args], capture_output=True,
                              text=True).stdout

    def cpu_seconds(self) -> float:
        out = subprocess.run(["ps", "-o", "time=", "-p", str(self.proc.pid)], capture_output=True,
                             text=True).stdout.strip()
        minutes, seconds = out.split(":")[-2:]
        return int(minutes) * 60 + float(seconds)

    def await_idle(self) -> None:
        last = self.cpu_seconds()
        while True:
            time.sleep(2)
            now = self.cpu_seconds()
            if now - last < 0.2:
                return
            last = now

    def stop(self) -> None:
        self.proc.terminate()
        try:
            self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        shutil.rmtree(self.dir, ignore_errors=True)


def document(i: int) -> dict:
    return {"_id": f"d{i}", "name": f"Customer number {i}", "email": f"user{i}@example.com", "cat": f"c{i % 20}",
            "price": i * 1.5, "qty": i % 97, "active": i % 2 == 0, "tags": ["alpha", "beta", f"t{i % 50}"],
            "address": {"street": f"{i} Main Street", "city": f"City{i % 300}", "zip": f"{10000 + i % 9000}"},
            "notes": "lorem ipsum dolor sit amet " * 20}


def bucket_report(recording: pathlib.Path, units: int) -> tuple[float, list[tuple[str, float]]]:
    out = subprocess.run([str(java_bin() / "jfr"), "print", "--json", "--stack-depth", "64", "--events",
                          "jdk.ObjectAllocationSample", str(recording)], capture_output=True, text=True).stdout
    total = 0
    weights: collections.Counter = collections.Counter()
    for event in json.loads(out)["recording"]["events"]:
        values = event["values"]
        weight = values["weight"]
        total += weight
        frames = (values.get("stackTrace") or {}).get("frames") or []
        stack = " ".join(frame["method"]["type"]["name"].split("/")[-1].split("$$")[0] + "."
                         + frame["method"]["name"] for frame in frames)
        label = next((name for name, key in BUCKETS if key in stack), "other")
        weights[label] += weight
    shares = [(label, weight / total * 100) for label, weight in weights.most_common()] if total else []
    return exact_allocated_bytes(recording) / max(units, 1), shares


def exact_allocated_bytes(recording: pathlib.Path) -> int:
    out = subprocess.run([str(java_bin() / "jfr"), "print", "--json", "--events", "jdk.ThreadAllocationStatistics",
                          str(recording)], capture_output=True, text=True).stdout
    per_thread: dict = collections.defaultdict(list)
    for event in json.loads(out)["recording"]["events"]:
        values = event["values"]
        thread = values.get("thread") or {}
        per_thread[thread.get("javaName") or thread.get("javaThreadId")].append(values["allocated"])
    return sum(max(samples) - min(samples) for samples in per_thread.values())


class Profiler:
    def __init__(self, server: Server, out_dir: pathlib.Path):
        self.server = server
        self.out_dir = out_dir
        self.rows: list[tuple[str, str, float, list[tuple[str, float]]]] = []

    def phase(self, name: str, units: int, unit_label: str, action: Callable[[], object], settle: bool = False) -> None:
        self.server.await_idle()
        self.server.jcmd("JFR.start", f"name={name}", "settings=profile")
        started = time.time()
        action()
        if settle:
            self.server.await_idle()
        elapsed = time.time() - started
        recording = self.out_dir / f"{name}.jfr"
        self.server.jcmd("JFR.dump", f"name={name}", f"filename={recording}")
        self.server.jcmd("JFR.stop", f"name={name}")
        per_unit, shares = bucket_report(recording, units)
        if unit_label == "MB per op":
            value = per_unit / 1e6
        elif unit_label == "seconds":
            value = elapsed
        else:
            value = per_unit / 1e3
        self.rows.append((name, unit_label, value, shares))
        print(f"{name:16s} {value:10.2f} {unit_label:11s} wall {elapsed:7.1f}s  "
              + ", ".join(f"{label} {share:.0f}%" for label, share in shares[:4]), flush=True)


def aggregate(conn: b.Conn, steps: list, coll: str = COLL) -> dict:
    response = conn.send({"type": "AGGREGATE", "databaseName": DB, "collectionName": coll,
                          "aggregationSteps": steps})
    if response.get("status") != "OK":
        raise SystemExit(f"aggregate failed: {response}")
    return response


def bulk_save(conn: b.Conn, coll: str, objects: list) -> None:
    response = conn.send({"type": "BULK_SAVE", "databaseName": DB, "collectionName": coll, "objects": objects})
    if response.get("status") != "OK":
        raise SystemExit(f"bulk save failed: {response}")


def warm_phases(profiler: Profiler) -> None:
    conn = b.Conn.authenticated(timeout=1800)
    conn.send({"type": "CREATE_DATABASE", "databaseName": DB})
    for coll in (COLL, REFS):
        conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": coll})
    for field in ("cat", "email"):
        conn.send({"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": field})

    def insert_all():
        for start in range(0, DOCS, BULK):
            bulk_save(conn, COLL, [document(i) for i in range(start, start + BULK)])

    profiler.phase("bulk_insert", DOCS, "seconds", insert_all, settle=True)
    bulk_save(conn, REFS, [{"_id": f"c{k}", "label": f"Category {k}"} for k in range(20)])
    small_qty = {"type": "FILTER", "operator": {"fieldOperatorType": "SMALLER_THAN", "field": "qty", "value": 10}}
    profiler.phase("scan_filter", 5 * DOCS, "KB per doc", lambda: [aggregate(conn, [small_qty]) for _ in range(5)])
    profiler.phase("find_by_id", DOCS // 10, "KB per doc", lambda: [
        conn.send({"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL, "_id": f"d{i}"})
        for i in range(0, DOCS, 10)])

    def update_some():
        for i in range(2000):
            changed = document(i)
            changed["qty"] = 1
            conn.send({"type": "SAVE", "databaseName": DB, "collectionName": COLL, "object": changed})

    profiler.phase("save_update", 2000, "MB per op", update_some, settle=True)
    concat = {"type": "MAP", "operators": [{"fieldName": "label", "operator": {
        "type": "CONCAT", "operands": ["name", "-: ", "email"]}}]}
    profiler.phase("map_concat", 3 * DOCS, "KB per doc",
                   lambda: [aggregate(conn, [concat, {"type": "COUNT"}]) for _ in range(3)])
    profiler.phase("group_by", 3 * DOCS, "KB per doc",
                   lambda: [aggregate(conn, [{"type": "GROUP_BY", "fieldName": "cat"}, {"type": "COUNT"}])
                            for _ in range(3)])
    join = {"type": "JOIN", "joinCollection": REFS, "localField": "cat", "remoteField": "_id", "asField": "catDoc"}
    profiler.phase("join", DOCS, "KB per doc", lambda: aggregate(conn, [join, {"type": "COUNT"}]))
    children = [{"fieldOperatorType": "NOT_EQUALS", "field": "qty", "value": -1 - j} for j in range(20)]
    conj = {"type": "FILTER", "operator": {"conjunctionType": "AND", "operators": children}}
    profiler.phase("conj20", 20 * DOCS, "KB per doc", lambda: aggregate(conn, [conj, {"type": "COUNT"}]))
    conn.close()


def cold_phases(profiler: Profiler) -> None:
    conn = b.Conn.authenticated(timeout=1800)
    conn.send({"type": "CREATE_DATABASE", "databaseName": DB})
    conn.send({"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL})

    def insert_all():
        for start in range(0, DOCS, BULK):
            bulk_save(conn, COLL, [document(i) for i in range(start, start + BULK)])

    profiler.phase("insert_noindex", DOCS, "KB per doc", insert_all)
    one_qty = {"type": "FILTER", "operator": {"fieldOperatorType": "SMALLER_THAN", "field": "qty", "value": 1}}
    profiler.phase("cold_scan", 5 * DOCS, "KB per doc",
                   lambda: [aggregate(conn, [one_qty, {"type": "COUNT"}]) for _ in range(5)])
    conn.close()


def verdicts(rows) -> int:
    failures = 0
    print("\nTarget check")
    for name, unit, value, _ in rows:
        if name in TARGETS:
            target_unit, limit = TARGETS[name]
            ok = value <= limit
            failures += 0 if ok else 1
            print(f"  {'PASS' if ok else 'FAIL'} {name}: {value:.2f} {target_unit} (target <= {limit})")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Profile LWNRDB's write and read paths with JFR allocation sampling and check the "
                    "storage-efficiency targets. Builds nothing: run `mvn package -DskipTests` first.")
    parser.add_argument("--out", default=None, help="directory for the .jfr recordings (default: a temp dir)")
    parser.add_argument("--only", choices=("warm", "cold"), default=None, help="run one half only")
    args = parser.parse_args()
    if not JAR.exists():
        raise SystemExit(f"{JAR} is missing; build it first")
    out_dir = pathlib.Path(args.out or tempfile.mkdtemp(prefix="lwnrdb-jfr-"))
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for half, max_memory, runner in (("warm", "512mb", warm_phases), ("cold", "-1", cold_phases)):
        if args.only not in (None, half):
            continue
        server = Server(max_memory)
        try:
            profiler = Profiler(server, out_dir)
            runner(profiler)
            rows.extend(profiler.rows)
        finally:
            server.stop()
    print(f"\nRecordings in {out_dir}")
    return 1 if verdicts(rows) else 0


if __name__ == "__main__":
    sys.exit(main())
