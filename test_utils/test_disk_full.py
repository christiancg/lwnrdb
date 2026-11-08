import argparse
import json
import random
import socket
import threading
import time

import base_utils as bu
from base_utils import check, section
from disk_full.container import DATA, DATA_BALLAST, LOGS, LOGS_BALLAST
from disk_full.scenario import PORT, Run, connect
from durability.workload import BULK_SAVE, SAVE, Divergence

HEADROOMS = [0, 100, 4096, 65536, 1048576]
MEGABYTE_BLOCKS = 256
HOLD_FULL_S = 2.0
LOGS_FULL_S = 10.0
D2_DOCS = 100
D3_HOT_IDS = 8
UNAUTHENTICATED_PER_ROUND = 8
BURST_CHUNK = 20
BACKGROUND_MARKERS = ("Could not checkpoint", "Failed to process a background event", "Could not write the page",
                      "Error while processing", "vacuum")
D5_SETTINGS = {"authTimeoutMs": 300}
PARTIAL_COPY_SUFFIXES = (".repair", ".vacuum")
COLLECTION_FENCED = "503-18"
FENCE_HELD_MARKER = "its collections stay locked"


def fill_during_workload(run: Run):
    section(f"D1 fill_during_workload (seed {run.seed}): the ballast grows 1 MB at a time to ENOSPC under 4 clients")
    workload = run.prepare()
    workload.start(connect)
    time.sleep(0.5)
    steps = 0
    while run.container.grow(DATA_BALLAST, MEGABYTE_BLOCKS) and steps < 64:
        steps += 1
        time.sleep(0.2)
    run.note(f"ENOSPC after {steps} MB of ballast, {run.container.free_blocks(DATA)} free blocks; holding "
             f"{HOLD_FULL_S:.0f} s")
    time.sleep(HOLD_FULL_S)
    run.container.free(DATA_BALLAST)
    history = workload.stop()
    run.note(f"outcomes: {run.outcomes(history)}")
    run.check_stranded()
    run.recover_and_check(history)


def exact_headroom(run: Run):
    section(f"D2 exact_headroom (seed {run.seed}): Workload.fixed_sequence at each headroom in {HEADROOMS}, each on"
            f" a fresh collection")
    workload = run.prepare(docs=D2_DOCS, collections=len(HEADROOMS))
    for step, headroom in enumerate(HEADROOMS):
        coll = run.schema.collections[step]
        before = len(workload.history().entries)
        actual = run.container.fill_to(DATA, DATA_BALLAST, headroom)
        sequence = run.run_sequence(workload.fixed_sequence(coll)).entries[before:]
        run.container.free(DATA_BALLAST)
        run.note(f"headroom {headroom} B (free {actual} B) on {coll}: " + ", ".join(
            f"{e.op}={e.outcome}{'/' + e.error_code if e.error_code else ''}" for e in sequence))
        run.check_stranded()
        if not run.restart(f"headroom {headroom}"):
            return
        run.check_clean_start()
        history = workload.history()
        run.report(f"O1-O4 after headroom {headroom}", run.check_all(history))
        run.check_honesty(history)
    run.recover_and_check(workload.history())


def background_burst(hot: list, coll: str, chunk: int) -> list:
    ops = [{"op": SAVE, "coll": coll, "ids": [hot[k % len(hot)]]} for k in range(BURST_CHUNK)]
    ops.append({"op": BULK_SAVE, "coll": coll, "ids": [f"d3-bulk-{chunk}-{j}" for j in range(40)]})
    return ops


def run_bursts(run: Run, coll: str, stop_at: list):
    hot = run.schema.ids[coll][:D3_HOT_IDS]
    chunk = 0
    while stop_at[0] is None or time.time() < stop_at[0]:
        run.run_sequence(background_burst(hot, coll, chunk))
        chunk += 1


def background_failures(output: str) -> str:
    return ", ".join(f"{marker}={output.count(marker)}" for marker in BACKGROUND_MARKERS)


