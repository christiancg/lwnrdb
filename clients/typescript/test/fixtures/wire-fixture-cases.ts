import {
    and,
    CastType,
    CustomType,
    distance,
    field,
    type FieldCondition,
    type Filter,
    GeoDistanceComparator,
    GeoPoint,
    LocalDateTime,
    LocalTime,
    type MidOperation,
    nand,
    nearest,
    nor,
    or,
    Order,
    script,
    Vector,
    within,
    xor,
} from "../../src/index.js";
import { remove, set } from "../../src/query/map-ops.js";
import * as MidOps from "../../src/query/mid-ops.js";
import type { FixtureCall } from "./recorder.js";
import { addAdminCases } from "./wire-fixture-admin-cases.js";

export const REDUCE_SUM = "export default (acc, doc) => acc + doc.total;";
const ACTIVE = field("status").eq("active");

type Comparison = (condition: FieldCondition, value: unknown) => Filter;

const COMPARISONS: readonly [string, Comparison][] = [
    ["equals", (condition, value) => condition.eq(value)],
    ["not_equals", (condition, value) => condition.ne(value)],
    ["greater_than", (condition, value) => condition.gt(value)],
    ["greater_than_equals", (condition, value) => condition.gte(value)],
    ["smaller_than", (condition, value) => condition.lt(value)],
    ["smaller_than_equals", (condition, value) => condition.lte(value)],
];

function filtered(filter: Filter): FixtureCall {
    return (r) => r.orders.query().filter(filter).toArray();
}

function data(cases: Map<string, FixtureCall>): void {
    cases.set("data.save_with_id", (r) => r.orders.save({ _id: "o-1", status: "active", total: 120 }));
    cases.set("data.save_without_id", (r) => r.orders.save({ status: "new" }));
    cases.set("data.save_nested", (r) =>
        r.orders.save({ _id: "o-2", customer: { name: "Ana", tags: ["vip", null, true, 1.5] } }),
    );
    cases.set("data.bulk_save", (r) => r.orders.saveAll([{ _id: "o-1", total: 1 }, { total: 2 }]));
    cases.set("data.find_by_id", (r) => r.orders.findById("o-1"));
    cases.set("data.find_by_id_dirty", (r) => r.orders.findById("o-1", { dirtyRead: true }));
    cases.set("data.delete", (r) => r.orders.delete("o-1"));
    cases.set("data.save_where", (r) =>
        r.orders.updateWhere(and(field("status").eq("pending"), field("total").lt(100)), {
            status: "expired",
            expiredBy: "sweeper",
        }),
    );
    cases.set("data.delete_where", (r) =>
        r.orders.deleteWhere(and(field("status").eq("archived"), field("total").lt(100))),
    );
}

function aggregate(cases: Map<string, FixtureCall>): void {
    cases.set("aggregate.all", (r) => r.orders.query().toArray());
    cases.set("aggregate.filter_sort_limit", (r) =>
        r.orders.query().filter(ACTIVE).sort("createdAt", Order.DESC).limit(10).toArray(),
    );
    cases.set("aggregate.skip_limit", (r) => r.orders.query().skip(20).limit(10).toArray());
    cases.set("aggregate.sort_ascending", (r) => r.orders.query().sort("name", Order.ASC).toArray());
    cases.set("aggregate.count", (r) => r.orders.query().filter(ACTIVE).count());
    cases.set("aggregate.group_by", (r) => r.orders.query().groupBy("status").toArray());
    cases.set("aggregate.distinct_field", (r) => r.orders.query().distinct("status").toArray());
    cases.set("aggregate.distinct_documents", (r) => r.orders.query().distinct().toArray());
    cases.set("aggregate.join", (r) => r.orders.query().join("customers", "customerId", "_id", "customer").toArray());
    cases.set("aggregate.reduce", (r) => r.orders.query().reduce(REDUCE_SUM, 0, "sum").toArray());
    cases.set("aggregate.reduce_defaults", (r) =>
        r.orders.query().reduce("export default (acc, doc) => acc;").toArray(),
    );
    cases.set("aggregate.dirty_read", (r) => r.orders.query().filter(ACTIVE).dirtyRead().toArray());
    cases.set("aggregate.analyze", (r) => r.orders.query().filter(ACTIVE).analyze());
    cases.set("aggregate.filter_in", filtered(field("status").in("active", "new")));
    cases.set("aggregate.filter_not_in", filtered(field("status").notIn("closed")));
    cases.set("aggregate.filter_contains", filtered(field("name").contains("an")));
    cases.set("aggregate.filter_null", filtered(field("deletedAt").eq(null)));
    cases.set("aggregate.filter_object_value", filtered(field("address").eq({ city: "Lima" })));
    cases.set("aggregate.filter_script", filtered(script("export default (doc) => doc.total > 100;")));
    COMPARISONS.forEach(([name, comparison]) => {
        cases.set(`aggregate.filter_${name}`, filtered(comparison(field("total"), 100)));
    });
    const conjunctions: readonly [string, (...operands: Filter[]) => Filter][] = [
        ["and", and],
        ["or", or],
        ["nor", nor],
        ["xor", xor],
        ["nand", nand],
    ];
    conjunctions.forEach(([name, conjunction]) => {
        cases.set(`aggregate.conjunction_${name}`, filtered(conjunction(ACTIVE, field("total").gt(100))));
    });
    cases.set(
        "aggregate.conjunction_nested",
        filtered(and(ACTIVE, or(field("total").lt(10), script("export default (doc) => doc.vip;")))),
    );
    mapCases(cases);
}

