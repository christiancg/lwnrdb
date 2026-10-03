package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;

public class PkIndexAppendRollbackTest {

    private static final String DB = "rollbackDb";
    private static final String COLL = "rollbackColl";
    private static final int ENTRIES_PAST_ONE_BUFFER = 1000;

    @TempDir
    File tmp;

    private FilePaths paths;
    private PkIndexStore store;

    @BeforeEach
    public void setUp() {
        paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(paths.collectionFolder(DB, COLL).mkdirs());
        store = new PkIndexStore(paths);
    }

    private static PkIndexEntry entry(int ordinal) {
        return new PkIndexEntry(DB, COLL, paddedId(ordinal), ordinal * 64L, 64L, 0L, ordinal + 1L);
    }

    private static String paddedId(int ordinal) {
        return "id-" + String.format("%037d", ordinal);
    }

    private static List<PkIndexEntry> batchLargerThanOneBuffer(int firstOrdinal) {
        final var batch = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < ENTRIES_PAST_ONE_BUFFER; i++) {
            batch.add(entry(firstOrdinal + i));
        }
        assertTrue(batch.size() * batch.getFirst().toFileEntry().length() > Globals.BUFFER_SIZE,
                "the batch must outgrow one writer buffer, or no flush happens before the failure and the"
                        + " test stops covering a partially flushed append");
        return batch;
    }

    private static List<PkIndexEntry> batchFailingAfterAFlush(int firstOrdinal) {
        final var batch = batchLargerThanOneBuffer(firstOrdinal);
        batch.add(new UnserializablePkIndexEntry());
        return batch;
    }

    private File pkIndexFile() {
        return paths.pkIndexFile(DB, COLL);
    }

    @Test
    public void test_a_partially_flushed_append_leaves_the_pk_index_byte_identical() throws IOException {
        store.bulkIndexNewPKValues(DB, COLL, List.of(entry(0), entry(1)));
        final var before = Files.readAllBytes(pkIndexFile().toPath());

        assertThrows(IllegalStateException.class,
                () -> store.bulkIndexNewPKValues(DB, COLL, batchFailingAfterAFlush(2)));

        assertArrayEquals(before, Files.readAllBytes(pkIndexFile().toPath()),
                "a partially flushed append must be truncated back; the rows it already flushed are"
                        + " well-formed, so the torn-line self-heal cannot drop them and nothing rebuilds pk.idx");
    }

    @Test
    public void test_a_failed_append_on_a_missing_index_file_creates_nothing_extra() {
        assertFalse(pkIndexFile().exists(), "the collection must start without a pk index");

        assertThrows(IllegalStateException.class,
                () -> store.bulkIndexNewPKValues(DB, COLL, batchFailingAfterAFlush(0)));

        assertEquals(0L, pkIndexFile().length(),
                "a failed append on a fresh collection must leave no addressable row behind");
    }

    @Test
    public void test_a_successful_append_still_writes_every_row() throws IOException {
        store.bulkIndexNewPKValues(DB, COLL, batchLargerThanOneBuffer(0));

        final var written = Files.readAllLines(pkIndexFile().toPath(), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank()).count();
        assertEquals(ENTRIES_PAST_ONE_BUFFER, (int) written, "the rollback must not disturb a successful append");
    }

    private static final class UnserializablePkIndexEntry extends PkIndexEntry {
        private UnserializablePkIndexEntry() {
            super(DB, COLL, "unserializable", 0L, 0L, 0L, 0L);
        }

        @Override
        public String toFileEntry() {
            throw new IllegalStateException("this row cannot be serialized");
        }
    }
}
