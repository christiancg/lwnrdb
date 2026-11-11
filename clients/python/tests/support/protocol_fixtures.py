from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

PROTOCOL_ROOT = Path(__file__).resolve().parents[3] / "protocol"


@dataclass(frozen=True)
class FixtureCase:
    name: str
    body: dict[str, Any]


def read(file: str) -> dict[str, Any]:
    loaded: dict[str, Any] = json.loads((PROTOCOL_ROOT / file).read_text(encoding="utf-8"))
    return loaded


def _cases(folder: str) -> list[FixtureCase]:
    directory = PROTOCOL_ROOT / "fixtures" / folder
    return [
        FixtureCase(body["name"], body)
        for path in sorted(directory.glob("*.json"))
        for body in read(f"fixtures/{folder}/{path.name}")["cases"]
    ]


def requests() -> list[FixtureCase]:
    return _cases("requests")


def responses() -> list[FixtureCase]:
    return _cases("responses")
