from __future__ import annotations

from collections.abc import Callable
from datetime import datetime, time

from lwnrdb import (
    CastType,
    CustomType,
    FieldCondition,
    Filter,
    GeoDistanceComparator,
    GeoPoint,
    LocalDateTime,
    LocalTime,
    MapOps,
    MidOperation,
    MidOps,
    Order,
    Vector,
    and_,
    distance,
    field,
    nand,
    nearest,
    nor,
    or_,
    script,
    within,
)
from tests.fixtures.recorder import FixtureCall
from tests.fixtures.wire_admin_cases import add_admin_cases

REDUCE_SUM = "export default (acc, doc) => acc + doc.total;"
ACTIVE = field("status").eq("active")

COMPARISONS: list[tuple[str, Callable[[FieldCondition, object], Filter]]] = [
    ("equals", lambda condition, value: condition.eq(value)),
    ("not_equals", lambda condition, value: condition.ne(value)),
    ("greater_than", lambda condition, value: condition > value),
    ("greater_than_equals", lambda condition, value: condition >= value),
    ("smaller_than", lambda condition, value: condition.lt(value)),
    ("smaller_than_equals", lambda condition, value: condition.lte(value)),
]


def filtered(condition: Filter) -> FixtureCall:
    return lambda r: r.orders.query().filter(condition).to_list()


def _data(cases: dict[str, FixtureCall]) -> None:
    cases["data.save_with_id"] = lambda r: r.orders.save({"_id": "o-1", "status": "active", "total": 120})
    cases["data.save_without_id"] = lambda r: r.orders.save({"status": "new"})
    cases["data.save_nested"] = lambda r: r.orders.save(
        {"_id": "o-2", "customer": {"name": "Ana", "tags": ["vip", None, True, 1.5]}}
    )
    cases["data.bulk_save"] = lambda r: r.orders.save_all([{"_id": "o-1", "total": 1}, {"total": 2}])
    cases["data.find_by_id"] = lambda r: r.orders.find_by_id("o-1")
    cases["data.find_by_id_dirty"] = lambda r: r.orders.find_by_id("o-1", dirty_read=True)
    cases["data.delete"] = lambda r: r.orders.delete("o-1")
    cases["data.delete_where"] = lambda r: r.orders.delete_where(
        and_(field("status").eq("archived"), field("total").lt(100))
    )


def _aggregate(cases: dict[str, FixtureCall]) -> None:
    cases["aggregate.all"] = lambda r: r.orders.query().to_list()
    cases["aggregate.filter_sort_limit"] = lambda r: (
        r.orders.query().filter(ACTIVE).sort("createdAt", Order.DESC).limit(10).to_list()
    )
    cases["aggregate.skip_limit"] = lambda r: r.orders.query().skip(20).limit(10).to_list()
    cases["aggregate.sort_ascending"] = lambda r: r.orders.query().sort("name").to_list()
    cases["aggregate.count"] = lambda r: r.orders.query().filter(ACTIVE).count()
    cases["aggregate.group_by"] = lambda r: r.orders.query().group_by("status").to_list()
    cases["aggregate.distinct_field"] = lambda r: r.orders.query().distinct("status").to_list()
    cases["aggregate.distinct_documents"] = lambda r: r.orders.query().distinct().to_list()
    cases["aggregate.join"] = lambda r: r.orders.query().join("customers", "customerId", "_id", "customer").to_list()
    cases["aggregate.reduce"] = lambda r: r.orders.query().reduce(REDUCE_SUM, 0, "sum").to_list()
    cases["aggregate.reduce_defaults"] = lambda r: (
        r.orders.query().reduce("export default (acc, doc) => acc;").to_list()
    )
    cases["aggregate.dirty_read"] = lambda r: r.orders.query().filter(ACTIVE).dirty_read().to_list()
    cases["aggregate.analyze"] = lambda r: r.orders.query().filter(ACTIVE).analyze()
    cases["aggregate.filter_in"] = filtered(field("status").in_("active", "new"))
    cases["aggregate.filter_not_in"] = filtered(field("status").not_in("closed"))
    cases["aggregate.filter_contains"] = filtered(field("name").contains("an"))
    cases["aggregate.filter_null"] = filtered(field("deletedAt").eq(None))
    cases["aggregate.filter_object_value"] = filtered(field("address").eq({"city": "Lima"}))
    cases["aggregate.filter_script"] = filtered(script("export default (doc) => doc.total > 100;"))
    for name, comparison in COMPARISONS:
        cases[f"aggregate.filter_{name}"] = filtered(comparison(field("total"), 100))
    cases["aggregate.conjunction_and"] = filtered(ACTIVE & (field("total") > 100))
    cases["aggregate.conjunction_or"] = filtered(ACTIVE | (field("total") > 100))
    cases["aggregate.conjunction_xor"] = filtered(ACTIVE ^ (field("total") > 100))
    cases["aggregate.conjunction_nor"] = filtered(nor(ACTIVE, field("total").gt(100)))
    cases["aggregate.conjunction_nand"] = filtered(nand(ACTIVE, field("total").gt(100)))
    cases["aggregate.conjunction_nested"] = filtered(
        and_(ACTIVE, or_(field("total") < 10, script("export default (doc) => doc.vip;")))
    )
    _map_cases(cases)


