package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.fs.StorageFixture.NEW_VERSION;
import static org.techhouse.fs.StorageFixture.OLD_VERSION;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public class WriteIntentRecoveryReplaceTest {
    private static final String DB = "replaceDb";
    private static final String COLL = "replaceColl";

    @TempDir
    File tmp;

    private IntentFixture it;
    private String page0;
    private String page1;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        it = new IntentFixture(tmp, DB, COLL);
        it.fx.seed(0, "a", "b", "c");
        it.fx.seed(1, "x");
        page0 = it.page(0);
        page1 = it.page(1);
    }

    private void assertUndone() throws Exception {
        assertEquals(page0, it.page(0));
        assertEquals(page1, it.page(1));
        assertEquals(OLD_VERSION, it.fx.indexed("b").getVersion());
        assertTrue(it.fx.everyRowReadsItsOwnRecord());
        assertEquals(0, it.intents());
    }

    private void assertCompleted(long targetPage) throws Exception {
        final var row = it.fx.indexed("b");
        assertEquals(targetPage, row.getPage());
        assertEquals(NEW_VERSION, row.getVersion());
        assertEquals(StorageFixture.record("b", "new-b"), it.fx.readAt(row));
        if (targetPage != 0) {
            assertEquals(Map.of("a", "old-a", "c", "old-c"), it.valuesById(0));
        }
        assertTrue(it.fx.everyRowReadsItsOwnRecord());
        assertEquals(0, it.intents());
    }

    @Test
    public void anIntentWrittenBeforeAnyAppendChangesNothing() throws Exception {
        it.replaceSubject(0);

        assertEquals(List.of(), it.recover());

        assertUndone();
    }

    @Test
    public void anUncommittedCopyOnTheSamePageIsCutBack() throws Exception {
        it.replaceSubject(0);
        it.appendCopy("b", 0);

        it.recover();

        assertUndone();
    }

    @Test
    public void anUncommittedCopyOnAnotherPageIsCutBack() throws Exception {
        it.replaceSubject(1);
        it.appendCopy("b", 1);

        it.recover();

        assertUndone();
    }

    @Test
    public void anUncommittedCopyBehindACommittedRecordIsBlankedNotCut() throws Exception {
        it.replaceSubject(1);
        final var lengthBefore = it.fx.page(1).length();
        it.appendCopy("b", 1);
        it.fx.seed(1, "later");
        assertTrue(it.fx.indexed("later").getPosition() > lengthBefore);

        it.recover();

        assertEquals(Map.of("later", "old-later", "x", "old-x"), it.valuesById(1));
        assertEquals(OLD_VERSION, it.fx.indexed("b").getVersion());
        assertEquals(StorageFixture.record("b", "old-b"), it.fx.readAt(it.fx.indexed("b")));
        assertTrue(it.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void aCommittedReplaceOnTheSamePageBlanksTheOldCopy() throws Exception {
        it.replaceSubject(0);
        it.commit(it.appendCopy("b", 0));

        it.recover();

        assertCompleted(0);
        assertEquals(Map.of("a", "old-a", "b", "new-b", "c", "old-c"), it.valuesById(0));
        assertEquals(page0.length() + StorageFixture.record("b", "new-b").length(), it.page(0).length());
    }

    @Test
    public void aCommittedRelocationBlanksTheSourceCopy() throws Exception {
        it.replaceSubject(1);
        it.commit(it.appendCopy("b", 1));

        it.recover();

        assertCompleted(1);
        assertEquals(Map.of("b", "new-b", "x", "old-x"), it.valuesById(1));
    }

    @Test
    public void aCommittedReplaceWhoseOldCopyIsAlreadyBlankIsJustEnded() throws Exception {
        final var old = it.fx.indexed("b");
        it.replaceSubject(1);
        it.commit(it.appendCopy("b", 1));
        it.blank(old);
        final var pages = it.page(0) + it.page(1);

        it.recover();

        assertEquals(pages, it.page(0) + it.page(1));
        assertCompleted(1);
    }

    @Test
    public void recoveryRunTwiceEndsTheSame() throws Exception {
        final var intent = it.replaceSubject(1);
        final var bytes = Files.readAllBytes(intent.file().toPath());
        it.commit(it.appendCopy("b", 1));
        it.recover();
        final var pages = it.page(0) + it.page(1);
        final var rows = it.fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList();

        Files.write(intent.file().toPath(), bytes);
        it.recover();

        assertEquals(pages, it.page(0) + it.page(1));
        assertEquals(rows, it.fx.indexedRows().stream().map(PkIndexEntry::toFileEntry).toList());
    }

    @Test
    public void aRegionTheEffectiveRowNamesIsNeverBlanked() throws Exception {
        final var old = it.fx.indexed("b");
        it.replaceSubject(1);
        it.commit(new PkIndexEntry(DB, COLL, "b", old.getPosition(), old.getLength(), 0, OLD_VERSION + 1));

        it.recover();

        assertEquals(StorageFixture.record("b", "old-b"), it.fx.readAt(it.fx.indexed("b")));
        assertEquals(page0, it.page(0));
    }

    @Test
    public void aBulkIntentWhoseCommitWasTornKeepsWhatLandedAndUndoesTheRest() throws Exception {
        final var rowA = it.fx.indexed("a");
        final var rowC = it.fx.indexed("c");
        final var lengthBefore = it.fx.page(0).length();
        it.fx.journal.begin(DB, COLL, List.of(WriteIntent.Replace.of(rowA, rowA.getPosition(), 0, lengthBefore),
                WriteIntent.Replace.of(rowC, rowC.getPosition(), 0, lengthBefore)));
        final var copyA = it.appendCopy("a", 0);
        it.appendCopy("c", 0);
        it.commit(copyA);

        it.recover();

        assertEquals(Map.of("a", "new-a", "b", "old-b", "c", "old-c"), it.valuesById(0));
        assertTrue(it.fx.everyRowReadsItsOwnRecord());
        assertEquals(0, it.intents());
    }

    @Test
    public void theTargetPageAlreadyTruncatedIsLeftAlone() throws Exception {
        it.replaceSubject(2);

        it.recover();

        assertUndone();
        assertFalse(it.fx.page(2).exists());
    }

    @Test
    public void thePageSidePassUndoesAReplaceWhoseOldCopyIsIntact() throws Exception {
        it.replaceSubject(1);
        it.commit(it.appendCopy("b", 1));

        assertEquals(1, it.fx.recovery.recoverPagesOf(DB, COLL));

        assertEquals(Map.of("a", "old-a", "b", "old-b", "c", "old-c"), it.valuesById(0));
        assertEquals(Map.of("x", "old-x"), it.valuesById(1));
        assertEquals(0, it.intents());
    }

    @Test
    public void thePageSidePassKeepsACopyWhoseOldOneIsAlreadyBlank() throws Exception {
        final var old = it.fx.indexed("b");
        it.replaceSubject(1);
        it.commit(it.appendCopy("b", 1));
        it.blank(old);

        it.fx.recovery.recoverPagesOf(DB, COLL);

        assertEquals(Map.of("b", "new-b", "x", "old-x"), it.valuesById(1));
    }

    @Test
    public void anUnrecognisedPkIndexKeepsTheIntent() throws Exception {
        it.replaceSubject(1);
        it.appendCopy("b", 1);
        final var pkFile = it.fx.paths.pkIndexFile(DB, COLL);
        Files.writeString(pkFile.toPath(), "garbage\nmore garbage\n", StandardCharsets.UTF_8);
        final var pages = it.page(0) + it.page(1);

        assertEquals(List.of(), it.recover());

        assertEquals(pages, it.page(0) + it.page(1));
        assertEquals(1, it.intents());
        assertEquals(1, it.fx.recovery.replayableMarkersOf(DB, COLL).size());
    }
}
