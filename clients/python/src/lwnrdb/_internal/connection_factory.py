from __future__ import annotations

import asyncio
import contextlib
import socket
import ssl
import warnings
from collections.abc import Awaitable, Callable

from lwnrdb._internal.request_encoder import RequestEncoder
from lwnrdb._internal.response_decoder import check
from lwnrdb._internal.wire_connection import WireConnection
from lwnrdb.errors import ConnectionLostError, LwnrdbError
from lwnrdb.settings import ClientSettings, ServerAddress, TlsSettings

Streams = tuple[asyncio.StreamReader, asyncio.StreamWriter]
Opener = Callable[[ServerAddress, ClientSettings], Awaitable[Streams]]

INSECURE_WARNING = "LWNRDB TLS certificate and hostname checks are disabled; use this only in development"


class InsecureTlsWarning(UserWarning):
    pass


def ssl_context(tls: TlsSettings) -> ssl.SSLContext:
    if tls.context is not None:
        return tls.context
    context = ssl.create_default_context(cafile=tls.ca_file, cadata=tls.ca_data)
    if tls.insecure:
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
    return context


class ResetTolerantSocket(socket.socket):
    def shutdown(self, how: int, /) -> None:
        with contextlib.suppress(OSError):
            super().shutdown(how)


async def connected_socket(host: str, port: int) -> socket.socket:
    loop = asyncio.get_running_loop()
    failure = OSError(f"{host} resolved to no address")
    for family, kind, proto, _, target in await loop.getaddrinfo(host, port, type=socket.SOCK_STREAM):
        raw = ResetTolerantSocket(family, kind, proto)
        try:
            raw.settimeout(0)
            await loop.sock_connect(raw, target)
            return raw
        except OSError as error:
            raw.close()
            failure = error
        except asyncio.CancelledError:
            raw.close()
            raise
    raise failure


async def open_streams(address: ServerAddress, settings: ClientSettings) -> Streams:
    tls = settings.tls
    async with asyncio.timeout(settings.connect_timeout):
        raw = await connected_socket(address.host, address.port)
        try:
            raw.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            if tls is None:
                return await asyncio.open_connection(sock=raw)
            return await asyncio.open_connection(
                sock=raw, ssl=ssl_context(tls), server_hostname=tls.server_hostname or address.host
            )
        except BaseException:
            raw.close()
            raise


class ConnectionFactory:
    def __init__(self, settings: ClientSettings, encoder: RequestEncoder, opener: Opener = open_streams) -> None:
        self.settings = settings
        self._encoder = encoder
        self._opener = opener
        self._next_host = 0
        if settings.tls is not None and settings.tls.insecure:
            warnings.warn(INSECURE_WARNING, InsecureTlsWarning, stacklevel=4)

    async def open(self) -> WireConnection:
        hosts = self.settings.hosts
        last: ConnectionLostError | None = None
        for _ in hosts:
            address = hosts[self._next_host % len(hosts)]
            self._next_host += 1
            try:
                reader, writer = await self._opener(address, self.settings)
            except (OSError, TimeoutError, ssl.SSLError) as error:
                reason = str(error) or type(error).__name__
                last = ConnectionLostError(f"could not connect to {address.host}:{address.port}: {reason}")
                continue
            connection = WireConnection(reader, writer, self.settings.max_reply_bytes, f"{address.host}:{address.port}")
            return await self._authenticated(connection)
        raise last or ConnectionLostError("no host to connect to")

    async def _authenticated(self, connection: WireConnection) -> WireConnection:
        credentials = self.settings.credentials
        if credentials is None:
            return connection
        try:
            check(
                await connection.request(
                    self._encoder.authenticate(credentials.username, credentials.password),
                    self.settings.request_timeout,
                )
            )
        except (LwnrdbError, asyncio.CancelledError):
            connection.close()
            raise
        return connection
