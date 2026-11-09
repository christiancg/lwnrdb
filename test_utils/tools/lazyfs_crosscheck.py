import argparse
import json
import os
import pathlib
import random
import subprocess
import sys
import time
from dataclasses import dataclass, field

PORT = int(os.environ.get("LAZYFS_TEST_PORT", "9458"))
NAME = os.environ.get("LAZYFS_CONTAINER", "lwnrdb-lazyfs")
os.environ["DISK_FULL_TEST_PORT"] = str(PORT)
TEST_UTILS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TEST_UTILS))

import base_utils as bu
from base_utils import check, section
from disk_full.scenario import Run as ScenarioRun
from disk_full.scenario import connect
from durability import oracles
from durability.interrupts import FAULT_HALT_STATUS, MODE_SALT, SEEDED_MODES, interrupt
from durability.regression_seeds import REGRESSION_SEEDS
from lazyfs.container import LazyFsContainer, LazyFsDied

ALL = "all"
SETTINGS = {"maxPageSize": "8kb", "maxEntrySize": "4kb", "walCheckpointBytes": "4kb", "walIdleCheckpointMs": 200,
            "maxMemory": "1mb", "syncWrites": True, "faultInjectionEnabled": True}
REBUILD = "lazyfs clear-cache"
DEFAULT_BUDGET_S = 3600.0
COMPARE_REPLAYS = 3
SMOKE_FAILED_STATUS = 2
LAZYFS_ONLY = "lazyfs-only"
BOTH = "both"
POWER_LOSS = TEST_UTILS / "test_power_loss.py"


class SmokeFailed(RuntimeError):
    pass


@dataclass
class Outcome:
    seed: int
    mode: str
    rebuild: str = REBUILD
    notes: list = field(default_factory=list)
    divergences: list = field(default_factory=list)
    label: str = ""
    inconclusive: str = ""


class ContainerProcess:
    def __init__(self, container):
        self.container = container

    def poll(self):
        return None if self.container.alive() else FAULT_HALT_STATUS

    def kill(self):
        self.container.kill_server()

    def wait(self, timeout):
        if not self.container.wait_exit(timeout):
            raise TimeoutError(f"the server did not exit within {timeout} s")
        return FAULT_HALT_STATUS


def quietly(payload: dict) -> dict:
    try:
        with connect() as conn:
            return conn.send(payload, timeout=5)
    except OSError as exc:
        return {"status": "ERROR", "message": str(exc)}


def planned_runs(seeds: str, start, mode: str, replay) -> list:
    modes = SEEDED_MODES if mode == ALL else (mode,)
    if replay is not None:
        return [(replay, each) for each in modes]
    if seeds == "regression":
        return [(seed, each) for seed, each, _ in REGRESSION_SEEDS if each in modes]
    first = start if start is not None else random.randrange(1, 2 ** 31)
    print(f"  start seed {first}")
    return [(first + offset, modes[offset % len(modes)]) for offset in range(int(seeds))]


def label_of(shadow_passes: list) -> str:
    return LAZYFS_ONLY if shadow_passes and all(shadow_passes) else BOTH


def smoke(container) -> tuple:
    container.up()
    container.mount()
    return container.smoke()


def power_loss(run: ScenarioRun, container, outcome: Outcome, rng: random.Random):
    if not container.start_server():
        raise RuntimeError(f"the server did not start on LazyFS: {container.output()[-1500:]}")
    workload = run.prepare()
    workload.start(connect)
    interrupt(outcome.mode, ContainerProcess(container), rng, run.schema, outcome.notes, quietly)
    history = workload.stop()
    container.kill_server()
    container.clear_cache()
    return history


def recover_and_check(run: ScenarioRun, history):
    if not run.restart("first restart after the power loss"):
        return
    run.check_clean_start()
    run.report("O1-O4 after the power loss", run.check_all(history))
    with connect() as conn:
        before = oracles.snapshot(conn, run.schema)
    if not run.restart("second restart"):
        return
    run.check_clean_start()
    run.settled_snapshot()
    with connect() as conn:
        run.report("O6 a second restart answers identically and accepts writes",
                   oracles.check_idempotent(before, conn, history, run.schema))
    run.scan_uncaught()


def run_seed(seed: int, mode: str, keep: bool) -> Outcome:
    section(f"LazyFS power loss, seed {seed}, mode {mode}")
    outcome = Outcome(seed, mode)
    rng = random.Random(seed * 7919 + MODE_SALT[mode])
    container = LazyFsContainer(NAME, PORT, SETTINGS)
    run = ScenarioRun(f"LazyFS {mode}", seed, keep, container=container)
    try:
        passed, detail = smoke(container)
        if not passed:
            raise SmokeFailed(detail)
        history = power_loss(run, container, outcome, rng)
        counts = {}
        for entry in history.entries:
            key = f"{entry.outcome}/{entry.error_code}" if entry.error_code else entry.outcome
            counts[key] = counts.get(key, 0) + 1
        outcome.notes.append(f"history {counts}")
        recover_and_check(run, history)
    finally:
        outcome.divergences = list(run.divergences)
        run.__exit__(None, None, None)
    return outcome


