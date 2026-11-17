package io.github.christiancg.lwnrdb.client.query;

import io.github.christiancg.lwnrdb.client.values.GeoDistanceComparator;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.util.List;

public final class Filters {
    private Filters() {
    }

    public static FieldCondition field(String field) {
        return new FieldCondition(field);
    }

    public static Filter and(Filter... operands) {
        return new ConjunctionFilter(Conjunction.AND, List.of(operands));
    }

    public static Filter or(Filter... operands) {
        return new ConjunctionFilter(Conjunction.OR, List.of(operands));
    }

    public static Filter nor(Filter... operands) {
        return new ConjunctionFilter(Conjunction.NOR, List.of(operands));
    }

    public static Filter xor(Filter... operands) {
        return new ConjunctionFilter(Conjunction.XOR, List.of(operands));
    }

    public static Filter nand(Filter... operands) {
        return new ConjunctionFilter(Conjunction.NAND, List.of(operands));
    }

    public static Filter distance(String field, GeoPoint target, GeoDistanceComparator comparator, double metres) {
        return new DistanceFilter(field, target, comparator, metres);
    }

    public static Filter within(String field, GeoPoint... polygon) {
        return new WithinFilter(field, List.of(polygon));
    }

    public static Filter within(String field, List<GeoPoint> polygon) {
        return new WithinFilter(field, polygon);
    }

    public static NearestFilter nearest(String field, Vector query, int k) {
        return new NearestFilter(field, query, k, false);
    }

    public static Filter script(String source) {
        return new ScriptFilter(source);
    }
}
