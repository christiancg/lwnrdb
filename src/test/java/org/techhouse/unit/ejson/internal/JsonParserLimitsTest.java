package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.exceptions.MalformedJsonException;
import org.techhouse.ejson.internal.JsonParser;
import org.techhouse.ioc.IocContainer;
import org.techhouse.support.ParserOutcomes;

public class JsonParserLimitsTest {
    private static final int UNBOUNDED = Integer.MAX_VALUE;

    @BeforeAll
    public static void registerCustomTypes() {
        IocContainer.get(EJson.class);
    }

    private static int smallestAcceptedBudget(String input) {
        var budget = 0;
        while (ParserOutcomes.legacy(input, UNBOUNDED, budget).startsWith("ERR")) {
            budget++;
        }
        return budget;
    }

    @Test
    public void tokenBudgetRefusesAtTheSameCount() {
        final var inputs = List.of("[1,1,1,1,1]", "{\"a\":[1,2,3],\"b\":{\"c\":true}}", "[ 1     ]",
                "{\"g\":\"#geo(1,2)\",\"v\":\"#vector(1,2,3,4)\"}", "[\"#vector(1,2,3)\",\"#time(10:00)\"]");
        for (final var input : inputs) {
            final var limit = smallestAcceptedBudget(input);
            for (var budget = limit - 1; budget <= limit + 1; budget++) {
                assertEquals(ParserOutcomes.legacy(input, UNBOUNDED, budget),
                        ParserOutcomes.current(input, UNBOUNDED, budget), input + " at " + budget);
            }
            assertThrows(MalformedJsonException.class, () -> JsonParser.parse(input, UNBOUNDED, limit - 1));
            assertTrue(ParserOutcomes.current(input, UNBOUNDED, limit).startsWith("OK"), input);
        }
    }

    @Test
    public void flatArrayCountsEveryTokenAndWhitespaceNone() {
        assertTrue(ParserOutcomes.current("[1,1,1,1,1]", UNBOUNDED, 11).startsWith("OK"));
        final var refusal = assertThrows(MalformedJsonException.class,
                () -> JsonParser.parse("[1,1,1,1,1]", UNBOUNDED, 10));
        assertEquals("JSON has more tokens than the allowed limit", refusal.getMessage());
        assertTrue(ParserOutcomes.current("[ 1     ]", UNBOUNDED, 3).startsWith("OK"));
    }

    @Test
    public void customComponentsCountAgainstTheBudget() {
        assertTrue(ParserOutcomes.current("[\"#vector(1,2,3)\"]", UNBOUNDED, 6).startsWith("OK"));
        assertThrows(MalformedJsonException.class, () -> JsonParser.parse("[\"#vector(1,2,3)\"]", UNBOUNDED, 5));
    }

    private static String nested(int levels) {
        return "{\"a\":".repeat(levels - 1) + "{}" + "}".repeat(levels - 1);
    }

    @Test
    public void depthLimitRefusesAtTheSameDepth() {
        final var limit = Globals.MAX_REQUEST_NESTING_DEPTH;
        for (var levels = limit - 1; levels <= limit + 1; levels++) {
            final var input = nested(levels);
            assertEquals(ParserOutcomes.legacy(input, limit, UNBOUNDED),
                    ParserOutcomes.current(input, limit, UNBOUNDED), levels + " levels");
            final var arrays = "[".repeat(levels) + "]".repeat(levels);
            assertEquals(ParserOutcomes.legacy(arrays, limit, UNBOUNDED),
                    ParserOutcomes.current(arrays, limit, UNBOUNDED), levels + " array levels");
        }
        assertTrue(ParserOutcomes.current(nested(limit), limit, UNBOUNDED).startsWith("OK"));
        final var refusal = assertThrows(MalformedJsonException.class,
                () -> JsonParser.parse(nested(limit + 1), limit, UNBOUNDED));
        assertEquals("JSON nesting is deeper than the allowed limit", refusal.getMessage());
    }

    private static JsonBaseElement only(String literal) {
        return JsonParser.parse("[\"" + literal + "\"]", UNBOUNDED, UNBOUNDED).asJsonArray().get(0);
    }

    @Test
    public void customTypesAreRecognisedAsBefore() {
        assertInstanceOf(JsonGeo.class, only("#geo(40.0,-74.0)"));
        assertInstanceOf(JsonVector.class, only("#vector(1,2,3)"));
        assertInstanceOf(JsonTime.class, only("#time(10:30:00)"));
        assertInstanceOf(JsonDateTime.class, only("#datetime(2024-01-01T10:00:00)"));
        assertEquals(JsonBaseElement.JsonType.STRING, only("#notacustomtype").getJsonType());
        final var cap = Configuration.getInstance().intValue(ConfigKey.MAX_VECTOR_DIMENSIONS);
        final var oversized = "[\"#vector(" + "1,".repeat(cap) + "1)\"]";
        final var refusal = assertThrows(MalformedJsonException.class,
                () -> JsonParser.parse(oversized, UNBOUNDED, oversized.length()));
        assertEquals("A custom value has more components than the allowed limit", refusal.getMessage());
        assertInstanceOf(JsonVector.class, JsonParser.parse(oversized, UNBOUNDED, UNBOUNDED).asJsonArray().get(0));
        assertEquals(ParserOutcomes.legacy(oversized, UNBOUNDED, oversized.length()),
                ParserOutcomes.current(oversized, UNBOUNDED, oversized.length()));
    }

    private static long allocatedBytes(Runnable work) {
        final var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var before = threads.getCurrentThreadAllocatedBytes();
        work.run();
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    @Test
    public void unescapedStringsAreSubstrings() {
        final var length = 1 << 20;
        final var input = "{\"s\":\"" + "x".repeat(length) + "\"}";
        for (var warmUp = 0; warmUp < 5; warmUp++) {
            JsonParser.parse(input, UNBOUNDED, UNBOUNDED);
        }
        final var allocated = allocatedBytes(() -> JsonParser.parse(input, UNBOUNDED, UNBOUNDED));
        assertTrue(allocated < length + length / 2,
                "an unescaped string is one copy of its characters, allocated " + allocated + " bytes");
        final var escaped = "{\"s\":\"\\n" + "x".repeat(length) + "\"}";
        JsonParser.parse(escaped, UNBOUNDED, UNBOUNDED);
        assertTrue(allocatedBytes(() -> JsonParser.parse(escaped, UNBOUNDED, UNBOUNDED)) > allocated,
                "an escaped string still goes through a builder");
    }
}
