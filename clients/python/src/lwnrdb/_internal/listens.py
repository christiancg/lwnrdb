from __future__ import annotations

import asyncio
from collections.abc import Callable, Sequence
from typing import Any

from lwnrdb._async.subscription import AsyncSubscription
from lwnrdb._internal.connection_factory import ConnectionFactory
from lwnrdb._internal.handler_adapter import Callbacks
from lwnrdb._internal.listen_channel import ListenChannel
from lwnrdb._internal.request_encoder import RequestEncoder
from lwnrdb._internal.values_codec import JsonObject
from lwnrdb.document import Document
from lwnrdb.error_codes import ErrorCode
from lwnrdb.errors import LwnrdbError
from lwnrdb.query.types import PipelineStep


class Listens:
    def __init__(self, factory: ConnectionFactory, encoder: RequestEncoder, request_timeout: float) -> None:
        self._factory = factory
        self._encoder = encoder
        self._request_timeout = request_timeout
        self._channels: list[ListenChannel] = []
        self._lock = asyncio.Lock()

    @property
    def open_channels(self) -> int:
        return sum(1 for channel in self._channels if channel.is_open)

    async def listen(
        self,
        database: str,
        collection: str,
        steps: Sequence[PipelineStep],
        decode: Callable[[Document], Any],
        callbacks: Callbacks,
    ) -> AsyncSubscription:
        request = self._encoder.listen(database, collection, steps)
        async with self._lock:
            return await self._register(request, decode, callbacks)

    async def close(self) -> None:
        channels, self._channels = self._channels, []
        await asyncio.gather(*(channel.close() for channel in channels))

    async def _register(
        self,
        request: JsonObject,
        decode: Callable[[Document], Any],
        callbacks: Callbacks,
    ) -> AsyncSubscription:
        for channel in list(self._channels):
            subscription = await self._try(channel, request, decode, callbacks)
            if subscription is not None:
                return subscription
        channel = ListenChannel(await self._factory.open(), self._encoder, self._request_timeout, self._remove)
        self._channels.append(channel)
        try:
            subscription = await channel.register(request, decode, callbacks)
        except BaseException:
            await self._discard(channel)
            raise
        if subscription is None:
            await self._discard(channel)
            raise LwnrdbError("a new listen connection closed before the listen registered")
        return subscription

    @staticmethod
    async def _try(
        channel: ListenChannel,
        request: JsonObject,
        decode: Callable[[Document], Any],
        callbacks: Callbacks,
    ) -> AsyncSubscription | None:
        if not channel.is_open:
            return None
        try:
            return await channel.register(request, decode, callbacks)
        except LwnrdbError as error:
            if error.has_code(ErrorCode.TOO_MANY_LISTENS):
                return None
            raise

    async def _discard(self, channel: ListenChannel) -> None:
        self._remove(channel)
        await channel.close()

    def _remove(self, channel: ListenChannel) -> None:
        if channel in self._channels:
            self._channels.remove(channel)
