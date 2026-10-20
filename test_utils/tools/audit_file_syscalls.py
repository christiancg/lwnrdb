#!/usr/bin/env python3
from __future__ import annotations

import argparse
import collections
import pathlib
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import base_utils as b

REPO = pathlib.Path(__file__).resolve().parents[2]
JAR = REPO / "target" / "lwnrdb-1.0-SNAPSHOT.jar"
IMAGE = "lwnrdb-syscall-audit"
CONTAINER = "lwnrdb-syscall-audit"
DB, COLL = "audit", "docs"
SEED_DOCS = 10000
BULK_DOCS = 2000
BULK_BATCH = 500
PAD = "p" * 400
DOCKERFILE = """FROM eclipse-temurin:27-jdk
RUN apt-get update -qq && apt-get install -y -qq strace procps >/dev/null && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY app.jar /app/app.jar
"""
TRACED = ("openat,stat,newfstatat,fstat,statx,lstat,access,faccessat,faccessat2,read,pread64,write,pwrite64,writev,"
          "fsync,fdatasync,ftruncate,lseek,rename,renameat,renameat2,unlink,unlinkat,close,mkdir,mkdirat,fchmod,"
          "fchmodat")
PATH_CALLS = {"openat", "stat", "newfstatat", "statx", "lstat", "access", "faccessat", "faccessat2", "unlinkat",
              "unlink", "renameat", "renameat2", "rename", "mkdirat", "mkdir", "fchmodat"}
LINE = re.compile(r'^\d+\s+(\w+)\((.*)$')
DESCRIPTOR_PATH = re.compile(r'<([^>]*)>')
QUOTED = re.compile(r'"([^"]*)"')
BUDGETS = {"insert": 32.0, "update": 38.0, "delete": 28.0, "bulk_insert": 1.5, "bulk_update": 1.5,
           "find_by_id": 1.0, "find_eq_indexed": 1.5}


