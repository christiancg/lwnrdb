from __future__ import annotations

import pytest

from lwnrdb import (
    AsyncLwnrdbClient,
    AuthenticationError,
    Credentials,
    DataPermission,
    ForbiddenError,
    Permissions,
    field,
)
from tests.it.conftest import settings_for
from tests.support.server_process import ServerProcess

pytestmark = pytest.mark.it
BOB_PASSWORD = "Bob-Secret-1234"


async def _as_bob(server: ServerProcess, password: str = BOB_PASSWORD) -> AsyncLwnrdbClient:
    return await AsyncLwnrdbClient.connect(settings_for(server, credentials=Credentials("bob", password)))


async def test_users_permissions_owners_and_passwords(server: ServerProcess, client: AsyncLwnrdbClient) -> None:
    await client.database("admin_it").create()
    await client.database("admin_it").collection("orders").create()
    users = client.admin().users()
    await users.create("bob", BOB_PASSWORD, Permissions(databases={"admin_it": DataPermission.READ}))
    async with await _as_bob(server) as bob:
        orders = bob.database("admin_it").collection("orders")
        assert await orders.query().to_list() == []
        with pytest.raises(ForbiddenError) as refused:
            await orders.save({"_id": "x"})
        assert str(refused.value.raw_code).startswith("403")
        with pytest.raises(ForbiddenError):
            await bob.admin().users().list()
        await bob.change_password(BOB_PASSWORD, "Bob-Secret-5678")
    with pytest.raises(AuthenticationError):
        await _as_bob(server)
    await users.change_permissions("bob", Permissions(databases={"admin_it": DataPermission.READ_WRITE}))
    await users.set_password("bob", BOB_PASSWORD)
    async with await _as_bob(server) as writer:
        await writer.database("admin_it").collection("orders").save({"_id": "y"})
    await client.database("admin_it").set_owners(["bob"])
    listed = await users.list(field("_id").eq("bob"))
    assert len(listed) == 1
    assert listed[0].owned_databases == ("admin_it",)
    assert len(await users.list()) >= 2
    await users.delete("bob")
    assert await users.list(field("_id").eq("bob")) == []


async def test_server_info_stats_and_transactions(client: AsyncLwnrdbClient) -> None:
    info = await client.server_info()
    assert info.state == "READY"
    assert info.version
    assert "node" in info.raw
    await client.database("stats_it").create()
    assert await client.database_stats()
    assert isinstance(await client.database("stats_it").stats(), dict)
    assert await client.admin().transactions().list() == []
