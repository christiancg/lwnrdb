package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.CharJsonOut;
import org.techhouse.ejson.internal.JsonStrings;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.ejson.internal.Utf8Encoder;
import org.techhouse.ejson.internal.Utf8JsonOut;

public class JsonOutEquivalenceTest {
    private static final String ALPHABET = "aZ09 \"\\/\b\f\n\r\t\u0000\u001f\u007féü中😀𐀀\u001f";
    private final EJson eJson = new EJson();

    private record Pojo(String text, int count, double ratio, Long big, List<String> tags) {
    }

    @BeforeEach
    public void setUp() {
        new EJson();
    }

    private static String randomText(Random random, int maxLength) {
        final var builder = new StringBuilder();
        final var length = random.nextInt(maxLength);
        for (var i = 0; i < length; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    private static JsonObject randomObject(Random random, int depth) {
        final var object = new JsonObject();
        final var members = random.nextInt(6);
        for (var i = 0; i < members; i++) {
            final var key = randomText(random, 6);
            object.add(key, switch (random.nextInt(depth > 0 ? 7 : 5)) {
                case 0 -> new JsonString(randomText(random, 12));
                case 1 -> new JsonNumber(random.nextInt(2000) - 1000);
                case 2 -> new JsonNumber(random.nextDouble() * Math.pow(10, random.nextInt(50) - 25));
                case 3 -> JsonBoolean.of(random.nextBoolean());
                case 4 -> JsonNull.INSTANCE;
                case 5 -> randomObject(random, depth - 1);
                default -> {
                    final var array = new JsonArray();
                    array.add(new JsonString(randomText(random, 4)));
                    array.add(randomObject(random, depth - 1));
                    yield array;
                }
            });
        }
        return object;
    }

    @Test
    public void directUtf8BytesEqualTheEncodedStringForRandomDocuments() {
        final var random = new Random(20261014L);
        for (var sample = 0; sample < 2000; sample++) {
            final var object = randomObject(random, 3);
            assertArrayEquals(Utf8Encoder.encode(eJson.toJson(object)), eJson.toJsonBytes(object, 4),
                    eJson.toJson(object));
        }
    }

    @Test
    public void aLineIsTheDocumentPlusItsTerminator() {
        final var object = randomObject(new Random(3L), 3);
        assertArrayEquals(Utf8Encoder.encode(eJson.toJson(object) + "\r\n"), eJson.toJsonLine(object, 64, "\r\n"));
    }

    @Test
    public void edgeNumbersRenderTheSameThroughBothSinks() {
        final var object = new JsonObject();
        final var values = new double[]{-0.0, 0.0, 5e-324, 1e21, 9007199254740992d, 9007199254740993d,
                -9007199254740991d, 0.1 + 0.2, 1.5, Double.MAX_VALUE};
        for (var i = 0; i < values.length; i++) {
            object.addProperty("n" + i, values[i]);
        }
        object.addProperty("long", Long.MAX_VALUE);
        object.addProperty("int", Integer.MIN_VALUE);
        object.addProperty("nan", Double.NaN);
        object.addProperty("inf", Double.NEGATIVE_INFINITY);

        assertArrayEquals(Utf8Encoder.encode(eJson.toJson(object)), eJson.toJsonBytes(object, 16));
    }

    @Test
    public void reflectedObjectsRenderTheSameThroughBothSinks() {
        final var pojo = new Pojo("é\"x", 3, 2.5, Long.MIN_VALUE, List.of("a", "b\n"));

        final var text = eJson.toJson(pojo);

        assertArrayEquals(Utf8Encoder.encode(text), eJson.toJsonBytes(pojo, 8));
    }

    @Test
    public void appendEscapedToAJsonOutMatchesTheStringBuilderOverload() {
        final var random = new Random(42L);
        for (var sample = 0; sample < 20_000; sample++) {
            final var text = randomText(random, 24);
            final var expected = new StringBuilder("p:");
            JsonStrings.appendEscaped(expected, text);
            final var chars = new CharJsonOut(new StringBuilder("p:"));
            JsonStrings.appendEscaped(chars, text);
            final var bytes = Utf8JsonOut.acquire(8);
            try {
                bytes.append("p:");
                JsonStrings.appendEscaped(bytes, text);
                assertEquals(expected.toString(), chars.toString(), text);
                assertArrayEquals(expected.toString().getBytes(StandardCharsets.UTF_8), bytes.toByteArray(), text);
            } finally {
                bytes.release();
            }
        }
        final var nothing = new CharJsonOut();
        JsonStrings.appendEscaped(nothing, null);
        assertEquals("null", nothing.toString());
    }

    @Test
    public void appendJsMatchesToJsString() {
        final var random = new Random(7L);
        final var values = new double[]{-0.0, 0.0, 5e-324, 1e21, -1e21, 9007199254740991d, 9007199254740992d,
                -9007199254740993d, 0.1 + 0.2, Long.MAX_VALUE, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY};
        for (final var value : values) {
            assertAppendJs(value);
        }
        assertRandomAppendJs(random);
    }

    private static void assertRandomAppendJs(Random random) {
        for (var sample = 0; sample < 10_000; sample++) {
            assertAppendJs(random.nextLong() / Math.pow(10, random.nextInt(30)));
        }
    }

    private static void assertAppendJs(double value) {
        final var out = new CharJsonOut();
        NumberFormatter.appendJs(value, out);
        assertEquals(NumberFormatter.toJsString(value), out.toString(), Double.toString(value));
    }
}
