package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public class WriteIntentRecoveryDeleteVacuumTest {
    private static final String DB = "deleteDb";
    private static final String COLL = "deleteColl";

    @TempDir
    File tmp;

    private IntentFixture it;
    private String page0;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        it = new IntentFixture(tmp, DB, COLL);
        it.fx.seed(0, "a", "b", "c");
        page0 = it.page(0);
    }

    @Test
    public void anUncommittedDeleteChangesNothing() throws Exception {
        it.deleteSubject();

        assertEquals(List.of(), it.recover());

        assertEquals(page0, it.page(0));
        assertEquals(List.of("a", "b", "c"), it.fx.indexedRows().stream().map(PkIndexEntry::getValue).toList());
        assertEquals(0, it.intents());
    }

    @Test
    public void aCommittedDeleteIsBlankedAndReported() throws Exception {
        final var removed = it.fx.indexed("b");
        it.deleteSubject();
        it.commitSubjectDelete();

        final var completed = it.recover();

        assertEquals(List.of(removed.toFileEntry()), completed.stream().map(PkIndexEntry::toFileEntry).toList());
        assertEquals(Map.of("a", "old-a", "c", "old-c"), it.valuesById(0));
        assertEquals(page0.length(), it.page(0).length(), "a delete never moves another record");
        assertTrue(it.fx.everyRowReadsItsOwnRecord());
        assertEquals(0, it.intents());
    }

    @Test
    public void aCommittedDeleteAlreadyBlankedIsStillReported() throws Exception {
        final var removed = it.fx.indexed("b");
        it.deleteSubject();
        it.commitSubjectDelete();
        it.blank(removed);
        final var blanked = it.page(0);

        assertEquals(1, it.recover().size(), "the index cleanup is owed whether or not the blank ran");

        assertEquals(blanked, it.page(0));
    }

    @Test
    public void aDeletedIdInsertedAgainBeforeTheRestartStillHasItsOldCopyBlanked() throws Exception {
        final var removed = it.fx.indexed("b");
        it.deleteSubject();
        it.commitSubjectDelete();
        final var reinserted = it.appendCopy("b", 0);
        it.fx.store.appendRows(DB, COLL, List.of(reinserted));

        assertEquals(List.of(), it.recover(), "the id is live again, so no index cleanup is owed");

        assertTrue(it.fx.readAt(removed).isBlank(), "the stale copy must not stay a second record of the id");
        assertEquals(Map.of("a", "old-a", "b", "new-b", "c", "old-c"), it.valuesById(0));
        assertTrue(it.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void thePageSidePassCompletesADelete() throws Exception {
        it.deleteSubject();

        assertEquals(1, it.fx.recovery.recoverPagesOf(DB, COLL));

        assertEquals(Map.of("a", "old-a", "c", "old-c"), it.valuesById(0));
        assertEquals(0, it.intents());
    }

    private String compactedWithoutB() {
        return StorageFixture.record("a", "old-a") + StorageFixture.record("c", "old-c");
    }

    private void deleteB() throws Exception {
        final var removed = it.fx.indexed("b");
        it.blank(removed);
        it.commitSubjectDelete();
    }

    @Test
    public void aVacuumKilledBeforeItsRenameOnlyDropsTheTempFile() throws Exception {
        deleteB();
        final var holed = it.page(0);
        it.vacuumFirstPage();
        final var temp = it.fx.paths.vacuumFile(DB, COLL, 0);
        Files.writeString(temp.toPath(), StorageFixture.record("a", "old-a"), StandardCharsets.UTF_8);
        final var rows = it.fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList();

        it.recover();

        assertFalse(temp.exists());
        assertEquals(holed, it.page(0));
        assertEquals(rows, it.fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList());
        assertEquals(0, it.intents());
    }

    @Test
    public void aVacuumKilledAfterItsRenameReDerivesTheMovedRows() throws Exception {
        deleteB();
        it.vacuumFirstPage();
        Files.writeString(it.fx.page(0).toPath(), compactedWithoutB(), StandardCharsets.UTF_8);
        final var stale = it.fx.indexed("c");
        assertTrue(stale.getPosition() + stale.getLength() > it.fx.page(0).length(),
                "the fixture must leave c's row at its pre-vacuum offset");

        assertEquals(List.of(), it.recover());

        assertTrue(it.fx.everyRowReadsItsOwnRecord());
        assertEquals(StorageFixture.OLD_VERSION, it.fx.indexed("c").getVersion(), "a moved row keeps its version");
        assertEquals(0, it.intents());
    }

    @Test
    public void aVacuumKilledAfterItsRowsLandedIsJustEnded() throws Exception {
        deleteB();
        it.vacuumFirstPage();
        Files.writeString(it.fx.page(0).toPath(), compactedWithoutB(), StandardCharsets.UTF_8);
        it.recover();
        final var rows = Files.readAllBytes(it.fx.paths.pkIndexFile(DB, COLL).toPath());
        it.vacuumFirstPage();

        it.recover();

        assertEquals(new String(rows, StandardCharsets.UTF_8),
                Files.readString(it.fx.paths.pkIndexFile(DB, COLL).toPath(), StandardCharsets.UTF_8));
        assertEquals(0, it.intents());
    }

    @Test
    public void aVacuumedPageHoldingOneIdTwiceIsRefusedAndTheIntentKept() throws Exception {
        it.vacuumFirstPage();
        Files.writeString(it.fx.page(0).toPath(), StorageFixture.record("c", "dup"), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        final var rows = Files.readAllBytes(it.fx.paths.pkIndexFile(DB, COLL).toPath());

        assertEquals(List.of(), it.recover());

        assertEquals(1, it.intents());
        assertEquals(new String(rows, StandardCharsets.UTF_8),
                Files.readString(it.fx.paths.pkIndexFile(DB, COLL).toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void thePageSidePassDropsAVacuumTempFile() throws Exception {
        it.vacuumFirstPage();
        final var temp = it.fx.paths.vacuumFile(DB, COLL, 0);
        Files.writeString(temp.toPath(), "partial", StandardCharsets.UTF_8);

        assertEquals(1, it.fx.recovery.recoverPagesOf(DB, COLL));

        assertFalse(temp.exists());
        assertEquals(page0, it.page(0));
    }

    @Test
    public void aTornIntentIsDiscardedWithoutTouchingAnything() throws Exception {
        final var intent = it.deleteSubject();
        final var bytes = Files.readAllBytes(intent.log().toPath());
        Files.write(intent.log().toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 2));

        assertEquals(List.of(), it.recover());

        assertEquals(page0, it.page(0));
        assertEquals(0, it.intents());
    }

    @Test
    public void aTornIntentIsNotReplayable() throws Exception {
        final var intent = it.deleteSubject();
        Files.writeString(intent.log().toPath(), "", StandardCharsets.UTF_8);

        assertTrue(it.fx.recovery.replayableIntentsOf(DB, COLL).isEmpty());
        assertEquals(0, it.fx.recovery.recoverPagesOf(DB, COLL));
        assertNull(it.fx.indexed("zzz"));
    }
}
