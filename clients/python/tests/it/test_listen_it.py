from __future__ import annotations

import asyncio
import threading
from typing import Any

import pytest

from lwnrdb import AsyncLwnrdbClient, ListenEndedError, ListenUpdate, LwnrdbClient, field

pytestmark = pytest.mark.it
SERVER_CONFIG = {"maxListensPerConnection": "2"}


class Collected:
    def __init__(self) -> None:
        self.updates: list[ListenUpdate[Any]] = []
        self.ended: list[ListenEndedError] = []
        self.arrived = asyncio.Event()

    async def on_update(self, update: ListenUpdate[Any]) -> None:
        self.updates.append(update)
        self.arrived.set()

    def on_ended(self, reason: ListenEndedError) -> None:
        self.ended.append(reason)
        self.arrived.set()

    async def until(self, condition: Any) -> None:
        async with asyncio.timeout(20):
            while not condition():
                self.arrived.clear()
                await self.arrived.wait()


async def _orders(client: AsyncLwnrdbClient, database: str) -> Any:
    shop = client.database(database)
    await shop.create()
    orders = shop.collection("orders")
    await orders.create()
    return orders


async def test_a_matching_write_pushes_and_a_closed_subscription_stops(client: AsyncLwnrdbClient) -> None:
    orders = await _orders(client, "listen_push")
    collected = Collected()
    subscription = await orders.listen(collected, orders.query().filter(field("status").eq("pending")))
    await collected.until(lambda: collected.updates)
    assert collected.updates[0].initial
    assert collected.updates[0].results == ()
    await orders.save({"_id": "p1", "status": "pending"})
    await collected.until(lambda: len(collected.updates) >= 2)
    assert [row["_id"] for row in collected.updates[-1].results] == ["p1"]
    await subscription.close()
    assert not subscription.is_active
    count = len(collected.updates)
    await orders.save({"_id": "p2", "status": "pending"})
    await asyncio.sleep(1)
    assert len(collected.updates) == count


async def test_updates_arrive_in_write_order(client: AsyncLwnrdbClient) -> None:
    orders = await _orders(client, "listen_order")
    collected = Collected()
    async with await orders.listen(collected):
        await collected.until(lambda: collected.updates)
        for index in range(5):
            await orders.save({"_id": f"o{index}", "n": index})
        await collected.until(lambda: len(collected.updates[-1].results) == 5)
    sizes = [len(update.results) for update in collected.updates]
    assert sizes == sorted(sizes)


async def test_dropping_the_collection_ends_the_listen(client: AsyncLwnrdbClient) -> None:
    orders = await _orders(client, "listen_drop")
    collected = Collected()
    subscription = await orders.listen(collected)
    await collected.until(lambda: collected.updates)
    await orders.drop()
    await collected.until(lambda: collected.ended)
    assert collected.ended[0].raw_code == "410-1"
    assert not subscription.is_active
    await subscription.close()


async def test_listens_past_the_per_connection_limit_spread_over_connections(client: AsyncLwnrdbClient) -> None:
    orders = await _orders(client, "listen_spread")
    collectors = [Collected() for _ in range(5)]
    subscriptions = [await orders.listen(collected) for collected in collectors]
    assert client._listens.open_channels == 3
    await orders.save({"_id": "x"})
    for collected in collectors:
        await collected.until(lambda collected=collected: any(update.results for update in collected.updates))
    for subscription in subscriptions:
        await subscription.close()
    assert client._listens.open_channels == 0


def test_a_sync_handler_receives_updates_in_order_and_may_call_the_client(sync_client: LwnrdbClient) -> None:
    shop = sync_client.database("listen_sync")
    shop.create()
    orders = shop.collection("orders")
    orders.create()
    seen: list[tuple[int, int]] = []
    done = threading.Event()

    def on_update(update: ListenUpdate[Any]) -> None:
        seen.append((len(update.results), orders.query().count()))
        if len(update.results) == 3:
            done.set()

    with orders.listen(on_update):
        for index in range(3):
            orders.save({"_id": f"s{index}"})
        assert done.wait(20)
    sizes = [size for size, _ in seen]
    assert sizes == sorted(sizes)
    assert seen[-1] == (3, 3)
