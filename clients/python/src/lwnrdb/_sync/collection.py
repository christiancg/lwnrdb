from __future__ import annotations

from collections.abc import Sequence
from typing import Generic, TypeVar

from lwnrdb._async.collection import AsyncCollection
from lwnrdb._internal.handler_adapter import threaded_callbacks
from lwnrdb._internal.portal import SyncRuntime
from lwnrdb._sync.indexes import Indexes, Schema, Triggers
from lwnrdb._sync.query import Query, SyncTarget
from lwnrdb._sync.subscription import Subscription
from lwnrdb.document import Document
from lwnrdb.listen import Handler
from lwnrdb.query.types import Filter
from lwnrdb.results import BulkSaveResult, SaveResult

T = TypeVar("T")
R = TypeVar("R")


class Collection(Generic[T, R]):
    def __init__(self, runtime: SyncRuntime, inner: AsyncCollection[T, R]) -> None:
        self._runtime = runtime
        self._inner = inner

    @property
    def database_name(self) -> str:
        return self._inner.database_name

    @property
    def name(self) -> str:
        return self._inner.name

    def create(self) -> None:
        self._runtime.run(self._inner.create())

    def drop(self) -> None:
        self._runtime.run(self._inner.drop())

    def save(self, value: T) -> SaveResult:
        return self._runtime.run(self._inner.save(value))

    def save_all(self, values: Sequence[T]) -> BulkSaveResult:
        return self._runtime.run(self._inner.save_all(values))

    def find_by_id(self, id: str, *, dirty_read: bool = False) -> R | None:  # noqa: A002
        return self._runtime.run(self._inner.find_by_id(id, dirty_read=dirty_read))

    def update_where(self, condition: Filter, patch: Document) -> int:
        return self._runtime.run(self._inner.update_where(condition, patch))

    def delete(self, id: str) -> None:  # noqa: A002
        self._runtime.run(self._inner.delete(id))

    def delete_where(self, condition: Filter) -> int:
        return self._runtime.run(self._inner.delete_where(condition))

    def query(self) -> Query[R]:
        return Query(SyncTarget(self._runtime, self._inner._ops))

    def indexes(self) -> Indexes:
        return Indexes(self._runtime, self._inner.indexes())

    def schema(self) -> Schema:
        return Schema(self._runtime, self._inner.schema())

    def triggers(self) -> Triggers:
        return Triggers(self._runtime, self._inner.triggers())

    def listen(self, handler: Handler[R], query: Query[R] | None = None) -> Subscription:
        callbacks = threaded_callbacks(handler, self._runtime.listen_executor)
        steps = () if query is None else query.steps
        return Subscription(self._runtime, self._runtime.run(self._inner._listen(callbacks, steps)))