function mapCases(cases: Map<string, FixtureCall>): void {
    const arithmetic: readonly [string, (...operands: unknown[]) => MidOperation][] = [
        ["avg", MidOps.avg],
        ["sum", MidOps.sum],
        ["subs", MidOps.subs],
        ["max", MidOps.max],
        ["min", MidOps.min],
        ["multiply", MidOps.multiply],
        ["divide", MidOps.divide],
        ["pow", MidOps.pow],
        ["root", MidOps.root],
    ];
    arithmetic.forEach(([name, operator]) => {
        cases.set(`aggregate.map_${name}`, (r) =>
            r.orders
                .query()
                .map(set("result", operator("price", "qty", 2)))
                .toArray(),
        );
    });
    cases.set("aggregate.map_concat", (r) =>
        r.orders
            .query()
            .map(set("label", MidOps.concat("name", MidOps.literal(" / "), "status")))
            .toArray(),
    );
    cases.set("aggregate.map_abs", (r) =>
        r.orders
            .query()
            .map(set("delta", MidOps.abs("change")))
            .toArray(),
    );
    cases.set("aggregate.map_size", (r) =>
        r.orders
            .query()
            .map(set("count", MidOps.size("items")))
            .toArray(),
    );
    cases.set("aggregate.map_script", (r) =>
        r.orders
            .query()
            .map(set("total", MidOps.script("export default (doc) => doc.price * doc.qty;")))
            .toArray(),
    );
    cases.set("aggregate.map_remove", (r) => r.orders.query().map(remove("secret")).toArray());
    cases.set("aggregate.map_conditional", (r) =>
        r.orders
            .query()
            .map(set("flag", MidOps.sum(1)).when(ACTIVE), remove("draft").when(field("status").eq("closed")))
            .toArray(),
    );
    [CastType.NUMBER, CastType.STRING, CastType.BOOLEAN].forEach((type) => {
        cases.set(`aggregate.map_cast_${type.toLowerCase()}`, (r) =>
            r.orders
                .query()
                .map(set("converted", MidOps.cast("raw", type)))
                .toArray(),
        );
    });
}

function customTypes(cases: Map<string, FixtureCall>): void {
    const newYork = GeoPoint.of(40.71, -74.0);
    const embedding = Vector.of(0.12, 0.44, 0.91);
    cases.set("custom_types.save_all_types", (r) =>
        r.orders.save({
            _id: "p-1",
            openedAt: LocalDateTime.of(2024, 1, 1, 10, 0),
            opensAt: LocalTime.of(9, 30),
            location: newYork,
            embedding,
            stops: [GeoPoint.of(1.0, 2.0), GeoPoint.of(3.0, 4.0)],
        }),
    );
    cases.set("custom_types.filter_in_geo", filtered(field("location").in(GeoPoint.of(1, 2), GeoPoint.of(3, 4))));
    cases.set(
        "custom_types.within_triangle",
        filtered(within("location", GeoPoint.of(40.0, -74.2), GeoPoint.of(40.9, -74.2), GeoPoint.of(40.9, -73.7))),
    );
    cases.set(
        "custom_types.within_many",
        filtered(
            within(
                "location",
                GeoPoint.of(40.0, -74.2),
                GeoPoint.of(40.9, -74.2),
                GeoPoint.of(40.9, -73.7),
                GeoPoint.of(40.0, -73.7),
                GeoPoint.of(39.5, -74.0),
            ),
        ),
    );
    cases.set("custom_types.nearest", filtered(nearest("embedding", embedding, 10)));
    cases.set("custom_types.nearest_exact", filtered(nearest("embedding", embedding, 3).exact()));
    cases.set("custom_types.sort_datetime", (r) => r.orders.query().sort("openedAt", Order.DESC).toArray());
    COMPARISONS.forEach(([name, comparison]) => {
        cases.set(
            `custom_types.${name}_datetime`,
            filtered(comparison(field("openedAt"), LocalDateTime.of(2024, 1, 1, 10, 0, 30))),
        );
        cases.set(`custom_types.${name}_time`, filtered(comparison(field("opensAt"), LocalTime.of(9, 30, 15))));
    });
    cases.set("custom_types.equals_geo", filtered(field("location").eq(newYork)));
    cases.set("custom_types.equals_vector", filtered(field("embedding").eq(Vector.of(1.0, 0.0))));
    Object.values(GeoDistanceComparator).forEach((comparator) => {
        cases.set(
            `custom_types.distance_${comparator.toLowerCase()}`,
            filtered(distance("location", newYork, comparator, 1000)),
        );
    });
    Object.values(CustomType).forEach((type) => {
        cases.set(`custom_types.cast_${type}`, (r) =>
            r.orders
                .query()
                .map(set("typed", MidOps.cast("raw", type)))
                .toArray(),
        );
    });
}

export function allCases(): Map<string, FixtureCall> {
    const cases = new Map<string, FixtureCall>();
    data(cases);
    aggregate(cases);
    customTypes(cases);
    addAdminCases(cases);
    return cases;
}
