from __future__ import annotations

from lwnrdb._internal.client_context import ClientContext
from lwnrdb._internal.response_decoder import number_of, strings
from lwnrdb._internal.values_codec import JsonObject
from lwnrdb.results import ReindexResult


def _result(reply: JsonObject) -> ReindexResult:
    return ReindexResult(
        strings(reply.get("rebuiltFields")),
        number_of(reply.get("primaryKeyEntries")),
        number_of(reply.get("unversionedEntries")),
    )


class AsyncIndexes:
    def __init__(self, context: ClientContext, database: str, collection: str) -> None:
        self._context = context
        self._database = database
        self._collection = collection

    async def create(self, field: str, *more_fields: str) -> None:
        await self._context.channel.send(
            "CREATE_INDEX",
            self._context.encoder.create_index(self._database, self._collection, (field, *more_fields)),
        )

    async def drop(self, field: str) -> None:
        await self._context.channel.send(
            "DROP_INDEX", self._context.encoder.drop_index(self._database, self._collection, field)
        )

    async def reindex(self, *fields: str) -> ReindexResult:
        return _result(
            await self._context.channel.send(
                "REINDEX", self._context.encoder.reindex(self._database, self._collection, fields)
            )
        )

    async def reindex_primary_key(self) -> ReindexResult:
        return _result(
            await self._context.channel.send(
                "REINDEX", self._context.encoder.reindex_primary_key(self._database, self._collection)
            )
        )
