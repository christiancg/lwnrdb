package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;

public class PendingIndexWritesTest {
    private PendingIndexWrites pending;

    @BeforeEach
    public void setUp() {
        pending = new PendingIndexWrites();
    }

    @Test
    public void test_the_dirty_marker_is_set_on_enqueue_and_cleared_on_drain() throws Exception {
        org.techhouse.test.TestUtils.standardInitialSetup();
        org.techhouse.test.TestUtils.createTestDatabaseAndCollection();
        final var fs = org.techhouse.ioc.IocContainer.get(org.techhouse.fs.FileSystem.class);
        try {
            pending.mark(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL, "1");
            assertEquals(List.of(org.techhouse.test.TestGlobals.DB + "|" + org.techhouse.test.TestGlobals.COLL),
                    fs.listDirtyIndexCollections(),
                    "queued index work must leave an on-disk marker an unclean stop can be seen by");

            pending.clear(org.techhouse.test.TestGlobals.DB, org.techhouse.test.TestGlobals.COLL, "1");

            assertTrue(fs.listDirtyIndexCollections().isEmpty(), "draining the collection clears its marker");
        } finally {
            org.techhouse.test.TestUtils.standardTearDown();
        }
    }

    @Test
    public void test_mark_then_clear_lifecycle() {
        pending.mark("db", "coll", "1");
        assertEquals(Set.of("1"), pending.idsFor("db", "coll"));
        pending.clear("db", "coll", "1");
        assertTrue(pending.idsFor("db", "coll").isEmpty());
    }

    @Test
    public void test_double_mark_needs_double_clear() {
        pending.mark("db", "coll", "1");
        pending.mark("db", "coll", "1");
        pending.clear("db", "coll", "1");
        assertEquals(Set.of("1"), pending.idsFor("db", "coll"));
        pending.clear("db", "coll", "1");
        assertTrue(pending.idsFor("db", "coll").isEmpty());
    }

    @Test
    public void test_clear_unmarked_is_no_op() {
        pending.clear("db", "coll", "ghost");
        assertTrue(pending.idsFor("db", "coll").isEmpty());
        pending.mark("db", "coll", "ghost");
        assertEquals(Set.of("ghost"), pending.idsFor("db", "coll"));
        pending.clear("db", "coll", "ghost");
        assertTrue(pending.idsFor("db", "coll").isEmpty());
    }

    @Test
    public void test_ids_for_returns_detached_snapshot() {
        pending.mark("db", "coll", "1");
        final var snapshot = pending.idsFor("db", "coll");
        snapshot.add("2");
        assertEquals(Set.of("1"), pending.idsFor("db", "coll"));
    }

    @Test
    public void test_bulk_mark_and_clear() {
        pending.mark("db", "coll", List.of("1", "2", "3"));
        assertEquals(Set.of("1", "2", "3"), pending.idsFor("db", "coll"));
        pending.clear("db", "coll", List.of("1", "2"));
        assertEquals(Set.of("3"), pending.idsFor("db", "coll"));
    }

    @Test
    public void test_pending_is_per_collection() {
        pending.mark("db", "collA", "1");
        assertTrue(pending.idsFor("db", "collB").isEmpty());
        assertEquals(Set.of("1"), pending.idsFor("db", "collA"));
    }

    @Test
    public void test_ids_for_unknown_collection_is_empty() {
        assertTrue(pending.idsFor("db", "unknown").isEmpty());
    }
}
