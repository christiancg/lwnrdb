from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, datetime, time
from enum import Enum
from typing import TypeAlias, cast
from zoneinfo import ZoneInfo

from lwnrdb._internal.custom_values import CustomValue, format_custom, looks_custom, parse_custom
from lwnrdb._internal.number_guard import check_float, check_int, label
from lwnrdb.document import Document, Value
from lwnrdb.errors import MappingError
from lwnrdb.values.local_date_time import LocalDateTime
from lwnrdb.values.local_time import LocalTime

Json: TypeAlias = "str | int | float | bool | list[Json] | dict[str, Json] | None"
JsonObject: TypeAlias = "dict[str, Json]"


@dataclass(frozen=True, slots=True)
class EncodeOptions:
    temporal_zone: ZoneInfo | None = None


NO_ZONE = EncodeOptions()


def _child(path: str, key: str) -> str:
    return key if path == "" else f"{path}.{key}"


def _encode_text(value: str, path: str) -> str:
    if looks_custom(value):
        raise MappingError(
            f"{label(path)} looks like a custom value ({value}); the server would read it as one, "
            "so it cannot be stored as text"
        )
    return value


def _encode_datetime(value: datetime, path: str, options: EncodeOptions) -> str:
    if value.tzinfo is None:
        return format_custom(LocalDateTime.from_datetime(value))
    if options.temporal_zone is None:
        raise MappingError(
            f"{label(path)} is an aware datetime, an instant; set temporal_zone or store a naive datetime, "
            "since the server's date-time has no zone"
        )
    wall_clock = value.astimezone(options.temporal_zone).replace(tzinfo=None)
    return format_custom(LocalDateTime.from_datetime(wall_clock))


def _encode_time(value: time, path: str) -> str:
    if value.tzinfo is not None:
        raise MappingError(f"{label(path)} is a time with a zone; the server's time has no zone")
    return format_custom(LocalTime.from_time(value))


def _encode_mapping(value: Mapping[object, object], path: str, options: EncodeOptions) -> JsonObject:
    encoded: JsonObject = {}
    for key, item in value.items():
        if not isinstance(key, str):
            raise MappingError(f"{label(path)} has the key {key!r}; document keys must be strings")
        encoded[key] = encode_value(item, _child(path, key), options)
    return encoded


def _encode_sequence(value: list[object] | tuple[object, ...], path: str, options: EncodeOptions) -> list[Json]:
    return [encode_value(item, f"{path}[{index}]", options) for index, item in enumerate(value)]


def _encode_scalar(value: object, path: str, options: EncodeOptions) -> Json:
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return check_int(value, path)
    if isinstance(value, float):
        return check_float(value, path)
    if isinstance(value, datetime):
        return _encode_datetime(value, path, options)
    if isinstance(value, date):
        raise MappingError(f"{label(path)} is a date; the server has no date type, store a datetime")
    if isinstance(value, time):
        return _encode_time(value, path)
    raise MappingError(f"{label(path)} is a {type(value).__name__}, which has no JSON form")


def encode_value(value: object, path: str = "", options: EncodeOptions = NO_ZONE) -> Json:
    if value is None:
        return None
    if isinstance(value, Enum):
        return encode_value(value.value, path, options)
    if isinstance(value, str):
        return _encode_text(value, path)
    if isinstance(value, CustomValue):
        return format_custom(value)
    if isinstance(value, list | tuple):
        return _encode_sequence(value, path, options)
    if isinstance(value, Mapping):
        return _encode_mapping(value, path, options)
    return _encode_scalar(value, path, options)


def encode_document(value: object, options: EncodeOptions = NO_ZONE) -> JsonObject:
    if not isinstance(value, Mapping):
        raise MappingError(f"a document must be a mapping, got {type(value).__name__}")
    return _encode_mapping(value, "", options)


def decode_value(value: object) -> Value:
    if isinstance(value, str):
        return parse_custom(value) if looks_custom(value) else value
    if isinstance(value, list):
        return [decode_value(item) for item in value]
    if isinstance(value, dict):
        return decode_document(value)
    return cast("Value", value)


def decode_document(value: Mapping[str, object]) -> Document:
    return {key: decode_value(item) for key, item in value.items()}
