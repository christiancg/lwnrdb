from __future__ import annotations

import pytest

from lwnrdb import AsyncLwnrdbClient, ServerClosedConnectionError

pytestmark = pytest.mark.it
SERVER_CONFIG = {"maxRequestSize": "128Kb", "maxEntrySize": "64Kb"}


async def test_an_oversized_request_closes_its_connection_and_the_pool_recovers(client: AsyncLwnrdbClient) -> None:
    shop = client.database("limits_it")
    await shop.create()
    notes = shop.collection("notes")
    await notes.create()
    with pytest.raises(ServerClosedConnectionError) as refused:
        await notes.save({"_id": "big", "text": "x" * (200 * 1024)})
    assert refused.value.raw_code == "400-22"
    await notes.save({"_id": "small", "text": "ok"})
    assert await notes.find_by_id("small") is not None
