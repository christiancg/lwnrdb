from __future__ import annotations

import asyncio

import pytest

from lwnrdb import AsyncLwnrdbClient, ConflictError, LwnrdbClient

pytestmark = pytest.mark.it
SERVER_CONFIG = {"transactionIdleTimeoutMs": "4000"}


async def _bank(client: AsyncLwnrdbClient, name: str) -> None:
    database = client.database(name)
    await database.create()
    await database.collection("accounts").create()
    await database.collection("ledger").create()


async def test_a_commit_is_visible_and_reads_inside_see_their_own_writes(client: AsyncLwnrdbClient) -> None:
    await _bank(client, "tx_commit")
    async with client.transaction() as tx:
        accounts = tx.collection("tx_commit", "accounts")
        await accounts.save({"_id": "a", "balance": 10})
        inside = await accounts.find_by_id("a")
        assert inside is not None
        assert inside["balance"] == 10
        outside = await client.database("tx_commit").collection("accounts").find_by_id("a", dirty_read=True)
        assert outside is None
        assert len(await accounts.query().to_list()) == 1
    assert await client.database("tx_commit").collection("accounts").find_by_id("a") is not None


async def _save_then_fail(client: AsyncLwnrdbClient) -> None:
    async with client.transaction() as tx:
        await tx.collection("tx_rollback", "accounts").save({"_id": "f"})
        raise RuntimeError("body")


async def test_a_rollback_and_a_failing_body_leave_nothing(client: AsyncLwnrdbClient) -> None:
    await _bank(client, "tx_rollback")
    transaction = await client.begin_transaction()
    await transaction.collection("tx_rollback", "accounts").save({"_id": "r"})
    await transaction.rollback()
    with pytest.raises(RuntimeError, match="body"):
        await _save_then_fail(client)
    assert await client.database("tx_rollback").collection("accounts").query().to_list() == []


async def test_an_idle_transaction_is_rolled_back_by_the_server(client: AsyncLwnrdbClient) -> None:
    await _bank(client, "tx_idle")
    transaction = await client.begin_transaction()
    await transaction.collection("tx_idle", "accounts").save({"_id": "idle"})
    await asyncio.sleep(6)
    with pytest.raises(ConflictError) as refused:
        await transaction.commit()
    assert refused.value.raw_code == "409-16"
    assert await client.database("tx_idle").collection("accounts").find_by_id("idle") is None


async def test_transactions_on_different_collections_run_concurrently(client: AsyncLwnrdbClient) -> None:
    await _bank(client, "tx_parallel")
    first = await client.begin_transaction()
    second = await client.begin_transaction()
    assert first.id != second.id
    await first.collection("tx_parallel", "accounts").save({"_id": "x"})
    await second.collection("tx_parallel", "ledger").save({"_id": "y"})
    await asyncio.gather(first.commit(), second.commit())
    database = client.database("tx_parallel")
    assert await database.collection("accounts").find_by_id("x") is not None
    assert await database.collection("ledger").find_by_id("y") is not None


def _save_then_refuse(client: LwnrdbClient) -> None:
    with client.transaction() as tx:
        tx.collection("tx_sync", "accounts").save({"_id": "dropped"})
        raise ValueError("nope")


def test_the_sync_client_commits_and_rolls_back(sync_client: LwnrdbClient) -> None:
    bank = sync_client.database("tx_sync")
    bank.create()
    bank.collection("accounts").create()
    with sync_client.transaction() as tx:
        tx.collection("tx_sync", "accounts").save({"_id": "kept"})
    with pytest.raises(ValueError, match="nope"):
        _save_then_refuse(sync_client)
    rows = bank.collection("accounts").query().to_list()
    assert [row["_id"] for row in rows] == ["kept"]
