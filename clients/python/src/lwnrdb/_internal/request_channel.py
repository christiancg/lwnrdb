from __future__ import annotations

import asyncio
import random
from collections.abc import Awaitable, Callable
from typing import Protocol

from lwnrdb._internal.connection_pool import ConnectionPool
from lwnrdb._internal.response_decoder import check
from lwnrdb._internal.values_codec import JsonObject
from lwnrdb._internal.wire_connection import WireConnection
from lwnrdb.error_codes import RETRYABLE_CODES
from lwnrdb.errors import ConnectionLostError, LwnrdbError, UnavailableError
from lwnrdb.operations import operation_details
from lwnrdb.settings import RetryPolicy

Backoff = Callable[[float], Awaitable[None]]


class RequestChannel(Protocol):
    async def send(self, operation: str, request: JsonObject) -> JsonObject: ...


async def jittered_backoff(delay: float) -> None:
    await asyncio.sleep(delay / 2 + random.random() * (delay / 2))


def is_retryable(operation: str, error: LwnrdbError) -> bool:
    if isinstance(error, ConnectionLostError):
        return operation_details(operation).read
    return isinstance(error, UnavailableError) and error.raw_code in RETRYABLE_CODES


class PooledChannel:
    def __init__(
        self, pool: ConnectionPool, retry_policy: RetryPolicy, request_timeout: float, backoff: Backoff
    ) -> None:
        self._pool = pool
        self._retry_policy = retry_policy
        self._request_timeout = request_timeout
        self._backoff = backoff

    async def send(self, operation: str, request: JsonObject) -> JsonObject:
        attempt = 1
        while True:
            try:
                return await self._once(request)
            except LwnrdbError as error:
                if attempt >= self._retry_policy.max_attempts or not is_retryable(operation, error):
                    raise
                await self._backoff(self._retry_policy.backoff_before(attempt))
                attempt += 1

    async def _once(self, request: JsonObject) -> JsonObject:
        lease = await self._pool.borrow()
        try:
            return check(await lease.connection.request(request, self._request_timeout))
        except (ConnectionLostError, asyncio.CancelledError):
            lease.discard()
            raise
        finally:
            lease.release()


class PinnedChannel:
    def __init__(self, connection: WireConnection, request_timeout: float) -> None:
        self._connection = connection
        self._request_timeout = request_timeout

    async def send(self, operation: str, request: JsonObject) -> JsonObject:
        if not operation_details(operation).allowed_in_transaction:
            raise LwnrdbError(f"{operation} is not allowed inside a transaction", operation=operation)
        return check(await self._connection.request(request, self._request_timeout))
