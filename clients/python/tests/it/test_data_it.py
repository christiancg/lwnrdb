from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from enum import Enum

import pytest

from lwnrdb import AsyncLwnrdbClient, ConflictError, LwnrdbClient, LwnrdbError, and_, field

pytestmark = pytest.mark.it


class Status(Enum):
    NEW = "new"
    PAID = "paid"


@dataclass
class Line:
    sku: str
    qty: int


@dataclass
class Invoice:
    status: Status
    total: float
    lines: list[Line] = dataclasses.field(default_factory=list)
    id: str | None = None


async def test_save_find_and_delete(client: AsyncLwnrdbClient) -> None:
    shop = client.database("data_it")
    await shop.create()
    orders = shop.collection("orders")
    await orders.create()
    saved = await orders.save({"_id": "o-1", "status": "active", "total": 120, "tags": ["a", None, True]})
    assert (saved.id, saved.inserted) == ("o-1", True)
    replaced = await orders.save({"_id": "o-1", "status": "closed", "total": 120})
    assert not replaced.inserted
    generated = await orders.save({"status": "new"})
    assert generated.id
    found = await orders.find_by_id("o-1")
    assert found is not None
    assert found["status"] == "closed"
    assert await orders.find_by_id("missing") is None
    await orders.delete("o-1")
    assert await orders.find_by_id("o-1") is None
    await orders.save_all(
        [
            {"_id": "dw-1", "status": "archived", "total": 10},
            {"_id": "dw-2", "status": "archived", "total": 500},
            {"_id": "dw-3", "status": "active", "total": 10},
        ]
    )
    assert await orders.delete_where(and_(field("status").eq("archived"), field("total").lt(100))) == 1
    assert await orders.find_by_id("dw-1") is None
    assert await orders.find_by_id("dw-2") is not None
    assert await orders.find_by_id("dw-3") is not None
    assert await orders.delete_where(field("status").eq("never")) == 0


async def test_update_where_merges_the_patch_into_every_match(client: AsyncLwnrdbClient) -> None:
    shop = client.database("data_update")
    await shop.create()
    orders = shop.collection("orders")
    await orders.create()
    await orders.save_all(
        [
            {"_id": "uw-1", "status": "pending", "total": 10, "note": "keep"},
            {"_id": "uw-2", "status": "pending", "total": 500},
            {"_id": "uw-3", "status": "active", "total": 10},
        ]
    )
    pending_and_small = and_(field("status").eq("pending"), field("total").lt(100))
    assert await orders.update_where(pending_and_small, {"status": "expired", "expiredBy": "sweeper"}) == 1
    assert await orders.find_by_id("uw-1") == {
        "_id": "uw-1",
        "status": "expired",
        "total": 10,
        "note": "keep",
        "expiredBy": "sweeper",
    }
    assert await orders.find_by_id("uw-2") == {"_id": "uw-2", "status": "pending", "total": 500}
    assert await orders.find_by_id("uw-3") == {"_id": "uw-3", "status": "active", "total": 10}
    assert await orders.update_where(field("status").eq("never"), {"status": "x"}) == 0
    with pytest.raises(LwnrdbError) as refused:
        await orders.update_where(field("status").eq("active"), {"_id": "uw-9", "status": "x"})
    assert refused.value.raw_code == "400-1"
    assert await orders.find_by_id("uw-9") is None


async def test_insert_refuses_an_id_that_is_already_stored(client: AsyncLwnrdbClient) -> None:
    shop = client.database("data_insert")
    await shop.create()
    orders = shop.collection("orders")
    await orders.create()
    inserted = await orders.insert({"_id": "in-1", "status": "new"})
    assert (inserted.id, inserted.inserted) == ("in-1", True)
    with pytest.raises(ConflictError) as refused:
        await orders.insert({"_id": "in-1", "status": "replaced"})
    assert refused.value.raw_code == "409-18"
    assert await orders.find_by_id("in-1") == {"_id": "in-1", "status": "new"}
    generated = await orders.insert({"status": "generated"})
    assert generated.inserted
    assert generated.id
    async with client.transaction() as tx:
        inside = tx.collection("data_insert", "orders")
        with pytest.raises(ConflictError) as refused_inside:
            await inside.insert({"_id": "in-1", "status": "replaced"})
        assert refused_inside.value.raw_code == "409-18"
        await inside.insert({"_id": "in-2", "status": "new"})
    assert await orders.find_by_id("in-1") == {"_id": "in-1", "status": "new"}
    assert await orders.find_by_id("in-2") == {"_id": "in-2", "status": "new"}


async def test_save_all_reports_inserted_and_updated_ids(client: AsyncLwnrdbClient) -> None:
    shop = client.database("data_bulk")
    await shop.create()
    orders = shop.collection("orders")
    await orders.create()
    await orders.save({"_id": "b-1", "n": 0})
    result = await orders.save_all([{"_id": "b-1", "n": 1}, {"_id": "b-2", "n": 2}, {"n": 3}])
    assert result.updated == ("b-1",)
    assert result.inserted[0] == "b-2"
    assert len(result.inserted) == 2
    with pytest.raises(LwnrdbError) as duplicate:
        await orders.save_all([{"_id": "d"}, {"_id": "d"}])
    assert duplicate.value.raw_code == "400-3"
    with pytest.raises(ConflictError):
        await client.database("data_bulk").create()


def test_a_dataclass_collection_round_trips_through_the_sync_client(sync_client: LwnrdbClient) -> None:
    shop = sync_client.database("data_typed")
    shop.create()
    shop.collection("invoices").create()
    invoices = shop.collection("invoices", Invoice)
    saved = invoices.save(Invoice(Status.NEW, 12.5, [Line("a", 2)]))
    assert saved.id is not None
    stored = invoices.find_by_id(saved.id)
    assert stored == Invoice(Status.NEW, 12.5, [Line("a", 2)], saved.id)
    invoices.save(Invoice(Status.PAID, 3, [], "inv-2"))
    paid = invoices.query().filter(field("status").eq(Status.PAID)).to_list()
    assert paid == [Invoice(Status.PAID, 3.0, [], "inv-2")]
