package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;

public class PkIndexLogTest {
    private static final String DB = "logDb";
    private static final String COLL = "logColl";

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

    private static PkIndexEntry row(String id, long position, long version) {
        return new PkIndexEntry(DB, COLL, id, position, 10L, 0L, version);
    }

    private File pkIndexFile() {
        return paths.pkIndexFile(DB, COLL);
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(pkIndexFile().toPath(), StandardCharsets.UTF_8);
    }

    private List<String> folded() throws IOException {
        return store.readWholePkIndexFile(DB, COLL).stream().map(PkIndexEntry::toFileEntry).toList();
    }

    @Test
    public void lastRowWins() throws IOException {
        store.appendRows(DB, COLL, List.of(row("a", 0, 1), row("b", 10, 1)));

        store.appendReplacements(DB, COLL, List.of(row("a", 20, 2)));

        assertEquals(List.of(row("a", 20, 2).toFileEntry(), row("b", 10, 1).toFileEntry()), folded());
        assertEquals(3, lines().size(), "a replacement is appended, never rewritten in place");
    }

    @Test
    public void aDeleteRowRemovesTheId() throws IOException {
        store.appendRows(DB, COLL, List.of(row("a", 0, 1), row("b", 10, 1)));

        store.appendDeletes(DB, COLL, List.of("a"));

        assertEquals(List.of(row("b", 10, 1).toFileEntry()), folded());
        assertEquals(PkIndexEntry.toDeleteRow("a"), lines().getLast());
    }

    @Test
    public void anIdDeletedThenReinsertedIsLive() throws IOException {
        store.appendRows(DB, COLL, List.of(row("a", 0, 1)));
        store.appendDeletes(DB, COLL, List.of("a"));

        store.appendRows(DB, COLL, List.of(row("a", 30, 3)));

        assertEquals(List.of(row("a", 30, 3).toFileEntry()), folded());
    }

    @Test
    public void deadRowsAreCountedAndCompactionTripsAtTheThreshold() throws IOException {
        final var live = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 10; i++) {
            live.add(row("id-" + i, i * 10L, 1));
        }
        store.appendRows(DB, COLL, live);
        for (var round = 0; round < Globals.PK_LOG_MIN_DEAD_ROWS - 1; round++) {
            store.appendReplacements(DB, COLL, List.of(row("id-" + (round % 10), 1000L + round, 2)));
        }
        assertEquals(10 + Globals.PK_LOG_MIN_DEAD_ROWS - 1, lines().size(), "below the threshold nothing compacts");

        store.appendDeletes(DB, COLL, List.of("id-9"));

