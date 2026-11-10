package io.github.christiancg.lwnrdb.client.query;

import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import java.util.List;
import java.util.Objects;

public record WithinFilter(String field, List<GeoPoint> polygon) implements Filter {
    public static final int MIN_POLYGON_POINTS = 3;

    public WithinFilter {
        Objects.requireNonNull(field, "field");
        polygon = List.copyOf(polygon);
        if (polygon.size() < MIN_POLYGON_POINTS) {
            throw new IllegalArgumentException("a within polygon needs at least 3 points, got " + polygon.size());
        }
    }
}