def full_at_background_work(run: Run):
    section(f"D3 full_at_background_work (seed {run.seed}): the disk fills while checkpoints, vacuums and index "
            f"folds are due")
    workload = run.prepare()
    coll = run.schema.collections[0]
    stop_at = [None]
    run.offset = len(run.container.output())
    burst = threading.Thread(target=run_bursts, args=(run, coll, stop_at))
    burst.start()
    time.sleep(1.0)
    run.container.fill_to(DATA, DATA_BALLAST, 0)
    stop_at[0] = time.time() + 0.5
    burst.join(timeout=300)
    time.sleep(HOLD_FULL_S)
    run.container.free(DATA_BALLAST)
    history = workload.history()
    run.note(f"outcomes: {run.outcomes(history)}")
    run.note(f"background failures while full: {background_failures(run.since())}")
    run.check_stranded()
    run.recover_and_check(history)


def full_at_startup(run: Run):
    section(f"D4 full_at_startup (seed {run.seed}): a kill under load, then a start with no free space")
    workload = run.prepare()
    workload.start(connect)
    time.sleep(1.5)
    run.container.kill_server()
    history = workload.stop()
    run.note(f"outcomes before the kill: {run.outcomes(history)}")
    run.container.fill_to(DATA, DATA_BALLAST, 0)
    before = run.container.checksums()
    run.offset = len(run.container.output())
    up = run.container.start_server(timeout_s=60.0)
    output = run.since()
    fatal = [line for line in output.splitlines() if "-FATAL-" in line]
    if up:
        run.report("D4 a server that came up on a full disk answers reads",
                   reads_fail(run, FENCE_HELD_MARKER in output))
        run.container.kill_server()
    elif run.container.alive():
        run.report("D4 the server either refuses or comes up", [Divergence(
            "D4", run.seed, f"neither up nor exited after 60 s: {output[-1500:]}")])
        run.container.kill_server()
    else:
        run.report("D4 a refused start says so in exactly one FATAL line", [] if len(fatal) == 1 else [Divergence(
            "D4", run.seed, f"{len(fatal)} FATAL lines: {fatal[:3]} tail: {output[-1500:]}")])
    after = run.container.checksums()
    changed = sorted(path for path in set(before) | set(after) if before.get(path) != after.get(path))
    run.note(f"the start attempt {'came up' if up else 'exited'}; files it changed: {changed[:30]}")
    run.report("D4 a start on a full disk leaves no partial copy of a file behind",
               partial_copies(run, set(after) - set(before)))
    run.container.free(DATA_BALLAST)
    run.offset = len(run.container.output())
    if not run.container.start_server():
        run.report("D4 the server starts once space is freed", [Divergence(
            "O5", run.seed, f"no start after freeing: {run.since()[-1500:]}")])
        return
    run.check_clean_start()
    run.report("O1-O4 after the full start", run.check_all(history))
    run.recover_and_check(history)


def partial_copies(run: Run, created: set) -> list:
    return [Divergence("D4", run.seed, f"{path} was left behind by a rewrite that could not finish")
            for path in sorted(created) if path.endswith(PARTIAL_COPY_SUFFIXES)]


def reads_fail(run: Run, fence_held: bool) -> list:
    found = []
    with connect() as conn:
        for coll in run.schema.collections:
            answer = conn.send({"type": "AGGREGATE", "databaseName": run.schema.db, "collectionName": coll,
                                "aggregationSteps": [{"type": "COUNT"}]})
            if fence_held and answer.get("errorCode") == COLLECTION_FENCED:
                run.note(f"{coll}: COUNT answered {COLLECTION_FENCED}, held by a commit the full disk cannot apply")
            elif answer.get("status") != "OK":
                found.append(Divergence("D4", run.seed, f"{coll}: COUNT answered {answer.get('errorCode')} "
                                                        f"{str(answer.get('message'))[:200]}"))
    return found


