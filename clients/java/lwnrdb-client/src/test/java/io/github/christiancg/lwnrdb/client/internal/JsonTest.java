package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.error.MappingException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

public class JsonTest {
    public static final class Exploding {
        public String getValue() {
            throw new IllegalStateException("cannot read");
        }
    }

    private static final Json JSON = new Json(Optional.empty(), 1024, Optional.empty());

    private static String nested(int depth) {
        return "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1);
    }

    @Test
    public void test_a_reply_at_the_depth_limit_parses_and_one_deeper_is_refused() {
        final var large = new Json(Optional.empty(), Json.DEFAULT_MAX_REPLY_BYTES, Optional.empty());
        final var atLimit = nested(Json.MAX_REPLY_DEPTH).getBytes(StandardCharsets.UTF_8);
        assertTrue(large.parseFrame(atLimit, 0, atLimit.length).isObject());
        final var tooDeep = nested(Json.MAX_REPLY_DEPTH + 1).getBytes(StandardCharsets.UTF_8);
        assertThrows(JacksonException.class, () -> large.parseFrame(tooDeep, 0, tooDeep.length));
    }

    @Test
    public void test_a_frame_parsed_from_a_slice_equals_the_frame_parsed_whole() {
        final var text = "xx{\"type\":\"SAVE\",\"n\":1}yy".getBytes(StandardCharsets.UTF_8);
        final var whole = "{\"type\":\"SAVE\",\"n\":1}".getBytes(StandardCharsets.UTF_8);
        assertEquals(JSON.parseFrame(whole, 0, whole.length), JSON.parseFrame(text, 2, whole.length));
    }

    @Test
    public void test_a_frame_must_be_an_object() {
        final var array = "[1]".getBytes(StandardCharsets.UTF_8);
        assertThrows(MappingException.class, () -> JSON.parseFrame(array, 0, array.length));
        assertThrows(MappingException.class, () -> JSON.parseObject("[1]"));
        assertThrows(MappingException.class, () -> JSON.parseObject("{\"a\":"));
    }

    @Test
    public void test_a_string_over_the_reply_limit_is_refused() {
        final var big = ("{\"s\":\"" + "x".repeat(2048) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThrows(JacksonException.class, () -> JSON.parseFrame(big, 0, big.length));
    }

    @Test
    public void test_encode_refuses_non_finite_numbers() {
        final var frame = JSON.values().createObjectNode().put("n", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> JSON.encodeFrame(frame));
    }

    @Test
    public void test_values_round_trip_and_unknown_shapes_become_mapping_errors() {
        assertEquals("{\"a\":1}", JSON.text(JSON.toNode(java.util.Map.of("a", 1))));
        assertThrows(MappingException.class, () -> JSON.fromNode(JSON.toNode("text"), Integer.class));
        assertThrows(MappingException.class, () -> JSON.toNode(new Exploding()));
    }
}
