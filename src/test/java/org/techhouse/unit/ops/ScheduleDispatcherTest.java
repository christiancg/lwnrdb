package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleExecutor;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.ScheduleDefinition;
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
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The dispatcher's own behaviour. Definer rights above all: a scheduled run has no caller, so it must behave
 * identically regardless of who happens to be connected.
 */
public class ScheduleDispatcherTest {
    private static final String OWNER = "schedowner";
    private static final String OUTSIDER = "schedoutsider";
    private static final String OUTPUT_COLL = "schedOutput";
    private static final Configuration configuration = Configuration.getInstance();
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
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(OWNER));
        createUser(OWNER);
        // No permissions at all, so a job installed by this user cannot write the output collection.
        createUser(OUTSIDER);
    }

    private static void createUser(String username) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scheduleTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", 16_777_216L);
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
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

    private void storeProcedure(String source) throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "job", source), OWNER);
    }

    private void storeWritingProcedure() throws Exception {
        storeProcedure("import db from 'db'; import args from 'args';" + " db.save(db.name, '" + OUTPUT_COLL
                + "', { _id: args.id, ok: true });" + " return 'ok';");
    }

    private ScheduleRegistry.Entry register(String procedureName, String definer, boolean enabled,
                                            long timeoutMs, JsonObject args) throws Exception {
        final var definition = new ScheduleDefinition("s", procedureName, null, 60_000L, args, timeoutMs, enabled,
                definer, null, 1L, 1L, 1L, definer);
        fs.writeSchedule(TestGlobals.DB, "s", eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.DB, "s");
        registry.reload(TestGlobals.DB);
        return registry.get(TestGlobals.DB, "s");
    }

    private static JsonObject idArgs(String id) {
        final var args = new JsonObject();
        args.add("id", new JsonString(id));
        return args;
    }

    private JsonObject outputRow(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, OUTPUT_COLL);
        request.set_id(id);
        final var response = IocContainer.get(OperationProcessor.class).processMessage(request);
        return response instanceof FindByIdResponse found ? found.getObject() : null;
    }

    // A scheduled job has no caller, so it runs as its installer. The pair below is the whole property:
    // the same procedure and the same arguments write or do not write purely on the definer's authority.
    @Test
    public void test_runs_with_definer_authority() throws Exception {
        storeWritingProcedure();
        ScheduleDispatcher.dispatch(register("job", OWNER, true, 0L, idArgs("by-definer")));
        assertNotNull(outputRow("by-definer"));
        assertTrue(Objects.requireNonNull(outputRow("by-definer")).get("ok").asJsonBoolean().getValue());
    }

    @Test
    public void test_is_bounded_by_the_definers_authority() throws Exception {
        storeWritingProcedure();
        final var before = scheduleExecutor.getFailed();
        ScheduleDispatcher.dispatch(register("job", OUTSIDER, true, 0L, idArgs("by-outsider")));
        assertNull(outputRow("by-outsider"));
        assertEquals(before + 1, scheduleExecutor.getFailed());
    }

    // Deliberately no fallback to an admin: that would let deleting a user widen a job's authority.
    @Test
    public void test_a_missing_definer_disables_the_schedule() throws Exception {
        createUser("tempdefiner");
        storeWritingProcedure();
        final var entry = register("job", "tempdefiner", true, 0L, idArgs("no-definer"));
        final var request = new DeleteUserRequest();
        request.setUsername("tempdefiner");
        UserOperationHelper.processDeleteUser(request);

        final var before = scheduleExecutor.getSkipped();
        ScheduleDispatcher.dispatch(entry);
        assertNull(outputRow("no-definer"));
        assertEquals(before + 1, scheduleExecutor.getSkipped());
    }

    @Test
    public void test_a_missing_procedure_is_counted_and_skipped() throws Exception {
        final var entry = register("nope", OWNER, true, 0L, idArgs("missing-procedure"));
        final var before = scheduleExecutor.getSkipped();
        assertDoesNotThrow(() -> ScheduleDispatcher.dispatch(entry));
        assertEquals(before + 1, scheduleExecutor.getSkipped());
    }

    @Test
    public void test_a_disabled_procedure_is_counted_and_skipped() throws Exception {
        storeWritingProcedure();
        final var procedure = cache.getProcedure(TestGlobals.DB, "job");
        final var disabled = new org.techhouse.data.ProcedureDefinition(procedure.getName(), procedure.getSource(),
                procedure.getVersion(), null, false, 1L, 1L, OWNER);
        cache.putProcedure(TestGlobals.DB, disabled);
        final var entry = register("job", OWNER, true, 0L, idArgs("disabled-procedure"));
        final var before = scheduleExecutor.getSkipped();
        ScheduleDispatcher.dispatch(entry);
        assertNull(outputRow("disabled-procedure"));
        assertEquals(before + 1, scheduleExecutor.getSkipped());
    }

    @Test
    public void test_a_disabled_schedule_is_counted_and_skipped() throws Exception {
        storeWritingProcedure();
        final var entry = register("job", OWNER, false, 0L, idArgs("disabled-schedule"));
        final var before = scheduleExecutor.getSkipped();
        ScheduleDispatcher.dispatch(entry);
        assertNull(outputRow("disabled-schedule"));
        assertEquals(before + 1, scheduleExecutor.getSkipped());
    }

    @Test
    public void test_a_script_error_is_counted_as_a_failure() throws Exception {
        storeProcedure("throw new Error('boom');");
        final var entry = register("job", OWNER, true, 0L, new JsonObject());
        final var before = scheduleExecutor.getFailed();
        assertDoesNotThrow(() -> ScheduleDispatcher.dispatch(entry));
        assertEquals(before + 1, scheduleExecutor.getFailed());
    }

    @Test
    public void test_per_schedule_timeout_overrides_the_default() throws Exception {
        TestUtils.setPrivateField(configuration, "scheduleTimeoutMs", 60_000L);
        storeProcedure("while (true) { }");
        final var entry = register("job", OWNER, true, 50L, new JsonObject());
        final var before = scheduleExecutor.getFailed();
        final var start = System.currentTimeMillis();
        ScheduleDispatcher.dispatch(entry);
        final var elapsed = System.currentTimeMillis() - start;
        assertEquals(before + 1, scheduleExecutor.getFailed());
        assertTrue(elapsed < 30_000L, "the schedule's own timeoutMs must bound the run, but it took " + elapsed + "ms");
    }

    // Unlike a trigger, a scheduled run is not already inside a transaction, so it may open its own.
    @Test
    public void test_a_scheduled_run_may_open_its_own_transaction() throws Exception {
        storeProcedure(
                "import db from 'db'; import args from 'args';" + " db.transaction(() => { db.save(db.name, '"
                        + OUTPUT_COLL + "', { _id: args.id, ok: true }); });" + " return 'ok';");
        ScheduleDispatcher.dispatch(register("job", OWNER, true, 0L, idArgs("in-transaction")));
        assertNotNull(outputRow("in-transaction"));
    }

    // A scheduled run's result is discarded, so the result cap must not fail a run for a value nobody reads.
    @Test
    public void test_scheduled_runs_are_not_result_capped() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", 256L);
        storeProcedure("import db from 'db'; import args from 'args';" + " db.save(db.name, '" + OUTPUT_COLL
                + "', { _id: args.id, ok: true });" + " return new Array(5000).fill('0123456789');");
        ScheduleDispatcher.dispatch(register("job", OWNER, true, 0L, idArgs("big-result")));
        assertNotNull(outputRow("big-result"), "the run must complete despite its oversized result");
    }
}
