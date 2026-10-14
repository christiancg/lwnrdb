package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.MalformedJsonException;
import org.techhouse.ejson.exceptions.UnexpectedCharacterException;
import org.techhouse.ejson.internal.JsonParser;
import org.techhouse.ejson.internal.KeyTable;
import org.techhouse.ioc.IocContainer;
import org.techhouse.support.ParserOutcomes;

public class JsonParserBytesTest {
    @BeforeAll
    public static void registerCustomTypes() {
        IocContainer.get(EJson.class);
    }

    private static JsonObject fromBytes(String json) {
        final var utf8 = json.getBytes(StandardCharsets.UTF_8);
        return JsonParser.parse(utf8, 0, utf8.length, Integer.MAX_VALUE, Integer.MAX_VALUE).asJsonObject();
    }

    private static JsonObject fromText(String json) {
        return JsonParser.parse(json, Integer.MAX_VALUE, Integer.MAX_VALUE).asJsonObject();
    }

    private static String onlyKey(JsonObject object) {
        return object.entrySet().iterator().next().getKey();
    }

    @Test
    public void keysAreTheSameCanonicalInstancesAsTheStringPath() {
        for (final var key : new String[]{"ascii-key", "clé", "日本", "😀k"}) {
            final var json = "{\"" + key + "\":1}";
            assertSame(onlyKey(fromText(json)), onlyKey(fromBytes(json)), key);
        }
    }

    @Test
    public void aPrivateKeyTableIsUsedForByteInput() {
        final var table = new KeyTable(Globals.KEY_TABLE_SLOTS);
        final var utf8 = "{\"private-table-key\":1}".getBytes(StandardCharsets.UTF_8);

        final var parsed = JsonParser.parse(utf8, 0, utf8.length, 4, 100, table).asJsonObject();

        assertSame(table.lookup("private-table-key", 0, 17), onlyKey(parsed));
    }

    @Test
    public void integersUpToFifteenDigitsMatchTheStringPath() {
        final var json = "{\"a\":0,\"b\":-0,\"c\":7,\"d\":-42,\"e\":2147483647,\"f\":2147483648,\"g\":-2147483648,"
                + "\"h\":999999999999999,\"i\":-999999999999999,\"j\":0001}";
        final var bytes = fromBytes(json);
        final var text = fromText(json);
        for (final var member : text.entrySet()) {
            final var expected = member.getValue().asJsonNumber();
            final var actual = (JsonNumber) bytes.get(member.getKey());
            assertEquals(expected.getValue(), actual.getValue(), member.getKey());
            assertEquals(expected.getValue().getClass(), actual.getValue().getClass(), member.getKey());
        }
    }

    @Test
    public void longerIntegersFractionsAndExponentsMatchTheStringPath() {
        final var json = "{\"a\":1234567890123456,\"b\":1.5,\"c\":-0.0,\"d\":1e5,\"e\":2E-3,\"f\":1e+2,"
                + "\"g\":12345678901234567890}";
        assertEquals(fromText(json), fromBytes(json));
    }

    @Test
    public void nonFiniteNumbersAreRefused() {
        assertThrows(MalformedJsonException.class, () -> fromBytes("{\"a\":1e400}"));
        assertThrows(MalformedJsonException.class, () -> fromBytes("{\"a\":-1e400}"));
    }

    @Test
    public void anUnexpectedCharacterIsReportedAtItsCharIndex() {
        final var json = "{\"é😀\":@}";
        final var fromText = assertThrows(UnexpectedCharacterException.class, () -> fromText(json));
        final var fromBytes = assertThrows(UnexpectedCharacterException.class, () -> fromBytes(json));
        assertEquals(fromText.getMessage(), fromBytes.getMessage());
    }

    @Test
    public void aRangeInsideALargerArrayIsParsedAlone() {
        final var utf8 = "xx{\"a\":\"é\"}yy".getBytes(StandardCharsets.UTF_8);

        final var parsed = JsonParser.parse(utf8, 2, utf8.length - 2, 8, 100).asJsonObject();

        assertEquals("é", parsed.get("a").asJsonString().getValue());
    }

    @Test
    public void tokenAndDepthBudgetsMatchTheStringPath() {
        final var json = "{\"a\":[1,2,3],\"b\":{\"c\":{\"d\":1}}}";
        final var utf8 = json.getBytes(StandardCharsets.UTF_8);
        for (var tokens = 0; tokens < 20; tokens++) {
            for (var depth = 0; depth < 4; depth++) {
                final var budgetTokens = tokens;
                final var budgetDepth = depth;
                assertEquals(ParserOutcomes.outcome(() -> JsonParser.parse(json, budgetDepth, budgetTokens)),
                        ParserOutcomes.outcome(() -> JsonParser.parse(utf8, 0, utf8.length, budgetDepth, budgetTokens)),
                        tokens + " tokens, depth " + depth);
            }
        }
    }

    @Test
    public void negativeZeroParsesToPositiveZeroOnBothPaths() {
        final var json = "{\"a\":-0,\"b\":-0.0,\"c\":-00,\"d\":-0e3}";
        for (final var parsed : new JsonObject[]{fromText(json), fromBytes(json)}) {
            for (final var key : new String[]{"a", "b", "c", "d"}) {
                assertEquals(0, Double.compare(0.0, parsed.get(key).asJsonNumber().doubleValue()), key);
            }
        }
    }
}
