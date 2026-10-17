package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.MalformedJsonException;
import org.techhouse.ejson.exceptions.MissingEndOfStringException;
import org.techhouse.ejson.exceptions.UnexpectedCharacterException;
import org.techhouse.ejson.internal.JsonReader;
import org.techhouse.ioc.IocContainer;
import org.techhouse.support.FuzzDocuments;
import org.techhouse.support.LegacyJsonReader;
import org.techhouse.support.ParserOutcomes;

public class JsonParserEquivalenceTest {
    private static final int SEEDED_DOCUMENTS = 5000;
    private static final int CORRUPTIONS = 20_000;
    private static final String CORRUPTING_CHARACTERS = "{}[],:\"\\ 1-.e#@tnf";

    @BeforeAll
    public static void registerCustomTypes() {
        IocContainer.get(EJson.class);
    }

    private static List<String> fixtures() {
        return List.of("{}", "[]", "{\"a\":1}", "{\"name\":\"John\",\"age\":30}", "{\"a\":[1,2,3],\"b\":\"text\"}",
                "{\"outer\":{\"inner\":{\"key\":\"value\"}}}", "{\"a\":[],\"b\":{},\"c\":[[]]}", "{ \"a\" :\t1\n}",
                "[\b1\r]", "{\"a\":1}  \n", "{\"stringArray\": [\"value1\",\"value2\"]}",
                "{\"t\":\"#time(10:30:00)\",\"g\":\"#geo(40.0,-74.0)\",\"v\":\"#vector(1,2,3)\"}",
                "{\"d\":\"#datetime(2024-01-01T10:00:00)\",\"x\":\"#notacustomtype\"}",
                "{\"e\":\"caf\\u00e9 \\u65e5 \\ud800 \\uzzzz \\u00 \\q \\/ \\b\\f\\n\\r\\t\\\"\\\\\"}",
                "{\"#geo(1,2)\":true,\"\":null,\"dup\":1,\"dup\":2}", "{\"n\":[1e5,1E5,1e+5,1e-5,1.5e3,-0,-0.0]}",
                "{\"big\":1.7976931348623157e308,\"tiny\":-4.9E-324,\"int\":2147483647,\"long\":2147483648}",
                "{\"esc\\\"key\":1,\"k\\u0041\":2}", "[{\"a\":[{\"b\":[1,2,{\"c\":\"d\"}]}]}]",
                "{\"k\":\"" + "x".repeat(70) + "\",\"" + "y".repeat(70) + "\":1}");
    }

