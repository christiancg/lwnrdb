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

public class PkIndexAppendTest {

    private static final String DB = "appendDb";
    private static final String COLL = "appendColl";
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

    private static PkIndexEntry entry(String id, long position) {
        return new PkIndexEntry(DB, COLL, id, position, 10L, 0L, 1_700_000_000_000L);
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

    private void writeUnterminatedButParseableLine() throws IOException {
        final var torn = entry("torn", 0L).toFileEntry();
        Files.writeString(pkIndexFile().toPath(), torn.substring(0, torn.length() - 4), StandardCharsets.UTF_8);
    }

    private List<String> ids() throws IOException {
        return store.readWholePkIndexFile(DB, COLL).stream().map(PkIndexEntry::getValue).sorted().toList();
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

    @Test
    public void test_an_append_after_an_unterminated_parseable_line_starts_a_new_line() throws IOException {
        writeUnterminatedButParseableLine();
        assertEquals(List.of("torn"), ids(), "the torn line must still parse, or this test covers nothing");

        store.indexNewPKValue(DB, COLL, "acked", 10L, 10, 0L, 2L);

        assertEquals(List.of("acked", "torn"), ids(),
                "an acknowledged entry must not be glued onto a torn line the next heal would drop");
    }

    @Test
    public void test_a_bulk_append_after_an_unterminated_line_keeps_every_entry() throws IOException {
        writeUnterminatedButParseableLine();

        store.bulkIndexNewPKValues(DB, COLL, List.of(entry("a", 10L), entry("b", 20L)));

        assertEquals(List.of("a", "b", "torn"), ids());
    }

    @Test
    public void test_an_append_to_an_empty_file_adds_no_leading_blank_line() throws IOException {
        Files.writeString(pkIndexFile().toPath(), "", StandardCharsets.UTF_8);

        store.indexNewPKValue(DB, COLL, "first", 0L, 10, 0L, 1L);

        final var content = Files.readString(pkIndexFile().toPath(), StandardCharsets.UTF_8);
        assertFalse(content.startsWith("\n") || content.startsWith("\r"), "an empty file needs no separator");
    }

    @Test
    public void test_an_append_after_a_terminated_line_adds_no_blank_line() throws IOException {
        store.indexNewPKValue(DB, COLL, "first", 0L, 10, 0L, 1L);
        store.indexNewPKValue(DB, COLL, "second", 10L, 10, 0L, 2L);

        final var lines = Files.readAllLines(pkIndexFile().toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "a properly terminated file must not gain an empty line");
    }

    @Test
    public void test_a_failed_append_also_truncates_the_separator_it_wrote() throws IOException {
        writeUnterminatedButParseableLine();
        final var before = Files.readAllBytes(pkIndexFile().toPath());

        assertThrows(IllegalStateException.class, () -> store.bulkIndexNewPKValues(DB, COLL,
                List.of(entry("ok", 10L), new UnserializablePkIndexEntry())));

        assertEquals(before.length, pkIndexFile().length(),
                "the rollback must remove the separator too, or a failed append changes the file");
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
