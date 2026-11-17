package org.techhouse.unit.ops.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestUtils;

public class SnapshotReadTest {
    private final SnapshotTestSupport support = new SnapshotTestSupport();

    @BeforeEach
    public void setUp() throws Exception {
        support.setUp();
    }

    @AfterEach
    public void tearDown() throws Exception {
        support.tearDown();
    }

    private void seed(int count) {
        for (var i = 0; i < count; i++) {
            support.save("d" + i, i);
        }
    }

    private List<String> readAll(String snapshotId, int limit) {
        return support.summaries(support.readAll(snapshotId, SnapshotTestSupport.COLL, limit));
    }

    @Test
    public void test_an_untouched_collection_reads_its_current_documents_in_id_order() {
        seed(3);
        final var snapshotId = support.start();

        assertEquals(List.of("d0=0", "d1=1", "d2=2"), readAll(snapshotId, 10));
    }

    @Test
    public void test_an_updated_document_reads_its_snapshot_version() {
        seed(3);
        final var snapshotId = support.start();
        support.save("d1", "changed");
        support.save("d1", "changed again");

        assertEquals(List.of("d0=0", "d1=1", "d2=2"), readAll(snapshotId, 10));
    }

    @Test
    public void test_a_deleted_document_is_still_read() {
        seed(3);
        final var snapshotId = support.start();
        support.delete(SnapshotTestSupport.COLL, "d1");
        support.delete(SnapshotTestSupport.COLL, "d2");

        assertEquals(List.of("d0=0", "d1=1", "d2=2"), readAll(snapshotId, 10));
    }

    @Test
    public void test_a_document_inserted_after_the_snapshot_is_skipped() {
        seed(2);
        final var snapshotId = support.start();
        support.save("a-new", "new");
        support.save("z-new", "new");

        assertEquals(List.of("d0=0", "d1=1"), readAll(snapshotId, 10));
    }

    @Test
    public void test_an_inserted_then_updated_or_deleted_document_is_skipped() {
        seed(1);
        final var snapshotId = support.start();
        support.save("n1", "new");
        support.save("n1", "updated");
        support.save("n2", "new");
        support.delete(SnapshotTestSupport.COLL, "n2");

        assertEquals(List.of("d0=0"), readAll(snapshotId, 10));
    }

    @Test
    public void test_a_deleted_then_reinserted_document_reads_its_snapshot_version() {
        seed(2);
        final var snapshotId = support.start();
        support.delete(SnapshotTestSupport.COLL, "d1");
        support.save("d1", "reborn");

        assertEquals(List.of("d0=0", "d1=1"), readAll(snapshotId, 10));
    }

    @Test
    public void test_paging_by_last_id_visits_every_id_once_while_writes_interleave() {
        seed(20);
        final var snapshotId = support.start();
        final var expected = readAll(snapshotId, 100);
        final var seen = new ArrayList<String>();
        String afterId = null;
        var round = 0;
        while (true) {
            final var page = support.page(snapshotId, SnapshotTestSupport.COLL, afterId, 3);
            seen.addAll(support.summaries(page.getResults()));
            support.delete(SnapshotTestSupport.COLL, "d" + round);
            support.save("d" + (19 - round), "late " + round);
            support.save("c" + round, "inserted");
            round++;
            if (page.isDone()) {
                break;
            }
            afterId = page.getLastId();
        }

        assertEquals(expected, seen);
    }

    @Test
    public void test_image_ids_beyond_the_last_live_id_are_still_read() {
        seed(3);
        final var snapshotId = support.start();
        support.delete(SnapshotTestSupport.COLL, "d2");
        final var first = support.page(snapshotId, SnapshotTestSupport.COLL, null, 2);

        assertFalse(first.isDone());
        final var second = support.page(snapshotId, SnapshotTestSupport.COLL, first.getLastId(), 2);
        assertEquals(List.of("d2=2"), support.summaries(second.getResults()));
        assertTrue(second.isDone());
    }

    @Test
    public void test_a_short_page_is_not_done_when_born_ids_were_skipped() {
        seed(1);
        final var snapshotId = support.start();
        support.save("a1", "new");
        support.save("a2", "new");

        final var first = support.page(snapshotId, SnapshotTestSupport.COLL, null, 2);

        assertTrue(first.getResults().isEmpty());
        assertFalse(first.isDone());
        assertEquals("a2", first.getLastId());
        final var second = support.page(snapshotId, SnapshotTestSupport.COLL, first.getLastId(), 2);
        assertEquals(List.of("d0=0"), support.summaries(second.getResults()));
        assertTrue(second.isDone());
    }

    @Test
    public void test_an_empty_collection_is_done_at_once() {
        final var snapshotId = support.start();

        final var page = support.page(snapshotId, SnapshotTestSupport.OTHER, null, 5);

        assertTrue(page.getResults().isEmpty());
        assertTrue(page.isDone());
    }

    @Test
    public void test_a_document_relocated_by_growing_is_read_once_in_its_snapshot_version() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "maxPageSize", 1024L);
        try {
            seed(5);
            final var snapshotId = support.start();
            final var pagesBefore = support.fs.pageFileCount(SnapshotTestSupport.DB, SnapshotTestSupport.COLL);
            support.save("d0", "x".repeat(2000));

            assertTrue(support.fs.pageFileCount(SnapshotTestSupport.DB, SnapshotTestSupport.COLL) > pagesBefore);
            assertEquals(List.of("d0=0", "d1=1", "d2=2", "d3=3", "d4=4"), readAll(snapshotId, 2));
        } finally {
            TestUtils.setPrivateField(Configuration.getInstance(), "maxPageSize",
                    ConfigKey.MAX_PAGE_SIZE.defaultValue());
        }
    }
}
