package org.techhouse.unit.ejson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class EJsonEscapingTest {
    private static final EJson EJSON = new EJson();

    private static String roundTrip(String value) {
        final var source = new JsonObject();
        source.add("v", new JsonString(value));
        final var json = EJSON.toJson(source);
        return EJSON.fromJson(json, JsonObject.class).get("v").asJsonString().getValue();
    }

    // Values containing the characters that used to produce invalid JSON survive a write/read cycle
    @Test
    public void test_round_trip_special_characters() {
        assertEquals("he said \"hi\"", roundTrip("he said \"hi\""));
        assertEquals("C:\\tmp", roundTrip("C:\\tmp"));
        assertEquals("line1\nline2", roundTrip("line1\nline2"));
        assertEquals("a\tb", roundTrip("a\tb"));
        assertEquals("a\r\bb\f", roundTrip("a\r\bb\f"));
        assertEquals("a\u0001b", roundTrip("a\u0001b"));
    }

    // Non-ASCII text including a surrogate pair round-trips
    @Test
    public void test_round_trip_unicode() {
        assertEquals("café", roundTrip("café"));
        assertEquals("\uD83D\uDE00", roundTrip("\uD83D\uDE00"));
    }

    // A key containing a quote is escaped and read back
    @Test
    public void test_round_trip_key_with_quote() {
        final var source = new JsonObject();
        source.add("quo\"te", new JsonString("v"));
        final var json = EJSON.toJson(source);
        assertEquals("{\"quo\\\"te\":\"v\"}", json);
        assertEquals("v", EJSON.fromJson(json, JsonObject.class).get("quo\"te").asJsonString().getValue());
    }

    // Escapes are decoded while reading
    @Test
    public void test_reader_decodes_escapes() {
        assertEquals("A\uD83D\uDE00", EJSON.fromJson("{\"v\":\"\\u0041\\uD83D\\uDE00\"}", JsonObject.class).get("v")
                .asJsonString().getValue());
        assertEquals("a/b", EJSON.fromJson("{\"v\":\"a\\/b\"}", JsonObject.class).get("v").asJsonString().getValue());
    }

    // An unrecognised escape is kept verbatim rather than failing the read
    @Test
    public void test_reader_tolerates_unknown_escape() {
        assertEquals("C:\\qmp",
                EJSON.fromJson("{\"v\":\"C:\\qmp\"}", JsonObject.class).get("v").asJsonString().getValue());
        assertEquals("\\u00", EJSON.fromJson("{\"v\":\"\\u00\"}", JsonObject.class).get("v").asJsonString().getValue());
        assertEquals("\\uZZZZ",
                EJSON.fromJson("{\"v\":\"\\uZZZZ\"}", JsonObject.class).get("v").asJsonString().getValue());
    }

    // A custom type keeps its marker syntax through escaping
    @Test
    public void test_custom_type_unaffected() {
        final var json = "{\"g\":\"#geo(1.5,2.5)\"}";
        assertEquals(json, EJSON.toJson(EJSON.fromJson(json, JsonObject.class)));
    }
}