def logs_full(run: Run):
    section(f"D5 logs_full (seed {run.seed}): {LOGS} is full; writes, reads, stats and LISTEN keep working "
            f"for {LOGS_FULL_S:.0f} s")
    run.prepare(docs=50)
    coll = run.schema.collections[0]
    db = run.schema.db
    run.offset = len(run.container.output())
    run.container.fill_to(LOGS, LOGS_BALLAST, 0)
    listener = connect()
    registered = listener.send({"type": "LISTEN", "databaseName": db, "collectionName": coll,
                                "aggregationSteps": [bu.filter_step("_id", "EQUALS", "d5-listened")]})
    found = [] if registered.get("status") == "OK" else [
        Divergence("D5", run.seed, f"LISTEN answered {registered.get('errorCode')}")]
    rng = random.Random(run.seed)
    deadline = time.time() + LOGS_FULL_S
    k = 0
    with connect() as conn:
        while time.time() < deadline:
            k += 1
            for label, request in (
                    ("SAVE", {"type": "SAVE", "databaseName": db, "collectionName": coll,
                              "object": d5_document(rng, f"d5-{k}")}),
                    ("FIND_BY_ID", {"type": "FIND_BY_ID", "databaseName": db, "collectionName": coll,
                                    "_id": f"d5-{k}"}),
                    ("GET_DATABASE_STATS", {"type": "GET_DATABASE_STATS"})):
                answer = conn.send(request, timeout=15.0)
                if answer.get("status") != "OK":
                    found.append(Divergence("D5", run.seed, f"{label} #{k} answered {answer.get('errorCode')} "
                                                            f"{str(answer.get('message'))[:200]}"))
            idle = idle_unauthenticated(UNAUTHENTICATED_PER_ROUND)
            time.sleep(0.5)
            for sock in idle:
                sock.close()
        conn.send({"type": "SAVE", "databaseName": db, "collectionName": coll,
                   "object": d5_document(rng, "d5-listened")})
    if not pushed(listener, 5.0):
        found.append(Divergence("D5", run.seed, "no LISTEN push arrived within 5 s of a matching write"))
    listener.close()
    refused = run.since().count("could not write log entry")
    run.note(f"{refused} log entries could not be written while {LOGS} was full")
    if refused == 0:
        bu.warn("D5 wrote no log entry while the log folder was full, so the window exercised nothing")
    run.report(f"D5 the server keeps serving for {LOGS_FULL_S:.0f} s with its log folder full", found)
    run.container.free(LOGS_BALLAST)
    run.check_stranded()


def d5_document(rng: random.Random, doc_id: str) -> dict:
    return {"_id": doc_id, "n": rng.randint(0, 50), "w": f"d5-{rng.random()}", "pad": "y" * rng.randint(100, 700)}


def idle_unauthenticated(count: int) -> list:
    return [socket.create_connection(("127.0.0.1", PORT), timeout=5.0) for _ in range(count)]


def pushed(listener, timeout_s: float) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        frame = listener.recv(timeout=max(deadline - time.time(), 0.1))
        if frame and frame.get("isUpdate") is True:
            return True
    return False


SCENARIOS = {
    "d1": ("fill_during_workload", fill_during_workload, {}),
    "d2": ("exact_headroom", exact_headroom, {}),
    "d3": ("full_at_background_work", full_at_background_work, {}),
    "d4": ("full_at_startup", full_at_startup, {}),
    "d5": ("logs_full", logs_full, D5_SETTINGS),
}


def selected(names: list) -> list:
    chosen = []
    for name in names:
        key = next((k for k, (title, _, _) in SCENARIOS.items() if name.lower() in (k, title)), None)
        if key is None:
            raise SystemExit(f"unknown scenario {name}; pick from {sorted(SCENARIOS)} or their names")
        chosen.append(key)
    return chosen


def main():
    parser = argparse.ArgumentParser(description="disk-full harness: a 48 MB tmpfs filled with a ballast file")
    parser.add_argument("--scenario", action="append", default=[], help="d1..d5 or a scenario name; repeatable")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--keep", action="store_true", help="leave the last container running")
    parser.add_argument("--report", help="write the divergences as JSON to this path")
    args = parser.parse_args()
    bu.banner("Disk-full harness", "127.0.0.1", PORT)
    summaries = []
    for key in selected(args.scenario or list(SCENARIOS)):
        title, scenario, settings = SCENARIOS[key]
        with Run(f"{key.upper()} {title}", args.seed, args.keep, settings) as run:
            try:
                scenario(run)
            except Exception as error:
                check(f"{run.name} ran to completion", False, f"{type(error).__name__}: {error}")
                run.divergences.append(Divergence("harness", args.seed, f"{type(error).__name__}: {error}"))
            run.scan_uncaught()
            summaries.append(run.summary())
    if args.report:
        with open(args.report, "w") as fp:
            json.dump(summaries, fp, indent=2)
    bu.summary()


if __name__ == "__main__":
    main()
