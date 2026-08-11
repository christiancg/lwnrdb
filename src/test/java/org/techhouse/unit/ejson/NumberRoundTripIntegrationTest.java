package org.techhouse.unit.ejson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;

public class NumberRoundTripIntegrationTest {
    // A document holding out-of-long-range numbers survives a serialize/parse round trip
    @Test
    public void test_save_then_read_large_number_preserves_value() {
        final var eJson = new EJson();
        final var object = new JsonObject();
        object.addProperty("big", 1e20);
        object.addProperty("huge", 9.223372036854776E18);
        object.addProperty("tiny", 1e-7);
        object.addProperty("max", Double.MAX_VALUE);
        object.addProperty("min", Double.MIN_VALUE);

        final var parsed = eJson.fromJson(eJson.toJson(object), JsonObject.class);

        assertEquals(1e20, parsed.get("big").asJsonNumber().getValue().doubleValue());
        assertEquals(9.223372036854776E18, parsed.get("huge").asJsonNumber().getValue().doubleValue());
        assertEquals(1e-7, parsed.get("tiny").asJsonNumber().getValue().doubleValue());
        assertEquals(Double.MAX_VALUE, parsed.get("max").asJsonNumber().getValue().doubleValue());
        assertEquals(Double.MIN_VALUE, parsed.get("min").asJsonNumber().getValue().doubleValue());
    }

    // Documents written before the formatter change, in Java exponent notation, still parse
    @Test
    public void test_old_java_format_still_parses() {
        final var eJson = new EJson();
        final var parsed = eJson.fromJson("{\"a\":1.0E21,\"b\":4.9E-324,\"c\":1.0E-7}", JsonObject.class);

        assertEquals(1e21, parsed.get("a").asJsonNumber().getValue().doubleValue());
        assertEquals(Double.MIN_VALUE, parsed.get("b").asJsonNumber().getValue().doubleValue());
        assertEquals(1e-7, parsed.get("c").asJsonNumber().getValue().doubleValue());
    }

    // The new exponential rendering is itself re-readable by the lexer
    @Test
    public void test_new_format_round_trips_through_the_lexer() {
        final var eJson = new EJson();
        final var object = new JsonObject();
        object.addProperty("a", 1e21);
        object.addProperty("b", Double.MIN_VALUE);

        final var json = eJson.toJson(object);
        assertEquals("{\"a\":1e+21,\"b\":5e-324}", json);

        final var parsed = eJson.fromJson(json, JsonObject.class);
        assertEquals(1e21, parsed.get("a").asJsonNumber().getValue().doubleValue());
        assertEquals(Double.MIN_VALUE, parsed.get("b").asJsonNumber().getValue().doubleValue());
    }
}
