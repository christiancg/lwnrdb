from __future__ import annotations

from collections.abc import Mapping

from lwnrdb._async.indexes import AsyncIndexes
from lwnrdb._async.schema import AsyncSchema
from lwnrdb._async.triggers import AsyncTriggers
from lwnrdb._internal.portal import SyncRuntime
from lwnrdb.document import Document
from lwnrdb.results import ReindexResult
from lwnrdb.scripting_types import TriggerDefinition, TriggerEvent, TriggerInfo, TriggerSaved, TriggerTestResult


class Indexes:
    def __init__(self, runtime: SyncRuntime, inner: AsyncIndexes) -> None:
        self._runtime = runtime
        self._inner = inner

    def create(self, field: str, *more_fields: str) -> None:
        self._runtime.run(self._inner.create(field, *more_fields))

    def drop(self, field: str) -> None:
        self._runtime.run(self._inner.drop(field))

    def reindex(self, *fields: str) -> ReindexResult:
        return self._runtime.run(self._inner.reindex(*fields))

    def reindex_primary_key(self) -> ReindexResult:
        return self._runtime.run(self._inner.reindex_primary_key())


class Schema:
    def __init__(self, runtime: SyncRuntime, inner: AsyncSchema) -> None:
        self._runtime = runtime
        self._inner = inner

    def save(self, schema: Mapping[str, object]) -> tuple[str, ...]:
        return self._runtime.run(self._inner.save(schema))

    def get(self) -> Document | None:
        return self._runtime.run(self._inner.get())

    def delete(self) -> None:
        self._runtime.run(self._inner.delete())


class Triggers:
    def __init__(self, runtime: SyncRuntime, inner: AsyncTriggers) -> None:
        self._runtime = runtime
        self._inner = inner

    def save(self, definition: TriggerDefinition) -> TriggerSaved:
        return self._runtime.run(self._inner.save(definition))

    def delete(self, name: str) -> None:
        self._runtime.run(self._inner.delete(name))

    def list(self) -> list[TriggerInfo]:
        return self._runtime.run(self._inner.list())

    def test(self, name: str, event: TriggerEvent, sample: Mapping[str, object]) -> TriggerTestResult:
        return self._runtime.run(self._inner.test(name, event, sample))
