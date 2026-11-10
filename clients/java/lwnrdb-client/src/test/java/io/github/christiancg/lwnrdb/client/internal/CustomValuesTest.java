package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

public class CustomValuesTest {
    @Test
    public void test_geo_bounds_are_inclusive() {
        assertEquals("#geo(90.0,180.0)", CustomValues.format(GeoPoint.of(90, 180)));
        assertEquals("#geo(-90.0,-180.0)", CustomValues.format(GeoPoint.of(-90, -180)));
        assertThrows(IllegalArgumentException.class, () -> GeoPoint.of(90.0001, 0));
        assertThrows(IllegalArgumentException.class, () -> GeoPoint.of(0, -180.0001));
        assertThrows(IllegalArgumentException.class, () -> GeoPoint.of(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> GeoPoint.of(0, Double.POSITIVE_INFINITY));
    }

    @Test
    public void test_geo_parsing_tolerates_spaces_and_refuses_other_shapes() {
        assertEquals(GeoPoint.of(1.5, -2), CustomValues.parseGeo("#geo(1.5, -2)"));
        assertThrows(IllegalArgumentException.class, () -> CustomValues.parseGeo("#geo(1)"));
        assertThrows(IllegalArgumentException.class, () -> CustomValues.parseGeo("#vector(1,2)"));
        assertThrows(IllegalArgumentException.class, () -> CustomValues.parseGeo("#geo(1,2"));
    }

    @Test
    public void test_vectors_round_trip_in_the_servers_spellings() {
        final var vector = Vector.of(1.0E-4, -0.0, 3, 1.0E21);
        assertEquals(vector, CustomValues.parseVector(CustomValues.format(vector)));
        assertEquals(Vector.of(0.5), CustomValues.parseVector("#vector(0.5)"));
        assertEquals(Vector.of(1.0E-4, 2), CustomValues.parseVector("#vector(1.0E-4, 2.0)"));
        assertThrows(IllegalArgumentException.class, () -> CustomValues.parseVector("#vector()"));
        assertThrows(IllegalArgumentException.class, () -> CustomValues.parseVector("#vector(NaN)"));
    }

    @Test
    public void test_vector_validation_and_copies() {
        assertThrows(IllegalArgumentException.class, Vector::of);
        assertThrows(IllegalArgumentException.class, () -> Vector.of(1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Vector.of(Double.NEGATIVE_INFINITY));
        final var source = new double[]{1, 2};
        final var vector = Vector.of(source);
        source[0] = 9;
        vector.toArray()[1] = 9;
        assertEquals(Vector.of(1, 2), vector);
        assertEquals(2, vector.dimensions());
        assertEquals(Vector.of(1, 2).hashCode(), vector.hashCode());
        assertTrue(vector.toString().contains("1.0, 2.0"));
        assertNotEquals(vector, "x");
    }

    @Test
    public void test_temporal_values_use_the_iso_spellings_the_server_parses() {
        assertEquals("#datetime(2024-01-01T10:00)", CustomValues.format(LocalDateTime.of(2024, 1, 1, 10, 0)));
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0), CustomValues.parseDateTime("#datetime(2024-01-01T10:00:00)"));
        final var precise = LocalDateTime.of(2024, 1, 1, 10, 0, 1, 123_456_789);
        assertEquals(precise, CustomValues.parseDateTime(CustomValues.format(precise)));
        assertEquals("#time(09:30)", CustomValues.format(LocalTime.of(9, 30)));
        assertEquals(LocalTime.of(9, 30, 15), CustomValues.parseTime("#time(09:30:15)"));
        assertThrows(DateTimeParseException.class, () -> CustomValues.parseDateTime("#datetime(2024-01-01T10:00Z)"));
    }

    @Test
    public void test_detection_matches_the_server_regex_boundaries() {
        assertFalse(CustomValues.looksCustom("#ab(x)"));
        assertTrue(CustomValues.looksCustom("#abc(x)"));
        assertTrue(CustomValues.looksCustom("#" + "a".repeat(20) + "(x)"));
        assertFalse(CustomValues.looksCustom("#" + "a".repeat(21) + "(x)"));
        assertFalse(CustomValues.looksCustom("#geo(1,\n2)"));
        assertFalse(CustomValues.looksCustom(""));
        assertFalse(CustomValues.looksCustom("geo(1,2)"));
        assertFalse(CustomValues.looksCustom("\\#geo(1,2)"));
    }
}
