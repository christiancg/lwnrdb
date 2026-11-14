package io.github.christiancg.lwnrdb.client;

import static io.github.christiancg.lwnrdb.client.query.Filters.and;
import static io.github.christiancg.lwnrdb.client.query.Filters.distance;
import static io.github.christiancg.lwnrdb.client.query.Filters.field;
import static io.github.christiancg.lwnrdb.client.query.Filters.nearest;
import static io.github.christiancg.lwnrdb.client.query.Filters.or;
import static io.github.christiancg.lwnrdb.client.query.Filters.script;
import static io.github.christiancg.lwnrdb.client.query.Filters.within;
import static io.github.christiancg.lwnrdb.client.query.MapOps.remove;
import static io.github.christiancg.lwnrdb.client.query.MapOps.set;

import io.github.christiancg.lwnrdb.client.query.CastType;
import io.github.christiancg.lwnrdb.client.query.FieldCondition;
import io.github.christiancg.lwnrdb.client.query.Filter;
import io.github.christiancg.lwnrdb.client.query.Filters;
import io.github.christiancg.lwnrdb.client.query.MidOps;
import io.github.christiancg.lwnrdb.client.query.Order;
import io.github.christiancg.lwnrdb.client.values.CustomType;
import io.github.christiancg.lwnrdb.client.values.GeoDistanceComparator;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

final class WireFixtureCases {
    static final String REDUCE_SUM = "export default (acc, doc) => acc + doc.total;";
    private static final Filter ACTIVE = field("status").eq("active");

    private WireFixtureCases() {
    }

    static Map<String, Consumer<Recorder>> all() {
        final var cases = new LinkedHashMap<String, Consumer<Recorder>>();
        data(cases);
        aggregate(cases);
        customTypes(cases);
        WireFixtureAdminCases.add(cases);
        return cases;
    }

    private static void data(Map<String, Consumer<Recorder>> cases) {
        cases.put("data.save_with_id", r -> r.orders.save(Document.of("_id", "o-1", "status", "active", "total", 120)));
        cases.put("data.save_without_id", r -> r.orders.save(Document.of("status", "new")));
        cases.put("data.save_nested", r -> r.orders.save(Document.of("_id", "o-2", "customer",
                Document.of("name", "Ana", "tags", Arrays.asList("vip", null, true, 1.5)))));
        cases.put("data.bulk_save",
                r -> r.orders.saveAll(List.of(Document.of("_id", "o-1", "total", 1), Document.of("total", 2))));
        cases.put("data.find_by_id", r -> r.orders.findById("o-1"));
        cases.put("data.find_by_id_dirty", r -> r.orders.findById("o-1", ReadMode.DIRTY));
        cases.put("data.delete", r -> r.orders.delete("o-1"));
        cases.put("data.delete_where",
                r -> r.orders.deleteWhere(and(field("status").eq("archived"), field("total").lt(100))));
    }

    private static void aggregate(Map<String, Consumer<Recorder>> cases) {
        cases.put("aggregate.all", r -> r.orders.query().toList());
        cases.put("aggregate.filter_sort_limit",
                r -> r.orders.query().filter(ACTIVE).sort("createdAt", Order.DESC).limit(10).toList());
        cases.put("aggregate.skip_limit", r -> r.orders.query().skip(20).limit(10).toList());
        cases.put("aggregate.sort_ascending", r -> r.orders.query().sort("name", Order.ASC).toList());
        cases.put("aggregate.count", r -> r.orders.query().filter(ACTIVE).count());
        cases.put("aggregate.group_by", r -> r.orders.query().groupBy("status").toList());
        cases.put("aggregate.distinct_field", r -> r.orders.query().distinct("status").toList());
        cases.put("aggregate.distinct_documents", r -> r.orders.query().distinct().toList());
        cases.put("aggregate.join", r -> r.orders.query().join("customers", "customerId", "_id", "customer").toList());
        cases.put("aggregate.reduce", r -> r.orders.query().reduce(REDUCE_SUM, 0, "sum").toList());
        cases.put("aggregate.reduce_defaults",
                r -> r.orders.query().reduce("export default (acc, doc) => acc;").toList());
        cases.put("aggregate.dirty_read", r -> r.orders.query().filter(ACTIVE).dirtyRead().toList());
        cases.put("aggregate.analyze", r -> r.orders.query().filter(ACTIVE).analyze());
        cases.put("aggregate.filter_in", filtered(field("status").in("active", "new")));
        cases.put("aggregate.filter_not_in", filtered(field("status").notIn("closed")));
        cases.put("aggregate.filter_contains", filtered(field("name").contains("an")));
        cases.put("aggregate.filter_null", filtered(field("deletedAt").eq(null)));
        cases.put("aggregate.filter_object_value", filtered(field("address").eq(Document.of("city", "Lima"))));
        cases.put("aggregate.filter_script", filtered(script("export default (doc) => doc.total > 100;")));
        final Map<String, BiFunction<FieldCondition, Object, Filter>> comparisons = comparisons();
        comparisons.forEach((name, comparison) -> cases.put("aggregate.filter_" + name,
                filtered(comparison.apply(field("total"), 100))));
        final Map<String, Function<Filter[], Filter>> conjunctions = Map.of("and", Filters::and, "or", Filters::or,
                "nor", Filters::nor, "xor", Filters::xor, "nand", Filters::nand);
        conjunctions.forEach((name, conjunction) -> cases.put("aggregate.conjunction_" + name,
                filtered(conjunction.apply(new Filter[]{ACTIVE, field("total").gt(100)}))));
        cases.put("aggregate.conjunction_nested",
                filtered(and(ACTIVE, or(field("total").lt(10), script("export default (doc) => doc.vip;")))));
        mapCases(cases);
    }

