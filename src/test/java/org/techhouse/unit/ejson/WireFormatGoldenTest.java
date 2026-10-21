package org.techhouse.unit.ejson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;

public class WireFormatGoldenTest {
    private static final String GOLDEN_RESOURCE = "/ejson/wire-golden.txt";
    private static final Path GOLDEN_SOURCE = Path.of("src/test/resources/ejson/wire-golden.txt");
    private static final String WRITE_PROPERTY = "lwnrdb.writeWireGolden";
    private static final String FINAL_PRIMITIVE = "a final primitive is never null, so a constructor-built instance"
            + " keeps its default";
    private static final String NO_ARG_CONSTRUCTOR = "the public no-arg constructor fixes message and errorCode,"
            + " which are final and non-null so the reader keeps them";
    private static final Map<String, String> NOT_REBUILT_BY_THE_GENERIC_READER = Map.of(
            "org.techhouse.ops.resp.CancelScriptResponse", FINAL_PRIMITIVE,
            "org.techhouse.ops.resp.ResolveTriggerRunResponse", FINAL_PRIMITIVE,
            "org.techhouse.ops.resp.TestTriggerResponse", FINAL_PRIMITIVE,
            "org.techhouse.ops.resp.CloseConnectionResponse", NO_ARG_CONSTRUCTOR,
            "org.techhouse.ops.resp.StopListenResponse", NO_ARG_CONSTRUCTOR);

    private static Map<String, String> currentLines() throws Exception {
        final var lines = new LinkedHashMap<String, String>();
        for (final var type : WireFixtures.wireClasses()) {
            final var line = WireFixtures.wireLine(type);
            lines.put(type.getName(), line.substring(type.getName().length() + 1));
        }
        return lines;
    }

    private static Map<String, String> goldenLines() throws IOException {
        final var lines = new LinkedHashMap<String, String>();
        try (InputStream in = WireFormatGoldenTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
            assertNotNull(in, "missing " + GOLDEN_RESOURCE);
            for (final var line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                final var tab = line.indexOf('\t');
                lines.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        return lines;
    }

    @Test
    public void test_every_wire_class_serialises_as_before() throws Exception {
        final var current = currentLines();
        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            final var text = new StringBuilder();
            current.forEach((name, json) -> text.append(name).append('\t').append(json).append('\n'));
            Files.writeString(GOLDEN_SOURCE, text.toString(), StandardCharsets.UTF_8);
            fail("wire-golden.txt written; rerun without -D" + WRITE_PROPERTY);
        }
        final var golden = goldenLines();
        assertEquals(golden.keySet(), current.keySet());
        for (final var entry : golden.entrySet()) {
            assertEquals(entry.getValue(), current.get(entry.getKey()), entry.getKey());
        }
    }

    @Test
    public void test_every_golden_line_parses_back_to_itself() throws Exception {
        final var eJson = new EJson();
        final var mismatched = new TreeSet<String>();
        for (final var entry : goldenLines().entrySet()) {
            final var parsed = eJson.fromJson(entry.getValue(), Class.forName(entry.getKey()));
            if (!entry.getValue().equals(eJson.toJson(parsed))) {
                mismatched.add(entry.getKey());
            }
        }
        assertEquals(NOT_REBUILT_BY_THE_GENERIC_READER.keySet(), mismatched);
    }

    @Test
    public void test_the_fixture_covers_every_wire_class() throws Exception {
        final var golden = goldenLines();
        final var scanned = WireFixtures.wireClasses();
        assertFalse(golden.isEmpty());
        assertTrue(scanned.size() >= golden.size(), "scan found fewer classes than the golden file holds");
        for (final var type : scanned) {
            assertTrue(golden.containsKey(type.getName()), type.getName() + " has no golden line");
        }
    }
}
