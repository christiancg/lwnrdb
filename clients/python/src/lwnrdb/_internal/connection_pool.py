from __future__ import annotations

import asyncio
import contextlib
from collections import deque

from lwnrdb._internal.connection_factory import ConnectionFactory
from lwnrdb._internal.wire_connection import WireConnection
from lwnrdb.errors import ConnectionLostError, PoolExhaustedError


class Lease:
    __slots__ = ("_discard", "_pool", "_released", "connection")

    def __init__(self, connection: WireConnection, pool: ConnectionPool) -> None:
        self.connection = connection
        self._pool = pool
        self._discard = False
        self._released = False

    def discard(self) -> None:
        self._discard = True

    def release(self) -> None:
        if self._released:
            return
        self._released = True
        self._pool.give_back(self.connection, discard=self._discard)


class ConnectionPool:
    def __init__(self, factory: ConnectionFactory) -> None:
        self._factory = factory
        self._size = factory.settings.pool_size
        self._acquire_timeout = factory.settings.acquire_timeout
        self._shutdown_timeout = factory.settings.shutdown_timeout
        self._idle: list[WireConnection] = []
        self._waiters: deque[asyncio.Future[None]] = deque()
        self._in_use = 0
        self._closed = False
        self._drained: asyncio.Event | None = None

    @property
    def idle_connections(self) -> int:
        return len(self._idle)

    @property
    def connections_in_use(self) -> int:
        return self._in_use

    async def borrow(self) -> Lease:
        if self._closed:
            raise ConnectionLostError("the client is closed")
        await self._acquire_permit()
        try:
            return Lease(await self._connection(), self)
        except BaseException:
            self._release_permit()
            raise

    def give_back(self, connection: WireConnection, *, discard: bool) -> None:
        if discard or self._closed or not connection.is_open:
            connection.close()
        else:
            self._idle.append(connection)
        self._release_permit()

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        while self._waiters:
            waiter = self._waiters.popleft()
            if not waiter.done():
                waiter.set_exception(ConnectionLostError("the client is closed"))
        await self._drain_idle()
        await self._await_in_use()
        await self._drain_idle()

    async def _connection(self) -> WireConnection:
        while self._idle:
            connection = self._idle.pop()
            if connection.is_open:
                return connection
        return await self._factory.open()

    async def _acquire_permit(self) -> None:
        if self._in_use < self._size:
            self._in_use += 1
            return
        waiter: asyncio.Future[None] = asyncio.get_running_loop().create_future()
        self._waiters.append(waiter)
        try:
            async with asyncio.timeout(self._acquire_timeout):
                await waiter
        except TimeoutError:
            self._abandon(waiter)
            raise PoolExhaustedError(
                f"no connection became free within {self._acquire_timeout:g} s; all {self._size} are in use"
            ) from None
        except BaseException:
            self._abandon(waiter)
            raise
        finally:
            with contextlib.suppress(ValueError):
                self._waiters.remove(waiter)

    def _abandon(self, waiter: asyncio.Future[None]) -> None:
        if waiter.done() and not waiter.cancelled() and waiter.exception() is None:
            self._release_permit()

    def _release_permit(self) -> None:
        while self._waiters:
            waiter = self._waiters.popleft()
            if not waiter.done():
                waiter.set_result(None)
                return
        self._in_use -= 1
        if self._in_use == 0 and self._drained is not None:
            self._drained.set()

    async def _await_in_use(self) -> None:
        if self._in_use == 0:
            return
        self._drained = asyncio.Event()
        with contextlib.suppress(TimeoutError):
            async with asyncio.timeout(self._shutdown_timeout):
                await self._drained.wait()

    async def _drain_idle(self) -> None:
        idle, self._idle = self._idle, []
        await asyncio.gather(*(connection.close_gracefully(self._shutdown_timeout) for connection in idle))
