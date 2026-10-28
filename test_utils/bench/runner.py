import multiprocessing
import queue
import random
import threading
import time
import traceback
import zlib
from typing import Any, Callable

from engines import create
from workload import Phase, Settings

CONTEXT = multiprocessing.get_context("fork")
PHASE_TIMEOUT_S = 6 * 3600


class PhaseFailed(RuntimeError):
    pass


def _attempt(phase: Phase, engine, argument) -> tuple:
    try:
        result = phase.execute(engine, argument)
    except Exception as error:
        return False, f"{type(error).__name__}: {error}"
    return True, None if phase.check(argument, result) else "invalid"


def _arguments(phase: Phase, rng: random.Random, first: int, stop: int, step: int) -> list:
    return [phase.argument(rng, n) for n in range(first, stop, step)]


def _worker(engine_name: str, phase: Phase, settings: Settings, index: int, barrier, results) -> None:
    workers = settings.concurrency
    try:
        engine = create(engine_name, settings.durability)
        engine.connect()
        rng = random.Random(settings.seed * 7919 + zlib.crc32(phase.name.encode()) + index)
        warmup = _arguments(phase, rng, phase.ops + index, phase.ops + phase.warmup, workers)
        timed = _arguments(phase, rng, index, phase.ops, workers)
        for argument in warmup:
            _attempt(phase, engine, argument)
        barrier.wait()
    except threading.BrokenBarrierError:
        results.put({"index": index, "fatal": "another worker failed before the timed run"})
        return
    except BaseException:
        barrier.abort()
        results.put({"index": index, "fatal": traceback.format_exc()})
        return
    latencies = []
    errors = 0
    invalid = 0
    first_error = None
    start = time.monotonic_ns()
    for argument in timed:
        began = time.perf_counter_ns()
        ok, problem = _attempt(phase, engine, argument)
        elapsed = time.perf_counter_ns() - began
        if not ok:
            errors += 1
            first_error = first_error or problem
            continue
        latencies.append(elapsed)
        if problem is not None:
            invalid += 1
            first_error = first_error or f"invalid result for {str(argument)[:120]}"
    end = time.monotonic_ns()
    engine.close()
    results.put({"index": index, "start": start, "end": end, "latencies": latencies, "errors": errors,
                 "invalid": invalid, "first_error": first_error})


def _percentile(ordered: list, fraction: float) -> float:
    if not ordered:
        return 0.0
    return ordered[min(len(ordered) - 1, int(fraction * len(ordered)))] / 1e6


def _summarize(phase: Phase, outcomes: list) -> dict:
    latencies = sorted(value for outcome in outcomes for value in outcome["latencies"])
    seconds = max(1, max(o["end"] for o in outcomes) - min(o["start"] for o in outcomes)) / 1e9
    completed = len(latencies)
    documents = phase.documents or completed
    return {
        "phase": phase.name,
        "ops": completed,
        "seconds": round(seconds, 3),
        "ops_per_s": round(completed / seconds, 1),
        "docs_per_s": round(documents / seconds, 1),
        "mean_ms": round(sum(latencies) / completed / 1e6, 3) if completed else 0.0,
        "p50_ms": round(_percentile(latencies, 0.50), 3),
        "p95_ms": round(_percentile(latencies, 0.95), 3),
        "p99_ms": round(_percentile(latencies, 0.99), 3),
        "max_ms": round(latencies[-1] / 1e6, 3) if latencies else 0.0,
        "errors": sum(o["errors"] for o in outcomes),
        "invalid": sum(o["invalid"] for o in outcomes),
        "first_error": next((o["first_error"] for o in outcomes if o["first_error"]), None),
    }


def _collect(results, processes: list, timeout_s: float) -> list:
    deadline = time.monotonic() + timeout_s
    outcomes = []
    while len(outcomes) < len(processes):
        try:
            outcomes.append(results.get(timeout=5))
        except queue.Empty:
            if time.monotonic() > deadline:
                raise PhaseFailed("timed out waiting for the workers")
            if not any(process.is_alive() for process in processes) and results.empty():
                raise PhaseFailed("a worker died without reporting")
    for process in processes:
        process.join()
    return outcomes


def run_phase(engine_name: str, phase: Phase, settings: Settings) -> dict:
    barrier = CONTEXT.Barrier(settings.concurrency)
    results = CONTEXT.Queue()
    processes = [CONTEXT.Process(target=_worker, args=(engine_name, phase, settings, index, barrier, results))
                 for index in range(settings.concurrency)]
    for process in processes:
        process.start()
    outcomes = _collect(results, processes, PHASE_TIMEOUT_S)
    fatal = [outcome["fatal"] for outcome in outcomes if "fatal" in outcome]
    if fatal:
        raise PhaseFailed(f"{phase.name}: {fatal[0]}")
    return _summarize(phase, outcomes)


def _call(task: Callable[[], Any], results) -> None:
    try:
        results.put({"value": task()})
    except BaseException:
        results.put({"fatal": traceback.format_exc()})


def in_child(task: Callable[[], Any], timeout_s: float = 900) -> Any:
    results = CONTEXT.Queue()
    process = CONTEXT.Process(target=_call, args=(task, results))
    process.start()
    outcome = _collect(results, [process], timeout_s)[0]
    if "fatal" in outcome:
        raise PhaseFailed(outcome["fatal"])
    return outcome["value"]
