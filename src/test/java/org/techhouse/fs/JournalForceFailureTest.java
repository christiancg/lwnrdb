package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;

public class JournalForceFailureTest {
    private static final String DB = "journalDb";
    private static final String COLL = "journal-coll";

    @TempDir
    File tmp;

    private FilePaths paths;
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(paths.collectionFolder(DB, COLL).mkdirs());
        recorder = RecordingForcer.install(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
        ForceFailures.forgetUnder(tmp);
        TombstoneStore.forgetUnder(tmp);
        WriteIntentJournal.forgetUnder(tmp);
    }

    private static Object inodeOf(File file) throws IOException {
        return Files.readAttributes(file.toPath(), BasicFileAttributes.class).fileKey();
    }

    @Test
    public void aTombstoneFileWhoseForceFailedIsRewrittenBeforeTheNextAppend() throws Exception {
        final var file = paths.tombstoneFile(DB, COLL);
        TombstoneStore.appendResident(file, List.of("a"), List.of(1L), true);
        recorder.failingFor(Globals.TOMBSTONE_FILE_NAME + ".idx");
        assertThrows(IOException.class, () -> TombstoneStore.appendResident(file, List.of("b"), List.of(2L), true));
        recorder.failingFor(null);
        final var failedInode = inodeOf(file);

        TombstoneStore.appendResident(file, List.of("c"), List.of(3L), true);

        assertNotEquals(failedInode, inodeOf(file), "the next append never re-forces the inode whose force failed");
        assertFalse(ForceFailures.hasFailed(file.toPath()));
        final var read = TombstoneStore.read(file);
        assertEquals(1L, read.get("a"));
        assertEquals(3L, read.get("c"));
    }

    @Test
    public void anIntentLogWhoseForceFailedIsRewrittenBeforeTheNextIntent() throws Exception {
        final var journal = new WriteIntentJournal(paths);
        final var held = journal.begin(DB, COLL, List.of(new WriteIntent.Vacuum(1)));
        recorder.failingFor(Globals.INTENT_LOG_EXTENSION);
        assertThrows(IOException.class, () -> journal.begin(DB, COLL, List.of(new WriteIntent.Vacuum(2))));
        recorder.failingFor(null);
        final var log = paths.intentLog(DB, COLL);
        final var failedInode = inodeOf(log);

        final var next = journal.begin(DB, COLL, List.of(new WriteIntent.Vacuum(3)));

        assertNotEquals(failedInode, inodeOf(log), "the next intent never re-forces the inode whose force failed");
        assertFalse(ForceFailures.hasFailed(log.toPath()));
        assertEquals(List.of(held, next), journal.openIntents(DB, COLL));
    }
}
