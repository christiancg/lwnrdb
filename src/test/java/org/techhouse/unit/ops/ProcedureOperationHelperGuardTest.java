package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.techhouse.test.LockHolder.startParked;
import static org.techhouse.unit.ops.ProcedureBindingSupport.BOUND;
import static org.techhouse.unit.ops.ProcedureBindingSupport.DEFINER;
import static org.techhouse.unit.ops.ProcedureBindingSupport.INTRUDER;
import static org.techhouse.unit.ops.ProcedureBindingSupport.REFUSAL;
import static org.techhouse.unit.ops.ProcedureBindingSupport.bindTrigger;
import static org.techhouse.unit.ops.ProcedureBindingSupport.installProcedure;
import static org.techhouse.unit.ops.ProcedureBindingSupport.saveProcedure;
import static org.techhouse.unit.ops.ProcedureBindingSupport.triggerRequest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.HybridClock;
import org.techhouse.cluster.admin.AdminRecord;
import org.techhouse.cluster.admin.AdminRecordMerge;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.TriggerOperationHelper;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.LockHolder;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureOperationHelperGuardTest {
    private static final long BUDGET_MS = 10_000L;
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final HybridClock clock = IocContainer.get(HybridClock.class);
    private final Configuration configuration = Configuration.getInstance();
    private final LockHolder holder = new LockHolder();

    @BeforeEach
    public void setUp() throws Exception {
        ProcedureBindingSupport.setUp();
        installProcedure(BOUND, "return 1;");
    }

    @AfterEach
    public void tearDown() throws Exception {
        holder.close();
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);
        ProcedureBindingSupport.tearDown();
    }

    @Test
    public void test_save_refused_leaves_the_stored_source_and_version_unchanged() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);

        final var refused = saveProcedure(BOUND, "return 666;", INTRUDER);

        assertEquals(REFUSAL, refused.getErrorCode());
        assertEquals(OperationStatus.FORBIDDEN, refused.getStatus());
        final var stored = cache.getProcedure(TestGlobals.DB, BOUND);
        assertEquals("return 1;", stored.getSource());
        assertEquals(1L, stored.getVersion());
        assertEquals(DEFINER, stored.getUpdatedBy());
    }

    @Test
    public void test_delete_refused_with_403_4_before_the_reference_refusal() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);

        final var foreign = ProcedureOperationHelper.executeDelete(new DeleteProcedureRequest(TestGlobals.DB, BOUND),
                INTRUDER);
        final var own = ProcedureOperationHelper.executeDelete(new DeleteProcedureRequest(TestGlobals.DB, BOUND),
                DEFINER);

        assertEquals(REFUSAL, foreign.getErrorCode());
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), own.getErrorCode());
        assertNotNull(cache.getProcedure(TestGlobals.DB, BOUND));
    }

    @Test
    public void test_the_guard_runs_before_the_version_conflict_check() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);
        final var stale = new SaveProcedureRequest(TestGlobals.DB, BOUND, "return 2;");
        stale.setIfVersion(7L);

        assertEquals(REFUSAL, ProcedureOperationHelper.executeSave(stale, INTRUDER).getErrorCode());
        assertEquals(ErrorCode.PROCEDURE_VERSION_CONFLICT.getCode(),
                ProcedureOperationHelper.executeSave(stale, DEFINER).getErrorCode());
    }

    @Test
    public void test_a_trigger_saved_while_a_procedure_save_holds_the_lock_is_seen_by_the_guard() throws Exception {
        holder.hold(() -> {
            locks.lock(TestGlobals.DB, Globals.PROCEDURES_FOLDER);
            return true;
        }, () -> locks.release(TestGlobals.DB, Globals.PROCEDURES_FOLDER));
        final var result = new AtomicReference<OperationResponse>();
        final var saver = startParked(() -> saveProcedure(BOUND, "return 2;", INTRUDER), result);

        final var trigger = holder.run(() -> TriggerOperationHelper
                .executeSave(triggerRequest("audit", BOUND, TriggerDefinition.TIMING_AFTER), DEFINER));
        assertEquals(OperationStatus.OK, trigger.getStatus());
        holder.release();
        saver.join(BUDGET_MS);

        assertEquals(REFUSAL, result.get().getErrorCode());
        assertEquals("return 1;", cache.getProcedure(TestGlobals.DB, BOUND).getSource());
    }

    @Test
    public void test_replicated_install_does_not_run_the_guard() throws Exception {
        bindTrigger("audit", BOUND, TriggerDefinition.TIMING_AFTER, DEFINER);
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
        final var version = clock.next();
        final var replicated = new ProcedureDefinition(BOUND, "return 9;", 2L, null, true, 1L, 2L, INTRUDER);

        final var merged = AdminRecordMerge
                .apply(List.of(new AdminRecord(AdminRecordKey.procedure(TestGlobals.DB, BOUND), version,
                        AdminStamp.stamped(replicated.toJsonObject(), version))), true);

        assertTrue(merged.complete());
        assertEquals("return 9;", cache.getProcedure(TestGlobals.DB, BOUND).getSource());
    }
}
