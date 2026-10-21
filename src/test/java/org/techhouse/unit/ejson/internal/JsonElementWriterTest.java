package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.CharJsonOut;
import org.techhouse.ejson.internal.JsonElementWriter;
import org.techhouse.ejson.internal.Utf8JsonOut;
import org.techhouse.ops.resp.AggregateResponse;

public class JsonElementWriterTest {
    private final EJson eJson = new EJson();

    @BeforeEach
    public void setUp() {
        new EJson();
    }

    private static String written(JsonBaseElement element) {
        final var out = new CharJsonOut();
        JsonElementWriter.write(element, out);
        return out.toString();
    }

    private JsonObject parsed(String json) {
        return eJson.fromJson(json, JsonObject.class);
    }

    @Test
    public void test_each_json_type_is_written_like_the_adapter_wrote_it() {
        assertEquals("null", written(JsonNull.INSTANCE));
        assertEquals("true", written(JsonBoolean.of(true)));
        assertEquals("false", written(JsonBoolean.of(false)));
        assertEquals("\"plain\"", written(new JsonString("plain")));
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0000\\u001f/é中😀\"",
                written(new JsonString("a\"b\\c\n\r\t\b\f\u0000\u001f/é中😀")));
        assertEquals("3", written(new JsonNumber(3)));
        assertEquals("-12", written(new JsonNumber(-12)));
        assertEquals("1.5", written(new JsonNumber(1.5)));
        assertEquals("0", written(new JsonNumber(-0.0)));
        assertEquals("null", written(new JsonNumber(Double.NaN)));
        assertEquals("null", written(new JsonNumber(Double.POSITIVE_INFINITY)));
        assertEquals("{}", written(new JsonObject()));
        assertEquals("[]", written(new JsonArray()));
    }

    @Test
    public void test_custom_values_are_written_as_their_string_spelling() {
        final var document = parsed("{\"g\":\"#geo(10.5,20.5)\",\"v\":\"#vector(1.5,2.5)\"}");

        assertEquals("{\"g\":\"#geo(10.5,20.5)\",\"v\":\"#vector(1.5,2.5)\"}", written(document));
    }

    @Test
    public void test_nested_objects_and_arrays_keep_member_order() {
        final var json = "{\"z\":[1,\"x\",null,{\"k\":[true,false,[]]}],\"a\":{\"b\":{}},\"m\\\"q\":\"v\"}";

        assertEquals(json, written(parsed(json)));
    }

    @Test
    public void test_rows_with_different_shapes_and_types_for_one_key() {
        final var rows = new ArrayList<JsonObject>();
        for (final var value : List.of("\"text\"", "7", "2.5", "true", "null", "{\"n\":[1]}", "[1,\"a\"]",
                "\"#geo(1.5,2.5)\"")) {
            rows.add(parsed("{\"_id\":\"r" + rows.size() + "\",\"v\":" + value + "}"));
        }
        rows.add(parsed("{\"_id\":\"missing\"}"));

        final var response = new AggregateResponse("m", rows);

        final var eachAlone = rows.stream().map(JsonElementWriterTest::written).toList();
        assertEquals(
                "{\"results\":[" + String.join(",", eachAlone)
                        + "],\"type\":\"AGGREGATE\",\"status\":\"OK\",\"message\":\"m\",\"errorCode\":null}",
                eJson.toJson(response));
    }

    @Test
    public void test_utf8_and_char_outputs_agree() {
        final var document = parsed("{\"s\":\"é中😀\\n\",\"n\":[1,2.5,-3],\"o\":{\"k\":null,\"b\":true},\"e\":[]}");
        final var utf8 = Utf8JsonOut.acquire(16);
        JsonElementWriter.write(document, utf8);

        assertArrayEquals(written(document).getBytes(StandardCharsets.UTF_8), utf8.toByteArray());
        utf8.release();
    }
}