def sh(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(list(args), check=check, capture_output=True, text=True)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    return port


def document(i: int, version: int = 0) -> dict:
    return {"_id": f"d{i}", "cat": f"c{(i + version) % 50}", "price": (i * 7 + version) % 1000, "v": version,
            "pad": PAD}


def ok(conn: b.Conn, request: dict) -> None:
    response = conn.send(request)
    if response.get("status") != "OK":
        raise SystemExit(f"{request['type']} failed: {response}")


class Container:
    def __init__(self, work: pathlib.Path, build: bool):
        self.work = work
        self.out = work / "out"
        self.out.mkdir()
        self.out.chmod(0o777)
        if build:
            context = work / "image"
            context.mkdir()
            shutil.copy(JAR, context / "app.jar")
            (context / "Dockerfile").write_text(DOCKERFILE)
            sh("docker", "build", "-q", "-t", IMAGE, str(context))
        cfg = work / "lwnrdb.cfg"
        cfg.write_text("port=8989\nfilePath=db\nlogPath=logs\ndefaultAdminUsername=admin\n"
                       f"defaultAdminPassword={b.PASSWORD}\nmaxMemory=256mb\nsyncWrites=true\n")
        cfg.chmod(0o644)
        self.port = free_port()
        sh("docker", "rm", "-f", CONTAINER, check=False)
        sh("docker", "run", "-d", "--name", CONTAINER, "--cap-add", "SYS_PTRACE", "--cpus", "2", "-p",
           f"{self.port}:8989", "-v", f"{self.out}:/out", "-v", f"{cfg}:/app/lwnrdb.cfg:ro", IMAGE, "java",
           "-XX:+UseZGC", "-jar", "app.jar")
        b.configure(port=self.port)
        self.await_ready()

    @staticmethod
    def await_ready() -> None:
        deadline = time.time() + 90
        while time.time() < deadline:
            try:
                with b.Conn.authenticated() as conn:
                    if conn.send({"type": "LIST_DATABASES"}).get("status") == "OK":
                        return
            except OSError:
                pass
            time.sleep(0.5)
        raise SystemExit("the server did not start: " + sh("docker", "logs", CONTAINER, check=False).stdout[-2000:])

    @staticmethod
    def trace(phase: str) -> None:
        pid = sh("docker", "exec", CONTAINER, "pgrep", "-f", "app.jar").stdout.split()[0]
        threads = sh("docker", "exec", CONTAINER, "ls", f"/proc/{pid}/task").stdout.split()
        command = ["docker", "exec", "-d", CONTAINER, "strace", "-f", "-qq", "-y", "-e", f"trace={TRACED}", "-o",
                   f"/out/{phase}.strace"]
        for thread in threads:
            command += ["-p", thread]
        subprocess.run(command, check=True)
        time.sleep(1.5)

    @staticmethod
    def untrace() -> None:
        sh("docker", "exec", CONTAINER, "pkill", "-INT", "strace", check=False)
        time.sleep(1.0)

    @staticmethod
    def stop() -> None:
        sh("docker", "rm", "-f", CONTAINER, check=False)


def normalize(path: str) -> str:
    path = path.replace("/app/db/", "")
    if path.startswith("db/"):
        path = path[3:]
    path = re.sub(r"-\d+\.dat", "-#.dat", path)
    path = re.sub(r"-\d+\.vacuum", "-#.vacuum", path)
    return re.sub(r"\.\d+\.tmp$|\.repair$", ".tmp", path)


def kind_of(path: str) -> str:
    admin = "admin " if path.startswith("admin") else ""
    if path.endswith(".idx") and "-pk.idx" not in path:
        return admin + ".idx"
    for suffix in (".idxlog", "-pk.idx", ".wal", ".dat", ".intents", ".dirty", ".tmp", ".vacuum"):
        if suffix in path:
            return admin + suffix
    return admin + "folder " + path.split("/")[-1]


def file_calls(trace: pathlib.Path) -> collections.Counter:
    counts: collections.Counter = collections.Counter()
    if not trace.exists():
        return counts
    for line in trace.read_text(errors="replace").splitlines():
        match = LINE.match(line)
        if not match:
            continue
        call, rest = match.group(1), match.group(2)
        quoted = QUOTED.search(rest)
        descriptor = DESCRIPTOR_PATH.search(rest)
        if call in PATH_CALLS and quoted:
            path = quoted.group(1)
        elif descriptor:
            path = descriptor.group(1)
        else:
            continue
        if path.startswith("/app/db") or path.startswith("db/"):
            counts[(call, kind_of(normalize(path)))] += 1
    return counts


class Audit:
    def __init__(self, container: Container, ops: int):
        self.container = container
        self.ops = ops
        self.per_op: dict[str, float] = {}

    def phase(self, conn: b.Conn, name: str, units: int, requests: list[dict]) -> None:
        self.container.trace(name)
        for request in requests:
            ok(conn, request)
        time.sleep(4.0)
        self.container.untrace()
        counts = file_calls(self.container.out / f"{name}.strace")
        total = sum(counts.values()) / units
        self.per_op[name] = total
        by_kind: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
        for (call, kind), count in counts.items():
            by_kind[kind][call] += count
        print(f"\n{name}: {total:.1f} file syscalls per {'document' if name.startswith('bulk') else 'op'}")
        for kind, calls in sorted(by_kind.items(), key=lambda item: -sum(item[1].values())):
            share = sum(calls.values()) / units
            if share >= 0.05:
                print(f"  {share:6.2f}  {kind:20s} "
                      + ", ".join(f"{call}={count / units:.2f}" for call, count in calls.most_common()))

    def run(self) -> None:
        with b.Conn.authenticated(timeout=600) as conn:
            ok(conn, {"type": "CREATE_DATABASE", "databaseName": DB})
            ok(conn, {"type": "CREATE_COLLECTION", "databaseName": DB, "collectionName": COLL})
            for field in ("cat", "price"):
                ok(conn, {"type": "CREATE_INDEX", "databaseName": DB, "collectionName": COLL, "fieldName": field})
            for start in range(0, SEED_DOCS, BULK_BATCH):
                ok(conn, bulk(start, 0))
            time.sleep(6)
            n = self.ops
            self.phase(conn, "insert", n, [save(document(20000 + i)) for i in range(n)])
            self.phase(conn, "update", n, [save(document(i, 1)) for i in range(n)])
            self.phase(conn, "delete", n, [{"type": "DELETE", "databaseName": DB, "collectionName": COLL,
                                            "_id": f"d{20000 + i}"} for i in range(n)])
            self.phase(conn, "bulk_insert", BULK_DOCS, [bulk(30000 + start, 0)
                                                        for start in range(0, BULK_DOCS, BULK_BATCH)])
            self.phase(conn, "bulk_update", BULK_DOCS, [bulk(1000 + start, 2)
                                                        for start in range(0, BULK_DOCS, BULK_BATCH)])
            self.phase(conn, "find_by_id", n, [{"type": "FIND_BY_ID", "databaseName": DB, "collectionName": COLL,
                                                "_id": f"d{5000 + i}"} for i in range(n)])
            self.phase(conn, "find_eq_indexed", n // 4, [
                {"type": "AGGREGATE", "databaseName": DB, "collectionName": COLL,
                 "aggregationSteps": [b.filter_step("cat", "EQUALS", f"c{i % 50}")]} for i in range(n // 4)])


def save(obj: dict) -> dict:
    return {"type": "SAVE", "databaseName": DB, "collectionName": COLL, "object": obj}


def bulk(start: int, version: int) -> dict:
    return {"type": "BULK_SAVE", "databaseName": DB, "collectionName": COLL,
            "objects": [document(start + i, version) for i in range(BULK_BATCH)]}


def over_budget(per_op: dict[str, float], budgets: dict[str, float]) -> list[str]:
    return [f"{name}: {per_op[name]:.1f} > {budget}" for name, budget in budgets.items()
            if name in per_op and per_op[name] > budget]


def parse_budgets(overrides: list[str]) -> dict[str, float]:
    budgets = dict(BUDGETS)
    for override in overrides:
        name, _, value = override.partition("=")
        if name not in budgets or not value:
            raise SystemExit(f"--budget takes <phase>=<calls>, phase one of {', '.join(budgets)}")
        budgets[name] = float(value)
    return budgets


def main() -> None:
    parser = argparse.ArgumentParser(description="Count the file syscalls each common operation makes, under strace "
                                                 "inside a Docker container")
    parser.add_argument("--ops", type=int, default=400, help="single-document operations per phase")
    parser.add_argument("--check", action="store_true", help="fail when a phase exceeds its budget")
    parser.add_argument("--budget", action="append", default=[], metavar="PHASE=CALLS",
                        help="override one phase's budget")
    parser.add_argument("--no-build", action="store_true", help=f"reuse the {IMAGE} image")
    parser.add_argument("--keep", action="store_true", help="keep the traces and print where they are")
    args = parser.parse_args()
    budgets = parse_budgets(args.budget)
    if not args.no_build and not JAR.exists():
        raise SystemExit(f"{JAR} is missing: run mvn clean package -DskipTests first")
    work = pathlib.Path(tempfile.mkdtemp(prefix="lwnrdb-syscalls-"))
    container = Container(work, build=not args.no_build)
    try:
        audit = Audit(container, args.ops)
        audit.run()
    finally:
        container.stop()
        if args.keep:
            print(f"\ntraces kept in {work / 'out'}")
        else:
            shutil.rmtree(work, ignore_errors=True)
    failures = over_budget(audit.per_op, budgets)
    print("\nbudgets: " + ", ".join(f"{name} {budget}" for name, budget in budgets.items()))
    if args.check and failures:
        raise SystemExit("over budget: " + "; ".join(failures))
    print("all phases within budget" if not failures else "over budget: " + "; ".join(failures))


if __name__ == "__main__":
    main()
