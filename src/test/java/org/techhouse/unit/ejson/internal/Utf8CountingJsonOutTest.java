package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
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
import org.techhouse.ejson.internal.JsonElementWriter;
import org.techhouse.ejson.internal.Utf8CountingJsonOut;
import org.techhouse.ops.EntrySizeGuard;

public class Utf8CountingJsonOutTest {
    private static final String ALPHABET = "aZ09 \"\\/\b\f\n\r\t\u0000\u001f\u007féüÿ߿ࠀ中￿😀𐀀𐀀😀";
    private static final String LONE_SURROGATES = "𐀀x\ud800y\udc00\ud800𐀀";
    private final EJson eJson = new EJson();

    @BeforeEach
    public void setUp() {
        new EJson();
    }

    private static String randomText(Random random, int maxLength) {
        final var builder = new StringBuilder();
        final var length = random.nextInt(maxLength);
        for (var i = 0; i < length; i++) {
            builder.append(random.nextInt(8) == 0
                    ? LONE_SURROGATES.charAt(random.nextInt(LONE_SURROGATES.length()))
                    : ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    private static JsonBaseElement randomElement(Random random, int depth) {
        return switch (random.nextInt(depth > 0 ? 8 : 5)) {
            case 0 -> new JsonString(randomText(random, 14));
            case 1 -> new JsonNumber(random.nextInt(2000) - 1000);
            case 2 -> new JsonNumber(random.nextDouble() * Math.pow(10, random.nextInt(50) - 25));
            case 3 -> JsonBoolean.of(random.nextBoolean());
            case 4 -> JsonNull.INSTANCE;
            case 5 -> {
                final var object = new JsonObject();
                for (var i = random.nextInt(5); i > 0; i--) {
                    object.add(randomText(random, 6), randomElement(random, depth - 1));
                }
                yield object;
            }
            case 6 -> {
                final var array = new JsonArray();
                for (var i = random.nextInt(5); i > 0; i--) {
                    array.add(randomElement(random, depth - 1));
                }
                yield array;
            }
            default -> new JsonNumber(random.nextLong());
        };
    }

    @Test
    public void test_the_count_equals_utf8_length_of_the_written_text() {
        final var random = new Random(20261020L);
        for (var round = 0; round < 3000; round++) {
            final var element = randomElement(random, 3);

            assertEquals(EntrySizeGuard.utf8Length(eJson.toJson(element)), JsonElementWriter.utf8Length(element),
                    "round " + round);
        }
    }

    @Test
    public void test_a_surrogate_pair_split_across_two_chars_counts_four_bytes() {
        final var out = new Utf8CountingJsonOut();
        out.append('\ud83d');
        out.append('\ude00');

        assertEquals(4, out.length());
    }

    @Test
    public void test_a_lone_high_surrogate_counts_three_bytes_wherever_it_ends_up() {
        final var atEnd = new Utf8CountingJsonOut();
        atEnd.append('\ud83d');
        final var beforeDigits = new Utf8CountingJsonOut();
        beforeDigits.append('\ud83d');
        beforeDigits.appendLong(-42);

        assertEquals(3, atEnd.length());
        assertEquals(6, beforeDigits.length());
    }

    @Test
    public void test_appended_longs_count_their_decimal_length() {
        for (final var value : new long[]{0, 7, -7, 10, 99, 100, 123456789012345L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            final var out = new Utf8CountingJsonOut();
            out.appendLong(value);

            assertEquals(Long.toString(value).length(), out.length(), Long.toString(value));
        }
    }

    @Test
    public void test_mark_and_reset_restore_the_count() {
        final var out = new Utf8CountingJsonOut();
        out.append("abc");
        final var mark = out.mark();
        out.append("é中😀");
        out.append('\ud83d');
        out.reset(mark);

        assertEquals(3, out.length());
        out.append("d");
        assertEquals(4, out.length());
    }
}
