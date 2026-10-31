package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public class PointScannedVacuumTest {
    private static final String DB = "pointScanDb";
    private static final String COLL = "pointScanColl";
    private static final long MAX_PAGE_SIZE = 1024 * 1024;
    private static final String LARGE = "x".repeat(20 * 1024);

    @TempDir
    File tmp;

    private final Configuration configuration = Configuration.getInstance();
    private long shippedMaxPageSize;
    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        shippedMaxPageSize = configuration.getMaxPageSize();
        TestUtils.setPrivateField(configuration, "maxPageSize", MAX_PAGE_SIZE);
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
        rf.fx.seed(1, "d", "e");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", shippedMaxPageSize);
    }

    private static LongFunction<PageOccupancy> occupancyOf(List<PkIndexEntry> rows) {
        return page -> {
            final List<PkIndexEntry> onPage = rows.stream().filter(row -> row.getPage() == page).toList();
            return new PageOccupancy(PageRows.liveBytes(onPage), onPage.size());
        };
    }

    private PkIndexEntry updateB(String value, PagePlacement placement, List<PkIndexEntry> residentRows)
            throws IOException {
        final var occupancy = occupancyOf(rf.fx.indexedRows());
        return rf.replacer.replace(rf.entry("b", value, 0), ReplacerFixture.line("b", value), rf.fx.indexed("b"), 0,
                placement, residentRows, occupancy);
    }

    private String firstPage() throws IOException {
        return Files.readString(rf.fx.page(0).toPath());
    }

    @Test
    public void test_a_post_write_vacuum_finds_its_page_rows_by_point_scan() throws Exception {
        updateB(LARGE, PagePlacement.APPEND, rf.fx.indexedRows());

        final var updated = updateB("small-b", PagePlacement.APPEND, PointScannedRows.INSTANCE);

        assertEquals(1, rf.vacuumed.size());
        assertEquals(StorageFixture.record("a", "old-a") + StorageFixture.record("c", "old-c")
                + StorageFixture.record("b", "small-b"), firstPage());
        assertEquals(StorageFixture.record("b", "small-b"), rf.valueOf(updated));
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
    }

    @Test
    public void test_a_vacuum_before_the_write_leaves_the_vacuum_after_it_fresh_rows() throws Exception {
        updateB(LARGE, PagePlacement.APPEND, rf.fx.indexedRows());

        final var updated = updateB("small-b", PagePlacement.VACUUM_THEN_APPEND, PointScannedRows.INSTANCE);

        assertEquals(2, rf.vacuumed.size(), "one vacuum before the append and one after the blank");
        assertEquals(
                StorageFixture.record("a", "old-a") + StorageFixture.record("c", "old-c")
                        + StorageFixture.record("b", "small-b"),
                firstPage(), "the second vacuum read the rows the first one moved");
        assertEquals(StorageFixture.record("b", "small-b"), rf.valueOf(updated));
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        assertEquals(0, rf.intents());
    }

    @Test
    public void test_the_marker_lists_no_rows_of_its_own() {
        assertTrue(PointScannedRows.INSTANCE.isEmpty());
    }
}
