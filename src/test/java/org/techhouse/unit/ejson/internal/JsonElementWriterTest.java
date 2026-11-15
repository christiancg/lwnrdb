package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
    private static final int CONCURRENT_WRITERS = 8;
    private static final String MIXED_STRINGS_DOCUMENT = "{\"_id\":\"d1\",\"plain\":\"" + "p".repeat(600)
            + "\",\"quoted\":\"a\\\"b\",\"wide\":\"é中😀\",\"control\":\"x\\ny\",\"decoded\":\"\\u0041BC\","
            + "\"list\":[\"one\",\"tw\\\\o\"]}";
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

    private static byte[] utf8Written(JsonBaseElement element) {
        final var out = Utf8JsonOut.acquire(64);
        try {
            JsonElementWriter.write(element, out);
            return out.toByteArray();
        } finally {
            out.release();
        }
    }

    @Test
    public void test_a_null_string_value_is_written_as_null() {
        final var nullString = new JsonString(null);

        assertEquals("null", written(nullString));
        assertArrayEquals("null".getBytes(StandardCharsets.UTF_8), utf8Written(nullString));
        assertEquals(4, JsonElementWriter.utf8Length(nullString));
    }

    @Test
    public void test_writing_a_document_twice_gives_identical_bytes() {
        final var document = parsed(MIXED_STRINGS_DOCUMENT);
        final var expected = written(parsed(MIXED_STRINGS_DOCUMENT)).getBytes(StandardCharsets.UTF_8);

        final var first = utf8Written(document);
        final var second = utf8Written(document);

        assertArrayEquals(expected, first);
        assertArrayEquals(expected, second);
    }

    @Test
    public void test_concurrent_writers_of_one_document_agree() throws Exception {
        final var document = parsed(MIXED_STRINGS_DOCUMENT);
        final var expected = written(parsed(MIXED_STRINGS_DOCUMENT)).getBytes(StandardCharsets.UTF_8);
        final var start = new CountDownLatch(1);
        final var writers = new ArrayList<Callable<byte[]>>();
        for (var i = 0; i < CONCURRENT_WRITERS; i++) {
            writers.add(() -> {
                start.await();
                return utf8Written(document);
            });
        }
        try (var pool = Executors.newFixedThreadPool(CONCURRENT_WRITERS)) {
            final var results = writers.stream().map(pool::submit).toList();
            start.countDown();
            for (final var result : results) {
                assertArrayEquals(expected, result.get());
            }
        }
    }
}
