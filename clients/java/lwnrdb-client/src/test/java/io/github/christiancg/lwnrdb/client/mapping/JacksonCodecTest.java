package io.github.christiancg.lwnrdb.client.mapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.christiancg.lwnrdb.client.ClientSettings;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.error.MappingException;
import io.github.christiancg.lwnrdb.client.internal.Json;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

public class JacksonCodecTest {
    private static final Json JSON = Json.defaults();
    private static final Json ZONED = new Json(
            ClientSettings.builder().host("localhost", 1).temporalZone(ZoneOffset.UTC).build());

    public record Place(String id, LocalDateTime openedAt, LocalTime opensAt, GeoPoint location, Vector embedding,
            Document extra) {
    }

    public record Annotated(@JsonProperty("_id") String key, @LwnrdbVector double[] embedding) {
    }

    public static class Bean {
        private String id;
        private String name;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Child extends Bean {
        public int age;
    }

    public record Moments(Instant instant, OffsetDateTime offset, ZonedDateTime zoned) {
    }

    @Test
    public void test_a_record_with_custom_values_round_trips_and_id_becomes_the_primary_key() {
        final var codec = new JacksonCodec<>(Place.class, JSON);
        final var place = new Place("p-1", LocalDateTime.of(2024, 1, 1, 10, 0), LocalTime.of(9, 30), GeoPoint.of(1, 2),
                Vector.of(0.5, 1), Document.of("k", 1));
        final var document = codec.encode(place);
        assertEquals("p-1", document.id().orElseThrow());
        assertFalse(document.has("id"));
        assertEquals("#datetime(2024-01-01T10:00)", document.getString("openedAt").orElseThrow());
        assertEquals("#geo(1.0,2.0)", document.getString("location").orElseThrow());
        assertEquals("#vector(0.5,1.0)", document.getString("embedding").orElseThrow());
        assertEquals(place.embedding(), codec.decode(document).embedding());
        assertEquals(place.openedAt(), codec.decode(document).openedAt());
        assertEquals(place.extra(), codec.decode(document).extra());
        assertEquals("p-1", codec.decode(document).id());
    }

    @Test
    public void test_a_null_id_is_left_out_and_an_annotated_id_maps_directly() {
        final var codec = new JacksonCodec<>(Place.class, JSON);
        assertFalse(codec.encode(new Place(null, null, null, null, null, null)).has("_id"));
        final var annotated = new JacksonCodec<>(Annotated.class, JSON);
        final var document = annotated.encode(new Annotated("k1", new double[]{1, 2}));
        assertEquals("k1", document.id().orElseThrow());
        assertEquals("#vector(1.0,2.0)", document.getString("embedding").orElseThrow());
        assertArrayEquals(new double[]{1, 2}, annotated.decode(document).embedding());
    }

    @Test
    public void test_beans_and_subclasses_alias_an_inherited_id() {
        final var codec = new JacksonCodec<>(Child.class, JSON);
        final var child = new Child();
        child.setId("c1");
        child.setName("Ana");
        child.age = 3;
        final var document = codec.encode(child);
        assertEquals("c1", document.id().orElseThrow());
        assertEquals("c1", codec.decode(document).getId());
    }

    @Test
    public void test_mapping_failures_name_the_document() {
        final var codec = new JacksonCodec<>(Place.class, JSON);
        final var error = assertThrows(MappingException.class,
                () -> codec.decode(Document.parse("{\"_id\":\"bad\",\"location\":\"#geo(500,1)\"}")));
        assertTrue(error.getMessage().contains("bad"), error.getMessage());
        assertThrows(MappingException.class, () -> new JacksonCodec<>(String.class, JSON).encode("x"));
    }

    @Test
    public void test_zoned_values_need_a_zone_and_round_trip_through_it() {
        final var moments = new Moments(Instant.parse("2024-01-01T10:00:00Z"),
                OffsetDateTime.parse("2024-01-01T12:00:00+02:00"), ZonedDateTime.parse("2024-01-01T10:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> new JacksonCodec<>(Moments.class, JSON).encode(moments));
        final var codec = new JacksonCodec<>(Moments.class, ZONED);
        final var document = codec.encode(moments);
        assertEquals("#datetime(2024-01-01T10:00)", document.getString("offset").orElseThrow());
        final var decoded = codec.decode(document);
        assertEquals(moments.instant(), decoded.instant());
        assertEquals(moments.offset().toInstant(), decoded.offset().toInstant());
        assertEquals(moments.zoned().toInstant(), decoded.zoned().toInstant());
    }

    @Test
    public void test_a_callers_mapper_is_extended_not_changed() {
        final var mapper = JsonMapper.builder().build();
        final var json = new Json(ClientSettings.builder().host("localhost", 1).objectMapper(mapper).build());
        assertEquals("\"#geo(1.0,2.0)\"", json.text(json.toNode(GeoPoint.of(1, 2))));
        assertTrue(mapper.valueToTree(GeoPoint.of(1, 2)).isObject());
        assertThrows(IllegalArgumentException.class, () -> json.fromNode(json.toNode("x"), Document.class));
    }
}
