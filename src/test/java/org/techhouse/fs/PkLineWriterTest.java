package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;

public class PkLineWriterTest {
    private static final String[] PIECES = {"a", "Z", "0", "_", "-", "|", "\\", "\n", "\r", Globals.ID_SEPARATOR, "é",
            "😀", String.valueOf((char) 0xD83D), "x".repeat(200)};
    private static final long[] EDGES = {0L, 1L, -1L, 9L, 10L, 99L, 100L, Long.MAX_VALUE, Long.MIN_VALUE,
            1_700_000_000_000L, -1_700_000_000_000L};

    private static long number(Random random) {
        return random.nextBoolean() ? EDGES[random.nextInt(EDGES.length)] : random.nextLong() >> random.nextInt(64);
    }

    private static PkIndexEntry row(Random random, long position) {
        final var id = new StringBuilder();
        for (var i = 1 + random.nextInt(6); i > 0; i--) {
            id.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return new PkIndexEntry("db", "coll", id.toString(), position, number(random), number(random), number(random));
    }

    @Test
    public void linesAreTheFileEntryBytes() throws IOException {
        final var random = new Random(31L);
        for (var round = 0; round < 20_000; round++) {
            final var position = number(random);
            final var override = number(random);
            final var entry = row(random, position);
            final var out = new ByteArrayOutputStream();
            final var writer = new PkLineWriter(out);
            final var digest = writer.write(entry, override);
            final var expected = new PkIndexEntry("db", "coll", entry.getValue(), override, entry.getLength(),
                    entry.getPage(), entry.getVersion());
            assertArrayEquals((expected.toFileEntry() + Globals.NEWLINE).getBytes(StandardCharsets.UTF_8),
                    out.toByteArray());
            assertEquals(PkRowDigest.of(expected), digest);
            assertEquals(position, entry.getPosition());
        }
    }

    @Test
    public void consecutiveLinesDoNotBleedIntoEachOther() throws IOException {
        final var random = new Random(37L);
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 500; i++) {
            rows.add(row(random, number(random)));
        }
        final var out = new ByteArrayOutputStream();
        final var writer = new PkLineWriter(out);
        for (final var entry : rows) {
            writer.write(entry);
        }
        assertArrayEquals(LegacyStorageOracle.joinLines(rows.stream().map(PkIndexEntry::toFileEntry).toList()),
                out.toByteArray());
    }

    @Test
    public void writingAnAsciiRowAllocatesNothingPerRow() throws IOException {
        final List<PkIndexEntry> rows = new ArrayList<>();
        for (var i = 0; i < 50_000; i++) {
            rows.add(new PkIndexEntry("db", "coll", "doc-" + i, i * 812L, 812L, i / 2500, 1_700_000_000_000L + i));
        }
        final var writer = new PkLineWriter(OutputStream.nullOutputStream());
        for (final var entry : rows) {
            writer.write(entry);
        }
        final var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var threadId = Thread.currentThread().threadId();
        final var before = threads.getThreadAllocatedBytes(threadId);
        for (final var entry : rows) {
            writer.write(entry, entry.getPosition() - 5);
        }
        final var allocated = threads.getThreadAllocatedBytes(threadId) - before;
        assertTrue(allocated < 64 * 1024, "writing 50000 rows allocated " + allocated + " bytes");
    }
}