    private static void assertSameOutcome(String input) {
        final var length = input.length();
        assertEquals(ParserOutcomes.legacy(input, Integer.MAX_VALUE, Integer.MAX_VALUE),
                ParserOutcomes.current(input, Integer.MAX_VALUE, Integer.MAX_VALUE), input);
        final var budget = Math.max(1, length / Globals.MIN_BYTES_PER_REQUEST_TOKEN);
        assertEquals(ParserOutcomes.legacy(input, Globals.MAX_REQUEST_NESTING_DEPTH, budget),
                ParserOutcomes.current(input, Globals.MAX_REQUEST_NESTING_DEPTH, budget), input);
        assertEquals(ParserOutcomes.legacy(input, 2, 12), ParserOutcomes.current(input, 2, 12), input);
        assertSameOutcomeFromBytes(input.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertSameOutcomeFromBytes(byte[] utf8) {
        final var decoded = new String(utf8, StandardCharsets.UTF_8);
        final var budget = Math.max(1, decoded.length() / Globals.MIN_BYTES_PER_REQUEST_TOKEN);
        assertEquals(ParserOutcomes.legacy(decoded, Integer.MAX_VALUE, Integer.MAX_VALUE),
                ParserOutcomes.currentFromBytes(utf8, Integer.MAX_VALUE, Integer.MAX_VALUE), decoded);
        assertEquals(ParserOutcomes.legacy(decoded, Globals.MAX_REQUEST_NESTING_DEPTH, budget),
                ParserOutcomes.currentFromBytes(utf8, Globals.MAX_REQUEST_NESTING_DEPTH, budget), decoded);
        assertEquals(ParserOutcomes.legacy(decoded, 2, 12), ParserOutcomes.currentFromBytes(utf8, 2, 12), decoded);
    }

    @Test
    public void malformedUtf8ParsesLikeItsLenientDecode() {
        final var inputs = new ArrayList<byte[]>();
        for (final var fixture : fixtures()) {
            final var utf8 = fixture.getBytes(StandardCharsets.UTF_8);
            inputs.add(java.util.Arrays.copyOf(utf8, Math.max(0, utf8.length - 1)));
        }
        inputs.add(bytesOf("{\"a\":\"caf", 0xC3, "\"}"));
        inputs.add(bytesOf("{\"a\":\"x", 0xE6, 0x97, "\"}"));
        inputs.add(bytesOf("{\"a\":\"x", 0xF0, 0x9F, 0x98, "\"}"));
        inputs.add(bytesOf("{\"a\":", 0xFF, "}"));
        inputs.add(bytesOf("{\"a\":1}", 0xC0, 0x80));
        inputs.add(bytesOf("{\"", 0xC0, 0xAF, "\":1}"));
        inputs.add(bytesOf("{\"a\":\"\\", 0xC3, 0xA9, "x\\u00", 0xE9, "\"}"));
        inputs.add(bytesOf("{\"a\":1e", 0xD9, 0xA3, "}"));
        inputs.add(bytesOf("{\"a\":[1,", 0xE2, 0x82, 0xAC, "]}"));
        inputs.add(bytesOf("{\"\\u00e9", 0xC3, 0xA9, "\":", 0xF0, 0x9F, 0x98, 0x80, "}"));
        for (final var input : inputs) {
            assertSameOutcomeFromBytes(input);
        }
    }

    private static byte[] bytesOf(Object... parts) {
        final var out = new java.io.ByteArrayOutputStream();
        for (final var part : parts) {
            if (part instanceof String text) {
                out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
            } else {
                out.write((Integer) part);
            }
        }
        return out.toByteArray();
    }

    @Test
    public void parsesEveryFixtureLikeTheLegacyReader() {
        final var eJson = IocContainer.get(EJson.class);
        final var inputs = new ArrayList<>(fixtures());
        final var documents = new FuzzDocuments(20_261_013L);
        for (var index = 0; index < SEEDED_DOCUMENTS; index++) {
            inputs.add(documents.document(index));
        }
        var parsed = 0;
        for (final var input : inputs) {
            assertSameOutcome(input);
            final var current = ParserOutcomes.current(input, Integer.MAX_VALUE, Integer.MAX_VALUE);
            if (current.startsWith("OK")) {
                parsed++;
                final var pageLine = eJson.toJson(ParserOutcomes.legacyTree(input));
                assertSameOutcome(pageLine);
                assertTrue(ParserOutcomes.current(pageLine, Integer.MAX_VALUE, Integer.MAX_VALUE).endsWith(pageLine),
                        pageLine);
            }
        }
        assertTrue(parsed > SEEDED_DOCUMENTS * 9 / 10, "most seeded documents are valid: " + parsed);
    }

    @Test
    public void readerFacadeAssignsLikeTheLegacyReader() {
        final var legacy = new LegacyJsonReader();
        final var current = new JsonReader();
        for (final var input : fixtures()) {
            if (input.startsWith("{")) {
                assertEquals(legacy.fromJson(input, JsonObject.class), current.fromJson(input, JsonObject.class));
            }
        }
        assertEquals(ParserOutcomes.outcome(() -> legacy.fromJson("[1]", JsonObject.class, 4, 10)),
                ParserOutcomes.outcome(() -> current.fromJson("[1]", JsonObject.class, 4, 10)));
    }

    private static List<String> malformed() {
        final var vectorCap = Configuration.getInstance().intValue(ConfigKey.MAX_VECTOR_DIMENSIONS);
        final var oversizedVector = "#vector(" + "1,".repeat(vectorCap) + "1)";
        return List.of("", " ", "\n\t", "[", "{", "]", "}", ",", ":", "[1,]", "[,1]", "[1,,2]", "{\"a\":1,}",
                "{\"a\" 1}", "{\"a\":}", "{\"a\":,\"b\":1}", "{,}", "{1:2}", "{true:1}", "{null:1}", "{[]:1}",
                "{\"#nope(1)\":1}", "{\"a\":1 \"b\":2}", "[1 2]", "{\"a\":1}{\"b\":2}", "{\"a\":1} 5", "{\"a\":1}]",
                "123", "\"str\"", "true", "null", "{\"a\":\"unterminated}", "{\"a\":\"x\\", "{\"a\":@}", "{\"a\":tru}",
                "{\"a\":nul}", "{\"a\":1e}", "{\"a\":1e+}", "{\"a\":--1}", "{\"a\":1.2.3}", "{\"a\":-}", "{\"a\":.}",
                "{\"a\":1e400}", "{\"a\":-1e400}", "{\"a\":\"#nope(1)\"}", "{\"a\":\"#geo(x,y)\"}", "{\"a\":[1,2}",
                "{\"a\":{\"b\":1]}", "[{]", "[}", "{]", "{\"a\"", "{\"a\":", "{\"a\":1", "{\"a\":1,", "[1", "[1,",
                "{\"a\":[", "{:1}", "{\"a\"::1}", "{\"a\":1:}", "[1:2]", "{\"a\":1}\n@", "{\"a\" 1, @}",
                "[1 2 \"unterminated", "{\"a\" 1, 1e400}", "{\"a\" 1, \"#nope(1)\"}", "{\"a\": }", "{\"a\":\f1}",
                "[[[ @ ]]]", "[[[[[1]]]]] @", "{\"v\":\"" + oversizedVector + "\"}",
                "{\"x\" 1,\"v\":\"" + oversizedVector + "\"}", "[" + "[".repeat(200) + "]".repeat(200) + "]",
                "{\"a\":1}" + " ".repeat(3) + "{");
    }

    @Test
    public void malformedInputsFailWithTheSameExceptionAndMessage() {
        final var inputs = malformed();
        assertTrue(inputs.size() >= 60, "the table has about 60 rows: " + inputs.size());
        for (final var input : inputs) {
            assertSameOutcome(input);
            for (var tokens = 0; tokens <= 24; tokens++) {
                assertEquals(ParserOutcomes.legacy(input, 3, tokens), ParserOutcomes.current(input, 3, tokens),
                        input + " at " + tokens + " tokens");
            }
        }
    }

    @Test
    public void randomCorruptionsFailAlike() {
        final var documents = new FuzzDocuments(7L);
        final var rng = new Random(11L);
        final var kinds = new TreeSet<String>();
        for (var index = 0; index < CORRUPTIONS; index++) {
            final var corrupted = corrupt(documents.document(index), rng);
            assertSameOutcome(corrupted);
            final var outcome = ParserOutcomes.current(corrupted, Integer.MAX_VALUE, Integer.MAX_VALUE);
            kinds.add(outcome.startsWith("OK") ? "OK" : outcome.substring(0, outcome.indexOf(':')));
        }
        assertTrue(kinds.containsAll(List.of("OK", "ERR " + MalformedJsonException.class.getName(),
                "ERR " + UnexpectedCharacterException.class.getName(),
                "ERR " + MissingEndOfStringException.class.getName(), "ERR " + NoSuchElementException.class.getName())),
                kinds.toString());
    }

    private static String corrupt(String document, Random rng) {
        final var builder = new StringBuilder(document);
        final var edits = 1 + rng.nextInt(3);
        for (var edit = 0; edit < edits && !builder.isEmpty(); edit++) {
            final var at = rng.nextInt(builder.length());
            switch (rng.nextInt(4)) {
                case 0 -> builder.deleteCharAt(at);
                case 1 -> builder.insert(at, CORRUPTING_CHARACTERS.charAt(rng.nextInt(CORRUPTING_CHARACTERS.length())));
                case 2 -> builder.setLength(at);
                default ->
                    builder.setCharAt(at, CORRUPTING_CHARACTERS.charAt(rng.nextInt(CORRUPTING_CHARACTERS.length())));
            }
        }
        return builder.toString();
    }
}
