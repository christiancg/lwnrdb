package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.CharJsonOut;
import org.techhouse.ejson.internal.JsonStrings;
import org.techhouse.ejson.internal.Utf8CountingJsonOut;
import org.techhouse.ejson.internal.Utf8JsonOut;

public class Utf8EscapingTest {
    private static final String ALPHABET = "aZ09 \"\\/\b\f\n\r\t\u0000\u001f\u007f\u2028\u2029éü中😀\ud800\udc00\ud800\udc00\udbff";
    private static final int STRINGS = 10_000;

    private static byte[] fused(String text) {
        final var out = Utf8JsonOut.acquire(1);
        JsonStrings.appendEscaped(out, text);
        return out.toByteArray();
    }

    private static byte[] runBased(String text) {
        final var builder = new StringBuilder();
        new CharJsonOut(builder).appendEscaped(text);
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static long counted(String text) {
        final var out = new Utf8CountingJsonOut();
        JsonStrings.appendEscaped(out, text);
        return out.length();
    }

    private static String randomText(Random random) {
        final var builder = new StringBuilder();
        final var length = random.nextInt(40);
        for (var i = 0; i < length; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    @Test
    public void test_fused_utf8_matches_char_out_on_random_strings() {
        final var random = new Random(2026);
        for (var round = 0; round < STRINGS; round++) {
            final var text = randomText(random);
            final var expected = runBased(text);
            assertArrayEquals(expected, fused(text), text);
            assertEquals(expected.length, counted(text), text);
        }
    }

    @Test
    public void test_buffer_grows_inside_an_escape() {
        final var text = "\"".repeat(300) + "\u0001".repeat(100);

        assertArrayEquals(runBased(text), fused(text));
    }

    @Test
    public void test_lone_high_surrogate_as_the_last_char() {
        assertArrayEquals("x\\ud800".getBytes(StandardCharsets.UTF_8), fused("x\ud800"));
        assertArrayEquals("\\udc00y".getBytes(StandardCharsets.UTF_8), fused("\udc00y"));
    }

    @Test
    public void test_pair_split_across_the_initial_capacity() {
        final var text = "a".repeat(255) + "😀" + "b".repeat(300);

        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), fused(text));
    }

    @Test
    public void test_raw_characters_stay_raw() {
        final var text = "\u007f\u2028\u2029é中";

        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), fused(text));
    }

    @Test
    public void test_an_empty_string_and_a_large_one() {
        assertArrayEquals(new byte[0], fused(""));
        final var large = "€\"x".repeat(400_000);
        assertArrayEquals(runBased(large), fused(large));
    }

    @Test
    public void test_null_is_written_as_the_null_literal() {
        assertArrayEquals("null".getBytes(StandardCharsets.UTF_8), fused(null));
    }
}
