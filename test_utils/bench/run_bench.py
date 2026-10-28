import argparse
import datetime
import json
import math
import os
import re
import subprocess
import sys
from typing import Callable, Dict, List, Optional

import phase_cpu
import report
from engines.base import DURABILITIES
from engines.lwnrdb_engine import PASSWORD, USERNAME

HERE = os.path.dirname(os.path.abspath(__file__))
COMPOSE = ["docker", "compose", "-f", os.path.join(HERE, "docker-compose.yml")]
RESULTS = os.path.join(HERE, "results")
GENERATED = os.path.join(HERE, "generated")
MIB = 1024 * 1024
DEFAULT_HEAP_PERCENT = 35
CACHE_SHARE_OF_HEAP_DIVISOR = 2
GC_LOG = "/tmp/gc.log"
G1_PAUSE = re.compile(r"\bPause (Young|Remark|Cleanup|Full)\b.*?(\d+(?:\.\d+)?)ms\s*$")
P99 = 0.99
UNITS = {"k": 1024, "m": MIB, "g": 1024 * MIB}
DATA_DIRS = {
    "lwnrdb": "/app/db",
    "mysql": "/var/lib/mysql",
    "postgres": "/var/lib/postgresql",
    "mongo": "/data/db",
    "couchbase": "/opt/couchbase/var/lib/couchbase/data",
}


def parse_size(text: str) -> int:
    value = text.strip().lower().rstrip("ib").rstrip("b")
    if value and value[-1] in UNITS:
        return int(float(value[:-1]) * UNITS[value[-1]])
    return int(value)


def docker_cpus() -> int:
    output = subprocess.run(["docker", "info", "--format", "{{.NCPU}}"], capture_output=True, text=True, check=True)
    return int(output.stdout.strip())


