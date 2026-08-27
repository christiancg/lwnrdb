package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
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
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The dispatcher's own behaviour, definer rights above all: the trigger must behave the same regardless of
 * who performed the write, which is exactly what invoker rights could not offer.
 */
public class TriggerDispatcherTest {
    private static final String OWNER = "trigowner";
    private static final String WRITER = "trigwriter";
    private static final String AUDIT_COLL = "auditColl";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, AUDIT_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.DB, AUDIT_COLL);
        AdminOperationHelper
                .saveCollectionEntry(new org.techhouse.data.admin.AdminCollEntry(TestGlobals.DB, AUDIT_COLL));
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(OWNER));
        createUser(OWNER, new HashMap<>());
        // The writer may write the source collection but has no access to the audit collection at all.
        final var writerPerms = new HashMap<String, PermissionLevel>();
        createUser(WRITER, writerPerms);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(TestGlobals.DB + "|" + TestGlobals.COLL, PermissionLevel.READ_WRITE);
        final var change = new org.techhouse.ops.req.ChangePermissionsRequest();
        change.setUsername(WRITER);
        change.setAdmin(false);
        change.setGlobalPermissions(new HashSet<>());
        change.setDatabasePermissions(new HashMap<>());
        change.setCollectionPermissions(collPerms);
        UserOperationHelper.processChangePermissions(change);
    }

    private static void createUser(String username, HashMap<String, PermissionLevel> dbPerms) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(dbPerms);
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
    }

    private void storeAuditProcedure() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "audit",
                "import db from 'db'; import args from 'args';" + "db.save(db.name, '" + AUDIT_COLL
                        + "', { _id: args.id, by: args.actingUser,"
                        + " definer: args.definer, event: args.event }); return 'ok';"),
                OWNER);
    }

    private void installTrigger(String definer, boolean enabled, boolean allowCascade) {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "audit",
                        TriggerDefinition.MODE_DOCUMENT, allowCascade, enabled, definer, 1L, 1L, 1L, definer)));
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

    private static TriggerEvent event(String id, String actingUser, int depth) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "audit", false,
                List.of(entry(id)), actingUser, depth);
    }

    private JsonObject auditRow(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, AUDIT_COLL);
        request.set_id(id);
        final var response = IocContainer.get(org.techhouse.ops.OperationProcessor.class).processMessage(request);
        if (response instanceof org.techhouse.ops.resp.FindByIdResponse found) {
            return found.getObject();
        }
        return null;
    }

    // The whole point of definer rights: a writer with no access to the audit collection still produces
    // the audit row, because the trigger runs as the user who installed it.
    @Test
    public void test_runs_with_definer_authority_not_writers_authority() throws Exception {
        storeAuditProcedure();
        installTrigger(OWNER, true, false);
        TriggerDispatcher.dispatch(event("low-privilege", WRITER, 0));
        final var row = auditRow("low-privilege");
        assertNotNull(row, "the audit row must exist even though the writer cannot write to that collection");
        assertEquals(WRITER, row.get("by").asJsonString().getValue());
        assertEquals(OWNER, row.get("definer").asJsonString().getValue());
    }

    // The property invoker rights broke: the effect must not depend on who wrote
    @Test
    public void test_behaves_identically_for_low_and_high_privilege_writers() throws Exception {
        storeAuditProcedure();
        installTrigger(OWNER, true, false);
        TriggerDispatcher.dispatch(event("by-writer", WRITER, 0));
        TriggerDispatcher.dispatch(event("by-owner", OWNER, 0));
        assertNotNull(auditRow("by-writer"));
        assertNotNull(auditRow("by-owner"));
    }

    // No fallback to the writer (that would reinstate invoker rights) nor to an admin (that would let
    // deleting a user widen a trigger's authority).
    @Test
    public void test_deleted_definer_disables_trigger_and_does_not_fall_back_to_writer() throws Exception {
        storeAuditProcedure();
        createUser("temporary", new HashMap<>());
        installTrigger("temporary", true, false);
        final var deleteRequest = new DeleteUserRequest();
        deleteRequest.setUsername("temporary");
        UserOperationHelper.processDeleteUser(deleteRequest);
        final var failedBefore = triggerExecutor.getFailed();
        TriggerDispatcher.dispatch(event("orphaned", OWNER, 0));
        assertNull(auditRow("orphaned"), "a trigger whose definer is gone must not run");
        assertEquals(failedBefore + 1, triggerExecutor.getFailed());
    }

    @Test
    public void test_null_definer_disables_the_trigger() throws Exception {
        storeAuditProcedure();
        installTrigger(null, true, false);
        TriggerDispatcher.dispatch(event("no-definer", OWNER, 0));
        assertNull(auditRow("no-definer"));
    }

    @Test
    public void test_drops_event_past_max_depth() throws Exception {
        storeAuditProcedure();
        installTrigger(OWNER, true, true);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 2);
        final var failedBefore = triggerExecutor.getFailed();
        TriggerDispatcher.dispatch(event("too-deep", OWNER, 2));
        assertNull(auditRow("too-deep"));
        assertEquals(failedBefore + 1, triggerExecutor.getFailed());
    }

    @Test
    public void test_skips_disabled_trigger() throws Exception {
        storeAuditProcedure();
        installTrigger(OWNER, false, false);
        TriggerDispatcher.dispatch(event("disabled", OWNER, 0));
        assertNull(auditRow("disabled"));
    }

    // The trigger record may have been dropped while the event was queued
    @Test
    public void test_skips_trigger_removed_while_queued() throws Exception {
        storeAuditProcedure();
        installTrigger(OWNER, true, false);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of());
        TriggerDispatcher.dispatch(event("gone", OWNER, 0));
        assertNull(auditRow("gone"));
    }

    @Test
    public void test_skips_when_the_procedure_was_removed_while_queued() throws Exception {
        storeAuditProcedure();
        installTrigger(OWNER, true, false);
        cache.removeProceduresForDatabase(TestGlobals.DB);
        fs.deleteProcedure(TestGlobals.DB, "audit");
        TriggerDispatcher.dispatch(event("no-procedure", OWNER, 0));
        assertNull(auditRow("no-procedure"));
    }

    // Every write a trigger issues carries depth+1, which is what bounds a cascade
    @Test
    public void test_stamps_issued_requests_with_incremented_depth() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "audit",
                "import db from 'db'; import args from 'args';" + "db.save(db.name, '" + AUDIT_COLL
                        + "', { _id: args.id, depth: args.depth });" + "return 'ok';"),
                OWNER);
        installTrigger(OWNER, true, true);
        TriggerDispatcher.dispatch(event("depth-one", OWNER, 1));
        final var row = auditRow("depth-one");
        assertNotNull(row);
        assertEquals(1d, row.get("depth").asJsonNumber().getValue().doubleValue());
    }

    // A trigger failure never reaches the write that fired it - it already committed
    @Test
    public void test_script_failure_is_logged_and_swallowed() throws Exception {
        ProcedureOperationHelper.executeSave(
                new SaveProcedureRequest(TestGlobals.DB, "audit", "throw new Error('trigger blew up');"), OWNER);
        installTrigger(OWNER, true, false);
        final var failedBefore = triggerExecutor.getFailed();
        assertDoesNotThrow(() -> TriggerDispatcher.dispatch(event("boom", OWNER, 0)));
        assertEquals(failedBefore + 1, triggerExecutor.getFailed());
    }

    @Test
    public void test_args_carry_writer_as_acting_user_and_installer_as_definer() throws Exception {
        storeAuditProcedure();
        installTrigger(OWNER, true, false);
        TriggerDispatcher.dispatch(event("identities", WRITER, 0));
        final var row = auditRow("identities");
        assert row != null;
        assertEquals(WRITER, row.get("by").asJsonString().getValue());
        assertEquals(OWNER, row.get("definer").asJsonString().getValue());
        assertEquals("CREATED", row.get("event").asJsonString().getValue());
    }

    // Batch mode hands the whole batch to one run
    @Test
    public void test_batch_mode_passes_documents() throws Exception {
        ProcedureOperationHelper
                .executeSave(
                        new SaveProcedureRequest(TestGlobals.DB, "audit",
                                "import db from 'db'; import args from 'args';" + "db.save(db.name, '" + AUDIT_COLL
                                        + "', { _id: 'batch', count: args.documents.length });" + "return 'ok';"),
                        OWNER);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "audit",
                        TriggerDefinition.MODE_BATCH, false, true, OWNER, 1L, 1L, 1L, OWNER)));
        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", true, List.of(entry("a"), entry("b")), OWNER, 0));
        assertEquals(2d,
                Objects.requireNonNull(auditRow("batch")).get("count").asJsonNumber().getValue().doubleValue());
    }

    // The trigger's own budget, tighter than scriptTimeoutMs because nobody is waiting on it
    @Test
    public void test_trigger_timeout_is_applied() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "audit", "while (true) { }"),
                OWNER);
        installTrigger(OWNER, true, false);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 50L);
        final var failedBefore = triggerExecutor.getFailed();
        final var start = System.currentTimeMillis();
        TriggerDispatcher.dispatch(event("slow", OWNER, 0));
        assertTrue(System.currentTimeMillis() - start < 4_000, "the trigger budget, not scriptTimeoutMs, applied");
        assertEquals(failedBefore + 1, triggerExecutor.getFailed());
    }
}
