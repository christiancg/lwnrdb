import argparse
import json
import os
import random
import shutil
import tempfile
import time
import traceback
from dataclasses import dataclass, field

import base_utils as bu
import transaction_kills
import write_intent_kills
from base_utils import check, check_status, section
from durability import oracles, shadow
from durability.interrupts import FSYNC_FAILURE, MODE_SALT, SEEDED_MODES, interrupt, kill_process
from durability.regression_seeds import REGRESSION_SEEDS
from durability.workload import Workload

HOST = "127.0.0.1"
PORT = int(os.environ.get("POWER_LOSS_TEST_PORT", "9454"))
bu.configure(host=HOST, port=PORT)

CASES = "cases"
ALL = "all"

SERVER_SETTINGS = {"maxPageSize": "8kb", "maxEntrySize": "4kb", "walCheckpointBytes": "4kb",
                   "walIdleCheckpointMs": 200, "maxMemory": "1mb", "syncWrites": True, "faultInjectionEnabled": True}
CASE_SETTINGS = {"maxMemory": "256mb", "maxPageSize": "4kb", "maxEntrySize": "1kb", "scriptsEnabled": True,
                 "faultInjectionEnabled": True, "syncWrites": True, "walCheckpointBytes": "16kb",
                 "walIdleCheckpointMs": 0, "triggersEnabled": True}
CASES_DB = write_intent_kills.DB
DEFAULT_BUDGET_S = 300.0
AUTO = "auto"
AT_POWER_LOSS = "db.at-power-loss"


@dataclass
class Run:
    seed: int
    mode: str
    rebuild: str = ""
    rebuild_mode: str = ""
    notes: list = field(default_factory=list)
    divergences: list = field(default_factory=list)
    work_dir: str = ""


def connect() -> bu.Conn:
    return bu.Conn.authenticated()


def quietly(payload: dict) -> dict:
    try:
        with connect() as conn:
            return conn.send(payload, timeout=5)
    except OSError as exc:
        return {"status": "ERROR", "message": str(exc)}


def restart_and_check(work_dir: str, log_path: str, seed: int, check_body) -> tuple:
    offset = len(bu.read_log(log_path))
    proc = bu.start_server(work_dir, log_path, port=PORT)
    try:
        with connect() as conn:
            found = oracles.check_clean_start(log_path, conn, seed, work_dir, offset)
            result = check_body(conn)
            found += result[0]
        return found, result[1]
    finally:
        bu.stop_server(proc, port=PORT)


def auto_rebuild(seed: int, mode: str) -> str:
    return shadow.STRICT if seed % 2 == 0 or mode == FSYNC_FAILURE else shadow.TORN


def run_seed(seed: int, mode: str, rebuild: str = AUTO) -> Run:
    rng = random.Random(seed * 7919 + MODE_SALT[mode])
    run = Run(seed, mode, work_dir=tempfile.mkdtemp(prefix=f"lwnrdb-powerloss-{seed}-"))
    shadow_root = tempfile.mkdtemp(prefix=f"lwnrdb-powerloss-shadow-{seed}-")
    log_path = os.path.join(run.work_dir, "server.log")
    bu.write_config(run.work_dir, PORT, powerLossShadowPath=shadow_root, **SERVER_SETTINGS)
    proc = bu.start_server(run.work_dir, log_path, port=PORT)
    try:
        workload = Workload(seed)
        schema = workload.setup(connect)
        schema.work_dir = run.work_dir
        workload.start(connect)
        interrupt(mode, proc, rng, schema, run.notes, quietly)
        history = workload.stop()
    finally:
        kill_process(proc)
    run.rebuild_mode = auto_rebuild(seed, mode) if rebuild == AUTO else rebuild
    db = os.path.join(run.work_dir, "db")
    run.rebuild = shadow.rebuild(shadow_root, db, run.rebuild_mode, rng).summary()
    shutil.copytree(db, os.path.join(run.work_dir, AT_POWER_LOSS))
    counts = {}
    for entry in history.entries:
        key = f"{entry.outcome}/{entry.error_code}" if entry.error_code else entry.outcome
        counts[key] = counts.get(key, 0) + 1
    run.notes.append(f"history {counts}")
    found, before = restart_and_check(run.work_dir, log_path, seed,
                                      lambda conn: (oracles.check_all(conn, history, schema),
                                                    oracles.snapshot(conn, schema)))
    run.divergences += found
    found, _ = restart_and_check(run.work_dir, log_path, seed,
                                 lambda conn: (oracles.check_idempotent(before, conn, history, schema), None))
    run.divergences += found
    shutil.rmtree(shadow_root, ignore_errors=True)
    return run


def strict_recovery(shadow_roots: dict):
    def recover(work_dir: str) -> None:
        report = shadow.rebuild(shadow_roots[work_dir], os.path.join(work_dir, "db"), shadow.STRICT, random.Random(0))
        print(f"    {report.summary()}")
    return recover


def case_config(work_dir: str, shadow_roots: dict) -> None:
    shadow_roots.setdefault(work_dir, tempfile.mkdtemp(prefix="lwnrdb-powerloss-case-shadow-"))
    bu.write_config(work_dir, PORT, powerLossShadowPath=shadow_roots[work_dir], **CASE_SETTINGS)


