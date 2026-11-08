package org.techhouse.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.IndexRepairEvent;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DeferredWritesListenerTest {
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final DeferredWrites listener = new DeferredWrites();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", taskManager);
        TestUtils.setPrivateStaticField(AdminOperationHelper.class, "fs", fs);
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void aFailedReplayKeepsItsMarksAndQueuesTheRepair() throws Exception {
        final var queued = Mockito.mock(BackgroundTaskManager.class);
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", queued);
        final var rows = List.of(new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "partly", 0L, 10L, 1L, 1L));

        final var generation = listener.replaying(TestGlobals.DB, TestGlobals.COLL, rows);
        listener.notReplayed(TestGlobals.DB, TestGlobals.COLL, rows, generation);

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("partly"),
                "redo may have written some of its bytes, so the index work stays outstanding");
        Mockito.verify(queued).submitMaintenance(ArgumentMatchers.any(IndexRepairEvent.class));
    }

    @Test
    public void theRedriveAppliesTransactionRecordsOnlyUnderTheirLock() throws Exception {
        final var spy = Mockito.spy(fs);
        TestUtils.setPrivateStaticField(AdminOperationHelper.class, "fs", spy);
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = Thread.ofPlatform().start(() -> {
            try {
                locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
                held.countDown();
                release.await();
                locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));

        final var started = System.nanoTime();
        AdminOperationHelper.applyDeferredTransactionRecords(100L);
        final var waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();
        holder.join(5_000L);

        assertTrue(waitedMillis < 2_000L, "the wait is budgeted");
        Mockito.verify(spy, Mockito.never()).applyDeferredWrites(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        AdminOperationHelper.applyDeferredTransactionRecords(100L);
        Mockito.verify(spy).applyDeferredWrites(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        assertFalse(locks.isWriteLockedByCurrentThread(Globals.ADMIN_DB_NAME + Globals.COLL_IDENTIFIER_SEPARATOR
                + Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME));
        assertEquals(0, held.getCount());
    }
}
