from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Awaitable, Callable, Iterator
from typing import Any, TypeVar

import pytest

from lwnrdb import AsyncCollection, AsyncLwnrdbClient, ClientSettings, Credentials, LwnrdbClient, ServerAddress
from tests.support.server_process import ADMIN, PASSWORD, ServerProcess

V = TypeVar("V")


def settings_for(server: ServerProcess, **overrides: Any) -> ClientSettings:
    values: dict[str, Any] = {
        "hosts": (ServerAddress("localhost", server.port),),
        "credentials": Credentials(ADMIN, PASSWORD),
        "request_timeout": 60.0,
    }
    values.update(overrides)
    return ClientSettings(**values)


@pytest.fixture(scope="module")
def server(request: pytest.FixtureRequest) -> Iterator[ServerProcess]:
    started = ServerProcess.start(getattr(request.module, "SERVER_CONFIG", {}))
    yield started
    started.stop()


@pytest.fixture
async def client(server: ServerProcess) -> AsyncIterator[AsyncLwnrdbClient]:
    connected = await AsyncLwnrdbClient.connect(settings_for(server))
    yield connected
    await connected.close()


@pytest.fixture
def sync_client(server: ServerProcess) -> Iterator[LwnrdbClient]:
    connected = LwnrdbClient.connect(settings_for(server))
    yield connected
    connected.close()


async def eventually(attempt: Callable[[], Awaitable[V]], done: Callable[[V], bool], seconds: float = 20.0) -> V:
    loop = asyncio.get_running_loop()
    deadline = loop.time() + seconds
    last = await attempt()
    while not done(last) and loop.time() < deadline:
        await asyncio.sleep(0.1)
        last = await attempt()
    return last


def ids(rows: list[Any]) -> list[str]:
    return [str(row.get("_id")) for row in rows]


async def same_with_and_without_index(
    collection: AsyncCollection[Any, Any], field: str, query: Callable[[], Awaitable[V]]
) -> V:
    scanned = await query()
    await collection.indexes().create(field)
    try:
        assert await query() == scanned, f"index and scan disagree on {field}"
    finally:
        await collection.indexes().drop(field)
    return scanned
