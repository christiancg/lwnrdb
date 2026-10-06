package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexHelperDropRaceTest {
    private static final String FIELD = "tag";
    private static final String ID = "race";
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private final long originalTimeout = configuration.getTransactionLockTimeoutMs();

    @FunctionalInterface
    private interface Maintenance {
        void run() throws Exception;
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", 10_000L);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", originalTimeout);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void saveIndexedDocument() throws Exception {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(ID));
        object.add(FIELD, new JsonString("value"));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(ID);
        SaveOperationHelper.executeSave(request);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        AdminOperationHelper.saveNewIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
    }

    private static List<String> fieldIndexFiles() {
        final var folder = new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
        final var prefix = TestGlobals.COLL + "-" + FIELD + "-";
        final var names = folder.list((_, name) -> name.startsWith(prefix) && name.endsWith(".idx"));
        return names == null ? List.of() : List.of(names);
    }

    private Thread holderThatDropsTheIndexOnSignal(CountDownLatch taken, CountDownLatch drop,
            AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
                try {
                    taken.countDown();
                    drop.await();
                    IndexHelper.dropIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
                    AdminOperationHelper.deleteIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
                } finally {
                    locks.release(TestGlobals.DB, TestGlobals.COLL);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
    }

    @SuppressWarnings("BusyWait")
    private void dropIndexWhileMaintenanceWaits(Maintenance maintenance) throws Exception {
        final var taken = new CountDownLatch(1);
        final var drop = new CountDownLatch(1);
        final var holderFailure = new AtomicReference<Throwable>();
        final var holder = holderThatDropsTheIndexOnSignal(taken, drop, holderFailure);
        holder.start();
        assertTrue(taken.await(5, TimeUnit.SECONDS));

        final var workerFailure = new AtomicReference<Throwable>();
        final var worker = new Thread(() -> {
            try {
                maintenance.run();
            } catch (Throwable t) {
                workerFailure.set(t);
            }
        });
        worker.start();
        for (var i = 0; i < 500 && worker.getState() != Thread.State.TIMED_WAITING; i++) {
            Thread.sleep(5);
        }
        assertEquals(Thread.State.TIMED_WAITING, worker.getState(), "the worker must be parked on the lock");
        drop.countDown();
        holder.join(5000);
        worker.join(5000);

        assertNull(holderFailure.get());
        assertNull(workerFailure.get());
    }

    @Test
    public void a_drop_index_while_maintenance_waits_leaves_no_index_file() throws Exception {
        saveIndexedDocument();

        dropIndexWhileMaintenanceWaits(() -> IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, ID));

        assertEquals(List.of(), fieldIndexFiles(), "maintenance must not write an index the drop just removed");
    }

    @Test
    public void the_same_race_through_bulkUpdateIndexes_leaves_no_index_file() throws Exception {
        saveIndexedDocument();

        dropIndexWhileMaintenanceWaits(
                () -> IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of(ID)));

        assertEquals(List.of(), fieldIndexFiles());
    }

    @Test
    public void a_collection_without_indexes_never_takes_the_collection_lock() throws Exception {
        final var taken = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = new Thread(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
                taken.countDown();
                release.await();
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertTrue(taken.await(5, TimeUnit.SECONDS));
        try {
            TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", 1L);
            assertDoesNotThrow(() -> IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, ID));
            assertDoesNotThrow(() -> IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of(ID)));
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }
}
