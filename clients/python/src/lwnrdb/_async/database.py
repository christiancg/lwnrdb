from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any, TypeVar, overload

from lwnrdb._async.collection import AsyncCollection
from lwnrdb._async.procedures import AsyncProcedures
from lwnrdb._async.schedules import AsyncSchedules
from lwnrdb._internal.client_context import ClientContext
from lwnrdb._internal.codecs import codec_for
from lwnrdb._internal.collection_ops import CollectionOps
from lwnrdb._internal.descriptors import script_result, trigger
from lwnrdb._internal.names import database_name
from lwnrdb._internal.response_decoder import document, documents, strings
from lwnrdb.document import Document, DocumentCodec
from lwnrdb.results import ScriptResult
from lwnrdb.scripting_types import TriggerInfo

T = TypeVar("T")
R = TypeVar("R")
M = TypeVar("M")


class AsyncDatabase:
    def __init__(self, context: ClientContext, name: str) -> None:
        self._context = context
        self._name = database_name(name)

    @property
    def name(self) -> str:
        return self._name

    async def create(self) -> None:
        await self._context.channel.send("CREATE_DATABASE", self._context.encoder.create_database(self._name))

    async def drop(self) -> None:
        await self._context.channel.send("DROP_DATABASE", self._context.encoder.drop_database(self._name))

    async def list_collections(self) -> tuple[str, ...]:
        reply = await self._context.channel.send("LIST_COLLECTIONS", self._context.encoder.list_collections(self._name))
        return strings(reply.get("collections"))

    @overload
    def collection(self, name: str) -> AsyncCollection[Document, Document]: ...

    @overload
    def collection(self, name: str, model: type[M]) -> AsyncCollection[M, M]: ...

    @overload
    def collection(self, name: str, *, codec: DocumentCodec[T, R]) -> AsyncCollection[T, R]: ...

    def collection(
        self,
        name: str,
        model: type[Any] | None = None,
        *,
        codec: DocumentCodec[Any, Any] | None = None,
    ) -> AsyncCollection[Any, Any]:
        return self._collection(name, model, codec)

    def _collection(
        self, name: str, model: type[Any] | None, codec: DocumentCodec[Any, Any] | None
    ) -> AsyncCollection[Any, Any]:
        return AsyncCollection(CollectionOps(self._context, self._name, name, codec_for(model, codec)))

    async def stats(self) -> Document:
        reply = await self._context.channel.send("GET_DATABASE_STATS", self._context.encoder.database_stats(self._name))
        return document(reply, "stats") or {}

    async def set_owners(self, owners: Sequence[str]) -> None:
        await self._context.channel.send(
            "SET_DATABASE_OWNERS", self._context.encoder.set_database_owners(self._name, owners)
        )

    async def run_script(self, script: str, args: Mapping[str, object] | None = None) -> ScriptResult:
        return script_result(
            await self._context.channel.send("RUN_SCRIPT", self._context.scripts.run_script(self._name, script, args))
        )

    def procedures(self) -> AsyncProcedures:
        return AsyncProcedures(self._context, self._name)

    def schedules(self) -> AsyncSchedules:
        return AsyncSchedules(self._context, self._name)

    async def list_triggers(self) -> list[TriggerInfo]:
        reply = await self._context.channel.send("LIST_TRIGGERS", self._context.scripts.list_triggers(self._name, None))
        return [trigger(row) for row in documents(reply, "triggers")]
