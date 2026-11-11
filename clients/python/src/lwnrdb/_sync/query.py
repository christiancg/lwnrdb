from __future__ import annotations

from typing import Generic, TypeVar

from lwnrdb._async.query import AsyncQuery
from lwnrdb._internal.collection_ops import QueryTarget
from lwnrdb._internal.portal import SyncRuntime
from lwnrdb.document import Document
from lwnrdb.query.builder import QueryBuilder
from lwnrdb.results import AnalyzeResult

R = TypeVar("R")


class SyncTarget(Generic[R]):
    __slots__ = ("inner", "runtime")

    def __init__(self, runtime: SyncRuntime, inner: QueryTarget[R]) -> None:
        self.runtime = runtime
        self.inner = inner


class Query(QueryBuilder[SyncTarget[R]], Generic[R]):
    __slots__ = ()

    def to_list(self) -> list[R]:
        return self._target.runtime.run(self._async().to_list())

    def to_documents(self) -> list[Document]:
        return self._target.runtime.run(self._async().to_documents())

    def first(self) -> R | None:
        return self._target.runtime.run(self._async().first())

    def count(self) -> int:
        return self._target.runtime.run(self._async().count())

    def analyze(self) -> AnalyzeResult:
        return self._target.runtime.run(self._async().analyze())

    def _async(self) -> AsyncQuery[R]:
        return AsyncQuery(self._target.inner, self._steps, self._dirty)
