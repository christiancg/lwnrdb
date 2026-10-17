package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public class PageHolesTest {
    private static final String DB = "holesDb";
    private static final String COLL = "holesColl";

    @TempDir
    File tmp;

    private StorageFixture fx;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        fx = new StorageFixture(tmp, DB, COLL);
        fx.seed(0, "a", "b", "c");
    }

    private void blank(String id) throws IOException {
        final var row = fx.indexed(id);
        try (var channel = FileChannel.open(fx.page(0).toPath(), StandardOpenOption.WRITE)) {
            PageHoles.blank(channel, row.getPosition(), row.getLength());
        }
        fx.store.appendDeletes(DB, COLL, List.of(id));
    }

    @Test
    public void blankKeepsTheLineTerminator() throws IOException {
        final var row = fx.indexed("b");
        final var record = fx.readAt(row);

        blank("b");

        final var bytes = Files.readAllBytes(fx.page(0).toPath());
        final var hole = new String(bytes, (int) row.getPosition(), (int) row.getLength(), StandardCharsets.UTF_8);
        assertEquals(" ".repeat(record.length() - 1) + "\n", hole, "the fixture writes LF-terminated records");
        assertEquals(record.getBytes(StandardCharsets.UTF_8).length, hole.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    public void blankKeepsEveryByteOfThePlatformTerminator() throws IOException {
        final var page = new File(tmp, "terminated.dat");
        final var record = "{\"_id\":\"x\"}" + Globals.NEWLINE;
        Files.writeString(page.toPath(), record + "{\"_id\":\"y\"}" + Globals.NEWLINE, StandardCharsets.UTF_8);
        try (var channel = FileChannel.open(page.toPath(), StandardOpenOption.WRITE)) {
            PageHoles.blank(channel, 0, record.length());
        }

        final var text = Files.readString(page.toPath(), StandardCharsets.UTF_8);
        assertEquals(" ".repeat(record.length() - Globals.NEWLINE.length()) + Globals.NEWLINE + "{\"_id\":\"y\"}"
                + Globals.NEWLINE, text);
    }

    @Test
    public void aHoleIsNeverParsedAsARecord() throws IOException {
        blank("b");

        final var read = new DocumentPageStore(fx.paths).readWholeCollectionPage(DB, COLL, 0);

        assertEquals(List.of("a", "c"), read.keySet().stream().sorted().toList());
        assertTrue(PageHoles.isHole(" x".getBytes(StandardCharsets.UTF_8), 0));
        assertFalse(PageHoles.isHole("{}".getBytes(StandardCharsets.UTF_8), 0));
        assertFalse(PageHoles.isHole(new byte[0], 0));
    }

    @Test
    public void adoptionSkipsHoles() throws IOException {
        blank("c");

        final var adopted = OrphanedPageRecords.adoptAll(fx.paths, fx.store, DB, COLL, List.of(0L));

        assertTrue(adopted.isEmpty(), "a hole past the last indexed record is not an orphan");
        assertEquals(List.of("a", "b"), fx.indexedRows().stream().map(PkIndexEntry::getValue).toList());
    }

    @Test
    public void pkRebuildSkipsHoles() throws IOException {
        blank("b");

        final var plan = PkIndexRebuild.plan(fx.paths, fx.store, DB, COLL, List.of(0L));

        assertEquals(List.of("a", "c"), plan.rows().stream().map(PkIndexEntry::getValue).sorted().toList());
        assertTrue(plan.vanished().isEmpty());
    }

    @Test
    public void tornTailHealIgnoresHoles() throws IOException {
        blank("c");
        final var before = Files.readAllBytes(fx.page(0).toPath());
        Files.writeString(fx.page(0).toPath(), "{\"_id\":\"torn", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertTrue(TornPageTail.heal(fx.page(0), 0, fx.indexedRows()));

        assertArrayEquals(before, Files.readAllBytes(fx.page(0).toPath()),
                "the heal cuts the torn tail back to the hole's terminator and keeps the hole");
    }

    @Test
    public void aPageOfOnlyHolesReadsAsEmpty() throws IOException {
        blank("a");
        blank("b");
        blank("c");

        assertTrue(new DocumentPageStore(fx.paths).readWholeCollectionPage(DB, COLL, 0).isEmpty());
        assertTrue(PageLines.recordsOf(fx.page(0), 0).isEmpty());
    }
}
