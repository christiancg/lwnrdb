from __future__ import annotations

from collections.abc import Mapping

from lwnrdb._internal.client_context import ClientContext
from lwnrdb._internal.descriptors import trigger
from lwnrdb._internal.response_decoder import boolean_of, document, documents, number_of, strings, text_of
from lwnrdb.scripting_types import TriggerDefinition, TriggerEvent, TriggerInfo, TriggerSaved, TriggerTestResult


class AsyncTriggers:
    def __init__(self, context: ClientContext, database: str, collection: str) -> None:
        self._context = context
        self._database = database
        self._collection = collection

    async def save(self, definition: TriggerDefinition) -> TriggerSaved:
        reply = await self._context.channel.send(
            "SAVE_TRIGGER", self._context.scripts.save_trigger(self._database, self._collection, definition)
        )
        return TriggerSaved(number_of(reply.get("version")) or 0, text_of(reply.get("definer")) or "")

    async def delete(self, name: str) -> None:
        await self._context.channel.send(
            "DELETE_TRIGGER", self._context.scripts.delete_trigger(self._database, self._collection, name)
        )

    async def list(self) -> list[TriggerInfo]:
        reply = await self._context.channel.send(
            "LIST_TRIGGERS", self._context.scripts.list_triggers(self._database, self._collection)
        )
        return [trigger(row) for row in documents(reply, "triggers")]

    async def test(self, name: str, event: TriggerEvent, sample: Mapping[str, object]) -> TriggerTestResult:
        reply = await self._context.channel.send(
            "TEST_TRIGGER",
            self._context.scripts.test_trigger(self._database, self._collection, name, event, sample),
        )
        return TriggerTestResult(
            text_of(reply.get("decision")) or "",
            document(reply, "document"),
            strings(reply.get("logs")),
            boolean_of(reply.get("logsTruncated"), fallback=False),
        )
