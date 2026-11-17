from __future__ import annotations

import asyncio
import contextlib
from collections.abc import Awaitable, Callable
from typing import Any

from lwnrdb._async.subscription import AsyncSubscription
from lwnrdb._internal.frame_router import is_listen_end
from lwnrdb._internal.handler_adapter import Callbacks
from lwnrdb._internal.request_encoder import RequestEncoder
from lwnrdb._internal.response_decoder import check, documents, raise_for, text_of
from lwnrdb._internal.values_codec import JsonObject
from lwnrdb._internal.wire_connection import WireConnection
from lwnrdb.document import Document
from lwnrdb.errors import ListenEndedError, LwnrdbError
from lwnrdb.listen import ListenUpdate

_LISTEN_ID = "listenId"
Task = Callable[[], Awaitable[None]]


class Registration:
    __slots__ = ("_callbacks", "_decode", "listen_id", "subscription")

    def __init__(
        self,
        listen_id: str,
        subscription: AsyncSubscription,
        decode: Callable[[Document], Any],
        callbacks: Callbacks,
    ) -> None:
        self.listen_id = listen_id
        self.subscription = subscription
        self._decode = decode
        self._callbacks = callbacks

    async def update(self, frame: JsonObject, *, initial: bool) -> None:
        try:
            results = tuple(self._decode(row) for row in documents(frame, "results"))
            await self._callbacks.on_update(
                ListenUpdate(self.listen_id, results, text_of(frame.get("resultHash")) or "", initial)
            )
        except Exception as error:
            await self.failed(error)

    async def ended(self, reason: ListenEndedError) -> None:
        try:
            await self._callbacks.on_ended(reason)
        except Exception as error:
            await self.failed(error)

    async def failed(self, error: BaseException) -> None:
        with contextlib.suppress(Exception):
            await self._callbacks.on_error(error)


class ListenChannel:
    def __init__(
        self,
        connection: WireConnection,
        encoder: RequestEncoder,
        request_timeout: float,
        on_lost: Callable[[ListenChannel], None],
    ) -> None:
        self._connection = connection
        self._encoder = encoder
        self._request_timeout = request_timeout
        self._on_lost = on_lost
        self._registrations: dict[str, Registration] = {}
        self._early: dict[str, list[JsonObject]] = {}
        self._tasks: asyncio.Queue[Task | None] = asyncio.Queue()
        self._active = 0
        self._closing = False
        self._stopped = False
        self._dispatcher = asyncio.get_running_loop().create_task(self._drain(), name="lwnrdb-listen-dispatch")
        connection.on_push(self._on_push)
        connection.on_close(self._on_close)

    @property
    def is_open(self) -> bool:
        return not self._closing and self._connection.is_open

    async def register(
        self,
        request: JsonObject,
        decode: Callable[[Document], Any],
        callbacks: Callbacks,
    ) -> AsyncSubscription | None:
        if not self._reserve():
            return None
        try:
            reply = check(await self._connection.request(request, self._request_timeout))
        except BaseException:
            await self._release()
            raise
        listen_id = text_of(reply.get(_LISTEN_ID)) or ""
        subscription = AsyncSubscription(listen_id, self)
        registration = Registration(listen_id, subscription, decode, callbacks)
        self._dispatch(lambda: self._activate(registration, reply))
        return subscription

    async def stop(self, listen_id: str) -> None:
        try:
            check(await self._connection.request(self._encoder.stop_listen(listen_id), self._request_timeout))
        finally:
            self._dispatch(lambda: self._forget(listen_id))
            await self._release()

    async def close(self) -> None:
        self._closing = True
        await self._connection.close_gracefully(self._request_timeout)
        self._stop_dispatching()

    def _on_push(self, frame: JsonObject) -> None:
        self._dispatch(lambda: self._deliver(frame))

    def _on_close(self, cause: LwnrdbError) -> None:
        self._dispatch(lambda: self._lost(cause))
        self._stop_dispatching()
        self._on_lost(self)

    def _dispatch(self, task: Task) -> None:
        if not self._stopped:
            self._tasks.put_nowait(task)

    def _stop_dispatching(self) -> None:
        if not self._stopped:
            self._stopped = True
            self._tasks.put_nowait(None)

    async def _drain(self) -> None:
        while (task := await self._tasks.get()) is not None:
            with contextlib.suppress(Exception):
                await task()

    def _reserve(self) -> bool:
        if not self.is_open:
            return False
        self._active += 1
        return True

    async def _release(self) -> None:
        self._active -= 1
        if self._active == 0 and not self._closing:
            await self.close()

    async def _forget(self, listen_id: str) -> None:
        self._registrations.pop(listen_id, None)

    async def _activate(self, registration: Registration, initial: JsonObject) -> None:
        self._registrations[registration.listen_id] = registration
        await registration.update(initial, initial=True)
        for frame in self._early.pop(registration.listen_id, []):
            await self._deliver(frame)

    async def _deliver(self, frame: JsonObject) -> None:
        listen_id = text_of(frame.get(_LISTEN_ID)) or ""
        registration = self._registrations.get(listen_id)
        if registration is None:
            self._early.setdefault(listen_id, []).append(frame)
            return
        if is_listen_end(frame):
            del self._registrations[listen_id]
            registration.subscription._mark_ended()
            reason = raise_for(frame)
            await registration.ended(reason if isinstance(reason, ListenEndedError) else ListenEndedError(str(reason)))
            await self._release()
            return
        if registration.subscription.is_active:
            await registration.update(frame, initial=False)

    async def _lost(self, cause: LwnrdbError) -> None:
        self._closing = True
        lost = list(self._registrations.values())
        self._registrations.clear()
        self._early.clear()
        for registration in lost:
            registration.subscription._mark_ended()
            await registration.failed(cause)
