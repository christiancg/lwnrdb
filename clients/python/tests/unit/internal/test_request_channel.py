from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest

from lwnrdb import ConnectionLostError, LwnrdbError, RetryPolicy, UnavailableError, UnknownOutcomeError
from lwnrdb._internal.connection_factory import ConnectionFactory
from lwnrdb._internal.connection_pool import ConnectionPool
from lwnrdb._internal.request_channel import PinnedChannel, PooledChannel, is_retryable, jittered_backoff
from lwnrdb._internal.request_encoder import RequestEncoder
from lwnrdb._internal.values_codec import JsonObject
from tests.support.fake_server import FakeServer, Responder, Session
from tests.support.recording_channel import error, ok


@pytest.fixture
async def pools() -> AsyncIterator[list[ConnectionPool]]:
    opened: list[ConnectionPool] = []
    yield opened
    for pool in opened:
        await pool.close()


def _channel(fake: FakeServer, pools: list[ConnectionPool], delays: list[float], attempts: int = 3) -> PooledChannel:
    async def record(delay: float) -> None:
        delays.append(delay)

    pool = ConnectionPool(ConnectionFactory(fake.settings(), RequestEncoder()))
    pools.append(pool)
    return PooledChannel(pool, RetryPolicy(attempts, 0.1, 0.15), 5.0, record)


def _fail_first(times: int, code: str) -> Responder:
    seen: list[int] = []

    def answer(request: JsonObject, session: Session) -> None:
        seen.append(1)
        if len(seen) <= times:
            session.send(error(request, "ERROR", code))
        else:
            session.send(ok(request))

    return answer


async def test_a_retryable_unavailable_error_is_retried_with_growing_backoff(
    fake: FakeServer, pools: list[ConnectionPool]
) -> None:
    fake.responder = _fail_first(2, "503-2")
    delays: list[float] = []
    reply = await _channel(fake, pools, delays).send("AGGREGATE", {"type": "AGGREGATE"})
    assert reply["status"] == "OK"
    assert delays == [0.1, 0.15]


async def test_retrying_stops_at_max_attempts(fake: FakeServer, pools: list[ConnectionPool]) -> None:
    fake.responder = _fail_first(5, "503-2")
    delays: list[float] = []
    with pytest.raises(UnavailableError):
        await _channel(fake, pools, delays, attempts=2).send("SAVE", {"type": "SAVE"})
    assert len(delays) == 1


@pytest.mark.parametrize("code", ["503-3", "500-33", "500-34", "400-1"])
async def test_codes_that_may_have_applied_are_never_retried(
    fake: FakeServer, pools: list[ConnectionPool], code: str
) -> None:
    fake.responder = _fail_first(1, code)
    delays: list[float] = []
    with pytest.raises(LwnrdbError):
        await _channel(fake, pools, delays).send("SAVE", {"type": "SAVE"})
    assert delays == []


async def test_a_lost_connection_is_retried_only_for_reads(fake: FakeServer, pools: list[ConnectionPool]) -> None:
    def hang_up(request: JsonObject, session: Session) -> None:
        del request
        session.close()

    fake.responder = hang_up
    delays: list[float] = []
    with pytest.raises(ConnectionLostError):
        await _channel(fake, pools, delays).send("AGGREGATE", {"type": "AGGREGATE"})
    assert len(delays) == 2
    delays.clear()
    with pytest.raises(ConnectionLostError):
        await _channel(fake, pools, delays).send("SAVE", {"type": "SAVE"})
    assert delays == []


def test_is_retryable_follows_the_rules() -> None:
    assert is_retryable("FIND_BY_ID", ConnectionLostError("lost"))
    assert not is_retryable("DELETE", ConnectionLostError("lost"))
    assert is_retryable("SAVE", UnavailableError("busy", code="503-2"))
    assert not is_retryable("SAVE", UnavailableError("committed", code="503-3"))
    assert not is_retryable("SAVE", UnknownOutcomeError("unknown", code="500-34"))


async def test_jittered_backoff_sleeps_at_least_half_the_delay() -> None:
    loop = asyncio.get_running_loop()
    started = loop.time()
    await jittered_backoff(0.02)
    assert loop.time() - started >= 0.009


async def test_a_pinned_channel_refuses_operations_not_allowed_in_a_transaction(fake: FakeServer) -> None:
    pool = ConnectionPool(ConnectionFactory(fake.settings(), RequestEncoder()))
    lease = await pool.borrow()
    channel = PinnedChannel(lease.connection, 5.0)
    with pytest.raises(LwnrdbError, match="CREATE_DATABASE is not allowed inside a transaction"):
        await channel.send("CREATE_DATABASE", {"type": "CREATE_DATABASE"})
    assert (await channel.send("SAVE", {"type": "SAVE"}))["status"] == "OK"
    lease.release()
    await pool.close()
