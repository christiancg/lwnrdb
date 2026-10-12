package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ProcedureBindingGuard;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleOperationHelper;
import org.techhouse.ops.TriggerOperationHelper;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

final class ProcedureBindingSupport {
    static final String DEFINER = "alice";
    static final String INTRUDER = "bob";
    static final String BOUND = "recalc";
    static final String REFUSAL = ErrorCode.PROCEDURE_BOUND_BY_OTHER_DEFINER.getCode();
    private static final Configuration configuration = Configuration.getInstance();
    private static final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);

    private ProcedureBindingSupport() {
    }

    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scheduleMaxPerDatabase", 100);
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", 10_000L);
        registry.clear();
    }

    static void tearDown() throws Exception {
        registry.clear();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    static OperationResponse saveProcedure(String name, String source, String actor) throws Exception {
        return ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, name, source), actor);
    }

    static void installProcedure(String name, String source) throws Exception {
        assertEquals(OperationStatus.OK, saveProcedure(name, source, DEFINER).getStatus());
    }

    static SaveTriggerRequest triggerRequest(String name, String procedure, String timing) {
        final var request = new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, name, List.of("CREATED"),
                procedure);
        request.setTiming(timing);
        return request;
    }

    static void bindTrigger(String name, String procedure, String timing, String definer) throws Exception {
        final var saved = TriggerOperationHelper.executeSave(triggerRequest(name, procedure, timing), definer);
        assertEquals(OperationStatus.OK, saved.getStatus(), saved.getMessage());
    }

    static void bindSchedule(String name, String procedure, String definer) throws Exception {
        final var request = new SaveScheduleRequest(TestGlobals.DB, name, procedure);
        request.setIntervalMs(3_600_000L);
        final var saved = ScheduleOperationHelper.executeSave(request, definer);
        assertEquals(OperationStatus.OK, saved.getStatus(), saved.getMessage());
    }

    static OperationResponse refusal(String procedure, String caller) throws Exception {
        return ProcedureBindingGuard.refusal(OperationType.SAVE_PROCEDURE, TestGlobals.DB, procedure, caller);
    }
}
