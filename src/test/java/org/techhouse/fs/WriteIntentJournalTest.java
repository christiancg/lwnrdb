package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;

public class WriteIntentJournalTest {
    private static final String DB = "intentDb";
    private static final String COLL = "intent-coll";

    @TempDir
    File tmp;

    private FilePaths paths;
    private WriteIntentJournal journal;

    @BeforeEach
    public void setUp() {
        paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(paths.collectionFolder(DB, COLL).mkdirs());
        journal = new WriteIntentJournal(paths);
    }

    private static List<WriteIntent> everyKind() {
        final var escaped = "id" + Globals.ID_SEPARATOR + "with\\escapes";
        return List.of(new WriteIntent.Replace(escaped, 2, 30, 40, 1_700_000_000_000L, 5, 900),
                new WriteIntent.Delete("plain", 0, 0, 12, 7), new WriteIntent.Vacuum(3));
    }

    @Test
    public void anIntentRoundTripsEveryKindWithEscapedIds() throws IOException {
        final var intent = journal.begin(DB, COLL, everyKind());

        final var decoded = WriteIntentJournal.decode(intent.file(), Files.readAllBytes(intent.file().toPath()));

        assertNotNull(decoded);
        assertEquals(DB, decoded.dbName());
        assertEquals(COLL, decoded.collName());
        assertEquals(everyKind(), decoded.items());
    }

    @Test
    public void aFileWithoutItsEndLineIsTorn() throws IOException {
        final var intent = journal.begin(DB, COLL, everyKind());
        final var bytes = Files.readAllBytes(intent.file().toPath());

        for (var cut = 0; cut < bytes.length; cut++) {
            assertNull(WriteIntentJournal.decode(intent.file(), Arrays.copyOf(bytes, cut)), "cut at " + cut);
        }
    }

    @Test
    public void aCountThatDisagreesWithTheItemsIsTorn() {
        final var text = String.join(Globals.ID_SEPARATOR, "INTENT", DB, COLL, "2") + "\n"
                + String.join(Globals.ID_SEPARATOR, "VACUUM", "1") + "\nEND\n";

        assertNull(WriteIntentJournal.decode(new File("x"), text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void anUnknownItemOrAMangledFieldIsTorn() {
        for (final var item : List.of("MOVE" + Globals.ID_SEPARATOR + "1", "VACUUM" + Globals.ID_SEPARATOR + "x",
                "DELETE" + Globals.ID_SEPARATOR + "id")) {
            final var text = String.join(Globals.ID_SEPARATOR, "INTENT", DB, COLL, "1") + "\n" + item + "\nEND\n";
            assertNull(WriteIntentJournal.decode(new File("x"), text.getBytes(StandardCharsets.UTF_8)), item);
        }
    }

    @Test
    public void endDeletesTheFile() throws IOException {
        final var intent = journal.begin(DB, COLL, everyKind());

        journal.end(intent);

        assertFalse(intent.file().exists());
    }

    @Test
    public void anIntentThatCannotBeDeletedIsEmptiedAndReadsAsTorn() throws IOException {
        final var intent = journal.begin(DB, COLL, everyKind());
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(any(Path.class))).thenThrow(new IOException("unlink refused"));
            assertDoesNotThrow(() -> journal.end(intent));
        }

        assertTrue(intent.file().exists());
        assertEquals(0L, intent.file().length());
        assertNull(WriteIntentJournal.decode(intent.file(), Files.readAllBytes(intent.file().toPath())));
    }

    @Test
    public void anIntentThatCanBeNeitherDeletedNorEmptiedIsLeftAsItWas() throws IOException {
        final var intent = journal.begin(DB, COLL, everyKind());
        final var bytes = Files.readAllBytes(intent.file().toPath());
        assertTrue(intent.file().setWritable(false));
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(any(Path.class))).thenThrow(new IOException("unlink refused"));
            assertDoesNotThrow(() -> journal.end(intent));
        } finally {
            assertTrue(intent.file().setWritable(true));
        }

        assertArrayEquals(bytes, Files.readAllBytes(intent.file().toPath()));
    }

    @Test
    public void markerNamesSortInCreationOrder() throws IOException {
        final var first = journal.begin(DB, COLL, List.of(new WriteIntent.Vacuum(9)));
        final var second = journal.begin(DB, COLL, List.of(new WriteIntent.Vacuum(1)));
        final var third = journal.begin(DB, COLL, List.of(new WriteIntent.Vacuum(5)));

        assertEquals(List.of(first.file(), second.file(), third.file()), journal.findMarkerFiles(DB, COLL));
        assertEquals(List.of(first.file(), second.file(), third.file()), journal.findMarkerFiles());
    }

    @Test
    public void anIntentIsCreatedOwnerOnly() throws IOException {
        final var intent = journal.begin(DB, COLL, everyKind());

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(intent.file().toPath()));
    }

    @Test
    public void aMissingDataFolderHasNoMarkers() throws IOException {
        paths.useDbPath(new File(tmp, "absent").getAbsolutePath());

        assertTrue(journal.findMarkerFiles().isEmpty());
        assertTrue(journal.findMarkerFiles(DB, COLL).isEmpty());
    }
}
