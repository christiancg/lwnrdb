from __future__ import annotations

from collections.abc import Mapping

from lwnrdb._internal.client_context import ClientContext
from lwnrdb._internal.response_decoder import document, strings
from lwnrdb.document import Document
from lwnrdb.errors import NotFoundError


class AsyncSchema:
    def __init__(self, context: ClientContext, database: str, collection: str) -> None:
        self._context = context
        self._database = database
        self._collection = collection

    async def save(self, schema: Mapping[str, object]) -> tuple[str, ...]:
        encoder = self._context.encoder
        reply = await self._context.channel.send(
            "SAVE_SCHEMA", encoder.save_schema(self._database, self._collection, encoder.document(schema))
        )
        return strings(reply.get("warnings"))

    async def get(self) -> Document | None:
        try:
            reply = await self._context.channel.send(
                "GET_SCHEMA", self._context.encoder.get_schema(self._database, self._collection)
            )
        except NotFoundError:
            return None
        return document(reply, "schema")

    async def delete(self) -> None:
        await self._context.channel.send(
            "DELETE_SCHEMA", self._context.encoder.delete_schema(self._database, self._collection)
        )
