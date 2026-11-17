from __future__ import annotations

import contextlib
from collections.abc import AsyncIterator
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from types import TracebackType
from typing import Self

from lwnrdb._async.admin import AsyncAdmin
from lwnrdb._async.database import AsyncDatabase
from lwnrdb._async.transaction import AsyncTransaction
from lwnrdb._internal.client_context import ClientContext
from lwnrdb._internal.connection_factory import ConnectionFactory, Opener, open_streams
from lwnrdb._internal.connection_pool import ConnectionPool
from lwnrdb._internal.descriptors import server
from lwnrdb._internal.listens import Listens
from lwnrdb._internal.request_channel import Backoff, PooledChannel, jittered_backoff
from lwnrdb._internal.request_encoder import RequestEncoder
from lwnrdb._internal.response_decoder import document, strings
from lwnrdb._internal.script_request_encoder import ScriptRequestEncoder
from lwnrdb._internal.values_codec import EncodeOptions
from lwnrdb.admin_types import ServerInfo
from lwnrdb.document import Document
from lwnrdb.errors import LwnrdbError
from lwnrdb.settings import ClientSettings, check_zone


class AsyncLwnrdbClient:
    def __init__(self, settings: ClientSettings, backoff: Backoff, opener: Opener) -> None:
        self._settings = settings
        zone = None if settings.temporal_zone is None else check_zone(settings.temporal_zone)
        encoder = RequestEncoder(EncodeOptions(zone))
        factory = ConnectionFactory(settings, encoder, opener)
        self._pool = ConnectionPool(factory)
        self._listens = Listens(factory, encoder, settings.request_timeout)
        self._context = ClientContext(
            channel=PooledChannel(self._pool, settings.retry_policy, settings.request_timeout, backoff),
            encoder=encoder,
            scripts=ScriptRequestEncoder(encoder),
            listens=self._listens,
            username=None if settings.credentials is None else settings.credentials.username,
            request_timeout=settings.request_timeout,
        )

    @classmethod
    async def connect(cls, settings: ClientSettings | None = None) -> AsyncLwnrdbClient:
        return await cls._connect(settings or ClientSettings(), jittered_backoff, open_streams)

    @classmethod
    async def _connect(cls, settings: ClientSettings, backoff: Backoff, opener: Opener) -> AsyncLwnrdbClient:
        client = cls(settings, backoff, opener)
        try:
            lease = await client._pool.borrow()
        except BaseException:
            await client.close()
            raise
        lease.release()
        return client

    @property
    def settings(self) -> ClientSettings:
        return self._settings

    def database(self, name: str) -> AsyncDatabase:
        return AsyncDatabase(self._context, name)

    async def list_databases(self) -> tuple[str, ...]:
        reply = await self._context.channel.send("LIST_DATABASES", self._context.encoder.list_databases())
        return strings(reply.get("databases"))

    async def server_info(self) -> ServerInfo:
        reply = await self._context.channel.send("SERVER_INFO", self._context.encoder.server_info())
        return server(document(reply, "server") or {})

    async def database_stats(self) -> Document:
        reply = await self._context.channel.send("GET_DATABASE_STATS", self._context.encoder.database_stats())
        return document(reply, "stats") or {}

    async def change_password(self, current_password: str, new_password: str) -> None:
        username = self._context.username
        if username is None:
            raise LwnrdbError("the client has no credentials to change")
        await self._context.channel.send(
            "SET_PASSWORD", self._context.scripts.set_password(username, current_password, new_password)
        )

    def admin(self) -> AsyncAdmin:
        return AsyncAdmin(self._context)

    async def begin_transaction(self) -> AsyncTransaction:
        return await AsyncTransaction._start(await self._pool.borrow(), self._context)

    def transaction(self) -> AbstractAsyncContextManager[AsyncTransaction]:
        return self._transaction()

    @asynccontextmanager
    async def _transaction(self) -> AsyncIterator[AsyncTransaction]:
        transaction = await self.begin_transaction()
        try:
            yield transaction
        except BaseException:
            if transaction.is_open:
                with contextlib.suppress(Exception):
                    await transaction.rollback()
            raise
        if transaction.is_open:
            await transaction.commit()

    async def close(self) -> None:
        await self._listens.close()
        await self._pool.close()

    async def __aenter__(self) -> Self:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.close()
