package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

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
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleOperationHelper;
import org.techhouse.ops.TriggerOperationHelper;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureReferenceRaceTest {
    private static final String ACTOR = "alice";
    private static final String PROCEDURE = "recalc";
    private static final long BUDGET_MS = 10_000L;
    private static final Configuration configuration = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private final ExecutorService holder = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean proceduresLockHeld = new java.util.concurrent.atomic.AtomicBoolean();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scheduleMaxPerDatabase", 100);
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", BUDGET_MS);
        registry.clear();
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, PROCEDURE, "return 1;"), ACTOR).getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (proceduresLockHeld.get()) {
            releaseProceduresLock();
        }
        holder.shutdown();
        assertTrue(holder.awaitTermination(5, TimeUnit.SECONDS));
        registry.clear();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_a_trigger_save_waiting_on_a_procedure_delete_refuses_the_dangling_reference() throws Exception {
        holdProceduresLock();
        final var result = new AtomicReference<OperationResponse>();
        final var saver = startParked(() -> TriggerOperationHelper.executeSave(
                new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "audit", List.of("CREATED"), PROCEDURE),
                ACTOR), result);

        deleteProcedureWhileHoldingTheLock();
        releaseProceduresLock();
        saver.join(BUDGET_MS);

        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertTrue(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_a_schedule_save_waiting_on_a_procedure_delete_refuses_the_dangling_reference() throws Exception {
        holdProceduresLock();
        final var result = new AtomicReference<OperationResponse>();
        final var request = new SaveScheduleRequest(TestGlobals.DB, "nightly", PROCEDURE);
        request.setIntervalMs(2000L);
        final var saver = startParked(() -> ScheduleOperationHelper.executeSave(request, ACTOR), result);

        deleteProcedureWhileHoldingTheLock();
        releaseProceduresLock();
        saver.join(BUDGET_MS);

        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertNull(cache.getSchedule(TestGlobals.DB, "nightly"));
        assertTrue(fs.listScheduleNames(TestGlobals.DB).isEmpty());
    }

    @Test
    public void test_a_procedure_delete_after_a_trigger_save_is_refused_as_referenced() throws Exception {
        assertEquals(OperationStatus.OK, TriggerOperationHelper.executeSave(
                new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "audit", List.of("CREATED"), PROCEDURE), ACTOR)
                .getStatus());

        final var deleted = ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.DB, PROCEDURE));

        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), deleted.getErrorCode());
    }

    private void deleteProcedureWhileHoldingTheLock() throws Exception {
        final var deleted = onHolder(
                () -> ProcedureOperationHelper.executeDelete(new DeleteProcedureRequest(TestGlobals.DB, PROCEDURE)));
        assertEquals(OperationStatus.OK, deleted.getStatus());
    }

    private void holdProceduresLock() throws Exception {
        onHolder(() -> {
            locks.lock(TestGlobals.DB, Globals.PROCEDURES_FOLDER);
            return null;
        });
        proceduresLockHeld.set(true);
    }

    private void releaseProceduresLock() throws Exception {
        onHolder(() -> {
            locks.release(TestGlobals.DB, Globals.PROCEDURES_FOLDER);
            return null;
        });
        proceduresLockHeld.set(false);
    }

    private <T> T onHolder(Callable<T> action) throws Exception {
        return holder.submit(action).get(BUDGET_MS, TimeUnit.MILLISECONDS);
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
        final var deadline = System.currentTimeMillis() + BUDGET_MS;
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.currentTimeMillis() < deadline, "the save never parked on the procedures lock");
            Thread.sleep(5);
        }
        return thread;
    }
}
