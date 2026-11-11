from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
PROTOCOL = ROOT.parent / "protocol"
TARGET = ROOT / "src" / "lwnrdb" / "_catalogue.py"


def _read(name: str) -> dict[str, Any]:
    loaded: dict[str, Any] = json.loads((PROTOCOL / name).read_text(encoding="utf-8"))
    return loaded


def _tuple_block(name: str, rows: list[tuple[object, ...]]) -> list[str]:
    return [f"{name} = (", *(f"    {row!r}," for row in rows), ")", ""]


def _enum_block(name: str, members: list[tuple[str, str]]) -> list[str]:
    return [f"class {name}(StrEnum):", *(f"    {member} = {value!r}" for member, value in members), "", ""]


def render() -> str:
    operations = _read("operations.json")["operations"]
    error_codes = _read("error-codes.json")["errorCodes"]
    custom_types = _read("custom-types.json")
    lines = [
        "from enum import StrEnum",
        "",
        "",
        *_enum_block("Operation", [(entry["name"], entry["name"]) for entry in operations]),
        *_enum_block("ErrorCode", [(entry["name"], entry["code"]) for entry in error_codes]),
        f"DETECTION_REGEX = {custom_types['detectionRegex']!r}",
        "",
        f"GEO_DISTANCE_COMPARATORS = {tuple(custom_types['geoDistanceComparators'])!r}",
        "",
        *_tuple_block(
            "CUSTOM_TYPES",
            [
                (entry["name"], tuple(entry["predicateOperators"]), tuple(entry["rankingOperators"]))
                for entry in custom_types["types"]
            ],
        ),
        *_tuple_block(
            "OPERATIONS",
            [
                (entry["name"], entry["scope"], entry["adminOnly"], entry["allowedInTransaction"])
                for entry in operations
            ],
        ),
        *_tuple_block(
            "ERROR_CODES",
            [
                (entry["name"], entry["code"], entry["status"], entry["retryable"], entry["message"])
                for entry in error_codes
            ],
        ),
    ]
    return "\n".join(lines)


def main(arguments: list[str]) -> int:
    if arguments == ["--write"]:
        TARGET.write_text(render(), encoding="utf-8")
        return 0
    if arguments == ["--check"]:
        if TARGET.read_text(encoding="utf-8") != render():
            sys.stderr.write(f"{TARGET} differs from clients/protocol; run build_tools/catalogue.py --write\n")
            return 1
        return 0
    sys.stderr.write("usage: catalogue.py --write | --check\n")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
