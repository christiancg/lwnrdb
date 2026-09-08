package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleDispatcher;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The cap's central invariant: it bounds client-initiated runs only. A trigger's pending-run record is
 * consumed by the transaction that applies its effects, so a run refused for want of a permit would be a
 * dropped trigger rather than a retried one - and a scheduled run has no caller to hand a 503-6 to.
 */
public class TriggerAdmissionExemptionTest {
    private static final String OWNER = "exemptowner";
    private static final String OUTPUT_COLL = "exemptOutput";
    private static final Configuration configuration = Configuration.getInstance();
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, OUTPUT_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.DB, OUTPUT_COLL);
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, OUTPUT_COLL));
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(OWNER));
        final var request = new CreateUserRequest();
        request.setUsername(OWNER);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scheduleTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", 16_777_216L);
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listScheduleNames(TestGlobals.DB)) {
            fs.deleteSchedule(TestGlobals.DB, name);
        }
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeSchedulesForDatabase(TestGlobals.DB);
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
        registry.clear();
    }

    @AfterEach
    void restoreAdmission() {
        admission.reconfigure(0, 0L);
    }

    private void storeWritingProcedure() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "job",
                "import db from 'db'; import args from 'args'; db.save(db.name, '" + OUTPUT_COLL
                        + "', { _id: args.id, ok: true }); return 'ok';"),
                OWNER);
    }

    private void installTrigger() {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "job",
                        TriggerDefinition.MODE_DOCUMENT, false, true, OWNER, 1L, 1L, 1L, OWNER)));
    }

    private ScheduleRegistry.Entry registerSchedule() throws Exception {
        final var args = new JsonObject();
        args.add("id", new JsonString("schedule-under-saturation"));
        final var definition = new ScheduleDefinition("s", "job", null, 60_000L, args, 0L, true, OWNER, null, 1L, 1L,
                1L, OWNER);
        fs.writeSchedule(TestGlobals.DB, "s", eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.DB, "s");
        registry.reload(TestGlobals.DB);
        return registry.get(TestGlobals.DB, "s");
    }

    private static TriggerEvent event() {
        final var data = new JsonObject();
        data.add("_id", new JsonString("trigger-under-saturation"));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id("trigger-under-saturation");
        dbEntry.setData(data);
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "job", false,
                List.of(dbEntry), OWNER, 0);
    }

    private JsonObject outputRow(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, OUTPUT_COLL);
        request.set_id(id);
        final var response = IocContainer.get(OperationProcessor.class).processMessage(request);
        return response instanceof FindByIdResponse found ? found.getObject() : null;
    }

    @Test
    public void test_trigger_runs_while_script_permits_are_exhausted() throws Exception {
        storeWritingProcedure();
        installTrigger();
        admission.reconfigure(1, 0L);
        assertTrue(admission.tryAcquire());
        try {
            TriggerDispatcher.dispatch(event());
        } finally {
            admission.release();
        }
        assertNotNull(outputRow("trigger-under-saturation"), "the trigger was dropped by the script cap");
        assertTrue(Objects.requireNonNull(outputRow("trigger-under-saturation")).get("ok").asJsonBoolean().getValue());
        // Nothing was refused: the trigger path never consults the pool at all.
        assertEquals(0, admission.getRejected());
    }

    @Test
    public void test_scheduled_run_proceeds_while_saturated() throws Exception {
        storeWritingProcedure();
        admission.reconfigure(1, 0L);
        final var entry = registerSchedule();
        assertTrue(admission.tryAcquire());
        try {
            ScheduleDispatcher.dispatch(entry);
        } finally {
            admission.release();
        }
        assertNotNull(outputRow("schedule-under-saturation"), "the scheduled run was dropped by the script cap");
        assertEquals(0, admission.getRejected());
    }
}
