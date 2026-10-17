package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;

public class DbEntryEncodeTest {
    private static final String[] PIECES = {"plain", "é", "😀", "\"quoted\"", "back\\slash", "tab\t",
            String.valueOf((char) 0x2028), String.valueOf((char) 1), String.valueOf((char) 0xD83D), ""};

    private static DbEntry entry(Random random) {
        final var data = new JsonObject();
        for (var field = random.nextInt(6); field > 0; field--) {
            final var value = new StringBuilder();
            for (var piece = random.nextInt(5); piece > 0; piece--) {
                value.append(PIECES[random.nextInt(PIECES.length)]);
            }
            if (random.nextBoolean()) {
                data.addProperty("f" + field + PIECES[random.nextInt(PIECES.length)], value.toString());
            } else {
                data.addProperty("n" + field, random.nextDouble() * 1e6);
            }
        }
        final var entry = new DbEntry();
        entry.setDatabaseName("db");
        entry.setCollectionName("coll");
        if (random.nextInt(4) > 0) {
            entry.set_id("id-" + random.nextInt(1000));
        }
        entry.setData(data);
        return entry;
    }

    @Test
    public void encodeLineEqualsLegacyToFileEntryPlusNewline() {
        final var random = new Random(5L);
        for (var round = 0; round < 2000; round++) {
            final var entry = entry(random);
            final var encoded = entry.encodeLine();
            assertArrayEquals((entry.toFileEntry() + Globals.NEWLINE).getBytes(StandardCharsets.UTF_8), encoded);
        }
    }

    @Test
    public void encodeLineSetsByteSize() {
        final var entry = entry(new Random(9L));
        final var encoded = entry.encodeLine();
        assertNotNull(entry.get_id());
        assertEquals(encoded.length, entry.byteSize());
    }

    @Test
    public void encodedBytesAreNotRetained() {
        for (Class<?> type = DbEntry.class; type != null; type = type.getSuperclass()) {
            for (final var field : type.getDeclaredFields()) {
                assertNotEquals(byte[].class, field.getType(), type.getSimpleName() + "." + field.getName());
            }
        }
        final var entry = entry(new Random(11L));
        final var first = entry.encodeLine();
        final var second = entry.encodeLine();
        assertNotSame(first, second);
        assertArrayEquals(first, second);
        assertEquals(first.length, entry.byteSize());
    }
}
