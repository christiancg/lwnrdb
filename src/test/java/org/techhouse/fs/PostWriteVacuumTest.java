package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.LongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class PostWriteVacuumTest {
    private static final String DB = "postWriteDb";
    private static final String COLL = "postWriteColl";
    private static final long MAX_PAGE_SIZE = 1024 * 1024;
    private static final String LARGE = "x".repeat(20 * 1024);

    @TempDir
    File tmp;

    private final Configuration configuration = Configuration.getInstance();
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private long shippedMaxPageSize;
    private IntConsumer originalHalt;
    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        shippedMaxPageSize = configuration.getMaxPageSize();
        TestUtils.setPrivateField(configuration, "maxPageSize", MAX_PAGE_SIZE);
        originalHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
        rf.fx.seed(1, "d", "e");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", shippedMaxPageSize);
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        TestUtils.setPrivateField(faultInjector, "halt", originalHalt);
    }

    private static LongFunction<PageOccupancy> occupancyOf(List<PkIndexEntry> rows) {
        return page -> {
            final List<PkIndexEntry> onPage = rows.stream().filter(row -> row.getPage() == page).toList();
            return new PageOccupancy(PageRows.liveBytes(onPage), onPage.size());
        };
    }

    private PkIndexEntry update(String id, String value, long targetPage) throws IOException {
        final var rows = rf.fx.indexedRows();
        return rf.replacer.replace(rf.entry(id, value, targetPage), ReplacerFixture.line(id, value), rf.fx.indexed(id),
                targetPage, targetPage == rf.fx.indexed(id).getPage() ? PagePlacement.APPEND : null, rows,
                occupancyOf(rows));
    }

    private String firstPageText() throws IOException {
        return Files.readString(rf.fx.page(0).toPath());
    }

    @Test
    public void test_an_update_whose_blank_leaves_as_many_dead_as_live_bytes_vacuums_its_page_afterward()
            throws Exception {
        update("b", LARGE, 0);
        assertTrue(rf.vacuumed.isEmpty(), "a 25-byte hole is below the 16 KB floor");

        final var updated = update("b", "small-b", 0);

        assertEquals(1, rf.vacuumed.size());
        assertEquals(
                StorageFixture.record("a", "old-a") + StorageFixture.record("c", "old-c")
                        + StorageFixture.record("b", "small-b"),
                firstPageText(), "the vacuum keeps no copy the update blanked");
        assertEquals(StorageFixture.record("b", "small-b"), rf.valueOf(updated), "the returned row is repositioned");
        assertEquals(updated.toFileEntry(), rf.fx.indexed("b").toFileEntry());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        assertEquals(0, rf.intents());
    }

    @Test
    public void test_an_update_below_both_thresholds_leaves_its_page_alone() throws Exception {
        final var lengthBefore = rf.fx.page(0).length();

        update("b", "new-b", 0);

        assertTrue(rf.vacuumed.isEmpty());
        assertTrue(rf.fx.page(0).length() > lengthBefore);
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void test_a_relocation_vacuums_the_source_page_it_left_dead() throws Exception {
        update("b", LARGE, 0);
        final var target = rf.fx.indexed("e");

        final var moved = update("b", "moved-b", 1);

        assertEquals(1L, moved.getPage());
        assertEquals(1, rf.vacuumed.size());
        assertEquals(0L, rf.vacuumed.getFirst().page());
        assertEquals(StorageFixture.record("a", "old-a") + StorageFixture.record("c", "old-c"), firstPageText());
        assertEquals(StorageFixture.record("b", "moved-b"), rf.valueOf(moved));
        assertEquals(target.toFileEntry(), rf.fx.indexed("e").toFileEntry(), "the target page is not rewritten");
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void test_an_update_that_empties_its_source_page_truncates_it() throws Exception {
        update("d", "moved-d", 0);
        update("e", "moved-e", 0);

        assertTrue(rf.fx.page(1).exists());
        assertEquals(0L, rf.fx.page(1).length());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void test_a_vacuum_failure_after_a_durable_update_is_logged_and_the_update_stands() throws Exception {
        update("b", LARGE, 0);
        assertTrue(rf.fx.paths.vacuumFile(DB, COLL, 0).mkdirs(), "a folder where the temp file goes");

        final var updated = update("b", "small-b", 0);

        assertEquals(StorageFixture.record("b", "small-b"), rf.valueOf(updated));
        assertEquals(updated.toFileEntry(), rf.fx.indexed("b").toFileEntry());
        assertTrue(rf.vacuumed.isEmpty());
        assertEquals(0, rf.intents());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void test_the_post_update_vacuum_begins_only_once_the_update_is_committed_and_blanked() throws Exception {
        update("b", LARGE, 0);
        final var old = rf.fx.indexed("b");
        final var observed = new ArrayList<String>();
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        TestUtils.setPrivateField(faultInjector, "halt", (IntConsumer) _ -> observed.add(describe(old)));
        faultInjector.arm(FaultPoint.WAL_REWRITE_BEGUN, FaultAction.HALT, DB + "|" + COLL);

        update("b", "small-b", 0);

        assertEquals(List.of("committed=true blanked=true"), observed);
    }

    private String describe(PkIndexEntry old) {
        try {
            return "committed="
                    + (rf.fx.indexed("b").getVersion() == StorageFixture.NEW_VERSION
                            && rf.fx.indexed("b").getLength() < old.getLength())
                    + " blanked=" + rf.fx.readAt(old).isBlank();
        } catch (IOException e) {
            return e.toString();
        }
    }

    @Test
    public void test_a_bulk_update_vacuums_each_touched_page_that_crossed_the_threshold_once_the_batch_landed()
            throws Exception {
        rf.bulk(List.of(rf.indexed("b", LARGE)));
        final var rows = rf.fx.indexedRows();
        final var lengthOfPageOne = rf.fx.page(1).length();

        final var updated = rf.bulk(
                List.of(rf.indexed("b", "small-b"), rf.indexed("a", "new-a"), rf.indexed("d", "new-d")), rows,
                occupancyOf(rows));

        assertEquals(1, rf.vacuumed.size());
        assertEquals(0L, rf.vacuumed.getFirst().page());
        assertEquals(StorageFixture.record("c", "old-c") + StorageFixture.record("b", "small-b")
                + StorageFixture.record("a", "new-a"), firstPageText());
        assertTrue(rf.fx.page(1).length() > lengthOfPageOne, "page 1 keeps its small hole");
        for (final var entry : updated) {
            assertEquals(entry.getIndex().toFileEntry(), rf.fx.indexed(entry.get_id()).toFileEntry());
            assertEquals(StorageFixture.record(entry.get_id(), entry.getData().get("v").asJsonString().getValue()),
                    rf.valueOf(entry.getIndex()));
        }
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        assertFalse(rf.fx.paths.vacuumFile(DB, COLL, 0).exists());
    }
}
