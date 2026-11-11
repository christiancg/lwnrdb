from __future__ import annotations

import math
import re
from datetime import datetime, time
from typing import TypeAlias

from lwnrdb._catalogue import DETECTION_REGEX
from lwnrdb.errors import MappingError
from lwnrdb.values.custom_type import CustomType
from lwnrdb.values.geo_point import GeoPoint
from lwnrdb.values.local_date_time import LocalDateTime
from lwnrdb.values.local_time import LocalTime
from lwnrdb.values.vector import Vector

DETECTION = re.compile(DETECTION_REGEX)
_SEPARATOR = ","

CustomValue: TypeAlias = LocalDateTime | LocalTime | GeoPoint | Vector
Decoded: TypeAlias = datetime | time | LocalDateTime | LocalTime | GeoPoint | Vector | str


def looks_custom(text: str) -> bool:
    return text.startswith("#") and DETECTION.match(text) is not None


def format_component(value: float) -> str:
    return repr(float(value))


def _wrap(kind: CustomType, body: str) -> str:
    return f"#{kind}({body})"


def format_custom(value: CustomValue) -> str:
    if isinstance(value, GeoPoint):
        return _wrap(CustomType.GEO, format_component(value.lat) + _SEPARATOR + format_component(value.lng))
    if isinstance(value, Vector):
        return _wrap(CustomType.VECTOR, _SEPARATOR.join(format_component(item) for item in value.to_list()))
    if isinstance(value, LocalDateTime):
        return _wrap(CustomType.DATETIME, str(value))
    return _wrap(CustomType.TIME, str(value))


def _components(body: str) -> list[float]:
    numbers = []
    for part in body.split(_SEPARATOR):
        trimmed = part.strip()
        number = float(trimmed)
        if not math.isfinite(number):
            raise ValueError(f"not a finite number: {part}")
        numbers.append(number)
    return numbers


def _geo(body: str) -> GeoPoint:
    components = _components(body)
    if len(components) != 2:
        raise ValueError("a geo value holds two coordinates")
    return GeoPoint(components[0], components[1])


def _date_time(body: str) -> datetime | LocalDateTime:
    parsed = LocalDateTime.parse(body)
    return parsed.to_datetime() if parsed.fits_datetime else parsed


def _time(body: str) -> time | LocalTime:
    parsed = LocalTime.parse(body)
    return parsed.to_time() if parsed.fits_time else parsed


def parse_custom(text: str) -> Decoded:
    name_end = text.index("(")
    name = text[1:name_end]
    body = text[name_end + 1 : -1]
    try:
        if name == CustomType.DATETIME:
            return _date_time(body)
        if name == CustomType.TIME:
            return _time(body)
        if name == CustomType.GEO:
            return _geo(body)
        if name == CustomType.VECTOR:
            return Vector(_components(body))
    except (ValueError, TypeError) as error:
        raise MappingError(f"the server sent a malformed {name} value: {text}") from error
    return text


def parse_custom_exact(text: str) -> CustomValue | str:
    decoded = parse_custom(text)
    if isinstance(decoded, datetime):
        return LocalDateTime.from_datetime(decoded)
    if isinstance(decoded, time):
        return LocalTime.from_time(decoded)
    return decoded
