package org.techhouse.unit.ejson.custom_types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;
import org.techhouse.utils.GeoPoint;

public class JsonGeoTest {
    // Parses a valid "#geo(lat,lng)" string into a GeoPoint.
    @Test
    public void test_parse_valid_geo() {
        final var geo = new JsonGeo("#geo(40.71,-74.0)");

        assertEquals(new GeoPoint(40.71, -74.0), geo.point());
        assertEquals("40.71,-74.0", geo.stringDataValue());
        assertEquals("geo", geo.getCustomTypeName());
    }

    // Builds the wire value from a GeoPoint.
    @Test
    public void test_construct_from_geo_point() {
        final var geo = new JsonGeo(new GeoPoint(1.5, 2.5));

        assertEquals("#geo(1.5,2.5)", geo.getValue());
    }

    // A non-numeric coordinate is rejected.
    @Test
    public void test_parse_invalid_number_throws() {
        assertThrows(WrongFormatCustomTypeException.class, () -> new JsonGeo("#geo(abc,1.0)"));
    }

    // The wrong number of coordinates is rejected.
    @Test
    public void test_parse_wrong_arity_throws() {
        assertThrows(WrongFormatCustomTypeException.class, () -> new JsonGeo("#geo(1.0)"));
        assertThrows(WrongFormatCustomTypeException.class, () -> new JsonGeo("#geo(1.0,2.0,3.0)"));
    }

    // Out-of-range latitude/longitude are rejected.
    @Test
    public void test_parse_out_of_range_throws() {
        assertThrows(WrongFormatCustomTypeException.class, () -> new JsonGeo("#geo(91.0,0.0)"));
        assertThrows(WrongFormatCustomTypeException.class, () -> new JsonGeo("#geo(0.0,181.0)"));
        assertThrows(WrongFormatCustomTypeException.class, () -> new JsonGeo("#geo(-91.0,0.0)"));
        assertThrows(WrongFormatCustomTypeException.class, () -> new JsonGeo("#geo(0.0,-181.0)"));
    }

    // Default constructor yields an empty, null-valued instance (used by the factory for reflection).
    @Test
    public void test_default_constructor_is_empty() {
        final var geo = new JsonGeo();

        assertEquals("", geo.getValue());
        assertNull(geo.getCustomValue());
    }

    // compare == 0 exactly for equal points and non-zero otherwise; ordering is total.
    @Test
    public void test_compare_equality_and_ordering() {
        final var a = new JsonGeo(new GeoPoint(40.0, -74.0));

        assertEquals(0, a.compare(new GeoPoint(40.0, -74.0)));
        assertNotEquals(0, a.compare(new GeoPoint(41.0, -74.0)));
        assertNotEquals(0, a.compare(new GeoPoint(40.0, -73.0)));
    }

    // Nearby points share a geohash prefix (spatial clustering the index relies on).
    @Test
    public void test_geohash_prefix_clusters_nearby_points() {
        final var a = new JsonGeo(new GeoPoint(40.0000, -74.0000)).geoHash();
        final var b = new JsonGeo(new GeoPoint(40.0001, -74.0001)).geoHash();
        final var far = new JsonGeo(new GeoPoint(-33.0, 151.0)).geoHash();

        assertEquals(a.substring(0, 5), b.substring(0, 5));
        assertNotEquals(a.substring(0, 1), far.substring(0, 1));
    }

    @Test
    public void test_custom_operator_names() {
        final var geo = new JsonGeo(new GeoPoint(0, 0));

        assertEquals(java.util.Set.of("distance", "within"), geo.customOperatorNames());
    }

    // distance operator: each comparator against a ~111km separation (1 degree of latitude).
    @Test
    public void test_distance_operator_comparators() {
        final var geo = new JsonGeo(new GeoPoint(0.0, 0.0));
        final var target = new JsonGeo(new GeoPoint(1.0, 0.0)); // ~111.2 km away

        assertTrue(geo.applyCustomOperator("distance", distanceArgs(target, "SMALLER_THAN", 200_000)));
        assertFalse(geo.applyCustomOperator("distance", distanceArgs(target, "SMALLER_THAN", 50_000)));
        assertTrue(geo.applyCustomOperator("distance", distanceArgs(target, "GREATER_THAN", 50_000)));
        assertFalse(geo.applyCustomOperator("distance", distanceArgs(target, "GREATER_THAN", 200_000)));
        assertTrue(geo.applyCustomOperator("distance", distanceArgs(target, "SMALLER_THAN_EQUALS", 200_000)));
        assertTrue(geo.applyCustomOperator("distance", distanceArgs(target, "GREATER_THAN_EQUALS", 50_000)));
        assertFalse(geo.applyCustomOperator("distance", distanceArgs(target, "EQUALS", 1000)));
    }

    // The distance target may also arrive as a raw "#geo(...)" string.
    @Test
    public void test_distance_target_as_string() {
        final var geo = new JsonGeo(new GeoPoint(0.0, 0.0));
        final var args = distanceArgs(new JsonString("#geo(1.0,0.0)"), "SMALLER_THAN", 200_000);

        assertTrue(geo.applyCustomOperator("distance", args));
    }

    @Test
    public void test_distance_invalid_comparator_throws() {
        final var geo = new JsonGeo(new GeoPoint(0.0, 0.0));
        // CONTAINS is not a valid geo distance comparator, so it fails to parse.
        final var args = distanceArgs(new JsonGeo(new GeoPoint(1.0, 0.0)), "CONTAINS", 10);

        assertThrows(IllegalArgumentException.class, () -> geo.applyCustomOperator("distance", args));
    }

    @Test
    public void test_distance_bad_target_throws() {
        final var geo = new JsonGeo(new GeoPoint(0.0, 0.0));
        final var args = distanceArgs(new JsonNumber(5), "SMALLER_THAN", 10);

        assertThrows(WrongFormatCustomTypeException.class, () -> geo.applyCustomOperator("distance", args));
    }

    // within operator: a point inside and a point outside a unit square around the origin.
    @Test
    public void test_within_operator() {
        final var inside = new JsonGeo(new GeoPoint(0.5, 0.5));
        final var outside = new JsonGeo(new GeoPoint(5.0, 5.0));
        final var args = Map.<String, JsonBaseElement>of("polygon", square());

        assertTrue(inside.applyCustomOperator("within", args));
        assertFalse(outside.applyCustomOperator("within", args));
    }

    @Test
    public void test_unknown_custom_operator_throws() {
        final var geo = new JsonGeo(new GeoPoint(0.0, 0.0));

        assertThrows(UnsupportedOperationException.class, () -> geo.applyCustomOperator("nope", Map.of()));
    }

    // geo declares no ranking operators and rejects any ranking evaluation.
    @Test
    public void test_no_ranking_operators() {
        final var geo = new JsonGeo(new GeoPoint(0.0, 0.0));

        assertTrue(geo.customRankingOperatorNames().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> geo.applyCustomRankingOperator("nearest", Map.of()));
    }

    private static Map<String, JsonBaseElement> distanceArgs(JsonBaseElement target, String comparator, double dist) {
        return Map.of("value", target, "comparator", new JsonString(comparator), "distance", new JsonNumber(dist));
    }

    private static JsonArray square() {
        final var polygon = new JsonArray();
        polygon.add(new JsonGeo(new GeoPoint(0.0, 0.0)));
        polygon.add(new JsonGeo(new GeoPoint(0.0, 1.0)));
        polygon.add(new JsonGeo(new GeoPoint(1.0, 1.0)));
        polygon.add(new JsonGeo(new GeoPoint(1.0, 0.0)));
        return polygon;
    }
}
