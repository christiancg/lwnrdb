package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.unit.ops.AdmissionAssertions.assertAdmitted;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleExecutor;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.DefinerAuthority;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleDispatcher;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerOperationHelper;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class DefinerDispatchAuthorityTest {
    private static final String OWNER = "authowner";
    private static final String DEFINER = "authdefiner";
    private static final String OUTPUT_COLL = "authOutput";
    private static final String THROTTLED_ID = "trigger-throttled";
    private static final Configuration configuration = Configuration.getInstance();
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, OUTPUT_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.DB, OUTPUT_COLL);
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, OUTPUT_COLL));
        TriggerTestSupport.createDefiner(OWNER);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TriggerTestSupport.clearTriggersAndProcedures();
        TriggerTestSupport.consumeAllPendingRuns();
        for (final var name : fs.listScheduleNames(TestGlobals.DB)) {
            fs.deleteSchedule(TestGlobals.DB, name);
        }
        cache.removeSchedulesForDatabase(TestGlobals.DB);
        registry.clear();
        grant(PermissionLevel.READ_WRITE, ScriptPermissionLevel.RUN);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "job",
                "import db from 'db'; import args from 'args'; db.save(db.name, '" + OUTPUT_COLL
                        + "', { _id: args.id || args.document._id, ok: true }); return 'ok';"),
                OWNER);
    }

    @AfterEach
    void restoreAdmission() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", ConfigKey.CLUSTER_ENABLED.defaultValue());
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs",
                ConfigKey.TRIGGER_RUN_RETENTION_MS.defaultValue());
        admission.reconfigure(configuration.getMaxConcurrentScripts(), configuration.getScriptQueueWaitMs(),
                Integer.parseInt(ConfigKey.MAX_CONCURRENT_SCRIPTS_PER_USER.defaultValue()),
                Integer.parseInt(ConfigKey.MAX_CONCURRENT_SCRIPTS_PER_DATABASE.defaultValue()));
    }

    private static void grant(PermissionLevel databaseLevel, ScriptPermissionLevel scriptLevel) throws Exception {
        final var databasePermissions = new HashMap<String, PermissionLevel>();
        if (databaseLevel != null) {
            databasePermissions.put(TestGlobals.DB, databaseLevel);
        }
        final var collectionPermissions = new HashMap<String, PermissionLevel>();
        collectionPermissions.put(TestGlobals.DB + "|" + OUTPUT_COLL, PermissionLevel.READ_WRITE);
        final var scriptPermissions = new HashMap<String, ScriptPermissionLevel>();
        scriptPermissions.put(TestGlobals.DB, scriptLevel);
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(DEFINER, "hash", false, new HashSet<>(),
                databasePermissions, collectionPermissions, scriptPermissions));
    }

    private void installTrigger() {
        final var trigger = new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "job",
                TriggerDefinition.MODE_DOCUMENT, false, true, DEFINER, 1L, 1L, 1L, DEFINER);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(trigger));
    }

    private ScheduleRegistry.Entry registerSchedule(String id) throws Exception {
        final var args = new JsonObject();
        args.add("id", new JsonString(id));
        final var definition = new ScheduleDefinition("s", "job", null, 60_000L, args, 0L, true, DEFINER, null, 1L, 1L,
                1L, DEFINER);
        fs.writeSchedule(TestGlobals.DB, "s", eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.DB, "s");
        registry.reload(TestGlobals.DB);
        return registry.get(TestGlobals.DB, "s");
    }

    private static TriggerEvent event(String id, String runId) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "job", false,
                List.of(TriggerTestSupport.entry(id)), OWNER, 0, runId);
    }

    private static String recordThrottledRun() {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "job", EventType.CREATED, false, OWNER, 0, System.currentTimeMillis(),
                List.of(TriggerTestSupport.entry(THROTTLED_ID))));
    }

    private JsonObject outputRow(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, OUTPUT_COLL);
        request.set_id(id);
        final var response = IocContainer.get(OperationProcessor.class).processMessage(request);
        return response instanceof FindByIdResponse found ? found.getObject() : null;
    }

    private TriggerDefinition storedTrigger() {
        return cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).getFirst();
    }

    @Test
    public void test_a_trigger_runs_while_its_definer_holds_read_and_run() {
        installTrigger();
        TriggerDispatcher.dispatch(event("granted", null));
        assertNotNull(outputRow("granted"));
        assertTrue(storedTrigger().isEnabled());
    }

    @Test
    public void test_a_trigger_whose_definer_lost_collection_read_is_disabled() throws Exception {
        installTrigger();
        grant(null, ScriptPermissionLevel.RUN);

        TriggerDispatcher.dispatch(event("revoked-read", null));

        assertNull(outputRow("revoked-read"), "a definer who lost READ must not receive the document");
        final var stored = storedTrigger();
        assertFalse(stored.isEnabled());
        assertEquals(2L, stored.getVersion());
        assertTrue(stored.getDisabledReason().contains("READ on collection '" + TestGlobals.COLL + "'"));
        assertTrue(fs.readTriggers(TestGlobals.DB, TestGlobals.COLL).contains("disabledReason"),
                "the disable is persisted, not only cached");
    }

    @Test
    public void test_re_granting_lets_an_owner_re_enable_the_trigger() throws Exception {
        installTrigger();
        grant(PermissionLevel.READ_WRITE, ScriptPermissionLevel.NONE);
        TriggerDispatcher.dispatch(event("revoked-run", null));
        assertFalse(storedTrigger().isEnabled());

        grant(PermissionLevel.READ_WRITE, ScriptPermissionLevel.RUN);
        TriggerDispatcher.dispatch(event("still-disabled", null));
        assertNull(outputRow("still-disabled"), "re-granting alone does not revive a disabled trigger");

        final var saved = TriggerOperationHelper.executeSave(
                new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "audit", List.of("CREATED"), "job"), OWNER);
        assertEquals(OperationStatus.OK, saved.getStatus());
        assertTrue(storedTrigger().isEnabled());
        assertNull(storedTrigger().getDisabledReason());
        TriggerDispatcher.dispatch(event("re-enabled", null));
        assertNotNull(outputRow("re-enabled"));
    }

    @Test
    public void test_a_schedule_whose_definer_lost_script_run_is_disabled() throws Exception {
        final var entry = registerSchedule("schedule-revoked");
        grant(PermissionLevel.READ_WRITE, ScriptPermissionLevel.NONE);
        final var skipped = scheduleExecutor.getSkipped();

        ScheduleDispatcher.dispatch(entry);

        assertNull(outputRow("schedule-revoked"));
        assertEquals(skipped + 1, scheduleExecutor.getSkipped());
        final var stored = cache.getSchedule(TestGlobals.DB, "s");
        assertFalse(stored.isEnabled());
        assertTrue(stored.getDisabledReason().contains("script RUN on database"));
        assertFalse(registry.get(TestGlobals.DB, "s").getDefinition().isEnabled(), "the registry stops firing it");
    }

    @Test
    public void test_a_schedule_run_acquires_an_admission_permit() throws Exception {
        final var entry = registerSchedule("schedule-throttled");
        admission.reconfigure(0, 0L, 1, 0);
        final var skipped = scheduleExecutor.getSkipped();
        try (var _ = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices(DEFINER, TestGlobals.DB))) {
            ScheduleDispatcher.dispatch(entry);
        }
        assertNull(outputRow("schedule-throttled"), "the definer's slice was full, so this occurrence is skipped");
        assertEquals(skipped + 1, scheduleExecutor.getSkipped());

        ScheduleDispatcher.dispatch(entry);
        assertNotNull(outputRow("schedule-throttled"));
        assertAdmitted(admission.acquireSlices(DEFINER, TestGlobals.DB), "the run released its slice");
    }

    @Test
    public void test_a_trigger_refused_a_slice_keeps_its_run_pending() throws Exception {
        installTrigger();
        admission.reconfigure(0, 0L, 1, 0);
        final var runId = recordThrottledRun();
        try (var _ = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices(DEFINER, TestGlobals.DB))) {
            TriggerDispatcher.dispatch(event(THROTTLED_ID, runId));
        }
        assertNull(outputRow(THROTTLED_ID));
        assertEquals(TriggerRunStatus.PENDING, TriggerTestSupport.statusOf(runId),
                "a refused trigger is deferred, never consumed or dead-lettered inside its window");
        final var pending = TriggerTestSupport.pendingRun(runId);
        assertNotNull(pending);
        assertEquals(0, pending.getAttempts(), "deferral consumes no attempt");
    }

    private String recordRunFor(String id) {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "job", EventType.CREATED, false, OWNER, 0, System.currentTimeMillis(),
                List.of(TriggerTestSupport.entry(id))));
    }

    private static void asNonDecidingNode() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
        assertFalse(DefinerAuthority.decidesDefinitions(), "a cluster node outside the ring decides no definitions");
    }

    @Test
    public void test_a_non_deciding_node_defers_a_refused_trigger_without_consuming_it() throws Exception {
        installTrigger();
        grant(null, ScriptPermissionLevel.RUN);
        final var runId = recordRunFor("deferred");
        asNonDecidingNode();

        TriggerDispatcher.dispatch(event("deferred", runId));

        assertNull(outputRow("deferred"));
        assertTrue(storedTrigger().isEnabled(), "only the deciding node writes the disable");
        assertEquals(TriggerRunStatus.PENDING, TriggerTestSupport.statusOf(runId));
        final var pending = TriggerTestSupport.pendingRun(runId);
        assertNotNull(pending);
        assertEquals(0, pending.getAttempts(), "deferral consumes no attempt");
    }

    @Test
    public void test_a_refused_trigger_is_dead_lettered_once_the_retry_window_closes() throws Exception {
        installTrigger();
        grant(null, ScriptPermissionLevel.RUN);
        final var runId = recordRunFor("window-closed");
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", "0");
        asNonDecidingNode();

        TriggerDispatcher.dispatch(event("window-closed", runId));

        assertNull(outputRow("window-closed"));
        assertEquals(TriggerRunStatus.DEAD, TriggerTestSupport.statusOf(runId));
        assertTrue(storedTrigger().isEnabled());
    }

    @Test
    public void test_a_deferred_run_is_consumed_once_the_disabled_definition_arrives() throws Exception {
        installTrigger();
        grant(null, ScriptPermissionLevel.RUN);
        final var runId = recordRunFor("arrives-disabled");
        asNonDecidingNode();
        TriggerDispatcher.dispatch(event("arrives-disabled", runId));

        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(storedTrigger().disabledCopy("definer revoked", System.currentTimeMillis())));
        TriggerDispatcher.dispatch(event("arrives-disabled", runId));

        assertNull(outputRow("arrives-disabled"));
        assertNull(TriggerTestSupport.pendingRun(runId), "a disabled trigger consumes its run");
    }
}
