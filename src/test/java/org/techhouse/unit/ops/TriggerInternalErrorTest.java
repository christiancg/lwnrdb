package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerInternalErrorTest {
    private static final String DEFINER = "arityowner";
    private static final String TRIGGER = "arityTrigger";
    private static final String FIRED_ID = "doc1";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        org.techhouse.ops.AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(DEFINER));
        final var request = new CreateUserRequest();
        request.setUsername(DEFINER);
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
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerMaxAttempts", 1);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    private void storeProcedure(String script) throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "arity", script), DEFINER);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition(TRIGGER, new LinkedHashSet<>(Set.of(EventType.CREATED)), "arity",
                        TriggerDefinition.MODE_DOCUMENT, false, true, DEFINER, 1L, 1L, 1L, DEFINER)));
    }

    private static DbEntry firedDocument() {
        final var data = new JsonObject();
        data.add("_id", new JsonString(FIRED_ID));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(FIRED_ID);
        dbEntry.setData(data);
        return dbEntry;
    }

    private static String recordRun() {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, TRIGGER,
                "arity", EventType.CREATED, false, DEFINER, 0, System.currentTimeMillis(), List.of(firedDocument())));
    }

    private static AdminTriggerRunEntry runRecord(String runId) throws Exception {
        for (final var entry : TriggerRunLog.pending()) {
            if (runId.equals(entry.getRunId())) {
                return entry;
            }
        }
        return null;
    }

    private static TriggerEvent event(String runId) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, TRIGGER, "arity", false,
                List.of(firedDocument()), DEFINER, 0, runId);
    }

    @Test
    public void test_a_trigger_calling_db_save_with_too_few_arguments_is_dead_lettered() throws Exception {
        storeProcedure("import db from 'db';\ndb.save('someColl', { _id: 'x' });\n");
        final var runId = recordRun();

        TriggerDispatcher.dispatch(event(runId));

        final var record = runRecord(runId);
        assertNotNull(record, "the run must stay visible for the operator");
        assertEquals(TriggerRunStatus.DEAD, record.getStatus());
        assertEquals(1, record.getAttempts());
        assertNotNull(record.getLastError(), "a dead-lettered run must carry why it died");
        assertTrue(record.getLastError().contains("TypeError"), record.getLastError());
    }

    @Test
    public void test_a_trigger_calling_db_aggregate_with_too_few_arguments_is_dead_lettered() throws Exception {
        storeProcedure("import db from 'db';\ndb.aggregate(db.name, 'someColl');\n");
        final var runId = recordRun();

        TriggerDispatcher.dispatch(event(runId));

        final var record = runRecord(runId);
        assertNotNull(record);
        assertEquals(TriggerRunStatus.DEAD, record.getStatus());
        assertTrue(record.getLastError().contains("TypeError"), record.getLastError());
    }

    @Test
    public void test_a_thrown_script_error_is_still_dead_lettered_the_same_way() throws Exception {
        storeProcedure("throw new Error('nope');");
        final var runId = recordRun();

        TriggerDispatcher.dispatch(event(runId));

        final var record = runRecord(runId);
        assertNotNull(record);
        assertEquals(TriggerRunStatus.DEAD, record.getStatus());
        assertEquals("Error: nope", record.getLastError());
    }
}
