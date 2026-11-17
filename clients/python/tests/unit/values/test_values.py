from __future__ import annotations

from datetime import UTC, datetime, time

import pytest

from lwnrdb import GeoPoint, LocalDateTime, LocalTime, Vector


@pytest.mark.parametrize(
    ("value", "text"),
    [
        (LocalDateTime.of(2024, 1, 1, 10, 0), "2024-01-01T10:00"),
        (LocalDateTime.of(2024, 1, 1, 10, 0, 5), "2024-01-01T10:00:05"),
        (LocalDateTime.of(2024, 1, 1, 10, 0, 0, 120_000_000), "2024-01-01T10:00:00.120"),
        (LocalDateTime.of(2024, 1, 1, 10, 0, 0, 123_456_000), "2024-01-01T10:00:00.123456"),
        (LocalDateTime.of(2024, 1, 1, 10, 0, 0, 1), "2024-01-01T10:00:00.000000001"),
        (LocalDateTime.of(10_000, 1, 1), "+10000-01-01T00:00"),
        (LocalDateTime.of(-1, 1, 1), "-0001-01-01T00:00"),
        (LocalDateTime.of(-10_000, 1, 1), "-10000-01-01T00:00"),
        (LocalDateTime.of(2024, 2, 29), "2024-02-29T00:00"),
    ],
)
def test_local_date_time_prints_like_java_and_parses_back(value: LocalDateTime, text: str) -> None:
    assert str(value) == text
    assert LocalDateTime.parse(text) == value


@pytest.mark.parametrize(
    "fields",
    [(2023, 2, 29), (2024, 13, 1), (2024, 4, 31), (2024, 1, 1, 24), (1_000_000_000, 1, 1), (2024, 1, 1, 0, 0, 0, -1)],
)
def test_an_invalid_local_date_time_is_refused(fields: tuple[int, ...]) -> None:
    with pytest.raises(ValueError):
        LocalDateTime.of(*fields)


@pytest.mark.parametrize("text", ["2024-01-01", "2024-01-01T1:00", "2024-01-01T10:00:00.1234567890", "x"])
def test_unparseable_date_time_text_is_refused(text: str) -> None:
    with pytest.raises(ValueError, match="not a"):
        LocalDateTime.parse(text)


def test_local_date_time_converts_to_and_from_datetime() -> None:
    exact = LocalDateTime.of(2024, 5, 6, 7, 8, 9, 123_456_789)
    assert not exact.fits_datetime
    assert exact.to_datetime() == datetime(2024, 5, 6, 7, 8, 9, 123_456)
    assert LocalDateTime.from_datetime(datetime(2024, 5, 6, 7, 8, 9, 5)).nanosecond == 5000
    assert LocalDateTime.of(2024, 1, 1).fits_datetime
    with pytest.raises(ValueError, match="outside the range"):
        LocalDateTime.of(10_000, 1, 1).to_datetime()
    with pytest.raises(ValueError, match="aware"):
        LocalDateTime.from_datetime(datetime(2024, 1, 1, tzinfo=UTC))


def test_local_date_times_order_chronologically() -> None:
    assert LocalDateTime.of(2024, 1, 1) < LocalDateTime.of(2024, 1, 1, 0, 0, 0, 1) < LocalDateTime.of(2025, 1, 1)


def test_local_time_prints_parses_and_converts() -> None:
    assert str(LocalTime.of(9, 30)) == "09:30"
    assert str(LocalTime.of(9, 30, 15, 500_000_000)) == "09:30:15.500"
    assert LocalTime.parse("09:30:15.5") == LocalTime.of(9, 30, 15, 500_000_000)
    assert LocalTime.of(9, 30, 0, 1).to_time() == time(9, 30)
    assert not LocalTime.of(9, 30, 0, 1).fits_time
    assert LocalTime.from_time(time(1, 2, 3, 4)) == LocalTime.of(1, 2, 3, 4000)
    assert LocalTime.of(1, 0) < LocalTime.of(1, 0, 0, 1)
    with pytest.raises(ValueError, match="zone"):
        LocalTime.from_time(time(1, tzinfo=UTC))
    with pytest.raises(ValueError, match="minute"):
        LocalTime.of(1, 60)
    with pytest.raises(ValueError, match="hour"):
        LocalTime.of(True, 0)


def test_geo_point_validates_its_coordinates() -> None:
    point = GeoPoint.of(40, -74)
    assert point == GeoPoint(40.0, -74.0)
    assert isinstance(point.lat, float)
    for lat, lng in [(91, 0), (0, 181), (float("nan"), 0)]:
        with pytest.raises(ValueError):
            GeoPoint(lat, lng)
    with pytest.raises(TypeError):
        GeoPoint(True, 0)
    with pytest.raises(TypeError):
        GeoPoint("1", 0)


def test_vector_holds_finite_components() -> None:
    vector = Vector.of(1, 2.5)
    assert vector.dimensions == 2
    assert vector.component(1) == 2.5
    assert vector.to_list() == [1.0, 2.5]
    assert vector == Vector.from_iterable([1.0, 2.5])
    assert vector != [1.0, 2.5]
    assert hash(vector) == hash(Vector.of(1, 2.5))
    assert repr(vector) == "Vector([1.0, 2.5])"
    with pytest.raises(IndexError):
        vector.component(2)
    with pytest.raises(ValueError, match="at least one"):
        Vector.of()
    with pytest.raises(ValueError, match="not finite"):
        Vector.of(float("inf"))
    with pytest.raises(TypeError):
        Vector.of(True)
