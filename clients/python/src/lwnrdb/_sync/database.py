from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any, TypeVar, overload

from lwnrdb._async.database import AsyncDatabase
from lwnrdb._internal.portal import SyncRuntime
from lwnrdb._sync.collection import Collection
from lwnrdb._sync.scripting import Procedures, Schedules
from lwnrdb.document import Document, DocumentCodec
from lwnrdb.results import ScriptResult
from lwnrdb.scripting_types import TriggerInfo

T = TypeVar("T")
R = TypeVar("R")
M = TypeVar("M")


class Database:
    def __init__(self, runtime: SyncRuntime, inner: AsyncDatabase) -> None:
        self._runtime = runtime
        self._inner = inner

    @property
    def name(self) -> str:
        return self._inner.name

    def create(self) -> None:
        self._runtime.run(self._inner.create())

    def drop(self) -> None:
        self._runtime.run(self._inner.drop())

    def list_collections(self) -> tuple[str, ...]:
        return self._runtime.run(self._inner.list_collections())

    @overload
    def collection(self, name: str) -> Collection[Document, Document]: ...

    @overload
    def collection(self, name: str, model: type[M]) -> Collection[M, M]: ...

    @overload
    def collection(self, name: str, *, codec: DocumentCodec[T, R]) -> Collection[T, R]: ...

    def collection(
        self,
        name: str,
        model: type[Any] | None = None,
        *,
        codec: DocumentCodec[Any, Any] | None = None,
    ) -> Collection[Any, Any]:
        return Collection(self._runtime, self._inner._collection(name, model, codec))

    def stats(self) -> Document:
        return self._runtime.run(self._inner.stats())

    def set_owners(self, owners: Sequence[str]) -> None:
        self._runtime.run(self._inner.set_owners(owners))

    def run_script(self, script: str, args: Mapping[str, object] | None = None) -> ScriptResult:
        return self._runtime.run(self._inner.run_script(script, args))

    def procedures(self) -> Procedures:
        return Procedures(self._runtime, self._inner.procedures())

    def schedules(self) -> Schedules:
        return Schedules(self._runtime, self._inner.schedules())

    def list_triggers(self) -> list[TriggerInfo]:
        return self._runtime.run(self._inner.list_triggers())
