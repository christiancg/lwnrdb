import argparse
import dataclasses
import datetime
import json
import os
import sys

import runner
import workload
from engines import ENGINES, create
from engines.base import DURABILITIES

READY_TIMEOUT_S = 300


def _initialize(engine_name: str, durability: str) -> None:
    engine = create(engine_name, durability)
    engine.prepare()
    engine.wait_ready(READY_TIMEOUT_S)
    engine.setup()
    engine.close()


def _count_all(engine_name: str, durability: str) -> int:
    engine = create(engine_name, durability)
    engine.connect()
    try:
        return engine.count_all()
    finally:
        engine.close()


def _parse(argv: list) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the benchmark workload against one engine.")
    parser.add_argument("--engine", required=True, choices=sorted(ENGINES))
    parser.add_argument("--out", required=True)
    parser.add_argument("--docs", type=int, default=100_000)
    parser.add_argument("--batch", type=int, default=500)
    parser.add_argument("--payload", type=int, default=512)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--ops-scale", type=float, default=1.0)
    parser.add_argument("--warmup", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--durability", choices=DURABILITIES, default="relaxed")
    parser.add_argument("--phases", default="", help="comma-separated phases to run; bulk_insert always runs")
    return parser.parse_args(argv)


def _line(result: dict) -> str:
    problems = ""
    if result["errors"] or result["invalid"]:
        problems = f"  errors={result['errors']} invalid={result['invalid']} ({result['first_error']})"
    return (f"{result['ops_per_s']:>11,.1f} ops/s  {result['docs_per_s']:>11,.1f} docs/s  "
            f"p50 {result['p50_ms']:>8.3f}ms  p99 {result['p99_ms']:>8.3f}ms{problems}")


def main(argv: list) -> int:
    args = _parse(argv)
    settings = workload.Settings(docs=args.docs, batch=args.batch, payload=args.payload,
                                 concurrency=args.concurrency, ops_scale=args.ops_scale,
                                 warmup=args.warmup, seed=args.seed, durability=args.durability)
    report = {"engine": args.engine, "started_at": datetime.datetime.now(datetime.UTC).isoformat(),
              "settings": dataclasses.asdict(settings), "phases": []}
    print(f"[{args.engine}] initializing", flush=True)
    runner.in_child(lambda: _initialize(args.engine, args.durability))
    status = 0
    try:
        selected = {name.strip() for name in args.phases.split(",") if name.strip()}
        for phase in workload.phases(settings):
            if selected and phase.name not in selected and phase.name != "bulk_insert":
                continue
            print(f"[{args.engine}] {phase.name:<22}", end="", flush=True)
            result = runner.run_phase(args.engine, phase, settings)
            report["phases"].append(result)
            print(_line(result), flush=True)
            status |= 1 if result["errors"] or result["invalid"] else 0
        report["final_count"] = runner.in_child(lambda: _count_all(args.engine, args.durability))
        report["expected_count"] = settings.docs
        if not selected and report["final_count"] != settings.docs:
            print(f"[{args.engine}] final count {report['final_count']} != expected {settings.docs}", flush=True)
            status = 1
    except runner.PhaseFailed as failure:
        report["failure"] = str(failure)
        print(f"\n[{args.engine}] FAILED: {failure}", flush=True)
        status = 2
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as handle:
        json.dump(report, handle, indent=2)
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