def _mapped(operation_field: str, operation: MidOperation) -> FixtureCall:
    return lambda r: r.orders.query().map(MapOps.set(operation_field, operation)).to_list()


def _map_cases(cases: dict[str, FixtureCall]) -> None:
    arithmetic = {
        "avg": MidOps.avg,
        "sum": MidOps.sum,
        "subs": MidOps.subs,
        "max": MidOps.max,
        "min": MidOps.min,
        "multiply": MidOps.multiply,
        "divide": MidOps.divide,
        "pow": MidOps.pow,
        "root": MidOps.root,
    }
    for name, operator in arithmetic.items():
        cases[f"aggregate.map_{name}"] = _mapped("result", operator("price", "qty", 2))
    cases["aggregate.map_concat"] = _mapped("label", MidOps.concat("name", MidOps.literal(" / "), "status"))
    cases["aggregate.map_abs"] = _mapped("delta", MidOps.abs("change"))
    cases["aggregate.map_size"] = _mapped("count", MidOps.size("items"))
    cases["aggregate.map_script"] = _mapped("total", MidOps.script("export default (doc) => doc.price * doc.qty;"))
    cases["aggregate.map_remove"] = lambda r: r.orders.query().map(MapOps.remove("secret")).to_list()
    cases["aggregate.map_conditional"] = lambda r: (
        r.orders.query()
        .map(
            MapOps.set("flag", MidOps.sum(1)).when(ACTIVE),
            MapOps.remove("draft").when(field("status").eq("closed")),
        )
        .to_list()
    )
    for cast_type in (CastType.NUMBER, CastType.STRING, CastType.BOOLEAN):
        cases[f"aggregate.map_cast_{cast_type.lower()}"] = _mapped("converted", MidOps.cast("raw", cast_type))


def _custom_types(cases: dict[str, FixtureCall]) -> None:
    new_york = GeoPoint(40.71, -74.0)
    embedding = Vector.of(0.12, 0.44, 0.91)
    cases["custom_types.save_all_types"] = lambda r: r.orders.save(
        {
            "_id": "p-1",
            "openedAt": datetime(2024, 1, 1, 10, 0),
            "opensAt": time(9, 30),
            "location": new_york,
            "embedding": embedding,
            "stops": [GeoPoint(1.0, 2.0), GeoPoint(3, 4)],
        }
    )
    cases["custom_types.filter_in_geo"] = filtered(field("location").in_(GeoPoint(1, 2), GeoPoint(3, 4)))
    cases["custom_types.within_triangle"] = filtered(
        within("location", GeoPoint(40.0, -74.2), GeoPoint(40.9, -74.2), GeoPoint(40.9, -73.7))
    )
    cases["custom_types.within_many"] = filtered(
        within(
            "location",
            GeoPoint(40.0, -74.2),
            GeoPoint(40.9, -74.2),
            GeoPoint(40.9, -73.7),
            GeoPoint(40.0, -73.7),
            GeoPoint(39.5, -74.0),
        )
    )
    cases["custom_types.nearest"] = filtered(nearest("embedding", embedding, 10))
    cases["custom_types.nearest_exact"] = filtered(nearest("embedding", embedding, 3).exact())
    cases["custom_types.sort_datetime"] = lambda r: r.orders.query().sort("openedAt", Order.DESC).to_list()
    for name, comparison in COMPARISONS:
        cases[f"custom_types.{name}_datetime"] = filtered(
            comparison(field("openedAt"), LocalDateTime.of(2024, 1, 1, 10, 0, 30))
        )
        cases[f"custom_types.{name}_time"] = filtered(comparison(field("opensAt"), LocalTime.of(9, 30, 15)))
    cases["custom_types.equals_geo"] = filtered(field("location").eq(new_york))
    cases["custom_types.equals_vector"] = filtered(field("embedding").eq(Vector.of(1.0, 0.0)))
    for comparator in GeoDistanceComparator:
        cases[f"custom_types.distance_{comparator.lower()}"] = filtered(
            distance("location", new_york, comparator, 1000)
        )
    for custom_type in CustomType:
        cases[f"custom_types.cast_{custom_type}"] = _mapped("typed", MidOps.cast("raw", custom_type))


def all_cases() -> dict[str, FixtureCall]:
    cases: dict[str, FixtureCall] = {}
    _data(cases)
    _aggregate(cases)
    _custom_types(cases)
    add_admin_cases(cases)
    return cases
