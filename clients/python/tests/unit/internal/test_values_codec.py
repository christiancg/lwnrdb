from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta, timezone
from decimal import Decimal
from enum import Enum, IntEnum
from zoneinfo import ZoneInfo

import pytest

from lwnrdb import GeoPoint, LocalDateTime, LocalTime, MappingError, Vector
from lwnrdb._internal.custom_values import format_custom, looks_custom, parse_custom, parse_custom_exact
from lwnrdb._internal.values_codec import EncodeOptions, decode_value, encode_document, encode_value


class Color(Enum):
    RED = "red"


class Level(IntEnum):
    HIGH = 3


def test_scalars_and_containers_encode_to_json() -> None:
    encoded = encode_document(
        {"s": "x", "i": 3, "f": 1.5, "b": True, "n": None, "l": [1, (2, 3)], "d": {"k": False}, "e": Color.RED}
    )
    assert encoded == {
        "s": "x",
        "i": 3,
        "f": 1.5,
        "b": True,
        "n": None,
        "l": [1, [2, 3]],
        "d": {"k": False},
        "e": "red",
    }
    assert encode_value(Level.HIGH) == 3
    assert encode_value(True) is True


def test_custom_values_encode_to_their_wire_text() -> None:
    assert encode_value(GeoPoint(40.71, -74)) == "#geo(40.71,-74.0)"
    assert encode_value(Vector.of(0.12, -0.0, 1e21)) == "#vector(0.12,-0.0,1e+21)"
    assert encode_value(datetime(2024, 1, 1, 10, 0)) == "#datetime(2024-01-01T10:00)"
    assert encode_value(datetime(2024, 1, 1, 10, 0, 5, 120_000)) == "#datetime(2024-01-01T10:00:05.120)"
    assert encode_value(time(9, 30)) == "#time(09:30)"
    assert encode_value(LocalDateTime.of(2024, 1, 1, 0, 0, 0, 1)) == "#datetime(2024-01-01T00:00:00.000000001)"
    assert encode_value(LocalTime.of(9, 30, 0, 123_456_000)) == "#time(09:30:00.123456)"


def test_an_aware_datetime_needs_a_temporal_zone() -> None:
    aware = datetime(2024, 1, 1, 12, 0, tzinfo=UTC)
    with pytest.raises(MappingError, match="temporal_zone"):
        encode_value(aware, "placedAt")
    options = EncodeOptions(ZoneInfo("America/Argentina/Buenos_Aires"))
    assert encode_value(aware, "", options) == "#datetime(2024-01-01T09:00)"
    plus_two = datetime(2024, 1, 1, 12, 0, tzinfo=timezone(timedelta(hours=2)))
    assert encode_value(plus_two, "", EncodeOptions(ZoneInfo("UTC"))) == "#datetime(2024-01-01T10:00)"


@pytest.mark.parametrize(
    ("value", "message"),
    [
        (2**53, "exceeds 2\\^53"),
        (float("nan"), "not a finite number"),
        (float("inf"), "not a finite number"),
        (Decimal("1.5"), "Decimal"),
        (b"bytes", "bytes"),
        ({1, 2}, "set"),
        (date(2024, 1, 1), "no date type"),
        (time(9, 0, tzinfo=UTC), "time with a zone"),
        ("#geo(1,2)", "looks like a custom value"),
    ],
)
def test_values_without_a_wire_form_are_refused(value: object, message: str) -> None:
    with pytest.raises(MappingError, match=message):
        encode_value(value, "field")


def test_refusals_name_the_field_path() -> None:
    with pytest.raises(MappingError, match=r"field 'a\.b\[1\]'"):
        encode_document({"a": {"b": [1, float("nan")]}})
    with pytest.raises(MappingError, match=r"has the key 1"):
        encode_document({"a": {1: "x"}})
    with pytest.raises(MappingError, match="must be a mapping"):
        encode_document(["not", "a", "document"])


def test_text_that_merely_starts_with_a_hash_is_plain_text() -> None:
    assert encode_value("#hashtag") == "#hashtag"
    assert not looks_custom("#ab(1)")
    assert looks_custom("#abc(1)")


def test_decoding_turns_custom_text_into_values() -> None:
    decoded = decode_value(
        {
            "at": "#datetime(2024-01-01T10:00)",
            "t": "#time(09:30:15.5)",
            "loc": "#geo(1,2)",
            "emb": ["#vector(1,2.5)"],
            "other": "#unknown(1)",
            "n": 1,
        }
    )
    assert decoded == {
        "at": datetime(2024, 1, 1, 10, 0),
        "t": time(9, 30, 15, 500_000),
        "loc": GeoPoint(1, 2),
        "emb": [Vector.of(1, 2.5)],
        "other": "#unknown(1)",
        "n": 1,
    }


def test_values_beyond_datetime_decode_losslessly() -> None:
    nanos = parse_custom("#datetime(2024-01-01T10:00:00.000000001)")
    assert nanos == LocalDateTime.of(2024, 1, 1, 10, 0, 0, 1)
    far = parse_custom("#datetime(+10000-01-01T00:00)")
    assert far == LocalDateTime.of(10000, 1, 1)
    assert parse_custom("#time(10:00:00.000000001)") == LocalTime.of(10, 0, 0, 1)
    assert isinstance(nanos, LocalDateTime)
    assert format_custom(nanos) == "#datetime(2024-01-01T10:00:00.000000001)"


@pytest.mark.parametrize(
    "text",
    ["#geo(1)", "#geo(1,2,3)", "#geo(a,b)", "#vector(1,nan)", "#datetime(2024-13-01T10:00)", "#time(25:00)"],
)
def test_a_malformed_value_of_a_known_type_raises(text: str) -> None:
    with pytest.raises(MappingError, match="malformed"):
        parse_custom(text)


def test_parse_custom_exact_keeps_the_exact_types() -> None:
    assert parse_custom_exact("#datetime(2024-01-01T10:00)") == LocalDateTime.of(2024, 1, 1, 10, 0)
    assert parse_custom_exact("#time(10:00)") == LocalTime.of(10, 0)
    assert parse_custom_exact("#geo(1,2)") == GeoPoint(1, 2)
