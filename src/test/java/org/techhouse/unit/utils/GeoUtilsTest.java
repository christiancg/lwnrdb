package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.utils.GeoPoint;
import org.techhouse.utils.GeoUtils;

public class GeoUtilsTest {
    @Test
    public void test_geohash_is_deterministic_and_clusters() {
        final var a = GeoUtils.geoHash(40.0, -74.0, 9);
        final var b = GeoUtils.geoHash(40.0, -74.0, 9);
        final var near = GeoUtils.geoHash(40.0005, -74.0005, 9);
        final var far = GeoUtils.geoHash(-33.0, 151.0, 9);

        assertEquals(a, b);
        assertEquals(9, a.length());
        assertEquals(a.substring(0, 4), near.substring(0, 4));
        assertNotEquals(a.charAt(0), far.charAt(0));
    }

    @Test
    public void test_haversine_known_distance() {
        // One degree of latitude is ~111.2 km.
        final var d = GeoUtils.haversineMeters(new GeoPoint(0.0, 0.0), new GeoPoint(1.0, 0.0));

        assertEquals(111_195, d, 500);
    }

    @Test
    public void test_haversine_zero_for_same_point() {
        assertEquals(0.0, GeoUtils.haversineMeters(new GeoPoint(10.0, 20.0), new GeoPoint(10.0, 20.0)), 1e-9);
    }

    @Test
    public void test_point_in_polygon_inside_and_outside() {
        final var square = List.of(new GeoPoint(0, 0), new GeoPoint(0, 10), new GeoPoint(10, 10), new GeoPoint(10, 0));

        assertTrue(GeoUtils.pointInPolygon(new GeoPoint(5, 5), square));
        assertFalse(GeoUtils.pointInPolygon(new GeoPoint(15, 5), square));
        assertFalse(GeoUtils.pointInPolygon(new GeoPoint(-1, -1), square));
    }

    @Test
    public void test_point_in_polygon_concave() {
        // An L-shaped (concave) polygon; the notch corner is outside.
        final var lShape = List.of(new GeoPoint(0, 0), new GeoPoint(0, 4), new GeoPoint(2, 4), new GeoPoint(2, 2),
                new GeoPoint(4, 2), new GeoPoint(4, 0));

        assertTrue(GeoUtils.pointInPolygon(new GeoPoint(1, 1), lShape));
        assertFalse(GeoUtils.pointInPolygon(new GeoPoint(3, 3), lShape));
    }

    @Test
    public void test_bounding_box_of_points() {
        final var bbox = GeoUtils.boundingBoxOf(List.of(new GeoPoint(1, 2), new GeoPoint(5, -3), new GeoPoint(3, 8)));

        assertEquals(1, bbox.minLat(), 1e-9);
        assertEquals(5, bbox.maxLat(), 1e-9);
        assertEquals(-3, bbox.minLng(), 1e-9);
        assertEquals(8, bbox.maxLng(), 1e-9);
    }

    @Test
    public void test_bounding_box_for_radius_contains_center() {
        final var center = new GeoPoint(40.0, -74.0);
        final var bbox = GeoUtils.boundingBoxForRadius(center, 1000);

        assertTrue(bbox.contains(center));
        assertTrue(bbox.minLat() < center.lat() && bbox.maxLat() > center.lat());
        assertTrue(bbox.minLng() < center.lng() && bbox.maxLng() > center.lng());
    }

    @Test
    public void test_bounding_box_for_radius_near_pole_clamps_longitude() {
        final var bbox = GeoUtils.boundingBoxForRadius(new GeoPoint(90.0, 0.0), 1000);

        assertEquals(-180, bbox.minLng(), 1e-9);
        assertEquals(180, bbox.maxLng(), 1e-9);
    }

    @Test
    public void test_bounding_box_contains_boundaries() {
        final var bbox = new GeoUtils.BoundingBox(0, 0, 10, 10);

        assertTrue(bbox.contains(new GeoPoint(0, 0)));
        assertTrue(bbox.contains(new GeoPoint(10, 10)));
        assertFalse(bbox.contains(new GeoPoint(10.1, 5)));
    }

    @Test
    public void test_covering_prefixes_cover_every_point_in_box() {
        final var bbox = new GeoUtils.BoundingBox(40.0, -74.1, 40.2, -73.9);
        final var prefixes = GeoUtils.coveringGeohashPrefixes(bbox);

        assertFalse(prefixes.isEmpty());
        // Every point inside the box — including points close to the max edges and off the grid — must
        // be covered by at least one returned prefix, so the index range scan cannot miss a candidate.
        // The fine, non-grid-aligned sampling deliberately probes the max-edge band a naive grid misses.
        final var latSpan = bbox.maxLat() - bbox.minLat();
        final var lngSpan = bbox.maxLng() - bbox.minLng();
        for (var i = 0; i <= 37; i++) {
            for (var j = 0; j <= 37; j++) {
                final var lat = bbox.minLat() + latSpan * i / 37.0;
                final var lng = bbox.minLng() + lngSpan * j / 37.0;
                final var hash = GeoUtils.geoHash(lat, lng, 12);
                assertTrue(prefixes.stream().anyMatch(hash::startsWith), "no covering prefix for " + lat + "," + lng);
            }
        }
    }

    @Test
    public void test_covering_prefixes_bounded_for_large_box() {
        final var world = new GeoUtils.BoundingBox(-80, -170, 80, 170);
        final var prefixes = GeoUtils.coveringGeohashPrefixes(world);

        assertFalse(prefixes.isEmpty());
        assertTrue(prefixes.size() <= 32, "covering should stay bounded, was " + prefixes.size());
    }
}
