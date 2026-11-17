from __future__ import annotations

from collections.abc import Sequence

from lwnrdb.query.types import (
    ArrayOperation,
    ArrayOperator,
    CastOperation,
    CastType,
    FieldFunction,
    Literal,
    MidOperation,
    ScriptOperation,
    require_text,
)
from lwnrdb.values.custom_type import CustomType


def _array(operator: ArrayOperator, operands: Sequence[object]) -> MidOperation:
    if not operands:
        raise ValueError(f"{operator} needs at least one operand")
    return ArrayOperation(operator, tuple(operands))


class MidOps:
    __slots__ = ()

    @staticmethod
    def avg(*operands: object) -> MidOperation:
        return _array(ArrayOperator.AVG, operands)

    @staticmethod
    def sum(*operands: object) -> MidOperation:
        return _array(ArrayOperator.SUM, operands)

    @staticmethod
    def subs(*operands: object) -> MidOperation:
        return _array(ArrayOperator.SUBS, operands)

    @staticmethod
    def max(*operands: object) -> MidOperation:
        return _array(ArrayOperator.MAX, operands)

    @staticmethod
    def min(*operands: object) -> MidOperation:
        return _array(ArrayOperator.MIN, operands)

    @staticmethod
    def multiply(*operands: object) -> MidOperation:
        return _array(ArrayOperator.MULTIPLY, operands)

    @staticmethod
    def divide(*operands: object) -> MidOperation:
        return _array(ArrayOperator.DIVIDE, operands)

    @staticmethod
    def pow(*operands: object) -> MidOperation:
        return _array(ArrayOperator.POW, operands)

    @staticmethod
    def root(*operands: object) -> MidOperation:
        return _array(ArrayOperator.ROOT, operands)

    @staticmethod
    def concat(*operands: object) -> MidOperation:
        return _array(ArrayOperator.CONCAT, operands)

    @staticmethod
    def literal(text: str) -> Literal:
        return Literal(require_text(text, "text"))

    @staticmethod
    def abs(field: str) -> MidOperation:
        return FieldFunction("ABS", require_text(field, "field"))

    @staticmethod
    def size(field: str) -> MidOperation:
        return FieldFunction("SIZE", require_text(field, "field"))

    @staticmethod
    def cast(field: str, to: CastType | CustomType) -> MidOperation:
        if isinstance(to, CastType) and to is not CastType.JSON_CUSTOM:
            return CastOperation(require_text(field, "field"), to, None)
        if isinstance(to, CustomType):
            return CastOperation(require_text(field, "field"), CastType.JSON_CUSTOM, to)
        raise TypeError(f"cast takes a CastType other than JSON_CUSTOM, or a CustomType such as CustomType.GEO: {to!r}")

    @staticmethod
    def script(source: str) -> MidOperation:
        return ScriptOperation(require_text(source, "source"))
