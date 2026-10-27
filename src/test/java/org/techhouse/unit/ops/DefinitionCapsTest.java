package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleOperationHelper;
import org.techhouse.ops.TriggerOperationHelper;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class DefinitionCapsTest {
    private static final String ACTOR = "capsowner";
    private static final String OTHER_DB = "capsOtherDb";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(OperationProcessor.class).processMessage(new CreateDatabaseRequest(OTHER_DB));
        TriggerTestSupport.createDefiner(ACTOR);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TriggerTestSupport.clearTriggersAndProcedures();
        for (final var db : List.of(TestGlobals.DB, OTHER_DB)) {
            for (final var name : fs.metadataFiles().listScheduleNames(db)) {
                fs.metadataFiles().deleteSchedule(db, name);
            }
            cache.removeSchedulesForDatabase(db);
        }
        registry.clear();
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "job", "return 1;"), ACTOR);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(OTHER_DB, "job", "return 1;"), ACTOR);
    }

    @AfterEach
    void restoreCaps() throws Exception {
        for (final var key : List.of(ConfigKey.TRIGGER_MAX_PER_COLLECTION, ConfigKey.SCHEDULE_MAX_TOTAL_PER_USER,
                ConfigKey.SCHEDULE_MIN_INTERVAL_MS, ConfigKey.MAX_PROCEDURES_PER_DATABASE)) {
            TestUtils.setPrivateField(configuration, key.key(), key.defaultValue());
        }
    }

    private static SaveScheduleRequest every(String db, String name, long intervalMs) {
        final var request = new SaveScheduleRequest(db, name, "job");
        request.setIntervalMs(intervalMs);
        return request;
    }

    private static SaveTriggerRequest trigger(String name) {
        return new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, name, List.of("CREATED"), "job");
    }

    @Test
    public void test_triggers_per_collection_are_capped() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerMaxPerCollection", 2);
        assertEquals(OperationStatus.OK, TriggerOperationHelper.executeSave(trigger("t1"), ACTOR).getStatus());
        assertEquals(OperationStatus.OK, TriggerOperationHelper.executeSave(trigger("t2"), ACTOR).getStatus());

        final var refused = TriggerOperationHelper.executeSave(trigger("t3"), ACTOR);

        assertEquals(ErrorCode.TOO_MANY_TRIGGERS.getCode(), refused.getErrorCode());
        assertEquals(2, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
        assertEquals(OperationStatus.OK, TriggerOperationHelper.executeSave(trigger("t2"), ACTOR).getStatus(),
                "replacing an existing trigger never counts against the cap");
    }

    @Test
    public void test_schedules_per_user_are_capped_across_databases() throws Exception {
        TestUtils.setPrivateField(configuration, "scheduleMaxTotalPerUser", 2);
        assertEquals(OperationStatus.OK,
                ScheduleOperationHelper.executeSave(every(TestGlobals.DB, "a", 5_000L), ACTOR).getStatus());
        assertEquals(OperationStatus.OK,
                ScheduleOperationHelper.executeSave(every(OTHER_DB, "b", 5_000L), ACTOR).getStatus());

        final var refused = ScheduleOperationHelper.executeSave(every(OTHER_DB, "c", 5_000L), ACTOR);

        assertEquals(ErrorCode.TOO_MANY_SCHEDULES_FOR_USER.getCode(), refused.getErrorCode());
        assertEquals(OperationStatus.OK,
                ScheduleOperationHelper.executeSave(every(OTHER_DB, "b", 6_000L), ACTOR).getStatus(),
                "replacing one of the user's own schedules takes no new slot");
        assertEquals(OperationStatus.OK,
                ScheduleOperationHelper.executeSave(every(OTHER_DB, "c", 5_000L), "anotheruser").getStatus(),
                "the cap is per user, not per node");
    }

    @Test
    public void test_a_schedule_interval_below_the_minimum_is_refused() throws Exception {
        final var refused = ScheduleOperationHelper.executeSave(every(TestGlobals.DB, "fast", 999L), ACTOR);

        assertEquals(ErrorCode.SCHEDULE_INTERVAL_TOO_SMALL.getCode(), refused.getErrorCode());
        assertTrue(refused.getMessage().contains("1000 ms"));
        assertEquals(OperationStatus.OK,
                ScheduleOperationHelper.executeSave(every(TestGlobals.DB, "fast", 1_000L), ACTOR).getStatus());
    }

    @Test
    public void test_a_stored_interval_below_the_minimum_runs_at_the_minimum() throws Exception {
        final var definition = new ScheduleDefinition("legacy", "job", null, 10L, null, 0L, true, ACTOR, null, 1L, 1L,
                1L, ACTOR);
        fs.metadataFiles().writeSchedule(TestGlobals.DB, "legacy", eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.DB, "legacy");
        registry.reload(TestGlobals.DB);
        final var entry = registry.get(TestGlobals.DB, "legacy");
        entry.setNextRunAt(1_000_000L);

        assertEquals(1_001_000L, registry.nextRunAfter(entry, 1_000_000L),
                "a definition replicated or stored before the minimum existed still fires at most once a second");
    }

    @Test
    public void test_procedures_per_database_are_capped() throws Exception {
        TestUtils.setPrivateField(configuration, "maxProceduresPerDatabase", 2);
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "second", "return 2;"), ACTOR).getStatus());

        final var refused = ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "third", "return 3;"), ACTOR);

        assertEquals(ErrorCode.TOO_MANY_PROCEDURES.getCode(), refused.getErrorCode());
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "second", "return 22;"), ACTOR).getStatus(),
                "replacing an existing procedure never counts against the cap");
    }
}
