package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ScriptRunHistory;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRecord;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DrainFollowOnEventTest {
    private static final long WAIT_MILLIS = 5_000L;

    private final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration configuration = Configuration.getInstance();
    private int origThreads;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origThreads = configuration.getBackgroundProcessingThreads();
        TestUtils.setPrivateField(configuration, "backgroundProcessingThreads", 2);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryKinds", "TRIGGER");
        ScriptRunHistory.reset();
    }

    @AfterEach
    public void tearDown() throws Exception {
        backgroundTaskManager.stopBackgroundWorkers();
        TestUtils.setPrivateField(configuration, "backgroundProcessingThreads", origThreads);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryEnabled", false);
        ScriptRunHistory.reset();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static ScriptRunRecord row(String runId) {
        return new ScriptRunRecord(runId, ScriptRunKind.TRIGGER, TestGlobals.DB, "audit", "proc", TestGlobals.COLL,
                "CREATED", "admin", "admin", 1L, 2L, 1, ScriptRunRecord.OUTCOME_OK, null, null, null, null, null,
                false);
    }

    private boolean isDraining() throws Exception {
        return TestUtils.getPrivateField(backgroundTaskManager, "draining", Boolean.class);
    }

    private static void awaitTrue(Condition condition) throws Exception {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (!condition.holds()) {
            assertTrue(System.nanoTime() < deadline, "the condition never became true");
            Thread.onSpinWait();
        }
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    @Test
    public void test_a_history_row_written_during_the_drain_keeps_its_index_event() throws Exception {
        backgroundTaskManager.startBackgroundWorkers();
        ScriptRunHistory.write(row("warm-up"));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, Globals.SCRIPT_RUNS_COLLECTION_NAME));
        awaitTrue(() -> backgroundTaskManager.pending() == 0);

        locks.lock(TestGlobals.DB, Globals.SCRIPT_RUNS_COLLECTION_NAME);
        final CompletableFuture<Boolean> drained;
        try {
            ScriptRunHistory.record(row("during-drain"));
            awaitTrue(() -> backgroundTaskManager.pending() == 1);
            drained = CompletableFuture.supplyAsync(() -> backgroundTaskManager.drain(WAIT_MILLIS));
            awaitTrue(this::isDraining);
        } finally {
            locks.release(TestGlobals.DB, Globals.SCRIPT_RUNS_COLLECTION_NAME);
        }

        assertTrue(drained.get(WAIT_MILLIS * 2, TimeUnit.MILLISECONDS), "the drain must wait for the follow-on event");
        assertEquals(0, backgroundTaskManager.pending());
        assertNotNull(
                cache.getWholeCollection(TestGlobals.DB, Globals.SCRIPT_RUNS_COLLECTION_NAME).get("during-drain"));
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, Globals.SCRIPT_RUNS_COLLECTION_NAME).isEmpty(),
                "a rejected index event left the row pending, and the next start reported an unclean stop");
    }
}
