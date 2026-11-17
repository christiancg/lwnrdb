from __future__ import annotations

import asyncio

import pytest

from lwnrdb import ConnectionLostError, PoolExhaustedError
from lwnrdb._internal.connection_factory import ConnectionFactory
from lwnrdb._internal.connection_pool import ConnectionPool
from lwnrdb._internal.request_encoder import RequestEncoder
from tests.support.fake_server import FakeServer


def _pool(fake: FakeServer, **overrides: float) -> ConnectionPool:
    return ConnectionPool(ConnectionFactory(fake.settings(**overrides), RequestEncoder()))


async def test_a_released_connection_is_reused(fake: FakeServer) -> None:
    pool = _pool(fake)
    first = await pool.borrow()
    connection = first.connection
    first.release()
    first.release()
    assert pool.idle_connections == 1
    second = await pool.borrow()
    assert second.connection is connection
    assert pool.connections_in_use == 1
    second.release()
    await pool.close()


async def test_a_closed_idle_connection_is_skipped(fake: FakeServer) -> None:
    pool = _pool(fake)
    lease = await pool.borrow()
    stale = lease.connection
    lease.release()
    stale.close()
    fresh = await pool.borrow()
    assert fresh.connection is not stale
    fresh.release()
    await pool.close()


async def test_a_discarded_lease_closes_its_connection(fake: FakeServer) -> None:
    pool = _pool(fake)
    lease = await pool.borrow()
    lease.discard()
    lease.release()
    assert not lease.connection.is_open
    assert pool.idle_connections == 0
    await pool.close()


async def test_a_full_pool_waits_and_then_hands_over_a_released_connection(fake: FakeServer) -> None:
    pool = _pool(fake, pool_size=1)
    held = await pool.borrow()
    waiting = asyncio.create_task(pool.borrow())
    await asyncio.sleep(0.01)
    assert not waiting.done()
    held.release()
    granted = await waiting
    assert granted.connection is held.connection
    granted.release()
    await pool.close()


async def test_a_full_pool_refuses_after_the_acquire_timeout(fake: FakeServer) -> None:
    pool = _pool(fake, pool_size=1, acquire_timeout=0.05)
    held = await pool.borrow()
    with pytest.raises(PoolExhaustedError, match="all 1 are in use"):
        await pool.borrow()
    held.release()
    again = await pool.borrow()
    again.release()
    await pool.close()


async def test_a_cancelled_waiter_gives_its_permit_back(fake: FakeServer) -> None:
    pool = _pool(fake, pool_size=1)
    held = await pool.borrow()
    waiting = asyncio.create_task(pool.borrow())
    await asyncio.sleep(0.01)
    waiting.cancel()
    with pytest.raises(asyncio.CancelledError):
        await waiting
    held.release()
    assert pool.connections_in_use == 0
    await pool.close()


async def test_closing_refuses_waiters_and_new_borrows(fake: FakeServer) -> None:
    pool = _pool(fake, pool_size=1, shutdown_timeout=0.2)
    held = await pool.borrow()
    waiting = asyncio.create_task(pool.borrow())
    await asyncio.sleep(0.01)
    closing = asyncio.create_task(pool.close())
    with pytest.raises(ConnectionLostError, match="closed"):
        await waiting
    held.release()
    await closing
    assert not held.connection.is_open
    with pytest.raises(ConnectionLostError):
        await pool.borrow()
    await pool.close()


async def test_closing_sends_close_connection_to_idle_connections(fake: FakeServer) -> None:
    pool = _pool(fake)
    lease = await pool.borrow()
    lease.release()
    await pool.close()
    assert fake.requests_of_type("CLOSE_CONNECTION")


async def test_closing_waits_at_most_the_shutdown_timeout_for_leases(fake: FakeServer) -> None:
    pool = _pool(fake, shutdown_timeout=0.05)
    held = await pool.borrow()
    await pool.close()
    held.release()
    assert not held.connection.is_open


async def test_a_failed_open_returns_its_permit(fake: FakeServer) -> None:
    pool = _pool(fake, pool_size=1)
    await fake.close()
    with pytest.raises(ConnectionLostError):
        await pool.borrow()
    assert pool.connections_in_use == 0
