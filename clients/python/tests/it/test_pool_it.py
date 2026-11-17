from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor

import pytest

from lwnrdb import AsyncLwnrdbClient, ConnectionLostError, LwnrdbClient
from tests.it.conftest import settings_for
from tests.support.server_process import ServerProcess

pytestmark = pytest.mark.it


async def test_many_concurrent_writers_share_a_small_pool(server: ServerProcess) -> None:
    async with await AsyncLwnrdbClient.connect(settings_for(server, pool_size=4)) as client:
        shop = client.database("pool_async")
        await shop.create()
        notes = shop.collection("notes")
        await notes.create()
        await asyncio.gather(*(notes.save({"_id": f"n{index}", "n": index}) for index in range(200)))
        assert await notes.query().count() == 200


def test_many_threads_share_one_sync_client(server: ServerProcess) -> None:
    with LwnrdbClient.connect(settings_for(server, pool_size=4)) as client:
        shop = client.database("pool_sync")
        shop.create()
        notes = shop.collection("notes")
        notes.create()
        with ThreadPoolExecutor(max_workers=16) as workers:
            list(workers.map(lambda index: notes.save({"_id": f"t{index}"}), range(200)))
        assert notes.query().count() == 200


async def test_closing_the_client_while_requests_run_fails_them_cleanly(server: ServerProcess) -> None:
    client = await AsyncLwnrdbClient.connect(settings_for(server, pool_size=2, shutdown_timeout=0.5))
    shop = client.database("pool_close")
    await shop.create()
    notes = shop.collection("notes")
    await notes.create()
    script = "let i = 0;\nwhile (i < 200000000) { i++; }\nreturn i;"
    running = [asyncio.create_task(shop.run_script(script)) for _ in range(4)]
    await asyncio.sleep(0.3)
    await client.close()
    results = await asyncio.gather(*running, return_exceptions=True)
    assert any(isinstance(result, ConnectionLostError) for result in results)
    with pytest.raises(ConnectionLostError):
        await notes.save({"_id": "after"})
