from __future__ import annotations

from collections.abc import AsyncIterator

import pytest

from tests.support.fake_server import FakeServer


@pytest.fixture
async def fake() -> AsyncIterator[FakeServer]:
    server = await FakeServer.start()
    yield server
    await server.close()


async def no_backoff(delay: float) -> None:
    del delay
