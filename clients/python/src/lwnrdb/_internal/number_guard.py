from __future__ import annotations

import math

from lwnrdb.errors import MappingError

MAX_SAFE_INTEGER = 2**53 - 1


def label(path: str) -> str:
    return "the value" if path == "" else f"field '{path}'"


def check_int(value: int, path: str) -> int:
    if abs(value) > MAX_SAFE_INTEGER:
        raise MappingError(f"{label(path)} exceeds 2^53, which the server stores as a double; store it as a string")
    return value


def check_float(value: float, path: str) -> float:
    if not math.isfinite(value):
        raise MappingError(f"{label(path)} is not a finite number: {value!r}")
    return value
