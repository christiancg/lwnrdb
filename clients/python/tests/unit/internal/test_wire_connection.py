from __future__ import annotations

import asyncio

import pytest

from lwnrdb import ConnectionLostError, LwnrdbError, MappingError, RequestTimeoutError, ServerClosedConnectionError
from lwnrdb._internal.values_codec import JsonObject
from lwnrdb._internal.wire_connection import WireConnection, decode_line
from tests.support.fake_server import FakeServer, Session
from tests.support.recording_channel import ok


async def _connect(fake: FakeServer, max_reply_bytes: int = 1024) -> WireConnection:
    reader, writer = await asyncio.open_connection("127.0.0.1", fake.port)
    return WireConnection(reader, writer, max_reply_bytes, "fake")


def _silent(request: JsonObject, session: Session) -> None:
    del request, session


async def test_replies_complete_requests_in_order(fake: FakeServer) -> None:
    connection = await _connect(fake)
    first = asyncio.create_task(connection.request({"type": "SERVER_INFO", "n": 1}, 5))
    second = asyncio.create_task(connection.request({"type": "SERVER_INFO", "n": 2}, 5))
    assert (await first)["type"] == "SERVER_INFO"
    assert (await second)["status"] == "OK"
    assert [request["n"] for request in fake.requests] == [1, 2]
    connection.close()


async def test_a_line_that_is_not_json_fails_only_the_oldest_request(fake: FakeServer) -> None:
    def answer(request: JsonObject, session: Session) -> None:
        if request.get("n") == 1:
            session.send_raw(b"The command is not valid\n")
            session.send_raw(b'{"n": NaN}\n')
            session.send(ok(request, n=request.get("n")))

    fake.responder = answer
    connection = await _connect(fake)
    first = asyncio.create_task(connection.request({"type": "SAVE", "n": 1}, 5))
    second = asyncio.create_task(connection.request({"type": "SAVE", "n": 2}, 5))
    third = asyncio.create_task(connection.request({"type": "SAVE", "n": 3}, 5))
    with pytest.raises(LwnrdbError, match="The command is not valid"):
        await first
    with pytest.raises(LwnrdbError, match="refused the request"):
        await second
    assert (await third)["n"] == 1
    assert connection.is_open
    connection.close()


async def test_an_overlong_reply_fails_its_request_and_keeps_the_connection(fake: FakeServer) -> None:
    def answer(request: JsonObject, session: Session) -> None:
        payload = "x" * 200 if request.get("big") else "y"
        session.send(ok(request, payload=payload))

    fake.responder = answer
    connection = await _connect(fake, max_reply_bytes=100)
    with pytest.raises(LwnrdbError, match="max_reply_bytes"):
        await connection.request({"type": "AGGREGATE", "big": True}, 5)
    assert (await connection.request({"type": "AGGREGATE"}, 5))["payload"] == "y"
    connection.close()


async def test_a_timeout_closes_the_connection_and_fails_the_others(fake: FakeServer) -> None:
    fake.responder = _silent
    connection = await _connect(fake)
    other = asyncio.create_task(connection.request({"type": "SAVE"}, 30))
    await asyncio.sleep(0)
    with pytest.raises(RequestTimeoutError, match="outcome is unknown"):
        await connection.request({"type": "SAVE"}, 0.05)
    with pytest.raises(RequestTimeoutError):
        await other
    assert not connection.is_open
    with pytest.raises(RequestTimeoutError):
        await connection.request({"type": "SAVE"}, 1)


async def test_cancelling_a_sent_request_closes_the_connection(fake: FakeServer) -> None:
    fake.responder = _silent
    connection = await _connect(fake)
    pending = asyncio.create_task(connection.request({"type": "SAVE"}, 30))
    await fake.await_request("SAVE")
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    assert not connection.is_open


async def test_an_unsolicited_close_fails_every_pending_request(fake: FakeServer) -> None:
    def answer(request: JsonObject, session: Session) -> None:
        del request
        session.send({"type": "CLOSE_CONNECTION", "status": "ERROR", "message": "backlog", "errorCode": "413-1"})

    fake.responder = answer
    closed: list[LwnrdbError] = []
    connection = await _connect(fake)
    connection.on_close(closed.append)
    with pytest.raises(ServerClosedConnectionError, match="backlog") as caught:
        await connection.request({"type": "AGGREGATE"}, 5)
    assert caught.value.raw_code == "413-1"
    assert closed == [caught.value]


async def test_an_awaited_close_completes_the_close_request(fake: FakeServer) -> None:
    connection = await _connect(fake)
    await connection.close_gracefully(5)
    assert not connection.is_open
    assert fake.requests_of_type("CLOSE_CONNECTION")
    await connection.close_gracefully(5)


async def test_a_reply_nobody_asked_for_closes_the_connection(fake: FakeServer) -> None:
    connection = await _connect(fake)
    session = await fake.await_session(0)
    session.send({"type": "SAVE", "status": "OK"})
    for _ in range(100):
        if not connection.is_open:
            break
        await asyncio.sleep(0.01)
    assert not connection.is_open


async def test_the_server_closing_the_socket_fails_pending_requests(fake: FakeServer) -> None:
    def hang_up(request: JsonObject, session: Session) -> None:
        del request
        session.close()

    fake.responder = hang_up
    connection = await _connect(fake)
    with pytest.raises(ConnectionLostError, match="closed the connection"):
        await connection.request({"type": "SAVE"}, 5)


async def test_pushes_go_to_the_push_sink(fake: FakeServer) -> None:
    connection = await _connect(fake)
    pushes: list[JsonObject] = []
    connection.on_push(pushes.append)
    session = await fake.await_session(0)
    session.send({"type": "LISTEN", "isUpdate": True, "listenId": "l"})
    session.send_raw(b"\n")
    assert (await connection.request({"type": "SERVER_INFO"}, 5))["status"] == "OK"
    assert pushes == [{"type": "LISTEN", "isUpdate": True, "listenId": "l"}]
    connection.close()
    await connection.wait_closed()


async def test_a_request_that_is_not_json_is_refused_before_it_is_sent(fake: FakeServer) -> None:
    connection = await _connect(fake)
    with pytest.raises(MappingError):
        await connection.request({"type": "SAVE", "value": float("nan")}, 5)
    with pytest.raises(MappingError):
        await connection.request({"type": "SAVE", "value": "\ud800"}, 5)
    assert connection.is_open
    connection.close()


def test_decode_line_accepts_only_json_objects() -> None:
    assert decode_line(b'{"a":1}') == {"a": 1}
    assert decode_line(b"[1]") is None
    assert decode_line(b"\xff") is None
    assert decode_line(b'{"a":Infinity}') is None
    assert decode_line(b"[" * 100_000) is None
