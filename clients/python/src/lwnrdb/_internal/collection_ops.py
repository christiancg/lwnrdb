from __future__ import annotations

from collections.abc import Awaitable, Callable, Sequence
from typing import Generic, Protocol, TypeVar

from lwnrdb._internal.client_context import ClientContext
from lwnrdb._internal.names import collection_name, database_name, document_id
from lwnrdb._internal.response_decoder import boolean_of, document, documents, strings, text_of
from lwnrdb._internal.values_codec import JsonObject
from lwnrdb.document import Document, DocumentCodec
from lwnrdb.error_codes import ErrorCode
from lwnrdb.errors import NotFoundError
from lwnrdb.query.types import PipelineStep
from lwnrdb.results import AnalyzeResult, BulkSaveResult, SaveResult

T = TypeVar("T")
R = TypeVar("R")
R_co = TypeVar("R_co", covariant=True)
V = TypeVar("V")


class QueryTarget(Protocol[R_co]):
    def decode(self, document: Document) -> R_co: ...

    async def aggregate(self, steps: Sequence[PipelineStep], dirty_read: bool) -> list[Document]: ...

    async def analyze(self, steps: Sequence[PipelineStep], dirty_read: bool) -> AnalyzeResult: ...


class DocumentRows:
    __slots__ = ()

    def encode(self, value: Document) -> Document:
        return value

    def decode(self, document: Document) -> Document:
        return document


DOCUMENT_ROWS = DocumentRows()


async def empty_on(code: str, empty: V, read: Callable[[], Awaitable[V]]) -> V:
    try:
        return await read()
    except NotFoundError as error:
        if error.has_code(code):
            return empty
        raise


class CollectionOps(Generic[T, R]):
    def __init__(self, context: ClientContext, database: str, collection: str, codec: DocumentCodec[T, R]) -> None:
        self.context = context
        self.database = database_name(database)
        self.collection = collection_name(collection)
        self.codec = codec

    def decode(self, document: Document) -> R:
        return self.codec.decode(document)

    async def save(self, value: T) -> SaveResult:
        reply = await self.context.channel.send(
            "SAVE", self.context.encoder.save(self.database, self.collection, self._encode(value))
        )
        return SaveResult(text_of(reply.get("_id")), boolean_of(reply.get("inserted"), fallback=False))

    async def save_all(self, values: Sequence[T]) -> BulkSaveResult:
        encoded = [self._encode(value) for value in values]
        reply = await self.context.channel.send(
            "BULK_SAVE", self.context.encoder.bulk_save(self.database, self.collection, encoded)
        )
        return BulkSaveResult(strings(reply.get("inserted")), strings(reply.get("updated")))

    async def find_by_id(self, value: str, dirty_read: bool) -> R | None:
        request = self.context.encoder.find_by_id(self.database, self.collection, document_id(value), dirty_read)

        async def read() -> R | None:
            found = document(await self.context.channel.send("FIND_BY_ID", request), "object")
            return None if found is None else self.codec.decode(found)

        return await empty_on(ErrorCode.ENTRY_NOT_FOUND, None, read)

    async def delete(self, value: str) -> None:
        await self.context.channel.send(
            "DELETE", self.context.encoder.delete(self.database, self.collection, document_id(value))
        )

    async def aggregate(self, steps: Sequence[PipelineStep], dirty_read: bool) -> list[Document]:
        request = self.context.encoder.aggregate(self.database, self.collection, steps, dirty_read, analyze=False)

        async def read() -> list[Document]:
            return documents(await self.context.channel.send("AGGREGATE", request), "results")

        return await empty_on(ErrorCode.NO_RESULTS, [], read)

    async def analyze(self, steps: Sequence[PipelineStep], dirty_read: bool) -> AnalyzeResult:
        reply = await self.context.channel.send(
            "AGGREGATE",
            self.context.encoder.aggregate(self.database, self.collection, steps, dirty_read, analyze=True),
        )
        return AnalyzeResult(tuple(documents(reply, "results")), document(reply, "analyzeResult") or {})

    def _encode(self, value: T) -> JsonObject:
        encoded = self.context.encoder.document(self.codec.encode(value))
        identifier = encoded.get("_id")
        if identifier is not None:
            document_id(identifier)
        return encoded
