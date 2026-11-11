from __future__ import annotations

import math

from lwnrdb.query.field_condition import FieldCondition
from lwnrdb.query.types import (
    Conjunction,
    DistanceFilter,
    Filter,
    NearestFilter,
    ScriptFilter,
    WithinFilter,
    conjunction,
    require_text,
)
from lwnrdb.values.geo_distance_comparator import GeoDistanceComparator
from lwnrdb.values.geo_point import GeoPoint
from lwnrdb.values.vector import Vector

_MIN_POLYGON_POINTS = 3


def _require_point(point: object, name: str) -> GeoPoint:
    if not isinstance(point, GeoPoint):
        raise TypeError(f"{name} must be a GeoPoint")
    return point


def field(name: str) -> FieldCondition:
    return FieldCondition(name)


def and_(*operands: Filter) -> Filter:
    return conjunction(Conjunction.AND, operands)


def or_(*operands: Filter) -> Filter:
    return conjunction(Conjunction.OR, operands)


def nor(*operands: Filter) -> Filter:
    return conjunction(Conjunction.NOR, operands)


def xor(*operands: Filter) -> Filter:
    return conjunction(Conjunction.XOR, operands)


def nand(*operands: Filter) -> Filter:
    return conjunction(Conjunction.NAND, operands)


def distance(name: str, target: GeoPoint, comparator: GeoDistanceComparator, metres: float) -> Filter:
    if isinstance(metres, bool) or not isinstance(metres, int | float) or not math.isfinite(metres) or metres < 0:
        raise ValueError(f"distance must be a finite, non-negative number of metres: {metres!r}")
    return DistanceFilter(
        require_text(name, "field"),
        _require_point(target, "target"),
        GeoDistanceComparator(comparator),
        metres,
    )


def within(name: str, *polygon: GeoPoint) -> Filter:
    if len(polygon) < _MIN_POLYGON_POINTS:
        raise ValueError(f"a within polygon needs at least 3 points, got {len(polygon)}")
    return WithinFilter(
        require_text(name, "field"),
        tuple(_require_point(point, "a polygon point") for point in polygon),
    )


def nearest(name: str, query: Vector, k: int) -> NearestFilter:
    return NearestFilter(name, query, k)


def script(source: str) -> Filter:
    return ScriptFilter(require_text(source, "source"))
