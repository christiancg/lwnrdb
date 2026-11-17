from __future__ import annotations

from dataclasses import dataclass
from datetime import time

from lwnrdb._internal.temporal_text import NANOS_PER_MICRO, check_time, format_time, parse_time


@dataclass(frozen=True, order=True, slots=True)
class LocalTime:
    hour: int
    minute: int
    second: int = 0
    nanosecond: int = 0

    def __post_init__(self) -> None:
        check_time(self.hour, self.minute, self.second, self.nanosecond)

    @classmethod
    def of(cls, hour: int, minute: int, second: int = 0, nanosecond: int = 0) -> LocalTime:
        return cls(hour, minute, second, nanosecond)

    @classmethod
    def parse(cls, text: str) -> LocalTime:
        fields = parse_time(text, "time")
        return cls(fields.hour, fields.minute, fields.second, fields.nanosecond)

    @classmethod
    def from_time(cls, value: time) -> LocalTime:
        if value.tzinfo is not None:
            raise ValueError("a time with a zone has no LWNRDB form; the server's time has no zone")
        return cls(value.hour, value.minute, value.second, value.microsecond * NANOS_PER_MICRO)

    @property
    def fits_time(self) -> bool:
        return self.nanosecond % NANOS_PER_MICRO == 0

    def to_time(self) -> time:
        return time(self.hour, self.minute, self.second, self.nanosecond // NANOS_PER_MICRO)

    def __str__(self) -> str:
        return format_time(check_time(self.hour, self.minute, self.second, self.nanosecond))
