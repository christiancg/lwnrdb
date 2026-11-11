from __future__ import annotations

import calendar
import re
from dataclasses import dataclass
from datetime import MAXYEAR, MINYEAR, datetime

from lwnrdb._internal.temporal_text import (
    NANOS_PER_MICRO,
    check_time,
    format_time,
    parse_time,
    require_range,
)

MAX_YEAR = 999_999_999
_FOUR_DIGIT_YEAR = 9999
_DATE_TIME_PATTERN = re.compile(r"([+-]?\d{4,9})-(\d{2})-(\d{2})T(.+)")


def _days_in_month(year: int, month: int) -> int:
    if month == 2:
        return 29 if calendar.isleap(year) else 28
    return 30 if month in {4, 6, 9, 11} else 31


def _format_year(year: int) -> str:
    if abs(year) <= _FOUR_DIGIT_YEAR:
        return f"-{-year:04d}" if year < 0 else f"{year:04d}"
    return f"+{year}" if year > 0 else str(year)


@dataclass(frozen=True, order=True, slots=True)
class LocalDateTime:
    year: int
    month: int
    day: int
    hour: int = 0
    minute: int = 0
    second: int = 0
    nanosecond: int = 0

    def __post_init__(self) -> None:
        require_range("year", self.year, -MAX_YEAR, MAX_YEAR)
        require_range("month", self.month, 1, 12)
        require_range("day", self.day, 1, _days_in_month(self.year, self.month))
        check_time(self.hour, self.minute, self.second, self.nanosecond)

    @classmethod
    def of(
        cls,
        year: int,
        month: int,
        day: int,
        hour: int = 0,
        minute: int = 0,
        second: int = 0,
        nanosecond: int = 0,
    ) -> LocalDateTime:
        return cls(year, month, day, hour, minute, second, nanosecond)

    @classmethod
    def parse(cls, text: str) -> LocalDateTime:
        match = _DATE_TIME_PATTERN.fullmatch(text)
        if match is None:
            raise ValueError(f"not a date-time: {text}")
        year, month, day, clock = match.groups()
        fields = parse_time(clock, "date-time")
        return cls(int(year), int(month), int(day), fields.hour, fields.minute, fields.second, fields.nanosecond)

    @classmethod
    def from_datetime(cls, value: datetime) -> LocalDateTime:
        if value.tzinfo is not None:
            raise ValueError("an aware datetime needs a zone to become a LocalDateTime; convert it first")
        return cls(
            value.year,
            value.month,
            value.day,
            value.hour,
            value.minute,
            value.second,
            value.microsecond * NANOS_PER_MICRO,
        )

    @property
    def fits_datetime(self) -> bool:
        return MINYEAR <= self.year <= MAXYEAR and self.nanosecond % NANOS_PER_MICRO == 0

    def to_datetime(self) -> datetime:
        if not MINYEAR <= self.year <= MAXYEAR:
            raise ValueError(f"year {self.year} is outside the range a Python datetime holds")
        return datetime(
            self.year,
            self.month,
            self.day,
            self.hour,
            self.minute,
            self.second,
            self.nanosecond // NANOS_PER_MICRO,
        )

    def __str__(self) -> str:
        clock = format_time(check_time(self.hour, self.minute, self.second, self.nanosecond))
        return f"{_format_year(self.year)}-{self.month:02d}-{self.day:02d}T{clock}"