def run_cases() -> None:
    section("Every hand-written kill case, replayed as a power loss with a strict rebuild")
    shadow_roots = {}
    work_dir = tempfile.mkdtemp(prefix="lwnrdb-powerloss-cases-")
    log_path = os.path.join(work_dir, "server.log")
    case_config(work_dir, shadow_roots)
    proc = bu.start_server(work_dir, log_path, port=PORT)
    with connect() as conn:
        check_status(f"create {CASES_DB}", conn.send({"type": "CREATE_DATABASE", "databaseName": CASES_DB}), "OK")
    bu.stop_server(proc, port=PORT)
    proc = None
    try:
        proc, rounds = write_intent_kills.run(work_dir, log_path, connect,
                                              lambda: case_config(work_dir, shadow_roots),
                                              recover=strict_recovery(shadow_roots))
        with connect() as conn:
            write_intent_kills.test_every_killed_write_recovers_to_what_its_log_committed(conn, work_dir, rounds,
                                                                                          power_loss=True)
    finally:
        bu.stop_server(proc, port=PORT)
    transaction_kills.run(connect, lambda kill_dir: case_config(kill_dir, shadow_roots),
                          recover=strict_recovery(shadow_roots))
    for root in [work_dir, *shadow_roots.values()]:
        shutil.rmtree(root, ignore_errors=True)


def report_run(run: Run, report: list) -> None:
    failed = oracles.failures(run.divergences)
    label = f"seed {run.seed} [{run.mode}]"
    check(f"{label}: every oracle holds after the power loss and after a second restart", not failed,
          "\n".join([run.rebuild, *run.notes, *(f"{d.oracle}: {d.detail}" for d in failed[:20])]))
    observations = {(d.oracle, d.detail) for d in run.divergences if d not in failed}
    for oracle, detail in sorted(observations):
        bu.warn(f"{label}: {oracle} observation", detail)
    if failed:
        print(f"    kept {run.work_dir} (db as rebuilt in {AT_POWER_LOSS}); replay: python3"
              f" test_utils/test_power_loss.py --rebuild {run.rebuild_mode} --replay {run.seed}"
              f" --mode {run.mode}")
    else:
        shutil.rmtree(run.work_dir, ignore_errors=True)
    report.append({"seed": run.seed, "mode": run.mode, "rebuild": run.rebuild, "notes": run.notes,
                   "divergences": [vars(d) for d in run.divergences], "work_dir": run.work_dir if failed else ""})


def run_and_report(seed: int, mode: str, rebuild: str, report: list) -> None:
    section(f"Power loss, seed {seed}, mode {mode}, rebuild {rebuild}")
    try:
        run = run_seed(seed, mode, rebuild)
    except Exception as exc:
        check(f"seed {seed} [{mode}] ran to completion", False, f"{exc}\n{traceback.format_exc()}")
        report.append({"seed": seed, "mode": mode, "error": str(exc)})
        return
    report_run(run, report)


def planned_runs(args) -> list:
    modes = SEEDED_MODES if args.mode in (ALL, CASES) else (args.mode,)
    if args.replay is not None:
        return [(args.replay, mode, args.rebuild) for mode in modes]
    if args.seeds == "regression":
        return [(entry[0], entry[1], pinned_rebuild(entry, args.rebuild)) for entry in REGRESSION_SEEDS
                if entry[1] in modes]
    start = args.start if args.start is not None else random.randrange(1, 2 ** 31)
    print(f"  start seed {start}")
    return [(start + offset, modes[offset % len(modes)], args.rebuild) for offset in range(int(args.seeds))]


def pinned_rebuild(entry: tuple, requested: str) -> str:
    return entry[3] if len(entry) > 3 else requested


def write_report(path: str, report: list) -> None:
    with open(path, "w") as fp:
        json.dump(report, fp, indent=2, default=str)


def main() -> None:
    parser = argparse.ArgumentParser(description="power-loss harness: SIGKILL, rebuild from what was forced, check")
    parser.add_argument("--seeds", default="regression", help="'regression' or a number of fresh seeds")
    parser.add_argument("--start", type=int, default=None, help="first seed when --seeds is a number")
    parser.add_argument("--mode", default=ALL, choices=[ALL, CASES, *SEEDED_MODES])
    parser.add_argument("--budget", type=float, default=DEFAULT_BUDGET_S, help="wall-clock seconds for seeded runs")
    parser.add_argument("--replay", type=int, default=None, help="run one seed, keeping its folders on failure")
    parser.add_argument("--report", default=None, help="write every run's divergences as JSON")
    parser.add_argument("--rebuild", default=AUTO, choices=[AUTO, *shadow.MODES],
                        help="how db/ is rebuilt from the shadow; auto picks strict or torn by seed")
    args = parser.parse_args()

    bu.banner("power-loss harness", HOST, PORT)
    bu.require_jar()
    report = []
    deadline = time.time() + args.budget
    if args.mode in (ALL, CASES) and args.replay is None:
        run_cases()
    if args.mode != CASES:
        for seed, mode, rebuild in planned_runs(args):
            if time.time() > deadline:
                bu.warn(f"seed {seed} [{mode}] skipped", "wall-clock budget exhausted")
                continue
            run_and_report(seed, mode, rebuild, report)
    if args.report:
        write_report(args.report, report)
    bu.summary()


if __name__ == "__main__":
    main()
