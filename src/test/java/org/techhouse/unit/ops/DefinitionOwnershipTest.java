package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleDispatcher;
import org.techhouse.ops.ScheduleOperationHelper;
import org.techhouse.ops.TriggerOperationHelper;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DefinitionOwnershipTest {
    private static final String DEFINER = "owner_alice";
    private static final String OTHER = "manager_bob";
    private static final String ADMIN = "root_carol";
    private static final String PROCEDURE = "ownedProc";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveUserEntry(user(DEFINER, false));
        AdminOperationHelper.saveUserEntry(user(OTHER, false));
        AdminOperationHelper.saveUserEntry(user(ADMIN, true));
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        TestUtils.setPrivateField(configuration, "scheduleMaxTimeoutMs",
                ConfigKey.SCHEDULE_MAX_TIMEOUT_MS.defaultValue());
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminUserEntry user(String name, boolean admin) {
        return new AdminUserEntry(name, "h", admin, Set.of(), new HashMap<>(), new HashMap<>());
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        fs.metadataFiles().deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.metadataFiles().listScheduleNames(TestGlobals.DB)) {
            fs.metadataFiles().deleteSchedule(TestGlobals.DB, name);
        }
        cache.removeSchedulesForDatabase(TestGlobals.DB);
        if (cache.getProcedure(TestGlobals.DB, PROCEDURE) == null) {
            ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, PROCEDURE, "return 1;"),
                    DEFINER);
        }
    }

    private static SaveTriggerRequest trigger() {
        return new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "audit", List.of("CREATED"), PROCEDURE);
    }

    private static SaveScheduleRequest schedule(long timeoutMs) {
        final var request = new SaveScheduleRequest(TestGlobals.DB, "nightly", PROCEDURE);
        request.setIntervalMs(60_000L);
        request.setTimeoutMs(timeoutMs);
        return request;
    }

    private String triggerDefiner() {
        return cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).getFirst().getDefiner();
    }

    @Test
    public void test_another_manager_can_neither_replace_nor_delete_a_trigger() throws Exception {
        TriggerOperationHelper.executeSave(trigger(), DEFINER);
        assertEquals(ErrorCode.DEFINITION_OWNED_BY_OTHER_DEFINER.getCode(),
                TriggerOperationHelper.executeSave(trigger(), OTHER).getErrorCode());
        assertEquals(ErrorCode.DEFINITION_OWNED_BY_OTHER_DEFINER.getCode(),
                TriggerOperationHelper
                        .executeDelete(new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "audit"), OTHER)
                        .getErrorCode());
        assertEquals(DEFINER, triggerDefiner());
    }

    @Test
    public void test_the_definer_and_an_admin_may_replace_and_delete_a_trigger() throws Exception {
        TriggerOperationHelper.executeSave(trigger(), DEFINER);
        assertEquals(OperationStatus.OK, TriggerOperationHelper.executeSave(trigger(), DEFINER).getStatus());
        assertEquals(OperationStatus.OK, TriggerOperationHelper.executeSave(trigger(), ADMIN).getStatus());
        assertEquals(ADMIN, triggerDefiner());
        assertEquals(OperationStatus.OK, TriggerOperationHelper
                .executeDelete(new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "audit"), ADMIN).getStatus());
    }

    @Test
    public void test_another_manager_can_neither_replace_nor_delete_a_schedule() throws Exception {
        ScheduleOperationHelper.executeSave(schedule(0L), DEFINER);
        assertEquals(ErrorCode.DEFINITION_OWNED_BY_OTHER_DEFINER.getCode(),
                ScheduleOperationHelper.executeSave(schedule(0L), OTHER).getErrorCode());
        assertEquals(ErrorCode.DEFINITION_OWNED_BY_OTHER_DEFINER.getCode(), ScheduleOperationHelper
                .executeDelete(new DeleteScheduleRequest(TestGlobals.DB, "nightly"), OTHER).getErrorCode());
        assertEquals(DEFINER, cache.getSchedule(TestGlobals.DB, "nightly").getDefiner());
    }

    @Test
    public void test_the_definer_may_delete_its_own_schedule() throws Exception {
        ScheduleOperationHelper.executeSave(schedule(0L), DEFINER);
        assertNotNull(cache.getSchedule(TestGlobals.DB, "nightly"));
        assertEquals(OperationStatus.OK, ScheduleOperationHelper
                .executeDelete(new DeleteScheduleRequest(TestGlobals.DB, "nightly"), DEFINER).getStatus());
        assertNull(cache.getSchedule(TestGlobals.DB, "nightly"));
    }

    @Test
    public void test_a_schedule_timeout_past_the_ceiling_is_refused() throws Exception {
        TestUtils.setPrivateField(configuration, "scheduleMaxTimeoutMs", 1_000L);
        assertEquals(ErrorCode.SCHEDULE_TIMEOUT_TOO_LARGE.getCode(),
                ScheduleOperationHelper.executeSave(schedule(1_001L), DEFINER).getErrorCode());
        assertEquals(OperationStatus.OK, ScheduleOperationHelper.executeSave(schedule(1_000L), DEFINER).getStatus());
    }

    @Test
    public void test_a_database_owner_may_delete_another_users_trigger_and_schedule() throws Exception {
        TriggerOperationHelper.executeSave(trigger(), DEFINER);
        ScheduleOperationHelper.executeSave(schedule(0L), DEFINER);
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(OTHER));
        try {
            assertEquals(OperationStatus.OK,
                    TriggerOperationHelper
                            .executeDelete(new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "audit"), OTHER)
                            .getStatus());
            assertEquals(OperationStatus.OK, ScheduleOperationHelper
                    .executeDelete(new DeleteScheduleRequest(TestGlobals.DB, "nightly"), OTHER).getStatus());
        } finally {
            AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of());
        }
    }

    @Test
    public void test_an_admin_may_replace_and_delete_another_users_schedule() throws Exception {
        ScheduleOperationHelper.executeSave(schedule(0L), DEFINER);
        assertEquals(OperationStatus.OK, ScheduleOperationHelper.executeSave(schedule(0L), ADMIN).getStatus());
        assertEquals(ADMIN, cache.getSchedule(TestGlobals.DB, "nightly").getDefiner());
        assertEquals(OperationStatus.OK, ScheduleOperationHelper
                .executeDelete(new DeleteScheduleRequest(TestGlobals.DB, "nightly"), ADMIN).getStatus());
    }

    @Test
    public void test_a_stored_schedule_timeout_is_clamped_to_the_ceiling_when_it_runs() throws Exception {
        TestUtils.setPrivateField(configuration, "scheduleMaxTimeoutMs", 1_000L);
        final var definition = new ScheduleDefinition("legacy", PROCEDURE, null, 60_000L, null, 9_000_000L, true,
                DEFINER, null, 1L, 1L, 1L, DEFINER);
        final var limits = ScheduleDispatcher.class.getDeclaredMethod("limits", ScheduleDefinition.class);
        limits.setAccessible(true);
        assertEquals(1_000L, ((ResourceLimits) limits.invoke(null, definition)).wallClockMillis());
    }
}
