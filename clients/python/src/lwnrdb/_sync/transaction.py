from __future__ import annotations

from collections.abc import Sequence
from types import TracebackType
from typing import Any, Generic, Self, TypeVar, overload

from lwnrdb._async.transaction import AsyncTransaction, AsyncTransactionCollection
from lwnrdb._internal.portal import SyncRuntime
from lwnrdb._sync.query import Query, SyncTarget
from lwnrdb.document import Document, DocumentCodec
from lwnrdb.query.types import Filter
from lwnrdb.results import BulkSaveResult, SaveResult

T = TypeVar("T")
R = TypeVar("R")
M = TypeVar("M")


class TransactionCollection(Generic[T, R]):
    def __init__(self, runtime: SyncRuntime, inner: AsyncTransactionCollection[T, R]) -> None:
        self._runtime = runtime
        self._inner = inner

    @property
    def database_name(self) -> str:
        return self._inner.database_name

    @property
    def name(self) -> str:
        return self._inner.name

    def save(self, value: T) -> SaveResult:
        return self._runtime.run(self._inner.save(value))

    def save_all(self, values: Sequence[T]) -> BulkSaveResult:
        return self._runtime.run(self._inner.save_all(values))

    def find_by_id(self, id: str) -> R | None:  # noqa: A002
        return self._runtime.run(self._inner.find_by_id(id))

    def delete(self, id: str) -> None:  # noqa: A002
        self._runtime.run(self._inner.delete(id))

    def delete_where(self, condition: Filter) -> int:
        return self._runtime.run(self._inner.delete_where(condition))

    def query(self) -> Query[R]:
        return Query(SyncTarget(self._runtime, self._inner._ops))


class Transaction:
    def __init__(self, runtime: SyncRuntime, inner: AsyncTransaction) -> None:
        self._runtime = runtime
        self._inner = inner

    @property
    def id(self) -> str:
        return self._inner.id

    @property
    def is_open(self) -> bool:
        return self._inner.is_open

    @overload
    def collection(self, database: str, collection: str) -> TransactionCollection[Document, Document]: ...

    @overload
    def collection(self, database: str, collection: str, model: type[M]) -> TransactionCollection[M, M]: ...

    @overload
    def collection(
        self, database: str, collection: str, *, codec: DocumentCodec[T, R]
    ) -> TransactionCollection[T, R]: ...

    def collection(
        self,
        database: str,
        collection: str,
        model: type[Any] | None = None,
        *,
        codec: DocumentCodec[Any, Any] | None = None,
    ) -> TransactionCollection[Any, Any]:
        return TransactionCollection(self._runtime, self._inner._collection(database, collection, model, codec))

    def commit(self) -> None:
        self._runtime.run(self._inner.commit())

    def rollback(self) -> None:
        self._runtime.run(self._inner.rollback())

    def close(self) -> None:
        self._runtime.run(self._inner.close())

    def __enter__(self) -> Self:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()
