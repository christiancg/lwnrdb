from __future__ import annotations

from typing import Generic, Self, TypeVar

from lwnrdb.query.types import (
    MISSING,
    DistinctStep,
    Filter,
    FilterStep,
    GroupByStep,
    JoinStep,
    LimitStep,
    MapOperation,
    MapStep,
    Missing,
    Order,
    PipelineStep,
    ReduceStep,
    SkipStep,
    SortStep,
    require_filter,
    require_text,
)

TargetT = TypeVar("TargetT")


def require_integer(name: str, value: object, minimum: int) -> int:
    if type(value) is not int or value < minimum:
        raise ValueError(f"{name} must be an integer of at least {minimum}: {value!r}")
    return value


class QueryBuilder(Generic[TargetT]):
    __slots__ = ("_dirty", "_steps", "_target")

    def __init__(self, target: TargetT, steps: tuple[PipelineStep, ...] = (), dirty: bool = False) -> None:
        self._target = target
        self._steps = steps
        self._dirty = dirty

    @property
    def steps(self) -> tuple[PipelineStep, ...]:
        return self._steps

    @property
    def is_dirty_read(self) -> bool:
        return self._dirty

    def filter(self, filter: Filter) -> Self:  # noqa: A002
        return self._then(FilterStep(require_filter(filter, "filter")))

    def map(self, *operations: MapOperation) -> Self:
        if not operations:
            raise ValueError("map needs at least one operation")
        if not all(isinstance(operation, MapOperation) for operation in operations):
            raise TypeError("map takes MapOperations from MapOps.set and MapOps.remove")
        return self._then(MapStep(tuple(operations)))

    def group_by(self, field: str) -> Self:
        return self._then(GroupByStep(require_text(field, "field")))

    def join(self, collection: str, local_field: str, remote_field: str, as_field: str) -> Self:
        return self._then(
            JoinStep(
                require_text(collection, "collection"),
                require_text(local_field, "local_field"),
                require_text(remote_field, "remote_field"),
                require_text(as_field, "as_field"),
            )
        )

    def distinct(self, field: str | None = None) -> Self:
        return self._then(DistinctStep(None if field is None else require_text(field, "field")))

    def skip(self, skip: int) -> Self:
        return self._then(SkipStep(require_integer("skip", skip, 0)))

    def limit(self, limit: int) -> Self:
        return self._then(LimitStep(require_integer("limit", limit, 1)))

    def sort(self, field: str, order: Order = Order.ASC) -> Self:
        if order not in (Order.ASC, Order.DESC):
            raise ValueError(f"order must be Order.ASC or Order.DESC: {order!r}")
        return self._then(SortStep(require_text(field, "field"), order == Order.ASC))

    def reduce(self, script: str, initial_value: object = MISSING, result_field: str | None = None) -> Self:
        return self._then(
            ReduceStep(
                require_text(script, "script"),
                initial_value,
                None if result_field is None else require_text(result_field, "result_field"),
            )
        )

    def dirty_read(self) -> Self:
        return type(self)(self._target, self._steps, dirty=True)

    def _then(self, step: PipelineStep) -> Self:
        return type(self)(self._target, (*self._steps, step), self._dirty)


__all__ = ["MISSING", "Missing", "QueryBuilder", "require_integer"]
