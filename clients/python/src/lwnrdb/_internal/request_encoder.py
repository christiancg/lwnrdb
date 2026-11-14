from __future__ import annotations

from collections.abc import Sequence

from lwnrdb._internal.pipeline_encoder import PipelineEncoder
from lwnrdb._internal.values_codec import NO_ZONE, EncodeOptions, Json, JsonObject, encode_document
from lwnrdb.query.types import Filter, PipelineStep


def request(kind: str, database: str | None = None, collection: str | None = None) -> JsonObject:
    frame: JsonObject = {"type": kind}
    if database is not None:
        frame["databaseName"] = database
    if collection is not None:
        frame["collectionName"] = collection
    return frame


def _dirty(frame: JsonObject, dirty_read: bool) -> JsonObject:
    return {**frame, "dirtyRead": True} if dirty_read else frame


class RequestEncoder:
    __slots__ = ("_options", "pipeline")

    def __init__(self, options: EncodeOptions = NO_ZONE) -> None:
        self._options = options
        self.pipeline = PipelineEncoder(options)

    def document(self, value: object) -> JsonObject:
        return encode_document(value, self._options)

    def authenticate(self, username: str, password: str) -> JsonObject:
        return {**request("AUTHENTICATE"), "username": username, "password": password}

    def save(self, database: str, collection: str, document: JsonObject) -> JsonObject:
        return {**request("SAVE", database, collection), "object": document}

    def bulk_save(self, database: str, collection: str, documents: list[JsonObject]) -> JsonObject:
        objects: list[Json] = list(documents)
        return {**request("BULK_SAVE", database, collection), "objects": objects}

    def find_by_id(self, database: str, collection: str, document_id: str, dirty_read: bool) -> JsonObject:
        return _dirty({**request("FIND_BY_ID", database, collection), "_id": document_id}, dirty_read)

    def delete(self, database: str, collection: str, document_id: str) -> JsonObject:
        return {**request("DELETE", database, collection), "_id": document_id}

    def delete_where(self, database: str, collection: str, condition: Filter) -> JsonObject:
        return {**request("DELETE", database, collection), "condition": self.pipeline.filter(condition)}

    def aggregate(
        self,
        database: str,
        collection: str,
        steps: Sequence[PipelineStep],
        dirty_read: bool,
        analyze: bool,
    ) -> JsonObject:
        frame = {**request("AGGREGATE", database, collection), "aggregationSteps": self.pipeline.steps(steps)}
        return _dirty({**frame, "analyze": True} if analyze else frame, dirty_read)

    def listen(self, database: str, collection: str, steps: Sequence[PipelineStep]) -> JsonObject:
        return {**request("LISTEN", database, collection), "aggregationSteps": self.pipeline.steps(steps)}

    def stop_listen(self, listen_id: str) -> JsonObject:
        return {**request("STOP_LISTEN"), "listenId": listen_id}

    def list_databases(self) -> JsonObject:
        return request("LIST_DATABASES")

    def create_database(self, database: str) -> JsonObject:
        return request("CREATE_DATABASE", database)

    def drop_database(self, database: str) -> JsonObject:
        return request("DROP_DATABASE", database)

    def list_collections(self, database: str) -> JsonObject:
        return request("LIST_COLLECTIONS", database)

    def create_collection(self, database: str, collection: str) -> JsonObject:
        return request("CREATE_COLLECTION", database, collection)

    def drop_collection(self, database: str, collection: str) -> JsonObject:
        return request("DROP_COLLECTION", database, collection)

    def create_index(self, database: str, collection: str, fields: Sequence[str]) -> JsonObject:
        frame = request("CREATE_INDEX", database, collection)
        if len(fields) == 1:
            return {**frame, "fieldName": fields[0]}
        names: list[Json] = list(fields)
        return {**frame, "fieldNames": names}

    def drop_index(self, database: str, collection: str, field: str) -> JsonObject:
        return {**request("DROP_INDEX", database, collection), "fieldName": field}

    def reindex(self, database: str, collection: str, fields: Sequence[str]) -> JsonObject:
        frame = request("REINDEX", database, collection)
        names: list[Json] = list(fields)
        return {**frame, "fieldNames": names} if names else frame

    def reindex_primary_key(self, database: str, collection: str) -> JsonObject:
        return {**request("REINDEX", database, collection), "primaryKey": True}

    def save_schema(self, database: str, collection: str, schema: JsonObject) -> JsonObject:
        return {**request("SAVE_SCHEMA", database, collection), "schema": schema}

    def get_schema(self, database: str, collection: str) -> JsonObject:
        return request("GET_SCHEMA", database, collection)

    def delete_schema(self, database: str, collection: str) -> JsonObject:
        return request("DELETE_SCHEMA", database, collection)

    def start_transaction(self) -> JsonObject:
        return request("START_TRANSACTION")

    def commit_transaction(self) -> JsonObject:
        return request("COMMIT_TRANSACTION")

    def rollback_transaction(self) -> JsonObject:
        return request("ROLLBACK_TRANSACTION")

    def server_info(self) -> JsonObject:
        return request("SERVER_INFO")

    def database_stats(self, database: str | None = None) -> JsonObject:
        return request("GET_DATABASE_STATS", database)

    def close_connection(self) -> JsonObject:
        return request("CLOSE_CONNECTION")

    def set_database_owners(self, database: str, owners: Sequence[str]) -> JsonObject:
        names: list[Json] = list(owners)
        return {**request("SET_DATABASE_OWNERS", database), "owners": names}
