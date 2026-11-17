import random
import time

RANDOM = "random"
POINTED = "pointed"
FSYNC_FAILURE = "fsync-failure"
SEEDED_MODES = (RANDOM, POINTED, FSYNC_FAILURE)
MODE_SALT = {RANDOM: 1, POINTED: 2, FSYNC_FAILURE: 3}
POINTS = ["WAL_APPENDED", "WAL_GROUP_APPENDED", "WAL_GROUP_FORCED", "WAL_CHECKPOINT_FORCED", "WAL_REWRITE_BEGUN",
          "PAGE_VACUUM_RENAMED", "PK_INDEX_REPLACED", "INDEX_BATCH_FLUSHED", "PAGE_COPY_APPENDED", "PK_ROW_COMMITTED",
          "GROUP_RUNS_STAGED"]
KILL_WINDOW_S = (0.2, 3.0)
ARM_DELAY_S = (0.1, 1.0)
HALT_WAIT_S = 10.0
FAIL_RUN_S = 1.0
FAULT_HALT_STATUS = 137


def kill_process(proc) -> None:
    if proc.poll() is None:
        proc.kill()
    proc.wait(timeout=30)


def interrupt(mode: str, proc, rng: random.Random, schema, notes: list, send) -> None:
    if mode == RANDOM:
        delay = rng.uniform(*KILL_WINDOW_S)
        notes.append(f"SIGKILL after {delay:.2f} s")
        time.sleep(delay)
        kill_process(proc)
        return
    if mode == POINTED:
        point = rng.choice(POINTS)
        time.sleep(rng.uniform(*ARM_DELAY_S))
        armed = send({"type": "ARM_FAULT", "point": point, "action": "HALT"})
        try:
            code = proc.wait(timeout=HALT_WAIT_S)
            notes.append(f"HALT at {point} (exit {code}, armed {armed.get('status')})")
        except Exception:
            notes.append(f"{point} never fired within {HALT_WAIT_S:.0f} s, so the run ends with a SIGKILL")
        kill_process(proc)
        return
    coll = rng.choice(schema.collections)
    target = rng.choice([f"{coll}.wal", f"{coll}-pk.idx", f"{coll}-{rng.randint(0, 3)}.dat", f"{coll}.intents"])
    armed = send({"type": "ARM_FAULT", "point": "FILE_FORCED", "action": "FAIL", "match": target})
    time.sleep(FAIL_RUN_S)
    fired = send({"type": "AWAIT_FAULT", "point": "FILE_FORCED", "timeoutMs": 1}).get("status") == "OK"
    notes.append(f"FAIL armed on {target} ({armed.get('status')}), {'fired' if fired else 'never fired'}")
    kill_process(proc)
