from __future__ import annotations

import math
from collections.abc import Iterable


def _component(index: int, value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise TypeError(f"vector component {index} must be a number, was {value!r}")
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"vector component {index} is not finite: {value!r}")
    return number


class Vector:
    __slots__ = ("_components",)

    def __init__(self, components: Iterable[float]) -> None:
        checked = tuple(_component(index, value) for index, value in enumerate(components))
        if not checked:
            raise ValueError("a vector needs at least one component")
        self._components = checked

    @classmethod
    def of(cls, *components: float) -> Vector:
        return cls(components)

    @classmethod
    def from_iterable(cls, components: Iterable[float]) -> Vector:
        return cls(components)

    @property
    def dimensions(self) -> int:
        return len(self._components)

    def component(self, index: int) -> float:
        if not 0 <= index < len(self._components):
            raise IndexError(f"component {index} is outside a vector of {self.dimensions} dimensions")
        return self._components[index]

    def to_list(self) -> list[float]:
        return list(self._components)

    def __eq__(self, other: object) -> bool:
        return isinstance(other, Vector) and other._components == self._components

    def __hash__(self) -> int:
        return hash(self._components)

    def __repr__(self) -> str:
        return f"Vector({list(self._components)!r})"
