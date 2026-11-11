from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Any

import pytest

from lwnrdb import (
    AsyncCollection,
    AsyncLwnrdbClient,
    Filter,
    MapOps,
    MidOps,
    Order,
    and_,
    field,
    nand,
    nor,
    or_,
    script,
    xor,
)
from tests.it.conftest import ids, same_with_and_without_index

pytestmark = pytest.mark.it

ROWS: list[dict[str, Any]] = [
    {"_id": "a", "status": "active", "total": 120, "name": "Ana", "items": [1, 2], "change": -3, "customerId": "c1"},
    {"_id": "b", "status": "active", "total": 40, "name": "Bruno", "items": [1], "change": 2, "customerId": "c2"},
    {"_id": "c", "status": "closed", "total": 300, "name": "Carla", "items": [], "change": -1, "customerId": "c1"},
    {"_id": "d", "status": "new", "total": 10, "name": "Dana", "items": [4, 5, 6], "change": 0},
]


@pytest.fixture
async def orders(client: AsyncLwnrdbClient) -> AsyncIterator[AsyncCollection[Any, Any]]:
    shop = client.database("query_it")
    await shop.create()
    collection = shop.collection("orders")
    await collection.create()
    await collection.save_all(ROWS)
    customers = shop.collection("customers")
    await customers.create()
    await customers.save_all([{"_id": "c1", "name": "Uno"}, {"_id": "c2", "name": "Dos"}])
    yield collection
    await shop.drop()


FILTERS: list[tuple[str, Filter, list[str]]] = [
    ("eq", field("status").eq("active"), ["a", "b"]),
    ("ne", field("status").ne("active"), ["c", "d"]),
    ("gt", field("total") > 40, ["a", "c"]),
    ("gte", field("total") >= 40, ["a", "b", "c"]),
    ("lt", field("total") < 40, ["d"]),
    ("lte", field("total").lte(40), ["b", "d"]),
    ("in", field("status").in_("new", "closed"), ["c", "d"]),
    ("not_in", field("status").not_in("active"), ["c", "d"]),
    ("contains", field("name").contains("na"), ["a", "d"]),
    ("and", field("status").eq("active") & (field("total") > 100), ["a"]),
    ("or", or_(field("status").eq("new"), field("total") > 200), ["c", "d"]),
    ("nor", nor(field("status").eq("active"), field("total") > 200), ["d"]),
    ("not", ~(field("total") < 100), ["a", "c"]),
    ("xor", xor(field("status").eq("active"), field("total") > 100), ["b", "c"]),
    ("nand", nand(field("status").eq("active"), field("total") > 100), ["b", "c", "d"]),
    ("nested", and_(field("status").ne("new"), or_(field("total") < 50, field("total") > 250)), ["b", "c"]),
]


@pytest.mark.parametrize(("name", "condition", "expected"), FILTERS, ids=[entry[0] for entry in FILTERS])
async def test_every_filter_agrees_with_and_without_an_index(
    orders: AsyncCollection[Any, Any], name: str, condition: Filter, expected: list[str]
) -> None:
    del name

    async def matching() -> list[str]:
        return sorted(ids(await orders.query().filter(condition).to_list()))

    assert await same_with_and_without_index(orders, "status", matching) == expected
    assert await same_with_and_without_index(orders, "total", matching) == expected


async def test_sort_skip_limit_first_and_count(orders: AsyncCollection[Any, Any]) -> None:
    by_total = orders.query().sort("total", Order.DESC)
    assert ids(await by_total.to_list()) == ["c", "a", "b", "d"]
    assert ids(await by_total.skip(1).limit(2).to_list()) == ["a", "b"]
    first = await by_total.first()
    assert first is not None
    assert first["_id"] == "c"
    assert await orders.query().filter(field("status").eq("active")).count() == 2
    assert await orders.query().filter(field("status").eq("gone")).count() == 0
    assert await orders.query().filter(field("status").eq("gone")).to_list() == []


async def test_map_operations(orders: AsyncCollection[Any, Any]) -> None:
    rows = await (
        orders.query()
        .filter(field("_id").eq("a"))
        .map(
            MapOps.set("double", MidOps.multiply("total", 2)),
            MapOps.set("label", MidOps.concat("name", MidOps.literal(" / "), "status")),
            MapOps.set("delta", MidOps.abs("change")),
            MapOps.set("count", MidOps.size("items")),
            MapOps.set("flag", MidOps.sum(1)).when(field("status").eq("active")),
            MapOps.remove("customerId"),
        )
        .to_list()
    )
    row = rows[0]
    assert (row["double"], row["label"], row["delta"], row["count"], row["flag"]) == (240, "Ana / active", 3, 2, 1)
    assert "customerId" not in row


async def test_group_distinct_join_and_reduce(orders: AsyncCollection[Any, Any]) -> None:
    groups = await orders.query().group_by("status").to_documents()
    assert len(groups) == 3
    distinct = await orders.query().distinct("status").to_documents()
    assert len(distinct) == 3
    joined = (
        await orders.query().filter(field("_id").eq("a")).join("customers", "customerId", "_id", "customer").to_list()
    )
    assert joined[0]["customer"][0]["name"] == "Uno"
    total = await orders.query().reduce("export default (acc, doc) => acc + doc.total;", 0, "sum").to_documents()
    assert total[0]["sum"] == 470


async def test_a_script_filter_and_analyze(orders: AsyncCollection[Any, Any]) -> None:
    rows = await orders.query().filter(script("export default (doc) => doc.total > 100;")).to_list()
    assert sorted(ids(rows)) == ["a", "c"]
    analyzed = await orders.query().filter(field("status").eq("active")).analyze()
    assert len(analyzed.results) == 2
    assert analyzed.analysis


async def test_a_dirty_read_sees_the_same_committed_rows(orders: AsyncCollection[Any, Any]) -> None:
    assert sorted(ids(await orders.query().dirty_read().to_list())) == ["a", "b", "c", "d"]
    found = await orders.find_by_id("b", dirty_read=True)
    assert found is not None
