package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.techhouse.test.LockHolder.startParked;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleOperationHelper;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.LockHolder;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class DatabaseBarrierRaceTest {
    private static final String ACTOR = "alice";
    private static final String PROCEDURE = "rollup";
    private static final String RACED_DB = "raced_db";
    private static final String LATE_COLL = "lateColl";
    private static final long DROP_BUDGET_MS = 1000L;
    private static final long SHORT_BUDGET_MS = 100L;
    private static final long LONG_BUDGET_MS = 10_000L;
    private static final Configuration configuration = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final LockHolder holder = new LockHolder();
    private final long originalTimeout = configuration.getTransactionLockTimeoutMs();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scheduleMaxPerDatabase", 100);
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        registry.clear();
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, PROCEDURE, "return 1;"), ACTOR).getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        holder.close();
        registry.clear();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", originalTimeout);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void holdBarrier(String dbName) throws Exception {
        holder.hold(() -> locks.tryLockDatabaseExclusive(dbName, LONG_BUDGET_MS),
                () -> locks.releaseDatabaseExclusive(dbName));
    }

    private void dropTestDatabaseWhileHoldingTheBarrier() throws Exception {
        final var dropped = holder.run(
                () -> DatabaseOperationHelper.processDropDatabaseOperation(new DropDatabaseRequest(TestGlobals.DB)));
        assertEquals(OperationStatus.OK, dropped.getStatus());
    }

    private static File metadataFolder(String folderName) {
        return new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + folderName);
    }

    private static SaveScheduleRequest scheduleRequest() {
        final var request = new SaveScheduleRequest(TestGlobals.DB, "nightly", PROCEDURE);
        request.setIntervalMs(2000L);
        return request;
    }

    @Test
    public void test_an_owner_change_waiting_on_a_drop_does_not_resurrect_the_database() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LONG_BUDGET_MS);
        holdBarrier(TestGlobals.DB);
        final var result = new AtomicReference<OperationResponse>();
        final var owners = new SetDatabaseOwnersRequest(TestGlobals.DB);
        owners.setOwners(List.of("mallory"));
        final var setter = startParked(() -> DatabaseOperationHelper.processSetDatabaseOwners(owners), result);

        dropTestDatabaseWhileHoldingTheBarrier();
        holder.release();
        setter.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertNull(cache.getAdminDbEntry(TestGlobals.DB));
        assertNull(cache.getPkIndexAdminDbEntry(TestGlobals.DB));
    }

    @Test
    public void test_a_procedure_save_waits_for_the_database_barrier() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdBarrier(TestGlobals.DB);

        final var response = ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "p", "return 2;"), ACTOR);

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
    }

    @Test
    public void test_a_procedure_save_after_the_database_was_dropped_writes_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LONG_BUDGET_MS);
        holdBarrier(TestGlobals.DB);
        final var result = new AtomicReference<OperationResponse>();
        final var saver = startParked(() -> ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "p", "return 2;"), ACTOR), result);

        dropTestDatabaseWhileHoldingTheBarrier();
        holder.release();
        saver.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertFalse(metadataFolder(Globals.PROCEDURES_FOLDER).exists());
        assertNull(cache.getProcedure(TestGlobals.DB, "p"));
    }

    @Test
    public void test_a_schedule_save_waits_for_the_database_barrier() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdBarrier(TestGlobals.DB);

        final var response = ScheduleOperationHelper.executeSave(scheduleRequest(), ACTOR);

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
    }

    @Test
    public void test_a_schedule_save_after_the_database_was_dropped_registers_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LONG_BUDGET_MS);
        holdBarrier(TestGlobals.DB);
        final var result = new AtomicReference<OperationResponse>();
        final var saver = startParked(() -> ScheduleOperationHelper.executeSave(scheduleRequest(), ACTOR), result);

        dropTestDatabaseWhileHoldingTheBarrier();
        holder.release();
        saver.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertFalse(metadataFolder(Globals.SCHEDULES_FOLDER).exists());
        assertNull(registry.get(TestGlobals.DB, "nightly"));
    }

    @Test
    public void test_a_duplicate_create_waiting_on_the_barrier_sees_the_first_entry() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LONG_BUDGET_MS);
        holdBarrier(RACED_DB);
        final var result = new AtomicReference<OperationResponse>();
        final var creator = startParked(
                () -> DatabaseOperationHelper.processCreateDatabaseOperation(new CreateDatabaseRequest(RACED_DB), null),
                result);

        holder.run(() -> {
            AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(RACED_DB, new ArrayList<>(), List.of("first")));
            return null;
        });
        holder.release();
        creator.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.DATABASE_ALREADY_EXISTS.getCode(), result.get().getErrorCode());
        assertEquals(List.of("first"), cache.getAdminDbEntry(RACED_DB).getOwners());
    }

    @Test
    public void test_create_database_times_out_on_a_held_barrier() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdBarrier(RACED_DB);

        final var response = DatabaseOperationHelper.processCreateDatabaseOperation(new CreateDatabaseRequest(RACED_DB),
                null);

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
        assertNull(cache.getAdminDbEntry(RACED_DB));
    }

    @Test
    public void test_create_database_releases_the_barrier() throws Exception {
        final var response = DatabaseOperationHelper.processCreateDatabaseOperation(new CreateDatabaseRequest(RACED_DB),
                null);

        assertEquals(OperationStatus.OK, response.getStatus());
        holdBarrier(RACED_DB);
    }

    @Test
    public void test_create_collection_times_out_while_a_drop_holds_the_database() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", DROP_BUDGET_MS);
        holdBarrier(TestGlobals.DB);

        final var response = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, LATE_COLL));

        assertEquals("409-5", response.getErrorCode());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, LATE_COLL));
    }

    @Test
    public void test_drop_gives_up_on_a_held_collection_lock_and_releases_everything() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", DROP_BUDGET_MS);
        holder.hold(() -> locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL),
                () -> locks.release(TestGlobals.DB, TestGlobals.COLL));

        final var response = dropAsync().get(10, TimeUnit.SECONDS);

        assertEquals("409-5", response.getErrorCode());
        assertNotNull(cache.getAdminDbEntry(TestGlobals.DB), "a drop that gave up must leave the database");
        assertTrue(locks.tryLockDatabaseExclusive(TestGlobals.DB, 0), "the drop must have released the barrier");
        locks.releaseDatabaseExclusive(TestGlobals.DB);
    }

    @Test
    public void test_drop_locks_a_collection_registered_while_it_waited_for_the_database() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", DROP_BUDGET_MS);
        assertTrue(holder.run(() -> locks.tryLockDatabaseShared(TestGlobals.DB, DROP_BUDGET_MS)));
        final var drop = dropAsync();
        awaitQueuedDrop();
        holder.hold(() -> {
            AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, LATE_COLL));
            IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, LATE_COLL);
            final var locked = locks.tryLockWrite(TestGlobals.DB, LATE_COLL, DROP_BUDGET_MS);
            locks.releaseDatabaseShared(TestGlobals.DB);
            return locked;
        }, () -> locks.release(TestGlobals.DB, LATE_COLL));

        final var response = drop.get(10, TimeUnit.SECONDS);

        assertEquals("409-5", response.getErrorCode(),
                "the drop must wait on the late collection's lock instead of deleting it unlocked");
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, LATE_COLL));
        holder.release();
        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getStatus());
    }

    private CompletableFuture<OperationResponse> dropAsync() {
        return CompletableFuture.supplyAsync(() -> processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)));
    }

    private void awaitQueuedDrop() throws Exception {
        final var type = new ReflectionUtils.TypeToken<Map<String, ReentrantReadWriteLock>>() {
        };
        final var barrier = TestUtils.getPrivateField(locks, "locks", type).get(TestGlobals.DB);
        final var deadline = System.currentTimeMillis() + 5000;
        while (!barrier.hasQueuedThreads()) {
            assertTrue(System.currentTimeMillis() < deadline);
            Thread.onSpinWait();
        }
    }
}
