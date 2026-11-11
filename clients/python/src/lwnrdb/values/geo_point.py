from __future__ import annotations

import math
from dataclasses import dataclass

_MAX_LATITUDE = 90.0
_MAX_LONGITUDE = 180.0


def _coordinate(label: str, value: object, limit: float) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise TypeError(f"{label} must be a number, was {value!r}")
    number = float(value)
    if not math.isfinite(number) or abs(number) > limit:
        raise ValueError(f"{label} must be between {-limit:g} and {limit:g}, was {value!r}")
    return number


@dataclass(frozen=True, slots=True)
class GeoPoint:
    lat: float
    lng: float

    def __post_init__(self) -> None:
        object.__setattr__(self, "lat", _coordinate("latitude", self.lat, _MAX_LATITUDE))
        object.__setattr__(self, "lng", _coordinate("longitude", self.lng, _MAX_LONGITUDE))

    @classmethod
    def of(cls, lat: float, lng: float) -> GeoPoint:
        return cls(lat, lng)
