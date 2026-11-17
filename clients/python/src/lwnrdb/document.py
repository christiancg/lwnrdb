from __future__ import annotations

from datetime import datetime, time
from typing import Protocol, TypeAlias, TypeVar

from lwnrdb.values.geo_point import GeoPoint
from lwnrdb.values.local_date_time import LocalDateTime
from lwnrdb.values.local_time import LocalTime
from lwnrdb.values.vector import Vector

Value: TypeAlias = (
    "str | int | float | bool | datetime | time | LocalDateTime | LocalTime | GeoPoint | Vector "
    "| list[Value] | dict[str, Value] | None"
)
Document: TypeAlias = "dict[str, Value]"

T_contra = TypeVar("T_contra", contravariant=True)
R_co = TypeVar("R_co", covariant=True)


class DocumentCodec(Protocol[T_contra, R_co]):
    def encode(self, value: T_contra) -> Document: ...

    def decode(self, document: Document) -> R_co: ...


class AsVector:
    pass
