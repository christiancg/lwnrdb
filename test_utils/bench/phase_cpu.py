import os
import re
import subprocess
from typing import Callable, Dict, List, Optional

StatReader = Callable[[], str]
START = "PHASE_START"
END = "PHASE_END"
USAGE_USEC = re.compile(r"^usage_usec\s+(\d+)\s*$", re.MULTILINE)


def parse_usage_usec(text: str) -> Optional[int]:
    match = USAGE_USEC.search(text)
    return int(match.group(1)) if match else None


def follow(command: List[str], env: Dict[str, str], on_line: Callable[[str], None]) -> int:
    with subprocess.Popen(command, env={**os.environ, **env}, stdout=subprocess.PIPE, text=True) as process:
        for line in process.stdout:
            on_line(line)
        status = process.wait()
    return status


def _seconds_between(first: Optional[int], second: Optional[int]) -> Optional[float]:
    if first is None or second is None or second < first:
        return None
    return (second - first) / 1e6


class PhaseCpu:
    def __init__(self, read_stat: StatReader):
        self._read_stat = read_stat
        self._order: List[str] = []
        self._started: Dict[str, Optional[int]] = {}
        self._ended: Dict[str, Optional[int]] = {}
        self._operations: Dict[str, int] = {}

    def on_line(self, line: str) -> None:
        parts = line.split()
        if len(parts) >= 2 and parts[0] == START:
            self._order.append(parts[1])
            self._started[parts[1]] = parse_usage_usec(self._read_stat())
        elif len(parts) >= 3 and parts[0] == END and parts[1] in self._started and parts[2].isdigit():
            self._ended[parts[1]] = parse_usage_usec(self._read_stat())
            self._operations[parts[1]] = int(parts[2])

    def cpu_seconds(self) -> Dict[str, Optional[float]]:
        seconds = {}
        for index, name in enumerate(self._order):
            last = index + 1 == len(self._order)
            boundary = self._ended.get(name) if last else self._started.get(self._order[index + 1])
            seconds[name] = _seconds_between(self._started.get(name), boundary)
        return seconds

    def attach(self, report: dict) -> None:
        seconds = self.cpu_seconds()
        for phase in report.get("phases", []):
            name = phase["phase"]
            operations = phase.get("ops_with_warmup") or self._operations.get(name)
            if seconds.get(name) is not None and operations:
                phase["cpu_seconds"] = round(seconds[name], 3)
                phase["cpu_ms_per_op"] = round(seconds[name] * 1000 / operations, 4)
