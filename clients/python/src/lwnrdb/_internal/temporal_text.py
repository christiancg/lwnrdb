from __future__ import annotations

import re
from dataclasses import dataclass

NANOS_PER_MICRO = 1_000
NANOS_PER_MILLI = 1_000_000
MAX_NANO = 999_999_999
_TIME_PATTERN = re.compile(r"(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?")


@dataclass(frozen=True, slots=True)
class TimeFields:
    hour: int
    minute: int
    second: int
    nanosecond: int


def require_range(label: str, value: object, low: int, high: int) -> int:
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f"{label} must be an integer between {low} and {high}, was {value!r}")
    return value


def check_time(hour: object, minute: object, second: object, nanosecond: object) -> TimeFields:
    return TimeFields(
        require_range("hour", hour, 0, 23),
        require_range("minute", minute, 0, 59),
        require_range("second", second, 0, 59),
        require_range("nanosecond", nanosecond, 0, MAX_NANO),
    )


def format_time(fields: TimeFields) -> str:
    hours_and_minutes = f"{fields.hour:02d}:{fields.minute:02d}"
    if fields.second == 0 and fields.nanosecond == 0:
        return hours_and_minutes
    return f"{hours_and_minutes}:{fields.second:02d}{_format_fraction(fields.nanosecond)}"


def _format_fraction(nanosecond: int) -> str:
    if nanosecond == 0:
        return ""
    if nanosecond % NANOS_PER_MILLI == 0:
        return f".{nanosecond // NANOS_PER_MILLI:03d}"
    if nanosecond % NANOS_PER_MICRO == 0:
        return f".{nanosecond // NANOS_PER_MICRO:06d}"
    return f".{nanosecond:09d}"


def parse_time(text: str, label: str) -> TimeFields:
    match = _TIME_PATTERN.fullmatch(text)
    if match is None:
        raise ValueError(f"not a {label}: {text}")
    hour, minute, second, fraction = match.groups()
    return check_time(int(hour), int(minute), int(second or "0"), int((fraction or "").ljust(9, "0")))
