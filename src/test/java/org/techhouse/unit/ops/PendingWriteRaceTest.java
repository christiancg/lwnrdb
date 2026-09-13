package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.PendingWriteReconciler;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PendingWriteRaceTest {
    private PendingIndexWrites pending;

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
        pending = IocContainer.get(PendingIndexWrites.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        for (final var id : pending.idsFor(TestGlobals.DB, TestGlobals.COLL)) {
            pending.clear(TestGlobals.DB, TestGlobals.COLL, id);
        }
        TestUtils.standardTearDown();
    }

    @Test
    public void test_an_id_cleared_between_the_two_reads_is_still_reconciled() {
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "racer");
        final var before = PendingWriteReconciler.pendingIds(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(before.contains("racer"));

        pending.clear(TestGlobals.DB, TestGlobals.COLL, "racer");

        final var around = PendingWriteReconciler.pendingIdsAround(before, TestGlobals.DB, TestGlobals.COLL);
        assertTrue(around.contains("racer"),
                "a write the background worker finished between the index read and the pending check must "
                        + "still be reconciled, or the read silently drops it");
    }

    @Test
    public void test_an_id_marked_after_the_first_read_is_still_reconciled() {
        final var before = PendingWriteReconciler.pendingIds(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(before.isEmpty());

        pending.mark(TestGlobals.DB, TestGlobals.COLL, "latecomer");

        final var around = PendingWriteReconciler.pendingIdsAround(before, TestGlobals.DB, TestGlobals.COLL);
        assertTrue(around.contains("latecomer"), "a write that landed after the index read must be reconciled");
    }

    @Test
    public void test_both_directions_are_covered_at_once() {
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "early");
        final var before = PendingWriteReconciler.pendingIds(TestGlobals.DB, TestGlobals.COLL);
        pending.clear(TestGlobals.DB, TestGlobals.COLL, "early");
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "late");

        final var around = PendingWriteReconciler.pendingIdsAround(before, TestGlobals.DB, TestGlobals.COLL);

        assertEquals(Set.of("early", "late"), around);
    }

    @Test
    public void test_a_quiet_collection_still_allocates_nothing_to_reconcile() {
        final var before = PendingWriteReconciler.pendingIds(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(PendingWriteReconciler.pendingIdsAround(before, TestGlobals.DB, TestGlobals.COLL).isEmpty(),
                "the fast path must stay empty when nothing is pending");
    }

    @Test
    public void test_an_id_pending_across_both_reads_appears_once() {
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "steady");
        final var before = PendingWriteReconciler.pendingIds(TestGlobals.DB, TestGlobals.COLL);

        final var around = PendingWriteReconciler.pendingIdsAround(before, TestGlobals.DB, TestGlobals.COLL);

        assertEquals(Set.of("steady"), around);
    }

    @Test
    public void test_the_union_does_not_mutate_the_earlier_snapshot() {
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "first");
        final var before = PendingWriteReconciler.pendingIds(TestGlobals.DB, TestGlobals.COLL);
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "second");

        PendingWriteReconciler.pendingIdsAround(before, TestGlobals.DB, TestGlobals.COLL);

        assertEquals(Set.of("first"), before, "the caller's snapshot must not be modified in place");
    }
}
