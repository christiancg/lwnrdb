package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.utils.GeoPoint;
import org.techhouse.utils.GeoUtils;

public class GeoAntimeridianTest {
    private static void assertBoxCoversEveryPointWithin(double lat, double lng, double radiusMeters,
            double[][] points) {
        final var centre = new GeoPoint(lat, lng);
        final var box = GeoUtils.boundingBoxForRadius(centre, radiusMeters);
        for (final var point : points) {
            final var candidate = new GeoPoint(point[0], point[1]);
            if (GeoUtils.haversineMeters(centre, candidate) <= radiusMeters) {
                assertTrue(box.contains(candidate),
                        "the box must be a superset of the true circle: FILTER re-tests exactly but can never"
                                + " recover a point the pre-filter excluded");
            }
        }
    }

    @Test
    public void test_a_radius_east_of_the_antimeridian_covers_the_far_side() {
        assertBoxCoversEveryPointWithin(0, 179.99, 50000, new double[][]{{0, 179.95}, {0, -179.95}});
    }

    @Test
    public void test_a_radius_west_of_the_antimeridian_covers_the_far_side() {
        assertBoxCoversEveryPointWithin(0, -179.99, 50000, new double[][]{{0, 179.95}, {0, -179.95}});
    }

    @Test
    public void test_a_radius_centred_on_the_antimeridian_covers_both_sides() {
        assertBoxCoversEveryPointWithin(0, 180.0, 1000000, new double[][]{{0, 179.95}, {0, -179.95}});
    }

    @Test
    public void test_a_near_pole_radius_covers_every_longitude() {
        assertBoxCoversEveryPointWithin(89.95, 0, 50000, new double[][]{{89.97, 170}, {89.97, -170}});
    }

    @Test
    public void test_an_ordinary_radius_keeps_a_selective_box() {
        final var box = GeoUtils.boundingBoxForRadius(new GeoPoint(0, 0), 50000);
        assertTrue(box.minLng() > -1 && box.maxLng() < 1,
                "widening to the whole longitude band away from the wrap would forfeit the index everywhere");
    }
}
