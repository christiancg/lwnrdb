from __future__ import annotations

import asyncio
from collections.abc import Sequence
from types import TracebackType
from typing import Any, Generic, Self, TypeVar, overload

from lwnrdb._async.query import AsyncQuery
from lwnrdb._internal.client_context import ClientContext, with_channel
from lwnrdb._internal.codecs import codec_for
from lwnrdb._internal.collection_ops import CollectionOps
from lwnrdb._internal.connection_pool import Lease
from lwnrdb._internal.request_channel import PinnedChannel
from lwnrdb._internal.response_decoder import text_of
from lwnrdb.document import Document, DocumentCodec
from lwnrdb.errors import ConnectionLostError, LwnrdbError, UnknownOutcomeError
from lwnrdb.query.types import Filter
from lwnrdb.results import BulkSaveResult, SaveResult

T = TypeVar("T")
R = TypeVar("R")
M = TypeVar("M")


class AsyncTransactionCollection(Generic[T, R]):
    def __init__(self, ops: CollectionOps[T, R]) -> None:
        self._ops = ops

    @property
    def database_name(self) -> str:
        return self._ops.database

    @property
    def name(self) -> str:
        return self._ops.collection

    async def save(self, value: T) -> SaveResult:
        return await self._ops.save(value)

    async def insert(self, value: T) -> SaveResult:
        return await self._ops.insert(value)

    async def save_all(self, values: Sequence[T]) -> BulkSaveResult:
        return await self._ops.save_all(values)

    async def find_by_id(self, id: str) -> R | None:  # noqa: A002
        return await self._ops.find_by_id(id, dirty_read=False)

    async def update_where(self, condition: Filter, patch: Document) -> int:
        return await self._ops.update_where(condition, patch)

    async def delete(self, id: str) -> None:  # noqa: A002
        await self._ops.delete(id)

    async def delete_where(self, condition: Filter) -> int:
        return await self._ops.delete_where(condition)

    def query(self) -> AsyncQuery[R]:
        return AsyncQuery(self._ops)


class AsyncTransaction:
    def __init__(self, transaction_id: str, lease: Lease, context: ClientContext) -> None:
        self._id = transaction_id
        self._lease = lease
        self._context = context
        self._finished = False

    @classmethod
    async def _start(cls, lease: Lease, parent: ClientContext) -> AsyncTransaction:
        context = with_channel(parent, PinnedChannel(lease.connection, parent.request_timeout))
        try:
            reply = await context.channel.send("START_TRANSACTION", context.encoder.start_transaction())
        except BaseException as error:
            if isinstance(error, ConnectionLostError | asyncio.CancelledError):
                lease.discard()
            lease.release()
            raise
        return cls(text_of(reply.get("transactionId")) or "", lease, context)

    @property
    def id(self) -> str:
        return self._id

    @property
    def is_open(self) -> bool:
        return not self._finished

    @overload
    def collection(self, database: str, collection: str) -> AsyncTransactionCollection[Document, Document]: ...

    @overload
    def collection(self, database: str, collection: str, model: type[M]) -> AsyncTransactionCollection[M, M]: ...

    @overload
    def collection(
        self, database: str, collection: str, *, codec: DocumentCodec[T, R]
    ) -> AsyncTransactionCollection[T, R]: ...

    def collection(
        self,
        database: str,
        collection: str,
        model: type[Any] | None = None,
        *,
        codec: DocumentCodec[Any, Any] | None = None,
    ) -> AsyncTransactionCollection[Any, Any]:
        return self._collection(database, collection, model, codec)

    def _collection(
        self, database: str, collection: str, model: type[Any] | None, codec: DocumentCodec[Any, Any] | None
    ) -> AsyncTransactionCollection[Any, Any]:
        self._require_open()
        return AsyncTransactionCollection(CollectionOps(self._context, database, collection, codec_for(model, codec)))

    async def commit(self) -> None:
        await self._end("COMMIT_TRANSACTION")

    async def rollback(self) -> None:
        await self._end("ROLLBACK_TRANSACTION")

    async def close(self) -> None:
        if self.is_open:
            await self.rollback()

    async def __aenter__(self) -> Self:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.close()

    async def _end(self, operation: str) -> None:
        self._require_open()
        self._finished = True
        encoder = self._context.encoder
        request = encoder.commit_transaction() if operation == "COMMIT_TRANSACTION" else encoder.rollback_transaction()
        try:
            await self._context.channel.send(operation, request)
        except BaseException as error:
            if isinstance(error, UnknownOutcomeError | ConnectionLostError | asyncio.CancelledError):
                self._lease.discard()
            raise
        finally:
            self._lease.release()

    def _require_open(self) -> None:
        if self._finished:
            raise LwnrdbError("the transaction is already committed or rolled back")
