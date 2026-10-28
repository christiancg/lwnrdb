package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public class BulkDeleteVacuumTest {
    private static final String DB = "bulkDeleteDb";
    private static final String COLL = "bulkDeleteColl";

    @TempDir
    File tmp;

    private final Configuration configuration = Configuration.getInstance();
    private long shippedMaxPageSize;
    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        shippedMaxPageSize = configuration.getMaxPageSize();
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
        rf.fx.seed(1, "d", "e", "f");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", shippedMaxPageSize);
    }

    private List<PkIndexEntry> deleteInOneBatch(String... ids) throws Exception {
        final var rows = new ArrayList<PkIndexEntry>();
        for (final var id : ids) {
            rows.add(rf.fx.indexed(id));
        }
        rf.appender.bulkInsertAndDelete(DB, COLL, List.of(), DbEntry::getPage, _ -> null, rows);
        return rf.fx.indexedRows();
    }

    private static LongFunction<PageOccupancy> occupancyOf(List<PkIndexEntry> rows) {
        return page -> {
            final List<PkIndexEntry> onPage = rows.stream().filter(row -> row.getPage() == page).toList();
            return new PageOccupancy(PageRows.liveBytes(onPage), onPage.size());
        };
    }

    @Test
    public void test_a_page_emptied_by_the_batch_is_truncated_to_zero_bytes_and_kept() throws Exception {
        final var survivors = deleteInOneBatch("a", "b", "c");

        rf.replacer.vacuumAfterDeletes(DB, COLL, List.of(0L, 0L), survivors, occupancyOf(survivors));

        assertTrue(rf.fx.page(0).exists());
        assertEquals(0L, rf.fx.page(0).length());
        assertEquals(3, rf.fx.indexedRows().size());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        assertEquals(0, rf.intents());
    }

    @Test
    public void test_a_page_left_a_quarter_dead_is_vacuumed_and_its_survivor_moved() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", rf.fx.page(1).length() + 3);
        final var survivor = StorageFixture.record("f", "old-f").length();
        final var survivors = deleteInOneBatch("d", "e");

        rf.replacer.vacuumAfterDeletes(DB, COLL, List.of(1L), survivors, occupancyOf(survivors));

        assertEquals(survivor, rf.fx.page(1).length());
        assertEquals(0L, rf.fx.indexed("f").getPosition());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        assertEquals(List.of(1L), rf.vacuumed.stream().map(VacuumedPage::page).toList());
    }

    @Test
    public void test_a_page_with_little_dead_space_is_left_as_it_is() throws Exception {
        final var lengthBefore = rf.fx.page(1).length();
        final var survivors = deleteInOneBatch("d");

        rf.replacer.vacuumAfterDeletes(DB, COLL, List.of(1L), survivors, occupancyOf(survivors));

        assertEquals(lengthBefore, rf.fx.page(1).length());
        assertTrue(rf.vacuumed.isEmpty());
    }

    @Test
    public void test_a_page_without_an_occupancy_row_is_skipped() throws Exception {
        final var lengthBefore = rf.fx.page(0).length();
        final var survivors = deleteInOneBatch("a", "b", "c");

        rf.replacer.vacuumAfterDeletes(DB, COLL, List.of(0L), survivors, _ -> null);

        assertEquals(lengthBefore, rf.fx.page(0).length());
        assertTrue(rf.vacuumed.isEmpty());
    }

    @Test
    public void test_a_vacuum_that_is_refused_is_logged_and_the_delete_stands() throws Exception {
        final var lengthBefore = rf.fx.page(0).length();
        deleteInOneBatch("a", "b");

        rf.replacer.vacuumAfterDeletes(DB, COLL, List.of(0L), List.of(), _ -> new PageOccupancy(0L, 0L));

        assertEquals(lengthBefore, rf.fx.page(0).length());
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        assertEquals(1, rf.fx.indexedRows().stream().filter(row -> row.getPage() == 0L).count());
        assertEquals(0, rf.intents());
    }
}
