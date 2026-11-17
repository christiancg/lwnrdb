from __future__ import annotations

from lwnrdb.query.types import FieldFilter, FieldOperator, Filter, require_text


class FieldCondition:
    __slots__ = ("_field",)

    def __init__(self, field: str) -> None:
        self._field = require_text(field, "field")

    @property
    def field(self) -> str:
        return self._field

    def eq(self, value: object) -> Filter:
        return self._of(FieldOperator.EQUALS, value)

    def ne(self, value: object) -> Filter:
        return self._of(FieldOperator.NOT_EQUALS, value)

    def gt(self, value: object) -> Filter:
        return self._of(FieldOperator.GREATER_THAN, value)

    def gte(self, value: object) -> Filter:
        return self._of(FieldOperator.GREATER_THAN_EQUALS, value)

    def lt(self, value: object) -> Filter:
        return self._of(FieldOperator.SMALLER_THAN, value)

    def lte(self, value: object) -> Filter:
        return self._of(FieldOperator.SMALLER_THAN_EQUALS, value)

    def in_(self, *values: object) -> Filter:
        return self._of(FieldOperator.IN, tuple(values))

    def not_in(self, *values: object) -> Filter:
        return self._of(FieldOperator.NOT_IN, tuple(values))

    def contains(self, value: object) -> Filter:
        return self._of(FieldOperator.CONTAINS, value)

    def __gt__(self, value: object) -> Filter:
        return self.gt(value)

    def __ge__(self, value: object) -> Filter:
        return self.gte(value)

    def __lt__(self, value: object) -> Filter:
        return self.lt(value)

    def __le__(self, value: object) -> Filter:
        return self.lte(value)

    def __repr__(self) -> str:
        return f"field({self._field!r})"

    def _of(self, operator: FieldOperator, value: object) -> Filter:
        return FieldFilter(operator, self._field, value)
