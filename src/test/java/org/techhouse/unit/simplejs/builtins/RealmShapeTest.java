package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class RealmShapeTest {
    private static final String WALKER_RESOURCE = "/simplejs/realm-shape.js";
    private static final String GOLDEN_RESOURCE = "/simplejs/realm-shape.txt";
    private static final Path GOLDEN_SOURCE = Path.of("src/test/resources/simplejs/realm-shape.txt");
    private static final String WRITE_PROPERTY = "lwnrdb.writeRealmShape";

    private static String resource(String name) throws IOException {
        try (InputStream in = RealmShapeTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String shape(String prelude) throws IOException {
        final var result = new SimpleJs().run(prelude + resource(WALKER_RESOURCE), SimpleHostBindings.empty());
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        return ((JsonString) result.getValue()).getValue() + "\n";
    }

    private static void assertSameShape(String golden, String current) {
        final var expected = golden.split("\n", -1);
        final var actual = current.split("\n", -1);
        for (var i = 0; i < Math.min(expected.length, actual.length); i++) {
            assertEquals(expected[i], actual[i], "line " + (i + 1));
        }
        assertEquals(expected.length, actual.length, "line count");
    }

    @Test
    public void test_a_fresh_realm_has_the_recorded_shape() throws IOException {
        final var current = shape("");
        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.writeString(GOLDEN_SOURCE, current, StandardCharsets.UTF_8);
            fail("realm-shape.txt written; rerun without -D" + WRITE_PROPERTY);
        }
        assertSameShape(resource(GOLDEN_RESOURCE), current);
    }

    @Test
    public void test_touching_one_intrinsic_first_does_not_change_the_shape() throws IOException {
        final var golden = resource(GOLDEN_RESOURCE);
        assertSameShape(golden,
                shape("[].map; 'a'.at; new Map(); Promise.resolve(1); Temporal.Now; Symbol.iterator;\n"));
        assertSameShape(golden,
                shape("Object.getPrototypeOf(function* () {}).prototype; new Uint8Array(1); /a/.flags;\n"));
    }
}
