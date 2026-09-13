package org.techhouse.unit.ejson.custom_types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonGeo;

public class JsonGeoOrderingTest {
    private static JsonGeo geo(double lat, double lng) {
        return new JsonGeo(String.format("#geo(%.6f,%.6f)", lat, lng));
    }

    private static List<JsonGeo> sample() {
        final var points = new ArrayList<JsonGeo>();
        var seed = 12345L;
        for (var i = 0; i < 120; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            final var lat = ((seed >>> 16) % 170_000) / 1000.0 - 85.0;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            final var lng = ((seed >>> 16) % 360_000) / 1000.0 - 180.0;
            points.add(geo(lat, lng));
        }
        return points;
    }

    @Test
    public void test_compareToCustom_agrees_with_compare_on_the_raw_point() {
        final var points = sample();
        for (final var a : points) {
            for (final var b : points) {
                assertEquals(Integer.signum(a.compare(b.point())), Integer.signum(a.compareToCustom(b)),
                        "comparing two JsonGeo values must match comparing against the raw point");
            }
        }
    }

    @Test
    public void test_compareToCustom_is_antisymmetric() {
        final var points = sample();
        for (final var a : points) {
            for (final var b : points) {
                assertEquals(Integer.signum(a.compareToCustom(b)), -Integer.signum(b.compareToCustom(a)));
            }
        }
    }

    @Test
    public void test_compareToCustom_is_zero_only_for_the_same_point() {
        final var a = geo(40.0, -74.0);
        assertEquals(0, a.compareToCustom(geo(40.0, -74.0)));
        assertNotEquals(0, a.compareToCustom(geo(40.0, -74.000001)));
        assertNotEquals(0, a.compareToCustom(geo(40.000001, -74.0)));
    }

    @Test
    public void test_sorting_with_compareToCustom_matches_sorting_with_compare() {
        final var viaCustom = new ArrayList<>(sample());
        final var viaPoint = new ArrayList<>(sample());
        viaCustom.sort(JsonGeo::compareToCustom);
        viaPoint.sort((a, b) -> a.compare(b.point()));

        assertEquals(viaPoint.stream().map(JsonGeo::getValue).toList(),
                viaCustom.stream().map(JsonGeo::getValue).toList(),
                "the memoized both-sides comparison must produce the same order as the original");
    }

    @Test
    public void test_geohash_ordering_clusters_nearby_points_before_distant_ones() {
        final var target = geo(40.0, -74.0);
        final var near = geo(40.001, -74.001);
        final var far = geo(-33.9, 151.2);

        assertTrue(
                Math.abs(target.compareToCustom(near)) < Math.abs(target.compareToCustom(far))
                        || target.geoHash().charAt(0) != far.geoHash().charAt(0),
                "a nearby point must share more of the geohash prefix than a distant one");
        assertEquals(target.geoHash().substring(0, 4), near.geoHash().substring(0, 4));
    }
}
