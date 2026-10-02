package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleOperationHelper;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DatabaseBarrierRaceTest {
    private static final String ACTOR = "alice";
    private static final String PROCEDURE = "rollup";
    private static final String RACED_DB = "raced_db";
    private static final long SHORT_BUDGET_MS = 100L;
    private static final long LONG_BUDGET_MS = 10_000L;
    private static final Configuration configuration = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private final ExecutorService holder = Executors.newSingleThreadExecutor();
    private final List<String> heldBarriers = new ArrayList<>();
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
        onHolder(() -> {
            for (final var dbName : heldBarriers) {
                locks.releaseDatabaseExclusive(dbName);
            }
            return null;
        });
        holder.shutdown();
        assertTrue(holder.awaitTermination(5, TimeUnit.SECONDS));
        registry.clear();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", originalTimeout);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private <T> T onHolder(Callable<T> action) throws Exception {
        return holder.submit(action).get(30, TimeUnit.SECONDS);
    }

    private void holdBarrier(String dbName) throws Exception {
        assertTrue(onHolder(() -> locks.tryLockDatabaseExclusive(dbName, LONG_BUDGET_MS)));
        heldBarriers.add(dbName);
    }

    private void releaseBarrier(String dbName) throws Exception {
        onHolder(() -> {
            locks.releaseDatabaseExclusive(dbName);
            return null;
        });
        heldBarriers.remove(dbName);
    }

    @SuppressWarnings("BusyWait")
    private static Thread startParked(Callable<OperationResponse> action, AtomicReference<OperationResponse> result)
            throws InterruptedException {
        final var thread = new Thread(() -> {
            try {
                result.set(action.call());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        thread.start();
        final var deadline = System.currentTimeMillis() + LONG_BUDGET_MS;
        while (thread.getState() != Thread.State.TIMED_WAITING && thread.getState() != Thread.State.WAITING) {
            assertTrue(System.currentTimeMillis() < deadline, "the request never parked on the barrier");
            Thread.sleep(5);
        }
        return thread;
    }

    private void dropTestDatabaseWhileHoldingTheBarrier() throws Exception {
        final var dropped = onHolder(
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
        releaseBarrier(TestGlobals.DB);
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
        releaseBarrier(TestGlobals.DB);
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
        releaseBarrier(TestGlobals.DB);
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

        onHolder(() -> {
            AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(RACED_DB, new ArrayList<>(), List.of("first")));
            return null;
        });
        releaseBarrier(RACED_DB);
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
}
