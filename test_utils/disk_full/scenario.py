import dataclasses
import os
import shutil
import tempfile
import time

import base_utils as bu
from base_utils import check
from disk_full.container import Container
from durability import oracles
from durability.workload import Divergence, History, Workload

PORT = int(os.environ.get("DISK_FULL_TEST_PORT", "9455"))
NAME = os.environ.get("DISK_FULL_CONTAINER", "lwnrdb-disk-full")
LOCK_TIMEOUT_MS = 5000
SETTLE_TIMEOUT_S = 10.0
SETTINGS = {"maxMemory": "1mb", "maxPageSize": "8kb", "maxEntrySize": "4kb", "walCheckpointBytes": "64kb",
            "walIdleCheckpointMs": 200, "faultInjectionEnabled": True, "syncWrites": True,
            "transactionLockTimeoutMs": LOCK_TIMEOUT_MS}

bu.configure(port=PORT)


def connect():
    return bu.Conn.authenticated(port=PORT, timeout=30.0)


class Run:

    def __init__(self, name: str, seed: int, keep: bool, settings: dict = None, container: Container = None):
        self.name = name
        self.seed = seed
        self.keep = keep
        self.container = container or Container(NAME, PORT, {**SETTINGS, **(settings or {})})
        self.divergences = []
        self.notes = []
        self.offset = 0
        self.scanned = 0
        self.workload = None
        self.schema = None
        self.scratch = tempfile.mkdtemp(prefix="lwnrdb-disk-full-run-")
        self.snapshots = 0
        self.stranded = set()

    def __enter__(self):
        self.container.up()
        if not self.container.start_server():
            raise RuntimeError(f"the server did not start:\n{self.container.output()[-3000:]}")
        return self

    def __exit__(self, *_):
        if not self.keep:
            self.container.remove()
            shutil.rmtree(self.scratch, ignore_errors=True)
        else:
            self.note(f"container kept: {self.container.describe()}; snapshots in {self.scratch}")

    def note(self, text: str):
        self.notes.append(text)
        print(f"         {text}")

    def report(self, label: str, found: list):
        self.divergences += found
        failed = oracles.failures(found)
        check(f"{self.name} seed {self.seed}: {label}", not failed,
              "\n         ".join(f"{d.oracle}: {d.detail}" for d in failed[:20]))
        for divergence in found:
            if divergence not in failed:
                bu.warn(f"{self.name} seed {self.seed}: {divergence.oracle} observation", divergence.detail)

    def prepare(self, docs: int = 400, collections: int = 3) -> Workload:
        self.workload = Workload(self.seed, docs=docs, collections=collections)
        self.schema = self.workload.setup(connect)
        return self.workload

    def run_sequence(self, ops: list) -> History:
        with connect() as conn:
            return self.workload.run_sequence(conn, ops)

    def restart(self, label: str) -> bool:
        if not self.container.stop_server():
            self.report(f"{label}: the server stops within its shutdown timeout",
                        [Divergence("O7", self.seed, "SIGTERM did not stop the server within 40 s; killed it")])
        self.offset = len(self.container.output())
        if self.container.start_server():
            return True
        self.report(f"{label}: the server restarts", [Divergence(
            "O5", self.seed, f"the server did not come up: {self.since()[-1500:]}")])
        return False

    def since(self) -> str:
        return self.container.output()[self.offset:]

    @staticmethod
    def outcomes(history: History) -> str:
        counts = {}
        for entry in history.entries:
            key = entry.outcome if not entry.error_code else f"{entry.outcome}/{entry.error_code}"
            counts[key] = counts.get(key, 0) + 1
        return ", ".join(f"{key}={counts[key]}" for key in sorted(counts))

    def check_stranded(self):
        output = self.container.output()
        found = oracles.check_no_stranded_state(connect, self.schema, self.seed, LOCK_TIMEOUT_MS,
                                                output[self.scanned:])
        self.stranded = {coll for coll in self.schema.collections
                         if any(d.detail.startswith(f"{self.schema.db}|{coll}:") for d in found)}
        self.report("O7 no stranded state", found)
        self.scanned = len(output)

    def scan_uncaught(self):
        output = self.container.output()
        self.report("O7 no uncaught exception in the server output",
                    oracles.uncaught_exceptions(self.seed, output[self.scanned:]))
        self.scanned = len(output)

    def snapshot(self) -> str:
        self.snapshots += 1
        folder = os.path.join(self.scratch, f"snapshot-{self.snapshots}")
        os.makedirs(folder)
        if not self.container.snapshot(folder):
            raise RuntimeError("could not copy the container's data folder")
        return folder

    def drifted(self, work_dir: str) -> bool:
        return any(oracles.occupancy_drift(oracles.recorded_pages(work_dir, self.schema.db, coll),
                                           oracles.actual_pages(work_dir, self.schema.db, coll))
                   for coll in self.schema.collections)

    def settled_snapshot(self) -> str:
        deadline = time.time() + SETTLE_TIMEOUT_S
        work_dir = self.snapshot()
        while self.drifted(work_dir) and time.time() < deadline:
            time.sleep(0.5)
            shutil.rmtree(work_dir, ignore_errors=True)
            work_dir = self.snapshot()
        self.schema.work_dir = work_dir
        return work_dir

    def check_clean_start(self):
        with connect() as conn:
            bu.wait_until(lambda: oracles.background_idle(conn), timeout_s=oracles.IDLE_DEADLINE_S, interval_s=0.25)
        work_dir = self.snapshot()
        log_path = os.path.join(self.scratch, f"startup-{self.snapshots}.log")
        with open(log_path, "w") as fp:
            fp.write(self.since())
        with connect() as conn:
            self.report("O5 clean start", oracles.check_clean_start(log_path, conn, self.seed, work_dir))

    def check_all(self, history: History, schema=None) -> list:
        schema = schema or self.schema
        schema.work_dir = self.settled_snapshot()
        with connect() as conn:
            return oracles.check_all(conn, history, schema)

    def check_live(self, history: History):
        reachable = [coll for coll in self.schema.collections if coll not in self.stranded]
        label = "O1-O4 live, once space is back and before any restart"
        if self.stranded:
            label += f", skipping the stranded {sorted(self.stranded)}"
        self.report(label, self.check_all(history, dataclasses.replace(self.schema, collections=reachable)))

    def check_honesty(self, history: History):
        with connect() as conn:
            self.report("O8 outcome honesty", oracles.check_outcome_honesty(conn, history, self.schema))

    def recover_and_check(self, history: History):
        self.check_live(history)
        if not self.restart("first restart"):
            return
        self.check_clean_start()
        self.report("O1-O4 after the restart", self.check_all(history))
        self.check_honesty(history)
        with connect() as conn:
            before = oracles.snapshot(conn, self.schema)
        if not self.restart("second restart"):
            return
        self.check_clean_start()
        self.settled_snapshot()
        with connect() as conn:
            self.report("O6 a second restart answers identically and accepts writes",
                        oracles.check_idempotent(before, conn, history, self.schema))

    def summary(self) -> dict:
        return {"scenario": self.name, "seed": self.seed, "notes": self.notes,
                "divergences": [vars(d) for d in self.divergences]}