        final var compacted = lines();
        assertEquals(9, compacted.size(), "reaching the threshold folds the log back to one row per live id");
        assertEquals(compacted.stream().sorted().toList(), compacted, "a compaction writes the rows sorted by id");
        assertEquals(folded(), compacted);
        assertFalse(new File(pkIndexFile().getPath() + ".repair").exists(), "the rewrite is atomic");
    }

    @Test
    public void aLoadCompactsALogThatIsMostlyDead() throws IOException {
        final var text = new StringBuilder();
        for (var i = 0; i < Globals.PK_LOG_MIN_DEAD_ROWS + 1; i++) {
            text.append(row("only", i, i).toFileEntry()).append(Globals.NEWLINE);
        }
        Files.writeString(pkIndexFile().toPath(), text, StandardCharsets.UTF_8);

        assertEquals(List.of(row("only", Globals.PK_LOG_MIN_DEAD_ROWS, Globals.PK_LOG_MIN_DEAD_ROWS).toFileEntry()),
                folded());

        assertEquals(1, lines().size());
    }

    @Test
    public void anUnterminatedLastRowIsDroppedEvenWhenItParses() throws IOException {
        store.appendRows(DB, COLL, List.of(row("a", 0, 1)));
        final var whole = row("b", 10, 1_700_000_000_000L).toFileEntry();
        final var torn = whole.substring(0, whole.length() - 4);
        Files.writeString(pkIndexFile().toPath(), torn, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertEquals(170_000_000L, PkIndexEntry.fromIndexFileEntry(DB, COLL, torn).getVersion(),
                "the torn row must still parse, as a wrong version, or this test covers nothing");

        assertEquals(List.of(row("a", 0, 1).toFileEntry()), folded());
        assertEquals(List.of(row("a", 0, 1).toFileEntry()), lines(), "the heal rewrites the file without the torn row");
    }

    @Test
    public void anAppendCutsATornTailInsteadOfJoiningIt() throws IOException {
        store.appendRows(DB, COLL, List.of(row("a", 0, 1)));
        Files.writeString(pkIndexFile().toPath(), "b" + Globals.ID_SEPARATOR + "10", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        store.appendRows(DB, COLL, List.of(row("c", 20, 1)));

        assertEquals(List.of(row("a", 0, 1).toFileEntry(), row("c", 20, 1).toFileEntry()), lines());
    }

    @Test
    public void aMalformedMiddleLineIsHealed() throws IOException {
        Files.writeString(pkIndexFile().toPath(), row("a", 0, 1).toFileEntry() + Globals.NEWLINE + "not a row"
                + Globals.NEWLINE + row("b", 10, 1).toFileEntry() + Globals.NEWLINE, StandardCharsets.UTF_8);

        assertEquals(List.of(row("a", 0, 1).toFileEntry(), row("b", 10, 1).toFileEntry()), folded());
        assertEquals(List.of(row("a", 0, 1).toFileEntry(), row("b", 10, 1).toFileEntry()), lines());
    }

    @Test
    public void aFileWithNoParseableLineStaysUnrecognisedAndUntouched() throws IOException {
        Files.writeString(pkIndexFile().toPath(), "garbage" + Globals.NEWLINE + "more", StandardCharsets.UTF_8);
        final var before = Files.readAllBytes(pkIndexFile().toPath());

        assertThrows(IOException.class, () -> store.readWholePkIndexFile(DB, COLL));
        assertTrue(store.readRecognisedPkIndex(DB, COLL).isEmpty());
        assertFalse(store.rewriteRows(DB, COLL, rows -> rows));

        assertArrayEquals(before, Files.readAllBytes(pkIndexFile().toPath()));
    }

    @Test
    public void aFileOfOnlyDeleteRowsIsRecognisedAndEmpty() throws IOException {
        Files.writeString(pkIndexFile().toPath(), PkIndexEntry.toDeleteRow("gone") + Globals.NEWLINE,
                StandardCharsets.UTF_8);

        assertTrue(store.readWholePkIndexFile(DB, COLL).isEmpty());
    }

    @Test
    public void aFailedAppendLeavesTheFileByteIdentical() throws IOException {
        store.appendRows(DB, COLL, List.of(row("a", 0, 1)));
        final var before = Files.readAllBytes(pkIndexFile().toPath());
        assertTrue(pkIndexFile().setWritable(false));
        try {
            assertThrows(IOException.class, () -> store.appendReplacements(DB, COLL, List.of(row("a", 10, 2))));
        } finally {
            assertTrue(pkIndexFile().setWritable(true));
        }

        assertArrayEquals(before, Files.readAllBytes(pkIndexFile().toPath()));
    }

    @Test
    public void anAppendAfterAnOutsideWriteFoldsTheFileFirst() throws IOException {
        store.appendRows(DB, COLL, List.of(row("a", 0, 1)));
        final var outside = new StringBuilder();
        for (var i = 0; i < Globals.PK_LOG_MIN_DEAD_ROWS * 2; i++) {
            outside.append(row("a", i, i).toFileEntry()).append(Globals.NEWLINE);
        }
        Files.writeString(pkIndexFile().toPath(), outside, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        store.appendReplacements(DB, COLL, List.of(row("a", 99_999, 9)));

        assertEquals(
                List.of(row("a", Globals.PK_LOG_MIN_DEAD_ROWS * 2 - 1, Globals.PK_LOG_MIN_DEAD_ROWS * 2 - 1)
                        .toFileEntry(), row("a", 99_999, 9).toFileEntry()),
                lines(),
                "a write the store did not make voids its counters, so the append folds the file before adding to it");
        assertEquals(List.of(row("a", 99_999, 9).toFileEntry()), folded());
    }

    @Test
    public void anAppendToAFreshFileCreatesItWithoutABlankLine() throws IOException {
        store.indexNewPKValue(DB, COLL, "first", 0L, 10, 0L, 1L);

        final var content = Files.readString(pkIndexFile().toPath(), StandardCharsets.UTF_8);
        assertEquals(row("first", 0, 1).toFileEntry() + Globals.NEWLINE, content);
    }

    @Test
    public void anEmptyAppendTouchesNothing() throws IOException {
        store.appendRows(DB, COLL, List.of());

        assertFalse(pkIndexFile().exists());
    }

    @Test
    public void aCompactionThatFailsAfterTheAppendDoesNotFailTheAppend() throws IOException {
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 10; i++) {
            rows.add(row("id-" + i, i * 10L, 1));
        }
        store.appendRows(DB, COLL, rows);
        for (var round = 0; round < Globals.PK_LOG_MIN_DEAD_ROWS - 1; round++) {
            store.appendReplacements(DB, COLL, List.of(row("id-" + (round % 10), 1000L + round, 2)));
        }
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readAllBytes(pkIndexFile().toPath())).thenThrow(new IOException("disk gone"));

            assertDoesNotThrow(() -> store.appendDeletes(DB, COLL, List.of("id-9")),
                    "the delete row landed, so a failed compaction must not report the append as failed");
        }

        assertEquals(PkIndexEntry.toDeleteRow("id-9"), lines().getLast());
        assertFalse(folded().stream().anyMatch(row -> row.startsWith("id-9")));
    }
}