def run_seed_safely(seed: int, mode: str, keep: bool) -> Outcome:
    try:
        return run_seed(seed, mode, keep)
    except SmokeFailed:
        raise
    except LazyFsDied as died:
        return Outcome(seed, mode, inconclusive=str(died))
    except Exception as exc:
        check(f"seed {seed} [{mode}] ran to completion", False, f"{type(exc).__name__}: {exc}")
        return Outcome(seed, mode, notes=[f"{type(exc).__name__}: {exc}"])


def shadow_passes(seed: int, mode: str) -> list:
    passes = []
    for _ in range(COMPARE_REPLAYS):
        replay = subprocess.run([sys.executable, str(POWER_LOSS), "--replay", str(seed), "--mode", mode],
                                capture_output=True, cwd=TEST_UTILS.parent)
        passes.append(replay.returncode == 0)
    return passes


def report_outcome(outcome: Outcome, report: list) -> None:
    failed = oracles.failures(outcome.divergences)
    label = f"seed {outcome.seed} [{outcome.mode}]"
    if outcome.inconclusive:
        bu.warn(f"{label}: inconclusive, LazyFS itself stopped", outcome.inconclusive[-600:])
        report.append({"seed": outcome.seed, "mode": outcome.mode, "rebuild": outcome.rebuild, "notes": outcome.notes,
                       "divergences": [], "label": "", "inconclusive": outcome.inconclusive})
        return
    suffix = f" ({outcome.label})" if outcome.label else ""
    check(f"{label}: every oracle holds after a LazyFS power loss and a second restart{suffix}", not failed,
          "\n".join([*outcome.notes, *(f"{d.oracle}: {d.detail}" for d in failed[:20])]))
    if failed:
        print(f"    replay: python3 test_utils/tools/lazyfs_crosscheck.py --replay {outcome.seed}"
              f" --mode {outcome.mode} --keep")
    report.append({"seed": outcome.seed, "mode": outcome.mode, "rebuild": outcome.rebuild, "notes": outcome.notes,
                   "divergences": [vars(d) for d in outcome.divergences], "label": outcome.label,
                   "inconclusive": ""})


def run_smoke_only() -> int:
    container = LazyFsContainer(NAME, PORT, SETTINGS)
    try:
        passed, detail = smoke(container)
    finally:
        container.remove()
    check("LazyFS keeps a forced write and drops an unforced one on clear-cache", passed, detail)
    return 0 if passed else SMOKE_FAILED_STATUS


def main() -> int:
    parser = argparse.ArgumentParser(description="LazyFS cross-check of the power-loss harness (local only)")
    parser.add_argument("--seeds", default="regression", help="'regression' or a number of fresh seeds")
    parser.add_argument("--start", type=int, default=None, help="first seed when --seeds is a number")
    parser.add_argument("--mode", default=ALL, choices=[ALL, *SEEDED_MODES])
    parser.add_argument("--budget", type=float, default=DEFAULT_BUDGET_S, help="wall-clock seconds for the seeds")
    parser.add_argument("--replay", type=int, default=None, help="run one seed")
    parser.add_argument("--report", default=None, help="write every run's divergences as JSON")
    parser.add_argument("--keep", action="store_true", help="leave the last container running")
    parser.add_argument("--smoke", action="store_true", help="run only the LazyFS negative control")
    parser.add_argument("--compare", action="store_true", help="replay each failure under the shadow and label it")
    args = parser.parse_args()
    bu.configure(port=PORT)
    bu.banner("LazyFS cross-check", "127.0.0.1", PORT)
    if args.smoke:
        return run_smoke_only()
    report = []
    started = time.time()
    try:
        for seed, mode in planned_runs(args.seeds, args.start, args.mode, args.replay):
            if time.time() - started > args.budget:
                bu.warn(f"seed {seed} [{mode}] skipped", "wall-clock budget exhausted")
                continue
            outcome = run_seed_safely(seed, mode, args.keep)
            if args.compare and oracles.failures(outcome.divergences):
                outcome.label = label_of(shadow_passes(seed, mode))
            report_outcome(outcome, report)
    except SmokeFailed as failed:
        check("LazyFS keeps a forced write and drops an unforced one on clear-cache", False, str(failed))
        return SMOKE_FAILED_STATUS
    finally:
        if args.report:
            with open(args.report, "w") as fp:
                json.dump(report, fp, indent=2, default=str)
    bu.summary()
    return 0


if __name__ == "__main__":
    sys.exit(main())
