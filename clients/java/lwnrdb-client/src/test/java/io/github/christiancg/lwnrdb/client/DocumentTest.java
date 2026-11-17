package io.github.christiancg.lwnrdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.error.MappingException;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class DocumentTest {
    private static final Document SAMPLE = Document.of("_id", "o-1", "name", "Ana", "total", 12.5, "count", 3, "vip",
            true, "missing", null, "address", Document.of("city", "Lima", "zip", 15001), "tags", List.of("a", "b"),
            "at", LocalDateTime.of(2024, 1, 1, 10, 0), "opens", LocalTime.of(9, 30), "where", GeoPoint.of(1, 2),
            "embedding", Vector.of(1, 0));

    @Test
    public void test_typed_getters_read_their_types() {
        assertEquals("o-1", SAMPLE.id().orElseThrow());
        assertEquals("Ana", SAMPLE.getString("name").orElseThrow());
        assertEquals(12.5, SAMPLE.getDouble("total").getAsDouble());
        assertEquals(3, SAMPLE.getLong("count").getAsLong());
        assertTrue(SAMPLE.getBoolean("vip").orElseThrow());
        assertEquals("Lima", SAMPLE.getString("address.city").orElseThrow());
        assertEquals(15001, SAMPLE.getLong("address.zip").getAsLong());
        assertEquals("Lima", SAMPLE.getDocument("address").orElseThrow().getString("city").orElseThrow());
        assertEquals(List.of("a", "b"), SAMPLE.getList("tags").orElseThrow());
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0), SAMPLE.getDateTime("at").orElseThrow());
        assertEquals(LocalTime.of(9, 30), SAMPLE.getTime("opens").orElseThrow());
        assertEquals(GeoPoint.of(1, 2), SAMPLE.getGeo("where").orElseThrow());
        assertEquals(Vector.of(1, 0), SAMPLE.getVector("embedding").orElseThrow());
        assertEquals(Instant.parse("2024-01-01T10:00:00Z"), SAMPLE.getInstant("at", ZoneOffset.UTC).orElseThrow());
        assertEquals("#geo(1.0,2.0)", SAMPLE.get("where").orElseThrow());
    }

    @Test
    public void test_missing_null_and_unreachable_paths_are_empty() {
        assertTrue(SAMPLE.getString("nope").isEmpty());
        assertTrue(SAMPLE.getString("missing").isEmpty());
        assertTrue(SAMPLE.getString("name.first").isEmpty());
        assertTrue(SAMPLE.getDouble("nope").isEmpty());
        assertTrue(SAMPLE.getLong("nope").isEmpty());
        assertTrue(SAMPLE.getDateTime("nope").isEmpty());
        assertFalse(SAMPLE.has("missing"));
        assertTrue(SAMPLE.has("address.city"));
    }

    @Test
    public void test_a_wrong_type_is_a_mapping_error() {
        assertThrows(MappingException.class, () -> SAMPLE.getString("total"));
        assertThrows(MappingException.class, () -> SAMPLE.getLong("total"));
        assertThrows(MappingException.class, () -> SAMPLE.getDocument("name"));
        assertThrows(MappingException.class, () -> SAMPLE.getList("name"));
        assertThrows(MappingException.class, () -> SAMPLE.getGeo("name"));
        assertThrows(MappingException.class, () -> SAMPLE.getVector("where"));
        assertThrows(MappingException.class, () -> SAMPLE.getBoolean("name"));
        assertThrows(MappingException.class, () -> SAMPLE.getDouble("name"));
    }

    @Test
    public void test_with_and_without_return_copies() {
        final var changed = SAMPLE.with("name", "Bea").without("vip");
        assertEquals("Ana", SAMPLE.getString("name").orElseThrow());
        assertEquals("Bea", changed.getString("name").orElseThrow());
        assertFalse(changed.has("vip"));
        assertNotEquals(SAMPLE, changed);
        assertEquals(SAMPLE, Document.parse(SAMPLE.toJson()));
        assertEquals(SAMPLE.hashCode(), Document.parse(SAMPLE.toJson()).hashCode());
        assertEquals(SAMPLE.toJson(), SAMPLE.toString());
        assertNotEquals(SAMPLE, "x");
    }

    @Test
    public void test_factories_and_conversions() {
        final var fromMap = Document.from(Map.of("a", 1));
        assertEquals(Set.of("a"), fromMap.fieldNames());
        assertEquals(Map.of("a", 1), fromMap.toMap());
        assertTrue(Document.empty().fieldNames().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Document.of("a"));
        assertThrows(IllegalArgumentException.class, () -> Document.of(1, "a"));
        assertThrows(MappingException.class, () -> Document.parse("[1]"));
    }

    @Test
    public void test_plain_text_that_looks_custom_is_refused_with_its_path() {
        final var error = assertThrows(IllegalArgumentException.class,
                () -> Document.of("nested", Map.of("note", "#geo(1,2)")));
        assertTrue(error.getMessage().contains("note"), error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> assertNotNull(SAMPLE.with("note", "#datetime(2024-01-01T10:00)")));
        assertEquals("\\#geo(1,2)", Document.parse("{\"e\":\"\\\\#geo(1,2)\"}").getString("e").orElseThrow());
    }

    @Test
    public void test_zoned_values_need_a_temporal_zone() {
        assertThrows(IllegalArgumentException.class, () -> Document.of("at", Instant.EPOCH));
    }
}
