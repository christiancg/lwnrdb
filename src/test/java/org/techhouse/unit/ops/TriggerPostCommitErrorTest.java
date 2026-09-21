package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerPostCommitErrorTest {
    private static final String DEFINER = "postcommitowner";
    private static final String AUDIT_COLL = "postCommitAudit";
    private static final Configuration configuration = Configuration.getInstance();

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final CopyOnWriteArrayList<TriggerEvent> requeued = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, AUDIT_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.DB, AUDIT_COLL);
        AdminOperationHelper
                .saveCollectionEntry(new org.techhouse.data.admin.AdminCollEntry(TestGlobals.DB, AUDIT_COLL));
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(DEFINER));
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
        IocContainer.get(TriggerExecutor.class).stop();
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
        TestUtils.setPrivateField(configuration, "triggerMaxAttempts", 3);
        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs", 0L);
        TestUtils.setPrivateField(configuration, "triggerRetryMaxBackoffMs", 0L);
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 86_400_000L);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 600_000L);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 500_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        requeued.clear();
        triggerExecutor.stop();
        triggerExecutor.start(requeued::add);
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

    private void storeProcedureThatCommitsThenExhaustsItsBudget() throws Exception {
        ProcedureOperationHelper.executeSave(
                new SaveProcedureRequest(TestGlobals.DB, "audit",
                        "import db from 'db'; import args from 'args';" + " db.save(db.name, '" + AUDIT_COLL
                                + "', { _id: args.id, hits: 1 });"
                                + " setInterval(() => { for (let i = 0; i < 2000; i++) { } }, 0);" + " return 'ok';"),
                DEFINER);
    }

    private void installTrigger() {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "audit",
                        TriggerDefinition.MODE_DOCUMENT, false, true, DEFINER, 1L, 1L, 1L, DEFINER)));
    }

    private static DbEntry entry(String id) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }

    private String recordedRunFor(String id) {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", EventType.CREATED, false, DEFINER, 0, System.currentTimeMillis(), List.of(entry(id))));
    }

    private JsonObject auditRow(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, AUDIT_COLL);
        request.set_id(id);
        final var response = new org.techhouse.ops.OperationProcessor().processMessage(request);
        return response instanceof FindByIdResponse found ? found.getObject() : null;
    }

    private static void settle() {
        try {
            Thread.sleep(300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void test_an_error_raised_after_the_commit_is_not_retried() throws Exception {
        storeProcedureThatCommitsThenExhaustsItsBudget();
        installTrigger();
        final var runId = recordedRunFor("post-commit");

        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", false, List.of(entry("post-commit")), DEFINER, 0, runId, 1));
        settle();

        assertNotNull(auditRow("post-commit"), "the body's write commits before the event loop is drained");
        assertTrue(requeued.isEmpty(),
                "the run record was consumed inside the committed transaction, so a retry would re-apply the write");
    }

    @Test
    public void test_the_committed_effects_are_applied_exactly_once() throws Exception {
        storeProcedureThatCommitsThenExhaustsItsBudget();
        installTrigger();
        final var runId = recordedRunFor("once");

        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", false, List.of(entry("once")), DEFINER, 0, runId, 1));
        settle();

        final var row = auditRow("once");
        assertNotNull(row);
        assertEquals(1L, row.get("hits").asJsonNumber().getValue().longValue());
        assertEquals(0, requeued.size());
    }

    @Test
    public void test_a_failure_before_the_commit_is_still_retried() throws Exception {
        ProcedureOperationHelper.executeSave(
                new SaveProcedureRequest(TestGlobals.DB, "audit", "throw new Error('nothing committed');"), DEFINER);
        installTrigger();
        final var runId = recordedRunFor("uncommitted");

        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", false, List.of(entry("uncommitted")), DEFINER, 0, runId, 1));
        settle();

        assertEquals(1, requeued.size(), "an error with no durable effects keeps its retry budget");
        assertEquals(2, requeued.getFirst().getAttempt());
    }
}
