from __future__ import annotations

import asyncio
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


async def open_streams(address: ServerAddress, settings: ClientSettings) -> Streams:
    tls = settings.tls
    async with asyncio.timeout(settings.connect_timeout):
        if tls is None:
            reader, writer = await asyncio.open_connection(address.host, address.port)
        else:
            reader, writer = await asyncio.open_connection(
                address.host,
                address.port,
                ssl=ssl_context(tls),
                server_hostname=tls.server_hostname or address.host,
            )
    raw = writer.get_extra_info("socket")
    if raw is not None:
        raw.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    return reader, writer


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