def resource_env(cpus: int, memory: int, host_cpus: int, synced: bool) -> Dict[str, str]:
    mb = memory // MIB
    return {
        "BENCH_CPUS": str(cpus),
        "BENCH_CPUSET": f"0-{cpus - 1}",
        "BENCH_CLIENT_CPUSET": f"{cpus}-{host_cpus - 1}",
        "BENCH_MEMORY": f"{mb}m",
        "LWNRDB_HEAP_PERCENT": str(DEFAULT_HEAP_PERCENT),
        "LWNRDB_JAVA_OPTS": "",
        "MYSQL_BUFFER_POOL": f"{mb // 2}M",
        "POSTGRES_SHARED_BUFFERS": f"{mb // 4}MB",
        "POSTGRES_EFFECTIVE_CACHE": f"{mb * 3 // 4}MB",
        "MONGO_CACHE_GB": f"{max(0.25, (mb - 1024) / 2 / 1024):.2f}",
        "COUCHBASE_DATA_QUOTA": str(mb // 2),
        "COUCHBASE_INDEX_QUOTA": str(max(256, mb * 15 // 100)),
        "MYSQL_FLUSH_AT_COMMIT": "1" if synced else "2",
        "POSTGRES_SYNCHRONOUS_COMMIT": "on" if synced else "off",
    }


def write_lwnrdb_config(memory: int, synced: bool, heap_percent: int = DEFAULT_HEAP_PERCENT) -> None:
    os.makedirs(GENERATED, exist_ok=True)
    cache_budget_mb = memory * heap_percent // 100 // MIB // CACHE_SHARE_OF_HEAP_DIVISOR
    lines = ["port=8989", "filePath=db", "logPath=logs", f"defaultAdminUsername={USERNAME}",
             f"defaultAdminPassword={PASSWORD}", f"maxMemory={cache_budget_mb}mb", "maxConnections=500",
             f"syncWrites={str(synced).lower()}"]
    path = os.path.join(GENERATED, "lwnrdb.cfg")
    with open(path, "w") as handle:
        handle.write("\n".join(lines) + "\n")
    os.chmod(path, 0o644)


def compose(env: Dict[str, str], *args: str, capture: bool = False, check: bool = True):
    return subprocess.run(COMPOSE + list(args), env={**os.environ, **env}, check=check,
                          capture_output=capture, text=True)


def profiles(engines: List[str]) -> List[str]:
    return [flag for engine in engines for flag in ("--profile", engine)]


def _exec_int(env: Dict[str, str], engine: str, *command: str) -> Optional[int]:
    result = compose(env, "--profile", engine, "exec", "-T", engine, *command, capture=True, check=False)
    try:
        return int(result.stdout.split()[0])
    except (IndexError, ValueError):
        return None


def parse_g1_pauses(log: Optional[str]) -> Optional[dict]:
    if log is None:
        return None
    pauses = []
    full = 0
    for line in log.splitlines():
        match = G1_PAUSE.search(line)
        if match:
            pauses.append(float(match.group(2)))
            full += match.group(1) == "Full"
    pauses.sort()
    return {
        "count": len(pauses),
        "p99_ms": pauses[max(math.ceil(P99 * len(pauses)) - 1, 0)] if pauses else 0.0,
        "max_ms": pauses[-1] if pauses else 0.0,
        "full_gcs": full,
    }


def _gc_pauses(env: Dict[str, str]) -> Optional[dict]:
    result = compose(env, "--profile", "lwnrdb", "exec", "-T", "lwnrdb", "sh", "-c",
                     f"test -f {GC_LOG} && cat {GC_LOG}", capture=True, check=False)
    return parse_g1_pauses(result.stdout if result.returncode == 0 else None)


def measure(env: Dict[str, str], engine: str) -> dict:
    kilobytes = _exec_int(env, engine, "du", "-sk", DATA_DIRS[engine])
    return {
        "memory_peak": _exec_int(env, engine, "cat", "/sys/fs/cgroup/memory.peak"),
        "memory_current": _exec_int(env, engine, "cat", "/sys/fs/cgroup/memory.current"),
        "data_bytes": None if kilobytes is None else kilobytes * 1024,
        "gc_pauses": _gc_pauses(env) if engine == "lwnrdb" else None,
    }


def cpu_stat_reader(env: Dict[str, str], engine: str) -> phase_cpu.StatReader:
    def read() -> str:
        result = compose(env, "--profile", engine, "exec", "-T", engine, "cat", "/sys/fs/cgroup/cpu.stat",
                         capture=True, check=False)
        return result.stdout or ""

    return read


def relay(meter: phase_cpu.PhaseCpu) -> Callable[[str], None]:
    def forward(line: str) -> None:
        print(line, end="", flush=True)
        meter.on_line(line)

    return forward


def run_engine(env: Dict[str, str], engine: str, run_id: str, workload_args: List[str], keep: bool,
               durability: str) -> int:
    print(f"\n=== {engine} ===", flush=True)
    compose(env, "--profile", engine, "down", "-v", "--remove-orphans")
    compose(env, "--profile", engine, "up", "-d", engine)
    try:
        meter = phase_cpu.PhaseCpu(cpu_stat_reader(env, engine))
        status = phase_cpu.follow(COMPOSE + ["--profile", "client", "run", "--rm", "-T", "bench", "python", "-u",
                                             "bench_client.py", "--engine", engine, "--out",
                                             f"/results/{run_id}/{engine}.json", "--durability", durability,
                                             *workload_args], env, relay(meter))
        path = os.path.join(RESULTS, run_id, f"{engine}.json")
        if os.path.exists(path):
            with open(path) as handle:
                result = json.load(handle)
            meter.attach(result)
            result["container"] = measure(env, engine)
            result["resources"] = {"cpus": env["BENCH_CPUS"], "cpuset": env["BENCH_CPUSET"],
                                   "memory": env["BENCH_MEMORY"]}
            with open(path, "w") as handle:
                json.dump(result, handle, indent=2)
        return status
    finally:
        if not keep:
            compose(env, "--profile", engine, "down", "-v")


def _parse(argv: List[str]):
    parser = argparse.ArgumentParser(
        description="Benchmark lwnrdb against other engines, each in an identically limited container. "
                    "Unrecognised flags (--docs, --concurrency, --ops-scale, ...) go to bench_client.py.")
    parser.add_argument("--engines", default=",".join(report.ORDER))
    parser.add_argument("--cpus", type=int, default=2)
    parser.add_argument("--memory", default="4g")
    parser.add_argument("--durability", choices=DURABILITIES, default="relaxed",
                        help="relaxed: no engine syncs per commit; synced: every engine syncs every write")
    parser.add_argument("--keep", action="store_true", help="leave the last engine's container running")
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--heap-percent", type=int, default=DEFAULT_HEAP_PERCENT,
                        help="-XX:MaxRAMPercentage of the lwnrdb container; maxMemory is set to half the resulting heap")
    parser.add_argument("--java-opts", default="",
                        help="extra JVM flags for lwnrdb, passed as JDK_JAVA_OPTIONS; write --java-opts=-XX:SoftMaxHeapSize=1g "
                             "with an equals sign, because a value that starts with a dash is read as a flag")
    return parser.parse_known_args(argv)


def main(argv: List[str]) -> int:
    args, workload_args = _parse(argv)
    engines = [name.strip() for name in args.engines.split(",") if name.strip()]
    unknown = [name for name in engines if name not in DATA_DIRS]
    if unknown:
        print(f"unknown engine(s): {', '.join(unknown)}; choose from {', '.join(report.ORDER)}")
        return 2
    host_cpus = docker_cpus()
    if args.cpus >= host_cpus:
        print(f"--cpus {args.cpus} leaves no CPU for the client: Docker has {host_cpus}")
        return 2
    memory = parse_size(args.memory)
    synced = args.durability == "synced"
    env = resource_env(args.cpus, memory, host_cpus, synced)
    env["LWNRDB_HEAP_PERCENT"] = str(args.heap_percent)
    env["LWNRDB_JAVA_OPTS"] = args.java_opts
    write_lwnrdb_config(memory, synced, args.heap_percent)
    run_id = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    os.makedirs(os.path.join(RESULTS, run_id), exist_ok=True)
    compose(env, *profiles(list(DATA_DIRS)), "down", "-v", "--remove-orphans")
    if not args.no_build:
        compose(env, *profiles([e for e in engines if e == "lwnrdb"] + ["client"]), "build")
    statuses = {engine: run_engine(env, engine, run_id, workload_args, args.keep and engine == engines[-1],
                                   args.durability)
                for engine in engines}
    run_dir = os.path.join(RESULTS, run_id)
    print()
    report.main([run_dir])
    print(f"\nResults: {run_dir}")
    failed = [engine for engine, status in statuses.items() if status != 0]
    if failed:
        print(f"Engines with errors: {', '.join(failed)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
