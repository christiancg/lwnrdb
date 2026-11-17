from __future__ import annotations

from collections.abc import Sequence

from lwnrdb._internal.custom_values import format_custom
from lwnrdb._internal.values_codec import NO_ZONE, EncodeOptions, Json, JsonObject, encode_value
from lwnrdb.query.types import (
    MISSING,
    ArrayOperation,
    CastOperation,
    ConjunctionFilter,
    CountStep,
    DistanceFilter,
    DistinctStep,
    FieldFilter,
    FieldFunction,
    Filter,
    FilterStep,
    GroupByStep,
    JoinStep,
    LimitStep,
    Literal,
    MapOperation,
    MapStep,
    MidOperation,
    NearestFilter,
    PipelineStep,
    ReduceStep,
    ScriptFilter,
    ScriptOperation,
    SkipStep,
    SortStep,
    WithinFilter,
)

_CUSTOM_OPERATOR = "customOperatorName"


class PipelineEncoder:
    __slots__ = ("_options",)

    def __init__(self, options: EncodeOptions = NO_ZONE) -> None:
        self._options = options

    def steps(self, steps: Sequence[PipelineStep]) -> list[Json]:
        return [self._step(step) for step in steps]

    def value(self, value: object, path: str = "") -> Json:
        return encode_value(value, path, self._options)

    def filter(self, filter: Filter) -> JsonObject:  # noqa: A002
        if isinstance(filter, FieldFilter):
            return {
                "fieldOperatorType": filter.operator.value,
                "field": filter.field,
                "value": self.value(filter.value, filter.field),
            }
        if isinstance(filter, ConjunctionFilter):
            return {
                "conjunctionType": filter.conjunction.value,
                "operators": [self.filter(operand) for operand in filter.operands],
            }
        return self._custom_filter(filter)

    def _custom_filter(self, filter: Filter) -> JsonObject:  # noqa: A002
        if isinstance(filter, DistanceFilter):
            return {
                _CUSTOM_OPERATOR: "distance",
                "field": filter.field,
                "value": format_custom(filter.target),
                "comparator": filter.comparator.value,
                "distance": filter.metres,
            }
        if isinstance(filter, WithinFilter):
            return {
                _CUSTOM_OPERATOR: "within",
                "field": filter.field,
                "polygon": [format_custom(point) for point in filter.polygon],
            }
        if isinstance(filter, NearestFilter):
            encoded: JsonObject = {
                _CUSTOM_OPERATOR: "nearest",
                "field": filter.field,
                "value": format_custom(filter.query),
                "k": filter.k,
            }
            return {**encoded, "exact": True} if filter.is_exact else encoded
        if isinstance(filter, ScriptFilter):
            return {"script": filter.source}
        raise TypeError(f"not a filter: {filter!r}")

    def _step(self, step: PipelineStep) -> JsonObject:
        match step:
            case FilterStep(filter=condition):
                return {"type": "FILTER", "operator": self.filter(condition)}
            case MapStep(operations=operations):
                return {"type": "MAP", "operators": [self._map_operation(operation) for operation in operations]}
            case GroupByStep(field=field):
                return {"type": "GROUP_BY", "fieldName": field}
            case JoinStep():
                return {
                    "type": "JOIN",
                    "joinCollection": step.collection,
                    "localField": step.local_field,
                    "remoteField": step.remote_field,
                    "asField": step.as_field,
                }
            case CountStep():
                return {"type": "COUNT"}
            case DistinctStep(field=field):
                return {"type": "DISTINCT"} if field is None else {"type": "DISTINCT", "fieldName": field}
            case LimitStep(limit=limit):
                return {"type": "LIMIT", "limit": limit}
            case SkipStep(skip=skip):
                return {"type": "SKIP", "skip": skip}
            case SortStep(field=field, ascending=ascending):
                return {"type": "SORT", "fieldName": field, "ascending": ascending}
            case ReduceStep():
                return self._reduce(step)
        raise TypeError(f"not a pipeline step: {step!r}")

    def _reduce(self, step: ReduceStep) -> JsonObject:
        encoded: JsonObject = {"type": "REDUCE", "script": step.script}
        if step.initial_value is not MISSING:
            encoded["initialValue"] = self.value(step.initial_value, "initialValue")
        if step.result_field is not None:
            encoded["resultField"] = step.result_field
        return encoded

    def _map_operation(self, operation: MapOperation) -> JsonObject:
        encoded: JsonObject = {"fieldName": operation.field}
        if operation.operation is not None:
            encoded["operator"] = self._mid_operation(operation.operation)
        if operation.condition is not None:
            encoded["condition"] = self.filter(operation.condition)
        return encoded

    def _mid_operation(self, operation: MidOperation) -> JsonObject:
        if isinstance(operation, ArrayOperation):
            return {
                "type": operation.operator.value,
                "operands": [self._operand(operand) for operand in operation.operands],
            }
        if isinstance(operation, FieldFunction):
            return {"type": operation.function, "operand": operation.field}
        if isinstance(operation, CastOperation):
            encoded: JsonObject = {"type": "CAST", "fieldName": operation.field, "toType": operation.type.value}
            if operation.custom_type is not None:
                encoded["customTypeName"] = operation.custom_type.value
            return encoded
        if isinstance(operation, ScriptOperation):
            return {"type": "SCRIPT", "script": operation.source}
        raise TypeError(f"not a map operation: {operation!r}")

    def _operand(self, operand: object) -> Json:
        if isinstance(operand, Literal):
            return Literal.WIRE_PREFIX + operand.text
        if isinstance(operand, str):
            return operand
        return self.value(operand)
