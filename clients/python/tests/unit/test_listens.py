from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from typing import Any

import pytest

from lwnrdb import AsyncLwnrdbClient, ConnectionLostError, ListenEndedError, ListenUpdate, LwnrdbError, field
from lwnrdb._internal.connection_factory import open_streams
from lwnrdb._internal.values_codec import JsonObject
from tests.conftest import no_backoff
from tests.support.fake_server import FakeServer, Session
from tests.support.recording_channel import error, ok


class ListenServer:
    def __init__(self, limit: int = 100) -> None:
        self.limit = limit
        self.issued = 0
        self.per_session: dict[int, int] = {}
        self.early_push = False

    def __call__(self, request: JsonObject, session: Session) -> None:
        kind = request.get("type")
        if kind == "LISTEN":
            self._listen(request, session)
        else:
            session.send(ok(request))

    def _listen(self, request: JsonObject, session: Session) -> None:
        held = self.per_session.get(id(session), 0)
        if held >= self.limit:
            session.send(error(request, "ERROR", "400-25", "Too many listen registrations"))
            return
        self.per_session[id(session)] = held + 1
        self.issued += 1
        listen_id = f"L{self.issued}"
        if self.early_push:
            session.send(push(listen_id, [{"_id": "early"}]))
        session.send(ok(request, listenId=listen_id, results=[{"_id": "a"}], resultHash="h0", isUpdate=False))


def push(listen_id: str, results: list[dict[str, Any]]) -> JsonObject:
    return {
        "type": "LISTEN",
        "status": "OK",
        "listenId": listen_id,
        "results": results,
        "resultHash": "h",
        "isUpdate": True,
    }


def ended(listen_id: str) -> JsonObject:
    return {"type": "LISTEN", "status": "NOT_FOUND", "errorCode": "410-1", "message": "dropped", "listenId": listen_id}


class Recorder:
    def __init__(self) -> None:
        self.updates: list[ListenUpdate[Any]] = []
        self.ended: list[ListenEndedError] = []
        self.errors: list[BaseException] = []
        self.changed = asyncio.Event()

    async def on_update(self, update: ListenUpdate[Any]) -> None:
        self.updates.append(update)
        self.changed.set()

    def on_ended(self, reason: ListenEndedError) -> None:
        self.ended.append(reason)
        self.changed.set()

    def on_error(self, failure: BaseException) -> None:
        self.errors.append(failure)
        self.changed.set()

    async def wait_for(self, condition: Any) -> None:
        async with asyncio.timeout(5):
            while not condition():
                self.changed.clear()
                await self.changed.wait()


@pytest.fixture
async def client(fake: FakeServer) -> AsyncIterator[AsyncLwnrdbClient]:
    fake.responder = ListenServer()
    connected = await AsyncLwnrdbClient._connect(fake.settings(), no_backoff, open_streams)
    yield connected
    await connected.close()


def _orders(client: AsyncLwnrdbClient):
    return client.database("shop").collection("orders")


async def test_the_initial_reply_and_pushes_reach_the_handler_in_order(
    client: AsyncLwnrdbClient, fake: FakeServer
) -> None:
    recorder = Recorder()
    subscription = await _orders(client).listen(recorder, _orders(client).query().filter(field("a").eq(1)))
    assert subscription.listen_id == "L1"
    assert subscription.is_active
    listen_session = fake.sessions[-1]
    for index in range(5):
        listen_session.send(push("L1", [{"_id": str(index)}]))
    await recorder.wait_for(lambda: len(recorder.updates) == 6)
    assert recorder.updates[0].initial
    assert recorder.updates[0].result_hash == "h0"
    assert [update.results[0]["_id"] for update in recorder.updates[1:]] == ["0", "1", "2", "3", "4"]
    assert fake.requests_of_type("LISTEN")[0]["aggregationSteps"][0]["type"] == "FILTER"
    await subscription.close()
    await subscription.close()
    assert len(fake.requests_of_type("STOP_LISTEN")) == 1
    assert not subscription.is_active


