from __future__ import annotations

import threading
from collections.abc import Iterator
from typing import Any

import pytest

from lwnrdb import (
    ClientSettings,
    ConnectionLostError,
    ListenUpdate,
    LwnrdbClient,
    LwnrdbError,
    ServerAddress,
    field,
)
from lwnrdb._internal.connection_factory import open_streams
from lwnrdb._internal.portal import Portal
from lwnrdb._internal.values_codec import JsonObject
from tests.conftest import no_backoff
from tests.support.fake_server import FakeServer, Session
from tests.support.recording_channel import ok
from tests.support.server_process import free_port
from tests.unit.test_listens import ListenServer, push


class Answers(ListenServer):
    def __call__(self, request: JsonObject, session: Session) -> None:
        kind = request.get("type")
        if kind == "START_TRANSACTION":
            session.send(ok(request, transactionId="tx-1"))
        elif kind == "FIND_BY_ID":
            session.send(ok(request, object={"_id": request["_id"], "at": "#time(10:00)"}))
        else:
            super().__call__(request, session)


@pytest.fixture
def server() -> Iterator[tuple[Portal, FakeServer]]:
    portal = Portal("fake-server")
    fake = portal.run(FakeServer.start(Answers()))
    yield portal, fake
    portal.run(fake.close())
    portal.stop(2)


@pytest.fixture
def client(server: tuple[Portal, FakeServer]) -> Iterator[LwnrdbClient]:
    connected = LwnrdbClient._connect(server[1].settings(), no_backoff, open_streams)
    yield connected
    connected.close()


def test_connect_failure_stops_the_runtime() -> None:
    settings = ClientSettings(hosts=(ServerAddress("127.0.0.1", free_port()),), connect_timeout=2)
    before = threading.active_count()
    with pytest.raises(ConnectionLostError):
        LwnrdbClient.connect(settings)
    assert threading.active_count() <= before + 1


def test_sync_calls_return_plain_values(client: LwnrdbClient) -> None:
    orders = client.database("shop").collection("orders")
    assert orders.name == "orders"
    assert orders.database_name == "shop"
    found = orders.find_by_id("o-1")
    assert found is not None
    assert found["_id"] == "o-1"
    assert orders.query().filter(field("a").eq(1)).to_list() == []
    assert orders.insert({"_id": "o-2"}).id is None
    assert orders.update_where(field("a").eq(1), {"b": 2}) == 0
    assert orders.delete_where(field("a").eq(1)) == 0
    assert client.settings.pool_size == 8


def test_a_with_block_transaction_commits_and_a_failing_one_rolls_back(
    client: LwnrdbClient, server: tuple[Portal, FakeServer]
) -> None:
    fake = server[1]
    with client.transaction() as transaction:
        assert transaction.id == "tx-1"
        transaction.collection("shop", "orders").save({"_id": "o-1"})
        assert not transaction.collection("shop", "orders").insert({"_id": "o-2"}).inserted
        assert transaction.collection("shop", "orders").update_where(field("status").eq("new"), {"status": "void"}) == 0
        assert transaction.collection("shop", "orders").delete_where(field("status").eq("void")) == 0
    assert fake.requests_of_type("COMMIT_TRANSACTION")
    with pytest.raises(RuntimeError), client.transaction():
        raise RuntimeError("body")
    assert fake.requests_of_type("ROLLBACK_TRANSACTION")
    with client.begin_transaction() as left_open:
        assert left_open.is_open
    assert len(fake.requests_of_type("ROLLBACK_TRANSACTION")) == 2


def test_a_sync_listen_handler_runs_on_its_own_thread_and_may_call_the_client(
    client: LwnrdbClient, server: tuple[Portal, FakeServer]
) -> None:
    portal, fake = server
    orders = client.database("shop").collection("orders")
    seen: list[tuple[bool, str, Any]] = []
    arrived = threading.Event()

    def on_update(update: ListenUpdate[Any]) -> None:
        found = orders.find_by_id("from-handler")
        seen.append((update.initial, threading.current_thread().name, found))
        if len(seen) == 2:
            arrived.set()

    with orders.listen(on_update, orders.query().filter(field("a").eq(1))) as subscription:
        assert subscription.listen_id == "L1"
        assert subscription.is_active
        listen_session = fake.sessions[-1]
        portal.run(_send(listen_session, push("L1", [{"_id": "x"}])))
        assert arrived.wait(5)
    assert [entry[0] for entry in seen] == [True, False]
    assert all(entry[1].startswith("lwnrdb-listen") for entry in seen)
    assert seen[0][2] is not None
    assert seen[0][2]["_id"] == "from-handler"


async def _send(session: Session, frame: JsonObject) -> None:
    session.send(frame)


def test_close_is_idempotent_and_later_calls_are_refused(server: tuple[Portal, FakeServer]) -> None:
    client = LwnrdbClient._connect(server[1].settings(), no_backoff, open_streams)
    database = client.database("shop")
    client.close()
    client.close()
    with pytest.raises(LwnrdbError, match="closed"):
        database.list_collections()


def test_the_client_is_a_context_manager(server: tuple[Portal, FakeServer]) -> None:
    with LwnrdbClient._connect(server[1].settings(), no_backoff, open_streams) as client:
        assert client.list_databases() == ()
    with pytest.raises(LwnrdbError):
        client.server_info()
