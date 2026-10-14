package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.CompactionJournal.Kind;

public class PageCompactorTailTest {
    private static final String DB = "tailDb";
    private static final String COLL = "tailColl";

    @TempDir
    File tmp;

    private CompactionFixture fx;

    @BeforeEach
    public void setUp() throws IOException {
        fx = new CompactionFixture(new File(tmp, "main"), DB, COLL);
        fx.seed(0, "a", "b", "c", "d", "e");
        fx.seed(1, "f", "g", "h");
    }

    private static final class CountingFile extends RandomAccessFile {
        private int reads;

        CountingFile(File file) throws FileNotFoundException {
            super(file, Globals.RW_PERMISSIONS);
        }

        @Override
        public int read() throws IOException {
            reads++;
            return super.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            reads++;
            return super.read(buffer, offset, length);
        }
    }

    @Test
    public void tailIsReadOnce() throws IOException {
        final var entry = fx.indexed("b");
        final var file = fx.page(0);
        final var expected = fx.pageText(0).replace(CompactionFixture.record("b", "old-b"), "");
        final byte[] tail;
        try (var plain = new RandomAccessFile(file, "r")) {
            tail = PageCompactor.readRegion(plain, entry.getPosition(), file.length());
        }
        try (var counting = new CountingFile(file)) {
            final var total = file.length();
            assertTrue(PageCompactor.shiftOtherEntriesToStart(counting, entry, tail));
            counting.setLength(total - entry.getLength());
            assertEquals(0, counting.reads);
        }
        assertEquals(expected, fx.pageText(0));
    }

    @Test
    public void lastRecordNeedsNoShift() throws IOException {
        final var entry = fx.indexed("e");
        final var file = fx.page(0);
        try (var counting = new CountingFile(file)) {
            final var tail = PageCompactor.readRegion(counting, entry.getPosition(), file.length());
            counting.reads = 0;
            assertFalse(PageCompactor.shiftOtherEntriesToStart(counting, entry, tail));
            assertEquals(0, counting.reads);
        }
    }

    private static final List<String> MARKER_IDS = List.of("plain", "with" + Globals.ID_SEPARATOR + "separator",
            "back\\slash", "é😀", "trailing\\");

    private CompactionJournal.Marker beginRandomMarker(Random random) throws IOException {
        final var tail = new byte[random.nextInt(300) + 1];
        random.nextBytes(tail);
        final var position = random.nextInt(10_000);
        final var removed = new PkIndexEntry(DB, COLL, MARKER_IDS.get(random.nextInt(MARKER_IDS.size())), position,
                1 + random.nextInt(tail.length), random.nextInt(5), random.nextLong());
        return fx.journal.begin(Kind.values()[random.nextInt(Kind.values().length)], removed, position + tail.length,
                tail);
    }

    private CompactionJournal.Marker retargetRandomly(CompactionJournal.Marker marker, Random random)
            throws IOException {
        return fx.journal.retarget(marker, random.nextInt(9), random.nextInt(50_000));
    }

    @Test
    public void journalBytesIdenticalToLegacyEncode() throws IOException {
        final var random = new Random(99L);
        for (var round = 0; round < 200; round++) {
            final var marker = beginRandomMarker(random);
            final var file = fx.journal.markerFile(marker);
            assertArrayEquals(LegacyStorageOracle.encodeMarker(marker), Files.readAllBytes(file.toPath()));
            final var retargeted = retargetRandomly(marker, random);
            assertArrayEquals(LegacyStorageOracle.encodeMarker(retargeted), Files.readAllBytes(file.toPath()));
            final var decoded = CompactionJournal.decode(Files.readAllBytes(file.toPath()));
            assertNotNull(decoded);
            assertEquals(retargeted.id(), decoded.id());
            assertArrayEquals(retargeted.tail(), decoded.tail());
            fx.journal.end(retargeted);
        }
    }

    @Test
    public void restoreAfterFailedIndexWriteStillRestores() throws IOException {
        final var compactor = new PageCompactor(fx.paths, fx.store, fx.journal);
        final var before = Files.readAllBytes(fx.page(0).toPath());
        final var pkBefore = Files.readAllBytes(fx.paths.pkIndexFile(DB, COLL).toPath());
        final var blocker = new File(fx.paths.pkIndexFile(DB, COLL).getPath() + ".repair");
        assertTrue(blocker.mkdirs());
        assertTrue(new File(blocker, "occupied").createNewFile());
        final var target = fx.indexed("b");
        final var entry = indexedUpdate(target, "grown-" + "x".repeat(40)).toDbEntry();
        assertThrows(IOException.class,
                () -> compactor.update(entry, entry.encodeLine(), target, fx.store.readWholePkIndexFile(DB, COLL)));
        assertArrayEquals(before, Files.readAllBytes(fx.page(0).toPath()));
        assertArrayEquals(pkBefore, Files.readAllBytes(fx.paths.pkIndexFile(DB, COLL).toPath()));
        assertEquals(0, fx.markers().length);
    }

    @Test
    public void bulkUpdateFromResidentRowsWritesTheSameFiles() throws IOException {
        final var twin = new CompactionFixture(new File(tmp, "twin"), DB, COLL);
        twin.seed(0, "a", "b", "c", "d", "e");
        twin.seed(1, "f", "g", "h");
        final var values = List.of("short", "a-much-longer-value-" + "y".repeat(30), "", "mid-length-value");
        final var resident = fx.store.readWholePkIndexFile(DB, COLL);
        final var fromMemory = new PageCompactor(fx.paths, fx.store, fx.journal).bulkUpdate(DB, COLL,
                updates(fx, values), resident, null);
        final var fromParse = new PageCompactor(twin.paths, twin.store, twin.journal).bulkUpdate(DB, COLL,
                updates(twin, values), null, null);
        for (final var page : List.of(0L, 1L)) {
            assertArrayEquals(Files.readAllBytes(twin.page(page).toPath()), Files.readAllBytes(fx.page(page).toPath()));
        }
        assertArrayEquals(Files.readAllBytes(twin.paths.pkIndexFile(DB, COLL).toPath()),
                Files.readAllBytes(fx.paths.pkIndexFile(DB, COLL).toPath()));
        assertEquals(fromParse.updated().stream().map(u -> u.getIndex().toFileEntry()).toList(),
                fromMemory.updated().stream().map(u -> u.getIndex().toFileEntry()).toList());
        assertTrue(fx.everyRowReadsItsOwnRecord());
    }

    private static List<IndexedDbEntry> updates(CompactionFixture fixture, List<String> values) throws IOException {
        final var updates = new ArrayList<IndexedDbEntry>();
        final var ids = List.of("b", "g", "d", "a", "h");
        for (var i = 0; i < ids.size(); i++) {
            updates.add(indexedUpdate(fixture.indexed(ids.get(i)), values.get(i % values.size())));
        }
        return updates;
    }

    private static IndexedDbEntry indexedUpdate(PkIndexEntry index, String value) {
        final var data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, index.getValue());
        data.addProperty("v", value);
        final var update = new IndexedDbEntry();
        update.setIndex(index);
        update.setDatabaseName(DB);
        update.setCollectionName(COLL);
        update.set_id(index.getValue());
        update.setData(data);
        update.setVersion(CompactionFixture.NEW_VERSION);
        return update;
    }
}
