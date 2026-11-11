from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest

from lwnrdb import (
    AsyncLwnrdbClient,
    ClientSettings,
    ConnectionLostError,
    Credentials,
    LwnrdbError,
    ServerAddress,
    UnknownOutcomeError,
)
from lwnrdb._internal.connection_factory import open_streams
from lwnrdb._internal.values_codec import JsonObject
from tests.conftest import no_backoff
from tests.support.fake_server import FakeServer, Session
from tests.support.recording_channel import error, ok
from tests.support.server_process import free_port


def transactional(commit_code: str | None = None) -> object:
    def answer(request: JsonObject, session: Session) -> None:
        kind = request.get("type")
        if kind == "START_TRANSACTION":
            session.send(ok(request, transactionId="tx-1"))
        elif kind == "COMMIT_TRANSACTION" and commit_code is not None:
            session.send(error(request, "ERROR", commit_code))
        elif kind == "SERVER_INFO":
            session.send(ok(request, server={"state": "READY", "version": "1.0"}))
        elif kind in {"LIST_DATABASES", "GET_DATABASE_STATS"}:
            session.send(ok(request, databases=["shop"], stats={"memory": {}}))
        else:
            session.send(ok(request))

    return answer


@pytest.fixture
async def client(fake: FakeServer) -> AsyncIterator[AsyncLwnrdbClient]:
    fake.responder = transactional()
    connected = await AsyncLwnrdbClient._connect(
        fake.settings(credentials=Credentials("alice", "pw")), no_backoff, open_streams
    )
    yield connected
    await connected.close()


async def test_connect_fails_cleanly_when_no_server_answers() -> None:
    settings = ClientSettings(hosts=(ServerAddress("127.0.0.1", free_port()),), connect_timeout=2)
    with pytest.raises(ConnectionLostError):
        await AsyncLwnrdbClient.connect(settings)


async def test_client_level_reads(client: AsyncLwnrdbClient, fake: FakeServer) -> None:
    assert await client.list_databases() == ("shop",)
    info = await client.server_info()
    assert (info.state, info.version) == ("READY", "1.0")
    assert await client.database_stats() == {"memory": {}}
    assert client.settings.credentials == Credentials("alice", "pw")
    await client.change_password("pw", "new")
    assert fake.requests_of_type("SET_PASSWORD")[0] == {
        "type": "SET_PASSWORD",
        "username": "alice",
        "currentPassword": "pw",
        "newPassword": "new",
    }


async def test_change_password_needs_credentials(fake: FakeServer) -> None:
    async with await AsyncLwnrdbClient._connect(fake.settings(), no_backoff, open_streams) as anonymous:
        with pytest.raises(LwnrdbError, match="no credentials"):
            await anonymous.change_password("a", "b")


async def test_a_transaction_body_commits_on_a_clean_exit(client: AsyncLwnrdbClient, fake: FakeServer) -> None:
    async with client.transaction() as transaction:
        assert transaction.id == "tx-1"
        await transaction.collection("shop", "orders").save({"_id": "o-1"})
    assert not transaction.is_open
    kinds = [request["type"] for request in fake.requests if request["type"] != "AUTHENTICATE"]
    assert kinds == ["START_TRANSACTION", "SAVE", "COMMIT_TRANSACTION"]
    sessions = {id(session) for session in fake.sessions if any(r["type"] == "SAVE" for r in session.requests)}
    assert len(sessions) == 1


async def test_a_failing_body_rolls_back_and_reraises(client: AsyncLwnrdbClient, fake: FakeServer) -> None:
    with pytest.raises(RuntimeError, match="body failed"):
        async with client.transaction():
            raise RuntimeError("body failed")
    assert fake.requests_of_type("ROLLBACK_TRANSACTION")
    assert not fake.requests_of_type("COMMIT_TRANSACTION")


async def test_a_body_that_ends_the_transaction_itself_is_not_committed_again(
    client: AsyncLwnrdbClient, fake: FakeServer
) -> None:
    async with client.transaction() as transaction:
        await transaction.rollback()
    assert not fake.requests_of_type("COMMIT_TRANSACTION")
    with pytest.raises(LwnrdbError, match="already committed or rolled back"):
        await transaction.commit()
    with pytest.raises(LwnrdbError, match="already committed"):
        transaction.collection("shop", "orders")


async def test_a_rollback_failure_does_not_hide_the_body_error(client: AsyncLwnrdbClient, fake: FakeServer) -> None:
    def refuse_rollback(request: JsonObject, session: Session) -> None:
        if request.get("type") == "ROLLBACK_TRANSACTION":
            session.send(error(request, "ERROR", "409-4"))
        else:
            transactional()(request, session)

    fake.responder = refuse_rollback
    with pytest.raises(RuntimeError, match="original"):
        async with client.transaction():
            raise RuntimeError("original")


async def test_an_unknown_commit_outcome_discards_the_connection(client: AsyncLwnrdbClient, fake: FakeServer) -> None:
    fake.responder = transactional("500-34")
    transaction = await client.begin_transaction()
    pinned = transaction._lease.connection
    with pytest.raises(UnknownOutcomeError):
        await transaction.commit()
    assert not pinned.is_open


async def test_a_transaction_used_as_a_context_rolls_back_when_left_open(
    client: AsyncLwnrdbClient, fake: FakeServer
) -> None:
    async with await client.begin_transaction() as transaction:
        query = transaction.collection("shop", "orders").query()
        assert await query.to_list() == []
    assert fake.requests_of_type("ROLLBACK_TRANSACTION")


async def test_a_lost_connection_during_start_discards_it(fake: FakeServer) -> None:
    def hang_up_on_start(request: JsonObject, session: Session) -> None:
        if request.get("type") == "START_TRANSACTION":
            session.close()
        else:
            session.send(ok(request))

    fake.responder = hang_up_on_start
    async with await AsyncLwnrdbClient._connect(fake.settings(), no_backoff, open_streams) as client:
        with pytest.raises(ConnectionLostError):
            await client.begin_transaction()
        assert client._pool.connections_in_use == 0


async def test_closing_while_requests_run_fails_them(fake: FakeServer) -> None:
    fake.responder = lambda request, session: None
    client = await AsyncLwnrdbClient._connect(fake.settings(shutdown_timeout=0.1), no_backoff, open_streams)
    pending = asyncio.create_task(client.database("shop").collection("orders").find_by_id("o-1"))
    await fake.await_request("FIND_BY_ID")
    await client.close()
    pending.cancel()
    with pytest.raises((ConnectionLostError, asyncio.CancelledError)):
        await pending