    private static void mapCases(Map<String, Consumer<Recorder>> cases) {
        final Map<String, Function<Object[], io.github.christiancg.lwnrdb.client.query.MidOperation>> arithmetic = new LinkedHashMap<>();
        arithmetic.put("avg", MidOps::avg);
        arithmetic.put("sum", MidOps::sum);
        arithmetic.put("subs", MidOps::subs);
        arithmetic.put("max", MidOps::max);
        arithmetic.put("min", MidOps::min);
        arithmetic.put("multiply", MidOps::multiply);
        arithmetic.put("divide", MidOps::divide);
        arithmetic.put("pow", MidOps::pow);
        arithmetic.put("root", MidOps::root);
        arithmetic.forEach((name, operator) -> cases.put("aggregate.map_" + name,
                r -> r.orders.query().map(set("result", operator.apply(new Object[]{"price", "qty", 2}))).toList()));
        cases.put("aggregate.map_concat", r -> r.orders.query()
                .map(set("label", MidOps.concat("name", MidOps.literal(" / "), "status"))).toList());
        cases.put("aggregate.map_abs", r -> r.orders.query().map(set("delta", MidOps.abs("change"))).toList());
        cases.put("aggregate.map_size", r -> r.orders.query().map(set("count", MidOps.size("items"))).toList());
        cases.put("aggregate.map_script", r -> r.orders.query()
                .map(set("total", MidOps.script("export default (doc) => doc.price * doc.qty;"))).toList());
        cases.put("aggregate.map_remove", r -> r.orders.query().map(remove("secret")).toList());
        cases.put("aggregate.map_conditional", r -> r.orders.query()
                .map(set("flag", MidOps.sum(1)).when(ACTIVE), remove("draft").when(field("status").eq("closed")))
                .toList());
        for (final var type : List.of(CastType.NUMBER, CastType.STRING, CastType.BOOLEAN)) {
            cases.put("aggregate.map_cast_" + type.name().toLowerCase(java.util.Locale.ROOT),
                    r -> r.orders.query().map(set("converted", MidOps.cast("raw", type))).toList());
        }
    }

    private static void customTypes(Map<String, Consumer<Recorder>> cases) {
        final var newYork = GeoPoint.of(40.71, -74.0);
        final var embedding = Vector.of(0.12, 0.44, 0.91);
        cases.put("custom_types.save_all_types",
                r -> r.orders.save(Document.of("_id", "p-1", "openedAt", LocalDateTime.of(2024, 1, 1, 10, 0), "opensAt",
                        LocalTime.of(9, 30), "location", newYork, "embedding", embedding, "stops",
                        List.of(GeoPoint.of(1.0, 2.0), GeoPoint.of(3.0, 4.0)))));
        cases.put("custom_types.filter_in_geo", filtered(field("location").in(GeoPoint.of(1, 2), GeoPoint.of(3, 4))));
        cases.put("custom_types.within_triangle", filtered(
                within("location", GeoPoint.of(40.0, -74.2), GeoPoint.of(40.9, -74.2), GeoPoint.of(40.9, -73.7))));
        cases.put("custom_types.within_many",
                filtered(within("location", GeoPoint.of(40.0, -74.2), GeoPoint.of(40.9, -74.2),
                        GeoPoint.of(40.9, -73.7), GeoPoint.of(40.0, -73.7), GeoPoint.of(39.5, -74.0))));
        cases.put("custom_types.nearest", filtered(nearest("embedding", embedding, 10)));
        cases.put("custom_types.nearest_exact", filtered(nearest("embedding", embedding, 3).exact()));
        cases.put("custom_types.sort_datetime", r -> r.orders.query().sort("openedAt", Order.DESC).toList());
        comparisons().forEach((name, comparison) -> {
            cases.put("custom_types." + name + "_datetime",
                    filtered(comparison.apply(field("openedAt"), LocalDateTime.of(2024, 1, 1, 10, 0, 30))));
            cases.put("custom_types." + name + "_time",
                    filtered(comparison.apply(field("opensAt"), LocalTime.of(9, 30, 15))));
        });
        cases.put("custom_types.equals_geo", filtered(field("location").eq(newYork)));
        cases.put("custom_types.equals_vector", filtered(field("embedding").eq(Vector.of(1.0, 0.0))));
        for (final var comparator : GeoDistanceComparator.values()) {
            cases.put("custom_types.distance_" + comparator.name().toLowerCase(java.util.Locale.ROOT),
                    filtered(distance("location", newYork, comparator, 1000)));
        }
        for (final var type : CustomType.values()) {
            cases.put("custom_types.cast_" + type.wireName(),
                    r -> r.orders.query().map(set("typed", MidOps.cast("raw", type))).toList());
        }
    }

    private static Map<String, BiFunction<FieldCondition, Object, Filter>> comparisons() {
        final Map<String, BiFunction<FieldCondition, Object, Filter>> comparisons = new LinkedHashMap<>();
        comparisons.put("equals", FieldCondition::eq);
        comparisons.put("not_equals", FieldCondition::ne);
        comparisons.put("greater_than", FieldCondition::gt);
        comparisons.put("greater_than_equals", FieldCondition::gte);
        comparisons.put("smaller_than", FieldCondition::lt);
        comparisons.put("smaller_than_equals", FieldCondition::lte);
        return comparisons;
    }

    private static Consumer<Recorder> filtered(Filter filter) {
        return r -> r.orders.query().filter(filter).toList();
    }
}
