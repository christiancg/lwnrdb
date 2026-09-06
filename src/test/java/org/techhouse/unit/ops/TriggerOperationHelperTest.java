package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.TriggerOperationHelper;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.resp.ListTriggersResponse;
import org.techhouse.ops.resp.SaveTriggerResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerOperationHelperTest {
    private static final String ACTOR = "alice";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "recalc", "return 1;"), ACTOR);
    }

    private static SaveTriggerRequest request(String name) {
        return new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, name, List.of("CREATED"), "recalc");
    }

    private SaveTriggerResponse save(SaveTriggerRequest request) throws Exception {
        final var response = TriggerOperationHelper.executeSave(request, ACTOR);
        assertInstanceOf(SaveTriggerResponse.class, response, response.getMessage());
        return (SaveTriggerResponse) response;
    }

    @Test
    public void test_save_persists_the_trigger() throws Exception {
        final var response = save(request("audit"));
        assertEquals(1L, response.getVersion());
        assertEquals(ACTOR, response.getDefiner());
        final var stored = cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, stored.size());
        assertEquals("audit", stored.getFirst().getName());
        assertEquals("recalc", stored.getFirst().getProcedureName());
    }

    @Test
    public void test_save_rejects_unknown_collection() throws Exception {
        final var response = TriggerOperationHelper.executeSave(
                new SaveTriggerRequest(TestGlobals.DB, "missingColl", "t", List.of("CREATED"), "recalc"), ACTOR);
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_save_rejects_empty_events() throws Exception {
        final var request = new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t", List.of(), "recalc");
        final var response = TriggerOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("at least one event"));
    }

    @Test
    public void test_save_rejects_unknown_event() throws Exception {
        final var request = new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t", List.of("EXPLODED"),
                "recalc");
        final var response = TriggerOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("EXPLODED"));
    }

    @Test
    public void test_save_rejects_unknown_mode() throws Exception {
        final var request = request("t");
        request.setMode("sometimes");
        final var response = TriggerOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("document"));
    }

    // A trigger pointing at nothing is a configuration error, not a run-time surprise
    @Test
    public void test_save_rejects_missing_procedure() throws Exception {
        final var request = new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t", List.of("CREATED"),
                "nonexistent");
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(),
                TriggerOperationHelper.executeSave(request, ACTOR).getErrorCode());
    }

    @Test
    public void test_save_rejects_disabled_procedure() throws Exception {
        final var disabled = new SaveProcedureRequest(TestGlobals.DB, "off", "return 1;");
        disabled.setEnabled(false);
        ProcedureOperationHelper.executeSave(disabled, ACTOR);
        final var request = new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t", List.of("CREATED"), "off");
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(),
                TriggerOperationHelper.executeSave(request, ACTOR).getErrorCode());
    }

    @Test
    public void test_save_replaces_trigger_of_same_name() throws Exception {
        save(request("audit"));
        final var second = request("audit");
        second.setMode(TriggerDefinition.MODE_BATCH);
        assertEquals(2L, save(second).getVersion());
        final var stored = cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(1, stored.size());
        assertTrue(stored.getFirst().isBatchMode());
    }

    @Test
    public void test_save_keeps_other_triggers() throws Exception {
        save(request("first"));
        save(request("second"));
        assertEquals(2, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
    }

    @Test
    public void test_stale_if_version_conflicts() throws Exception {
        save(request("audit"));
        final var request = request("audit");
        request.setIfVersion(99L);
        assertEquals(ErrorCode.PROCEDURE_VERSION_CONFLICT.getCode(),
                TriggerOperationHelper.executeSave(request, ACTOR).getErrorCode());
    }

    @Test
    public void test_save_stamps_definer_from_acting_user() throws Exception {
        save(request("audit"));
        assertEquals(ACTOR, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).getFirst().getDefiner());
    }

    // An edit must not leave a trigger running with a previous installer's authority
    @Test
    public void test_re_save_re_stamps_definer() throws Exception {
        save(request("audit"));
        TriggerOperationHelper.executeSave(request("audit"), "bob");
        assertEquals("bob", cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).getFirst().getDefiner());
    }

    // Two nodes must never disagree about a trigger's definer
    @Test
    public void test_definer_is_stamped_on_the_request_for_deterministic_re_execution() throws Exception {
        final var request = request("audit");
        TriggerOperationHelper.executeSave(request, ACTOR);
        assertEquals(ACTOR, request.getStampedDefiner());
        assertEquals(1L, request.getStampedVersion());
        assertTrue(request.getStampedUpdatedAt() > 0);
        // Re-executing the stamped request on a "peer" writes the same record, including the definer.
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        TriggerOperationHelper.executeSave(request, "peer-has-no-acting-user");
        final var replicated = cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).getFirst();
        assertEquals(ACTOR, replicated.getDefiner());
        assertEquals(1L, replicated.getVersion());
    }

    @Test
    public void test_delete_is_idempotent_when_absent() throws Exception {
        assertEquals(OperationStatus.OK, TriggerOperationHelper
                .executeDelete(new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "never")).getStatus());
    }

    @Test
    public void test_delete_removes_the_trigger_and_the_file_when_last() throws Exception {
        save(request("audit"));
        assertEquals(OperationStatus.OK, TriggerOperationHelper
                .executeDelete(new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "audit")).getStatus());
        assertTrue(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
        assertNull(fs.readTriggers(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_delete_rejects_unknown_collection() throws Exception {
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), TriggerOperationHelper
                .executeDelete(new DeleteTriggerRequest(TestGlobals.DB, "missingColl", "t")).getErrorCode());
    }

    @Test
    public void test_deleting_referenced_procedure_is_rejected() throws Exception {
        save(request("audit"));
        final var response = ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.DB, "recalc"));
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("audit"));
        assertNotNull(cache.getProcedure(TestGlobals.DB, "recalc"));
    }

    @Test
    public void test_deleting_unreferenced_procedure_is_allowed() throws Exception {
        save(request("audit"));
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "spare", "return 2;"), ACTOR);
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.DB, "spare")).getStatus());
    }

    @Test
    public void test_list_returns_the_collection_name() throws Exception {
        save(request("audit"));
        final var response = (ListTriggersResponse) TriggerOperationHelper
                .executeList(new ListTriggersRequest(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(1, response.getTriggers().size());
        assertEquals(TestGlobals.COLL,
                response.getTriggers().getFirst().get("collectionName").asJsonString().getValue());
    }

    @Test
    public void test_list_without_collection_covers_the_whole_database() throws Exception {
        save(request("audit"));
        final var response = (ListTriggersResponse) TriggerOperationHelper
                .executeList(new ListTriggersRequest(TestGlobals.DB, null));
        assertEquals(1, response.getTriggers().size());
    }

    @Test
    public void test_list_rejects_unknown_database() {
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(),
                TriggerOperationHelper.executeList(new ListTriggersRequest("missingDb", null)).getErrorCode());
    }

    @Test
    public void test_trigger_referencing_finds_nothing_when_absent() {
        assertNull(TriggerOperationHelper.triggerReferencing(TestGlobals.DB, "recalc"));
    }

    @Test
    public void test_save_response_type_is_save_trigger() throws Exception {
        assertEquals(OperationType.SAVE_TRIGGER, save(request("audit")).getType());
    }
}
