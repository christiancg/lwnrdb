package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WalPlanTest {
    private static final String DB = "planDb";
    private static final String COLL = "planColl";

    @TempDir
    File tmp;

    private ReplacerFixture rf;
    private File readOnly;

    @BeforeEach
    public void setUp() throws Exception {
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
    }

    @AfterEach
    public void tearDown() {
        if (readOnly != null) {
            assertTrue(readOnly.setWritable(true, true));
        }
    }

    private void makeReadOnly(File file) {
        readOnly = file;
        assertTrue(file.setWritable(false, false));
    }

    private File log() {
        return rf.fx.paths.writeAheadLog(DB, COLL);
    }

    private WalCodec.Decoded decodedLog() throws IOException {
        return WalCodec.decodeAll(Files.readAllBytes(log().toPath()));
    }

    @Test
    public void test_a_write_that_cannot_be_applied_is_taken_back_and_aborted_in_the_log() throws Exception {
        final var page = Files.readAllBytes(rf.fx.page(0).toPath());
        final var pk = Files.readAllBytes(rf.fx.paths.pkIndexFile(DB, COLL).toPath());
        final var records = decodedLog().records().size();
        makeReadOnly(rf.fx.page(0));

        assertThrows(IOException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));

        assertArrayEquals(page, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertArrayEquals(pk, Files.readAllBytes(rf.fx.paths.pkIndexFile(DB, COLL).toPath()));
        assertEquals(records, decodedLog().records().size(), "the aborted record is not replayed");
    }

    @Test
    public void test_the_write_after_an_aborted_one_lands_where_the_log_says() throws Exception {
        makeReadOnly(rf.fx.page(0));
        assertThrows(IOException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));
        assertTrue(readOnly.setWritable(true, true));

        rf.replace("b", "newer-b", 0, PagePlacement.APPEND);
        WalRedo.replay(rf.fx.paths, DB, COLL, decodedLog());

        assertEquals(StorageFixture.record("b", "newer-b"), rf.fx.readAt(rf.fx.indexed("b")));
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void test_a_blank_that_cannot_be_applied_keeps_the_write_and_is_applied_before_the_next() throws Exception {
        final var old = rf.fx.indexed("b");
        makeReadOnly(rf.fx.page(0));

        final var moved = rf.replace("b", "moved-b", 1, null);

        assertEquals(StorageFixture.record("b", "moved-b"), rf.fx.readAt(moved), "the copy and its row landed");
        assertEquals(StorageFixture.record("b", "old-b"), rf.fx.readAt(old), "the old copy is not blanked yet");
        assertTrue(readOnly.setWritable(true, true));

        rf.replace("c", "new-c", 1, PagePlacement.APPEND);

        assertTrue(rf.fx.readAt(old).isBlank(), "the next write applies the logged blank first");
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void test_a_write_refuses_while_an_earlier_one_cannot_be_applied() throws Exception {
        makeReadOnly(rf.fx.page(0));
        rf.replace("b", "moved-b", 1, null);

        assertThrows(IOException.class, () -> rf.replace("c", "new-c", 1, PagePlacement.APPEND),
                "a write must not compute its offsets over a page the log has not finished");
    }

    @Test
    public void test_a_checkpoint_applies_what_an_earlier_write_could_not() throws Exception {
        final var old = rf.fx.indexed("b");
        makeReadOnly(rf.fx.page(0));
        rf.replace("b", "moved-b", 1, null);
        assertTrue(readOnly.setWritable(true, true));

        new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);

        assertTrue(rf.fx.readAt(old).isBlank());
        assertEquals(List.of(), decodedLog().records());
    }

    @Test
    public void test_an_empty_plan_writes_nothing() throws Exception {
        final var before = Files.readAllBytes(log().toPath());

        new WalPlan(rf.fx.paths, rf.fx.store, DB, COLL).commit();

        assertArrayEquals(before, Files.readAllBytes(log().toPath()));
    }

    @Test
    public void test_one_record_carries_every_line_of_a_page_as_one_change() throws Exception {
        final var plan = new WalPlan(rf.fx.paths, rf.fx.store, DB, COLL);
        final var first = plan.append(0, ReplacerFixture.line("x", "x"));
        final var second = plan.append(0, ReplacerFixture.line("y", "y"));
        plan.commit();

        final var record = decodedLog().records().getLast();
        assertEquals(1, record.changes().size());
        assertEquals(first, record.changes().getFirst().offset());
        assertEquals(second - first, ReplacerFixture.line("x", "x").length);
        assertEquals(StorageFixture.record("x", "x") + StorageFixture.record("y", "y"),
                new String(record.changes().getFirst().bytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
