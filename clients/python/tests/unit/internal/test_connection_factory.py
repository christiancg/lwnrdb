from __future__ import annotations

import asyncio
import socket
import ssl

import pytest

from lwnrdb import AuthenticationError, ConnectionLostError, Credentials, InsecureTlsWarning, ServerAddress, TlsSettings
from lwnrdb._internal.connection_factory import (
    ConnectionFactory,
    ResetTolerantSocket,
    Streams,
    connected_socket,
    open_streams,
    ssl_context,
)
from lwnrdb._internal.request_encoder import RequestEncoder
from lwnrdb._internal.values_codec import JsonObject
from lwnrdb.settings import ClientSettings
from tests.support.fake_server import FakeServer, Session
from tests.support.recording_channel import error
from tests.support.server_process import free_port


async def test_the_factory_tries_hosts_in_order_and_skips_unreachable_ones(fake: FakeServer) -> None:
    unreachable = ServerAddress("127.0.0.1", free_port())
    factory = ConnectionFactory(fake.settings(hosts=(unreachable, *fake.hosts)), RequestEncoder())
    connection = await factory.open()
    assert connection.is_open
    assert connection.label == f"127.0.0.1:{fake.port}"
    connection.close()


async def test_no_reachable_host_raises_connection_lost() -> None:
    factory = ConnectionFactory(
        ClientSettings(hosts=(ServerAddress("127.0.0.1", free_port()),), connect_timeout=2), RequestEncoder()
    )
    with pytest.raises(ConnectionLostError, match=r"could not connect to 127\.0\.0\.1"):
        await factory.open()


async def test_a_connect_timeout_is_a_connection_failure(fake: FakeServer) -> None:
    async def never(address: ServerAddress, settings: ClientSettings) -> Streams:
        del address, settings
        raise TimeoutError

    factory = ConnectionFactory(fake.settings(), RequestEncoder(), never)
    with pytest.raises(ConnectionLostError, match="TimeoutError"):
        await factory.open()


async def test_credentials_authenticate_each_new_connection(fake: FakeServer) -> None:
    factory = ConnectionFactory(fake.settings(credentials=Credentials("alice", "pw")), RequestEncoder())
    connection = await factory.open()
    assert fake.requests == [{"type": "AUTHENTICATE", "username": "alice", "password": "pw"}]
    connection.close()


async def test_a_refused_authentication_closes_the_connection(fake: FakeServer) -> None:
    def refuse(request: JsonObject, session: Session) -> None:
        session.send(error(request, "UNAUTHENTICATED", "401-3", "bad credentials"))

    fake.responder = refuse
    factory = ConnectionFactory(fake.settings(credentials=Credentials("alice", "nope")), RequestEncoder())
    with pytest.raises(AuthenticationError, match="401-3"):
        await factory.open()


def test_an_insecure_tls_setting_warns() -> None:
    settings = ClientSettings(tls=TlsSettings(insecure=True))
    with pytest.warns(InsecureTlsWarning, match="disabled"):
        ConnectionFactory(settings, RequestEncoder())


def test_ssl_context_honours_the_settings() -> None:
    insecure = ssl_context(TlsSettings(insecure=True))
    assert insecure.verify_mode is ssl.CERT_NONE
    assert not insecure.check_hostname
    strict = ssl_context(TlsSettings())
    assert strict.verify_mode is ssl.CERT_REQUIRED
    given = ssl.create_default_context()
    assert ssl_context(TlsSettings(context=given)) is given


async def test_open_streams_connects_plainly(fake: FakeServer) -> None:
    reader, writer = await open_streams(fake.hosts[0], fake.settings())
    writer.write(b'{"type":"SERVER_INFO"}\n')
    assert b'"OK"' in await reader.readline()
    writer.close()


def test_shutting_down_a_socket_that_is_no_longer_connected_does_not_raise() -> None:
    with ResetTolerantSocket() as raw:
        raw.shutdown(socket.SHUT_RDWR)
        assert raw.fileno() != -1


async def test_open_streams_turns_keepalive_on(fake: FakeServer) -> None:
    reader, writer = await open_streams(fake.hosts[0], fake.settings())
    del reader
    assert writer.get_extra_info("socket").getsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE)
    writer.close()


async def test_a_connect_cancelled_midway_closes_its_socket(monkeypatch: pytest.MonkeyPatch) -> None:
    loop = asyncio.get_running_loop()
    opened: list[socket.socket] = []
    connecting = asyncio.Event()

    async def hang(raw: socket.socket, target: object) -> None:
        del target
        opened.append(raw)
        connecting.set()
        await asyncio.Event().wait()

    monkeypatch.setattr(loop, "sock_connect", hang)
    task = asyncio.create_task(connected_socket("127.0.0.1", free_port()))
    await connecting.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert isinstance(opened[0], ResetTolerantSocket)
    assert opened[0].fileno() == -1


async def test_a_tls_handshake_the_server_hangs_up_on_is_a_connection_failure() -> None:
    async def hang_up(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        del reader
        writer.close()

    server = await asyncio.start_server(hang_up, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    settings = ClientSettings(hosts=(ServerAddress("127.0.0.1", port),), tls=TlsSettings(), connect_timeout=2)
    with pytest.raises(ConnectionLostError, match="could not connect"):
        await ConnectionFactory(settings, RequestEncoder()).open()
    server.close()
    await server.wait_closed()
