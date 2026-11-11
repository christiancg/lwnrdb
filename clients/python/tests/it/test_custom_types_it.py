from __future__ import annotations

import math
from collections.abc import AsyncIterator
from datetime import UTC, datetime, time
from typing import Any

import pytest

from lwnrdb import (
    AsyncCollection,
    AsyncLwnrdbClient,
    GeoDistanceComparator,
    GeoPoint,
    LocalDateTime,
    LocalTime,
    LwnrdbError,
    MappingError,
    Order,
    Vector,
    distance,
    field,
    nearest,
    within,
)
from tests.it.conftest import ids, settings_for
from tests.support.server_process import ServerProcess

pytestmark = pytest.mark.it
SERVER_CONFIG = {"maxVectorDimensions": "8"}

NEW_YORK = GeoPoint(40.7128, -74.006)
NEWARK = GeoPoint(40.7357, -74.1724)
LONDON = GeoPoint(51.5074, -0.1278)


@pytest.fixture
async def places(client: AsyncLwnrdbClient) -> AsyncIterator[AsyncCollection[Any, Any]]:
    shop = client.database("types_it")
    await shop.create()
    collection = shop.collection("places")
    await collection.create()
    await collection.save_all(
        [
            {
                "_id": "ny",
                "location": NEW_YORK,
                "openedAt": datetime(2024, 1, 1, 10, 0),
                "opensAt": time(9, 30),
                "embedding": Vector.of(1, 0, 0),
            },
            {
                "_id": "nwk",
                "location": NEWARK,
                "openedAt": datetime(2023, 6, 1, 8, 15, 30, 250_000),
                "opensAt": time(7, 0),
                "embedding": Vector.of(0.9, 0.1, 0),
            },
            {
                "_id": "ldn",
                "location": LONDON,
                "openedAt": LocalDateTime.of(2025, 2, 3, 4, 5, 6, 7),
                "opensAt": LocalTime.of(11, 0, 0, 1),
                "embedding": Vector.of(0, 0, 1),
            },
        ]
    )
    yield collection
    await shop.drop()


async def test_custom_values_round_trip(places: AsyncCollection[Any, Any]) -> None:
    ny = await places.find_by_id("ny")
    assert ny is not None
    assert ny["location"] == NEW_YORK
    assert ny["openedAt"] == datetime(2024, 1, 1, 10, 0)
    assert ny["opensAt"] == time(9, 30)
    assert ny["embedding"] == Vector.of(1, 0, 0)
    london = await places.find_by_id("ldn")
    assert london is not None
    assert london["openedAt"] == LocalDateTime.of(2025, 2, 3, 4, 5, 6, 7)
    assert london["opensAt"] == LocalTime.of(11, 0, 0, 1)
    await places.save(london)
    assert await places.find_by_id("ldn") == london


async def test_temporal_comparisons_and_sorting(places: AsyncCollection[Any, Any]) -> None:
    later = await places.query().filter(field("openedAt") > datetime(2023, 12, 31)).to_list()
    assert sorted(ids(later)) == ["ldn", "ny"]
    early = await places.query().filter(field("opensAt") <= time(9, 30)).to_list()
    assert sorted(ids(early)) == ["nwk", "ny"]
    ordered = await places.query().sort("openedAt", Order.DESC).to_list()
    assert ids(ordered) == ["ldn", "ny", "nwk"]


async def test_geo_filters(places: AsyncCollection[Any, Any]) -> None:
    near = (
        await places.query()
        .filter(distance("location", NEW_YORK, GeoDistanceComparator.SMALLER_THAN, 20_000))
        .to_list()
    )
    assert sorted(ids(near)) == ["nwk", "ny"]
    far = (
        await places.query()
        .filter(distance("location", NEW_YORK, GeoDistanceComparator.GREATER_THAN, 20_000))
        .to_list()
    )
    assert ids(far) == ["ldn"]
    box = within("location", GeoPoint(40, -75), GeoPoint(41, -75), GeoPoint(41, -73), GeoPoint(40, -73))
    assert sorted(ids(await places.query().filter(box).to_list())) == ["nwk", "ny"]
    assert ids(await places.query().filter(field("location").eq(LONDON)).to_list()) == ["ldn"]


async def test_vector_nearest(places: AsyncCollection[Any, Any]) -> None:
    exact = await places.query().filter(nearest("embedding", Vector.of(1, 0, 0), 2).exact()).to_list()
    assert sorted(ids(exact)) == ["nwk", "ny"]
    approximate = await places.query().filter(nearest("embedding", Vector.of(1, 0, 0), 1)).to_list()
    assert len(approximate) <= 1
    assert math.isclose(exact[0]["embedding"].component(0) + exact[1]["embedding"].component(0), 1.9)


async def test_an_aware_datetime_needs_a_temporal_zone(
    server: ServerProcess, places: AsyncCollection[Any, Any]
) -> None:
    aware = datetime(2024, 1, 1, 12, 0, tzinfo=UTC)
    with pytest.raises(MappingError, match="temporal_zone"):
        await places.save({"_id": "z", "at": aware})
    async with await AsyncLwnrdbClient.connect(settings_for(server, temporal_zone="America/New_York")) as zoned:
        stored = zoned.database("types_it").collection("places")
        await stored.save({"_id": "z", "at": aware})
        found = await stored.find_by_id("z")
    assert found is not None
    assert found["at"] == datetime(2024, 1, 1, 7, 0)


async def test_a_vector_over_the_limit_is_refused_and_the_connection_survives(
    places: AsyncCollection[Any, Any],
) -> None:
    with pytest.raises(LwnrdbError):
        await places.save({"_id": "big", "embedding": Vector.of(*range(1, 20))})
    assert await places.find_by_id("ny") is not None
