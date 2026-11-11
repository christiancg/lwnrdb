from __future__ import annotations

from typing import Generic, TypeVar

from lwnrdb._internal.collection_ops import QueryTarget
from lwnrdb.document import Document
from lwnrdb.query.builder import QueryBuilder
from lwnrdb.query.types import CountStep
from lwnrdb.results import AnalyzeResult

R = TypeVar("R")
_COUNT_FIELD = "count"


class AsyncQuery(QueryBuilder[QueryTarget[R]], Generic[R]):
    __slots__ = ()

    async def to_list(self) -> list[R]:
        return [self._target.decode(row) for row in await self.to_documents()]

    async def to_documents(self) -> list[Document]:
        return await self._target.aggregate(self._steps, self._dirty)

    async def first(self) -> R | None:
        rows = await self.limit(1).to_documents()
        return self._target.decode(rows[0]) if rows else None

    async def count(self) -> int:
        rows = await self._then(CountStep()).to_documents()
        count = rows[0].get(_COUNT_FIELD) if rows else None
        return int(count) if isinstance(count, int | float) and not isinstance(count, bool) else 0

    async def analyze(self) -> AnalyzeResult:
        return await self._target.analyze(self._steps, self._dirty)
