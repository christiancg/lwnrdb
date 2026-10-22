import json
import os
import sys
from typing import Callable, List, Optional

ORDER = ("lwnrdb", "mysql", "postgres", "mongo", "couchbase")
MIB = 1024 * 1024


def load(run_dir: str) -> list:
    reports = []
    for name in sorted(os.listdir(run_dir)):
        if name.endswith(".json") and not name.startswith("run"):
            with open(os.path.join(run_dir, name)) as handle:
                reports.append(json.load(handle))
    rank = {engine: position for position, engine in enumerate(ORDER)}
    return sorted(reports, key=lambda report: rank.get(report["engine"], len(ORDER)))


def _table(headers: list, rows: list) -> List[str]:
    lines = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] + ["---:"] * (len(headers) - 1)) + "|"]
    lines += ["| " + " | ".join(row) + " |" for row in rows]
    return lines


def _phase_names(reports: list) -> list:
    names = []
    for report in reports:
        for phase in report["phases"]:
            if phase["phase"] not in names:
                names.append(phase["phase"])
    return names


def _phase(report: dict, name: str) -> Optional[dict]:
    return next((phase for phase in report["phases"] if phase["phase"] == name), None)


def _metric_table(reports: list, title: str, value: Callable[[dict], float], fmt: str, higher_is_better: bool,
                  bulk_label: Optional[str] = None) -> list:
    rows = []
    for name in _phase_names(reports):
        phases = [_phase(report, name) for report in reports]
        values = [value(phase) if phase else None for phase in phases]
        present = [v for v in values if v is not None]
        best = (max if higher_is_better else min)(present) if present else None
        cells = ["—" if v is None else (f"**{fmt.format(v)}**" if v == best and len(present) > 1 else fmt.format(v))
                 for v in values]
        label = f"{name} ({bulk_label})" if name == "bulk_insert" and bulk_label else name
        rows.append([label] + cells)
    return [f"### {title}", ""] + _table(["phase"] + [r["engine"] for r in reports], rows) + [""]


def _mib(value: Optional[int]) -> str:
    return "—" if value is None else f"{value / MIB:,.0f} MiB"


def _footer(reports: list) -> list:
    def cell(report: dict, key: str) -> str:
        return _mib(report.get("container", {}).get(key))

    def problems(report: dict) -> str:
        if report.get("failure"):
            return "FAILED"
        errors = sum(phase["errors"] for phase in report["phases"])
        invalid = sum(phase["invalid"] for phase in report["phases"])
        return f"{errors} / {invalid}"

    def stalls(report: dict) -> str:
        value = report.get("container", {}).get("gc_allocation_stalls")
        return "—" if value is None else str(value)

    rows = [
        ["peak container memory"] + [cell(report, "memory_peak") for report in reports],
        ["data on disk at the end"] + [cell(report, "data_bytes") for report in reports],
        ["final document count"] + [str(report.get("final_count", "—")) for report in reports],
        ["errors / invalid results"] + [problems(report) for report in reports],
    ]
    if any(report.get("container", {}).get("gc_allocation_stalls") is not None for report in reports):
        rows.insert(2, ["ZGC allocation stalls"] + [stalls(report) for report in reports])
    return ["### Container", ""] + _table([""] + [r["engine"] for r in reports], rows) + [""]


def _header(reports: list) -> list:
    first = reports[0]
    resources = first.get("resources", {})
    settings = first["settings"]
    return [
        "## Benchmark results", "",
        f"- Per engine container: {resources.get('cpus', '?')} CPUs (cpuset {resources.get('cpuset', '?')}), "
        f"{resources.get('memory', '?')} memory, no swap; engines run one at a time on fresh volumes",
        f"- Workload: {settings['docs']:,} documents of ~{settings['payload']} B payload, "
        f"{settings['concurrency']} client processes, bulk batches of {settings['batch']}, "
        f"ops scale {settings['ops_scale']}, warm-up {settings['warmup']:.0%} per phase (untimed), seed {settings['seed']}",
        f"- Durability: {_durability(first)} for every engine",
        "",
    ]


def _durability(report: dict) -> str:
    return report["settings"].get("durability", "relaxed")


def render(run_dir: str) -> str:
    reports = load(run_dir)
    if not reports:
        return f"No results in {run_dir}"
    modes = sorted({_durability(report) for report in reports})
    if len(modes) > 1:
        raise ValueError(f"{run_dir} mixes durability modes ({', '.join(modes)}); compare runs of one mode only")
    lines = _header(reports)
    lines += _metric_table(reports, "Throughput (higher is better)",
                           lambda p: p["docs_per_s"] if p["phase"] == "bulk_insert" else p["ops_per_s"],
                           "{:,.0f}", True, "docs/s")
    lines += _metric_table(reports, "p50 latency, ms (lower is better)", lambda p: p["p50_ms"], "{:,.2f}", False)
    lines += _metric_table(reports, "p99 latency, ms (lower is better)", lambda p: p["p99_ms"], "{:,.2f}", False)
    if any(phase.get("cpu_ms_per_op") is not None for report in reports for phase in report["phases"]):
        lines += _metric_table(reports, "Engine CPU per operation, ms (lower is better)",
                               lambda p: p.get("cpu_ms_per_op"), "{:,.3f}", False, "per batch")
    lines += _footer(reports)
    return "\n".join(lines)


def main(argv: list) -> int:
    run_dir = argv[0]
    try:
        text = render(run_dir)
    except ValueError as mixed:
        print(mixed)
        return 2
    with open(os.path.join(run_dir, "summary.md"), "w") as handle:
        handle.write(text + "\n")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
