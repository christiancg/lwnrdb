from __future__ import annotations

from types import TracebackType
from typing import Protocol, Self


class SubscriptionChannel(Protocol):
    @property
    def is_open(self) -> bool: ...

    async def stop(self, listen_id: str) -> None: ...


class AsyncSubscription:
    def __init__(self, listen_id: str, channel: SubscriptionChannel) -> None:
        self._listen_id = listen_id
        self._channel = channel
        self._closed = False

    @property
    def listen_id(self) -> str:
        return self._listen_id

    @property
    def is_active(self) -> bool:
        return not self._closed and self._channel.is_open

    def _mark_ended(self) -> None:
        self._closed = True

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        if self._channel.is_open:
            await self._channel.stop(self._listen_id)

    async def __aenter__(self) -> Self:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.close()
