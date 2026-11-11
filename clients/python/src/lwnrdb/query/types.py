from __future__ import annotations

import typing
from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum

from lwnrdb.values.custom_type import CustomType
from lwnrdb.values.geo_distance_comparator import GeoDistanceComparator
from lwnrdb.values.geo_point import GeoPoint
from lwnrdb.values.vector import Vector


class FieldOperator(StrEnum):
    EQUALS = "EQUALS"
    NOT_EQUALS = "NOT_EQUALS"
    GREATER_THAN = "GREATER_THAN"
    GREATER_THAN_EQUALS = "GREATER_THAN_EQUALS"
    SMALLER_THAN = "SMALLER_THAN"
    SMALLER_THAN_EQUALS = "SMALLER_THAN_EQUALS"
    IN = "IN"
    NOT_IN = "NOT_IN"
    CONTAINS = "CONTAINS"


class Conjunction(StrEnum):
    AND = "AND"
    OR = "OR"
    NOR = "NOR"
    XOR = "XOR"
    NAND = "NAND"


class Order(StrEnum):
    ASC = "ASC"
    DESC = "DESC"


class ArrayOperator(StrEnum):
    AVG = "AVG"
    SUM = "SUM"
    SUBS = "SUBS"
    MAX = "MAX"
    MIN = "MIN"
    MULTIPLY = "MULTIPLY"
    DIVIDE = "DIVIDE"
    POW = "POW"
    ROOT = "ROOT"
    CONCAT = "CONCAT"


class CastType(StrEnum):
    NUMBER = "NUMBER"
    STRING = "STRING"
    BOOLEAN = "BOOLEAN"
    JSON_CUSTOM = "JSON_CUSTOM"


def require_text(value: object, name: str) -> str:
    if not isinstance(value, str):
        raise TypeError(f"{name} must be a string")
    return value


def require_filter(value: object, name: str) -> Filter:
    if not isinstance(value, Filter):
        hint = "; compare with field(...).eq(value), since == is not a filter" if isinstance(value, bool) else ""
        raise TypeError(f"{name} needs a Filter, got {type(value).__name__}{hint}")
    return value


def conjunction(kind: Conjunction, operands: Sequence[Filter]) -> ConjunctionFilter:
    if not operands:
        raise ValueError(f"{kind} needs at least one operand")
    return ConjunctionFilter(kind, tuple(require_filter(operand, f"an {kind} operand") for operand in operands))


def _flattened(kind: Conjunction, left: Filter) -> tuple[Filter, ...]:
    if isinstance(left, ConjunctionFilter) and left.conjunction is kind:
        return left.operands
    return (left,)


class Filter:
    __slots__ = ()

    def __and__(self, other: Filter) -> Filter:
        return conjunction(Conjunction.AND, (*_flattened(Conjunction.AND, self), other))

    def __or__(self, other: Filter) -> Filter:
        return conjunction(Conjunction.OR, (*_flattened(Conjunction.OR, self), other))

    def __xor__(self, other: Filter) -> Filter:
        return conjunction(Conjunction.XOR, (self, other))

    def __invert__(self) -> Filter:
        return conjunction(Conjunction.NOR, (self,))


@dataclass(frozen=True, slots=True)
class FieldFilter(Filter):
    operator: FieldOperator
    field: str
    value: object


@dataclass(frozen=True, slots=True)
class ConjunctionFilter(Filter):
    conjunction: Conjunction
    operands: tuple[Filter, ...]


@dataclass(frozen=True, slots=True)
class DistanceFilter(Filter):
    field: str
    target: GeoPoint
    comparator: GeoDistanceComparator
    metres: float


@dataclass(frozen=True, slots=True)
class WithinFilter(Filter):
    field: str
    polygon: tuple[GeoPoint, ...]


@dataclass(frozen=True, slots=True)
class NearestFilter(Filter):
    field: str
    query: Vector
    k: int
    is_exact: bool = False

    def __post_init__(self) -> None:
        require_text(self.field, "field")
        if type(self.k) is not int or self.k <= 0:
            raise ValueError(f"nearest needs an integer k > 0, got {self.k!r}")
        if not isinstance(self.query, Vector):
            raise TypeError("nearest needs a Vector query")

    def exact(self) -> NearestFilter:
        return NearestFilter(self.field, self.query, self.k, is_exact=True)


@dataclass(frozen=True, slots=True)
class ScriptFilter(Filter):
    source: str


@dataclass(frozen=True, slots=True)
class Literal:
    text: str

    WIRE_PREFIX: typing.ClassVar[str] = "-"


@dataclass(frozen=True, slots=True)
class ArrayOperation:
    operator: ArrayOperator
    operands: tuple[object, ...]


@dataclass(frozen=True, slots=True)
class FieldFunction:
    function: typing.Literal["ABS", "SIZE"]
    field: str


@dataclass(frozen=True, slots=True)
class CastOperation:
    field: str
    type: CastType
    custom_type: CustomType | None


@dataclass(frozen=True, slots=True)
class ScriptOperation:
    source: str


MidOperation: typing.TypeAlias = ArrayOperation | FieldFunction | CastOperation | ScriptOperation


@dataclass(frozen=True, slots=True)
class MapOperation:
    field: str
    operation: MidOperation | None = None
    condition: Filter | None = None

    def __post_init__(self) -> None:
        require_text(self.field, "field")
        if self.condition is not None:
            require_filter(self.condition, "a map condition")

    def when(self, condition: Filter) -> MapOperation:
        return MapOperation(self.field, self.operation, require_filter(condition, "when"))

    @property
    def removes(self) -> bool:
        return self.operation is None


class Missing:
    __slots__ = ()

    def __repr__(self) -> str:
        return "MISSING"


MISSING = Missing()


@dataclass(frozen=True, slots=True)
class FilterStep:
    filter: Filter


@dataclass(frozen=True, slots=True)
class MapStep:
    operations: tuple[MapOperation, ...]


@dataclass(frozen=True, slots=True)
class GroupByStep:
    field: str


@dataclass(frozen=True, slots=True)
class JoinStep:
    collection: str
    local_field: str
    remote_field: str
    as_field: str


@dataclass(frozen=True, slots=True)
class CountStep:
    pass


@dataclass(frozen=True, slots=True)
class DistinctStep:
    field: str | None


@dataclass(frozen=True, slots=True)
class LimitStep:
    limit: int


@dataclass(frozen=True, slots=True)
class SkipStep:
    skip: int


@dataclass(frozen=True, slots=True)
class SortStep:
    field: str
    ascending: bool


@dataclass(frozen=True, slots=True)
class ReduceStep:
    script: str
    initial_value: object
    result_field: str | None


PipelineStep: typing.TypeAlias = (
    FilterStep
    | MapStep
    | GroupByStep
    | JoinStep
    | CountStep
    | DistinctStep
    | LimitStep
    | SkipStep
    | SortStep
    | ReduceStep
)
