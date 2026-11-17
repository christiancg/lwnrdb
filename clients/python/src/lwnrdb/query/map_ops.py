from __future__ import annotations

from lwnrdb.query.types import ArrayOperation, CastOperation, FieldFunction, MapOperation, MidOperation, ScriptOperation

_MID_OPERATIONS = (ArrayOperation, FieldFunction, CastOperation, ScriptOperation)


class MapOps:
    __slots__ = ()

    @staticmethod
    def set(field: str, operation: MidOperation) -> MapOperation:
        if not isinstance(operation, _MID_OPERATIONS):
            raise TypeError("MapOps.set takes an operation from MidOps")
        return MapOperation(field, operation)

    @staticmethod
    def remove(field: str) -> MapOperation:
        return MapOperation(field)
