package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.CharJsonOut;
import org.techhouse.ejson.internal.JsonStrings;
import org.techhouse.ejson.internal.Utf8CountingJsonOut;
import org.techhouse.ejson.internal.Utf8JsonOut;
import org.techhouse.test.TestUtils;

public class Utf8EscapingTest {
    private static final String ALPHABET = "aZ09 \"\\/\b\f\n\r\t\u0000\u001f\u007f\u2028\u2029éü中😀\ud800\udc00\ud800\udc00\udbff";
    private static final int STRINGS = 10_000;
    private static final char HIGH = (char) 0xD83D;
    private static final char LOW = (char) 0xDE00;
    private static final int[][] SPECIAL_CHARS = {{'"'}, {'\\'}, {'/'}, {'\b'}, {'\f'}, {'\n'}, {'\r'}, {'\t'}, {0x00},
            {0x01}, {0x1F}, {0x7F}, {0x80}, {0xE9}, {0x7FF}, {0x800}, {0x4E2D}, {0x2028}, {0x2029}, {0xFFFF},
            {HIGH, LOW}, {0xD800, 0xDC00}, {0xDBFF, 0xDFFF}, {0xD800}, {0xDBFF}, {0xDC00}, {0xDFFF}};
    private static final String[] SPECIALS = specials();
    private static final String NON_ASCII = new String(new char[]{0xE9, 0x4E2D, HIGH, LOW, 0x2028});
    private static final int[] PREFIX_LENGTHS = {0, 1, 2, 3, 31, 63, 64, 127, 128, 254, 255, 256, 257, 510, 511, 512,
            513, 1023, 1024, 1025, 4097};
    private static final int PROPERTY_ROUNDS = 5_000;
    private static final int LONGEST_PROPERTY_PREFIX = 1_100;
    private static final int MOST_SPECIALS = 6;
    private static final int SLACK_STEPS = 4;

    private static String[] specials() {
        final var specials = new String[SPECIAL_CHARS.length];
        for (var i = 0; i < specials.length; i++) {
            final var chars = new char[SPECIAL_CHARS[i].length];
            for (var j = 0; j < chars.length; j++) {
                chars[j] = (char) SPECIAL_CHARS[i][j];
            }
            specials[i] = new String(chars);
        }
        return specials;
    }

    private static byte[] fused(String text) {
        final var out = Utf8JsonOut.acquire(1);
        JsonStrings.appendEscaped(out, text);
        return out.toByteArray();
    }

    private static byte[] fusedAtTheCapacityEdge(String text, int slack)
            throws NoSuchFieldException, IllegalAccessException {
        final var out = Utf8JsonOut.acquire(1);
        final var capacity = TestUtils.getPrivateField(out, "buffer", byte[].class).length;
        final var filler = Math.max(0, capacity - text.length() - slack);
        out.append("x".repeat(filler), 0, filler);
        JsonStrings.appendEscaped(out, text);
        final var written = out.toByteArray();
        return Arrays.copyOfRange(written, filler, written.length);
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

    private static void assertMatchesTheRunEscaper(String text) {
        final var expected = runBased(text);
        assertArrayEquals(expected, fused(text), text);
        assertEquals(expected.length, counted(text), text);
    }

    private static String safeAscii(Random random, int length) {
        final var builder = new StringBuilder(length);
        for (var i = 0; i < length; i++) {
            final var c = (char) (' ' + random.nextInt(0x7f - ' '));
            builder.append(c == '"' || c == '\\' ? 'q' : c);
        }
        return builder.toString();
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

    @Test
    public void test_a_long_safe_prefix_then_an_escape_matches_the_run_escaper() {
        for (var prefixLength : PREFIX_LENGTHS) {
            final var prefix = "p".repeat(prefixLength);
            for (var special : SPECIALS) {
                assertMatchesTheRunEscaper(prefix + special);
                assertMatchesTheRunEscaper(prefix + special + "tail" + special);
                assertMatchesTheRunEscaper(prefix + special.repeat(300));
            }
        }
    }

    @Test
    public void test_non_ascii_after_a_safe_prefix() {
        for (var prefixLength : PREFIX_LENGTHS) {
            final var text = "a".repeat(prefixLength) + NON_ASCII + "b".repeat(prefixLength);
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), fused(text), text);
            assertMatchesTheRunEscaper(text);
        }
    }

    @Test
    public void test_a_surrogate_pair_straddling_the_prefix_end() throws NoSuchFieldException, IllegalAccessException {
        for (var prefixLength : PREFIX_LENGTHS) {
            final var prefix = "s".repeat(prefixLength);
            final var paired = prefix + HIGH + LOW + "ttt";
            final var lone = prefix + HIGH;
            final var swapped = prefix + LOW + HIGH + "t";
            for (var text : new String[]{paired, lone, swapped}) {
                assertMatchesTheRunEscaper(text);
                for (var slack = 0; slack < SLACK_STEPS; slack++) {
                    assertArrayEquals(runBased(text), fusedAtTheCapacityEdge(text, slack), text);
                }
            }
            assertArrayEquals(paired.getBytes(StandardCharsets.UTF_8), fused(paired));
        }
    }

    @Test
    public void test_every_character_class_at_every_prefix_and_capacity_position()
            throws NoSuchFieldException, IllegalAccessException {
        final var random = new Random(1103);
        for (var round = 0; round < PROPERTY_ROUNDS; round++) {
            final var text = new StringBuilder(safeAscii(random, random.nextInt(LONGEST_PROPERTY_PREFIX)));
            final var specials = 1 + random.nextInt(MOST_SPECIALS);
            for (var i = 0; i < specials; i++) {
                text.append(SPECIALS[random.nextInt(SPECIALS.length)]).append(safeAscii(random, random.nextInt(4)));
            }
            final var generated = text.toString();
            final var expected = runBased(generated);
            assertArrayEquals(expected, fused(generated), generated);
            assertArrayEquals(expected, fusedAtTheCapacityEdge(generated, random.nextInt(SLACK_STEPS)), generated);
            assertEquals(expected.length, counted(generated), generated);
        }
    }
}
