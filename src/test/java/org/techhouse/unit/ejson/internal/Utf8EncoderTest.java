package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.Utf8Encoder;

public class Utf8EncoderTest {
    private static final long SEED = 0x5EED_0006L;
    private static final int SAMPLES = 20_000;
    private static final int MAX_LENGTH = 40;

    private static char randomChar(Random random) {
        return switch (random.nextInt(7)) {
            case 0 -> (char) random.nextInt(0x80);
            case 1 -> (char) (0x80 + random.nextInt(0x800 - 0x80));
            case 2 -> (char) (0x800 + random.nextInt(0xD800 - 0x800));
            case 3 -> (char) (0xE000 + random.nextInt(0x10000 - 0xE000));
            case 4 -> (char) (Character.MIN_HIGH_SURROGATE + random.nextInt(0x400));
            case 5 -> (char) (Character.MIN_LOW_SURROGATE + random.nextInt(0x400));
            default -> "a\"\\\n界😀".charAt(random.nextInt(7));
        };
    }

    private static String randomString(Random random) {
        final var length = random.nextInt(MAX_LENGTH + 1);
        final var out = new StringBuilder(length + 2);
        for (var i = 0; i < length; i++) {
            if (random.nextInt(5) == 0) {
                out.appendCodePoint(Character.MIN_SUPPLEMENTARY_CODE_POINT
                        + random.nextInt(Character.MAX_CODE_POINT - Character.MIN_SUPPLEMENTARY_CODE_POINT + 1));
            } else {
                out.append(randomChar(random));
            }
        }
        return out.toString();
    }

    @Test
    public void utf8EncoderMatchesStringGetBytes() {
        final var random = new Random(SEED);
        for (var i = 0; i < SAMPLES; i++) {
            final var text = randomString(random);
            final var expected = text.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(expected, Utf8Encoder.encode(text), () -> describe(text));
            assertArrayEquals(expected, Utf8Encoder.encode(new StringBuilder(text)), () -> describe(text));
        }
    }

    @Test
    public void test_unpaired_surrogates_become_question_marks() {
        for (final var text : new String[]{"\uD800", "\uDC00", "a\uD800", "\uDC00a", "\uD800𐀀", "\uDC00\uD800",
                "x\uDBFFy\uDFFFz", ""}) {
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), Utf8Encoder.encode(text), describe(text));
        }
    }

    @Test
    public void test_a_frame_matches_what_the_buffered_writer_put_on_the_wire() throws IOException {
        final var random = new Random(SEED + 1);
        for (var i = 0; i < SAMPLES / 10; i++) {
            final var text = randomString(random);
            final var legacy = new ByteArrayOutputStream();
            final var writer = new BufferedWriter(new OutputStreamWriter(legacy, StandardCharsets.UTF_8));
            writer.write(text);
            writer.newLine();
            writer.flush();
            final var encoded = new ByteArrayOutputStream();
            encoded.write(Utf8Encoder.encode(text));
            encoded.write(System.lineSeparator().getBytes(StandardCharsets.US_ASCII));
            assertArrayEquals(legacy.toByteArray(), encoded.toByteArray(), describe(text));
        }
    }

    private static String describe(String text) {
        final var out = new StringBuilder();
        text.chars().forEach(c -> out.append(String.format("%04X ", c)));
        return out.toString();
    }
}
