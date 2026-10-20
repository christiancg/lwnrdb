package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestUtils;

public class WriteAheadLogTest {
    private static final String DB = "walDb";
    private static final String COLL = "walColl";
    private static final long SHIPPED_CHECKPOINT_BYTES = 8L * 1024 * 1024;

    @TempDir
    File tmp;

    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", SHIPPED_CHECKPOINT_BYTES);
    }

    private File log() {
        return rf.fx.paths.writeAheadLog(DB, COLL);
    }

    private WalCodec.Decoded decoded() throws IOException {
        return WalCodec.decodeAll(Files.readAllBytes(log().toPath()));
    }

    @Test
    public void test_the_first_write_starts_the_log_with_a_checkpoint() throws Exception {
        final var decoded = decoded();

        assertEquals(DB, decoded.checkpoint().dbName());
        assertEquals(COLL, decoded.checkpoint().collName());
        assertEquals(3, decoded.records().size(), "one record per seeded row");
    }

    @Test
    public void test_the_log_checkpoints_itself_past_wal_checkpoint_bytes() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", 1L);

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        assertEquals(List.of(), decoded().records());
        assertEquals(rf.fx.page(0).length(), decoded().checkpoint().pageLengths().get(0L));
        assertEquals(rf.fx.paths.pkIndexFile(DB, COLL).length(), decoded().checkpoint().pkIndexLength());
    }

    @Test
    public void test_a_record_appended_by_another_writer_is_seen_by_length() throws Exception {
        final var other = WalCodec
                .encode(new WalRecord(500L, List.of(new WalChange(WalTarget.PAGE_BLANK, 0L, 0L, 0L, new byte[0]))));
        Files.write(log().toPath(), other, StandardOpenOption.APPEND);

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        final var records = decoded().records();
        assertEquals(501L, records.getLast().seq(), "the bookkeeping re-reads a log it did not write last");
        assertEquals(5, records.size());
    }

    @Test
    public void test_a_torn_tail_is_cut_before_the_next_append() throws Exception {
        Files.write(log().toPath(), new byte[]{'W', 'R', 0}, StandardOpenOption.APPEND);

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        final var decoded = decoded();
        assertEquals(Files.size(log().toPath()), decoded.cleanLength());
        assertEquals(4, decoded.records().size());
    }

    @Test
    public void test_checkpoint_all_empties_every_log_under_the_data_folder() throws Exception {
        final var other = new ReplacerFixture(tmp, DB, "otherColl");
        other.fx.seed(0, "x");

        new WriteAheadLog(rf.fx.paths).checkpointAll();

        assertEquals(List.of(), decoded().records());
        assertEquals(List.of(), WalCodec
                .decodeAll(Files.readAllBytes(other.fx.paths.writeAheadLog(DB, "otherColl").toPath())).records());
    }

    @Test
    public void test_a_dropped_collection_is_not_checkpointed_back_into_existence() throws Exception {
        final var folder = rf.fx.paths.collectionFolder(DB, COLL);
        assertTrue(FileTreeDeleter.delete(folder));

        new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);

        assertFalse(folder.exists());
    }
}
