#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[1]
BENCH = REPO / "test_utils" / "bench"
sys.path.insert(0, str(BENCH))

import run_bench

JFR_SETTINGS = "settings=profile,dumponexit=true,jdk.ExecutionSample#period=5ms,jdk.ObjectAllocationSample#throttle=off"
DOCKERFILE = "FROM eclipse-temurin:27-jdk\nCOPY lwnrdb.jar /app/app.jar\nWORKDIR /app\n"
OVERRIDE = """services:
  lwnrdb:
    build:
      context: {context}
    image: lwnrdb-profile
    command:
      - java
      - -XX:+UseZGC
      - -XX:MaxRAMPercentage=70
      - -XX:+UnlockDiagnosticVMOptions
      - -XX:+DebugNonSafepoints
      - -XX:StartFlightRecording=filename=/profile/recording.jfr,{jfr}
      - -jar
      - app.jar
    volumes:
      - lwnrdb-data:/app/db
      - {config}:/app/lwnrdb.cfg:ro
      - {output}:/profile
"""


def parse(argv: list[str]) -> tuple[argparse.Namespace, list[str]]:
    parser = argparse.ArgumentParser(
        description="Run the Docker bench against a JFR-recording lwnrdb and keep the recording and a "
                    "timestamped client log, so ProfileReport.java can split the samples per phase. "
                    "Unrecognised flags (--docs, --ops-scale, --phases, ...) go to bench_client.py.")
    parser.add_argument("name", help="base name of the .jfr and .log files")
    parser.add_argument("--jar", default=str(REPO / "target" / "lwnrdb.jar"))
    parser.add_argument("--out", default=str(BENCH / "results" / "profiles"))
    parser.add_argument("--cpus", type=int, default=2)
    parser.add_argument("--memory", default="4g")
    parser.add_argument("--durability", choices=("relaxed", "synced"), default="synced")
    return parser.parse_known_args(argv)


def stream(command: list[str], env: dict[str, str], log_path: pathlib.Path) -> int:
    process = subprocess.Popen(command, env={**os.environ, **env}, stdout=subprocess.PIPE, text=True)
    with open(log_path, "w") as log:
        for line in process.stdout:
            stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
            log.write(f"{stamp} {line}")
            log.flush()
            print(line, end="", flush=True)
    return process.wait()


def main(argv: list[str]) -> int:
    args, workload = parse(argv)
    jar = pathlib.Path(args.jar).resolve()
    if not jar.is_file():
        print(f"jar not found: {jar}")
        return 2
    out = pathlib.Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    memory = run_bench.parse_size(args.memory)
    synced = args.durability == "synced"
    env = run_bench.resource_env(args.cpus, memory, run_bench.docker_cpus(), synced)
    run_bench.write_lwnrdb_config(memory, synced)
    with tempfile.TemporaryDirectory() as scratch, tempfile.TemporaryDirectory() as recording:
        context = pathlib.Path(scratch)
        (context / "Dockerfile").write_text(DOCKERFILE)
        shutil.copy(jar, context / "lwnrdb.jar")
        override = context / "override.yml"
        override.write_text(OVERRIDE.format(context=context, jfr=JFR_SETTINGS, output=recording,
                                            config=pathlib.Path(run_bench.GENERATED) / "lwnrdb.cfg"))
        run_bench.COMPOSE = run_bench.COMPOSE + ["-f", str(override)]
        run_bench.compose(env, "--profile", "lwnrdb", "down", "-v", "--remove-orphans")
        try:
            run_bench.compose(env, "--profile", "lwnrdb", "--profile", "client", "build")
            run_bench.compose(env, "--profile", "lwnrdb", "up", "-d", "lwnrdb")
            status = stream(run_bench.COMPOSE + ["--profile", "client", "run", "--rm", "bench", "python", "-u",
                                                  "bench_client.py", "--engine", "lwnrdb", "--out",
                                                  f"/results/profile-{args.name}/lwnrdb.json", "--durability",
                                                  args.durability, *workload], env, out / f"{args.name}.log")
        finally:
            run_bench.compose(env, "--profile", "lwnrdb", "stop", "-t", "120", check=False)
            run_bench.compose(env, "--profile", "lwnrdb", "down", "-v", check=False)
        produced = pathlib.Path(recording) / "recording.jfr"
        if not produced.is_file():
            print("the server wrote no recording")
            return 1
        shutil.move(str(produced), out / f"{args.name}.jfr")
    print(f"\nrecording: {out / (args.name + '.jfr')}\nlog:       {out / (args.name + '.log')}")
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