async def test_a_push_before_the_registration_is_buffered(client: AsyncLwnrdbClient, fake: FakeServer) -> None:
    server = ListenServer()
    server.early_push = True
    fake.responder = server
    recorder = Recorder()
    async with await _orders(client).listen(recorder):
        await recorder.wait_for(lambda: len(recorder.updates) == 2)
    assert recorder.updates[0].initial
    assert recorder.updates[1].results == ({"_id": "early"},)


async def test_a_listen_end_marks_the_subscription_closed_without_a_stop(
    client: AsyncLwnrdbClient, fake: FakeServer
) -> None:
    recorder = Recorder()
    subscription = await _orders(client).listen(recorder)
    fake.sessions[-1].send(ended("L1"))
    await recorder.wait_for(lambda: recorder.ended)
    assert recorder.ended[0].raw_code == "410-1"
    assert not subscription.is_active
    await subscription.close()
    assert fake.requests_of_type("STOP_LISTEN") == []


async def test_a_lost_listen_connection_reports_through_on_error(client: AsyncLwnrdbClient, fake: FakeServer) -> None:
    recorder = Recorder()
    subscription = await _orders(client).listen(recorder)
    fake.sessions[-1].close()
    await recorder.wait_for(lambda: recorder.errors)
    assert isinstance(recorder.errors[0], ConnectionLostError)
    assert not subscription.is_active


async def test_handler_failures_go_to_on_error(client: AsyncLwnrdbClient, fake: FakeServer) -> None:
    failures: list[BaseException] = []
    done = asyncio.Event()

    class Failing:
        def on_update(self, update: ListenUpdate[Any]) -> None:
            raise RuntimeError(update.listen_id)

        def on_ended(self, reason: ListenEndedError) -> None:
            raise RuntimeError("ended")

        def on_error(self, failure: BaseException) -> None:
            failures.append(failure)
            if len(failures) == 2:
                done.set()
            raise RuntimeError("ignored")

    await _orders(client).listen(Failing())
    fake.sessions[-1].send(ended("L1"))
    async with asyncio.timeout(5):
        await done.wait()
    assert [str(failure) for failure in failures] == ["L1", "ended"]


async def test_a_plain_callable_is_a_handler(client: AsyncLwnrdbClient) -> None:
    seen: list[ListenUpdate[Any]] = []
    arrived = asyncio.Event()
    subscription = await _orders(client).listen(lambda update: (seen.append(update), arrived.set()))
    async with asyncio.timeout(5):
        await arrived.wait()
    assert seen[0].initial
    await subscription.close()


async def test_something_that_is_not_a_handler_is_refused(client: AsyncLwnrdbClient) -> None:
    with pytest.raises(LwnrdbError, match="needs a handler"):
        await _orders(client).listen(3)


async def test_listens_past_the_per_connection_limit_open_another_connection(
    client: AsyncLwnrdbClient, fake: FakeServer
) -> None:
    fake.responder = ListenServer(limit=2)
    subscriptions = [await _orders(client).listen(Recorder()) for _ in range(3)]
    assert client._listens.open_channels == 2
    for subscription in subscriptions:
        await subscription.close()
    assert client._listens.open_channels == 0


async def test_a_refused_listen_on_a_new_connection_raises(client: AsyncLwnrdbClient, fake: FakeServer) -> None:
    fake.responder = lambda request, session: session.send(error(request, "ERROR", "400-19", "no scripts"))
    with pytest.raises(LwnrdbError, match="400-19"):
        await _orders(client).listen(Recorder())
    assert client._listens.open_channels == 0


async def test_listens_are_not_available_inside_a_transaction(client: AsyncLwnrdbClient) -> None:
    transaction = await client.begin_transaction()
    transaction_orders = transaction.collection("shop", "orders")
    assert not hasattr(transaction_orders, "listen")
    await transaction.rollback()


async def test_closing_the_client_closes_listen_connections(client: AsyncLwnrdbClient, fake: FakeServer) -> None:
    recorder = Recorder()
    subscription = await _orders(client).listen(recorder)
    await client.close()
    assert not subscription.is_active
    assert fake.requests_of_type("CLOSE_CONNECTION")
