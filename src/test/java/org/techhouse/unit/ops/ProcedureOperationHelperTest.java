package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.ListProceduresRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.ListProceduresResponse;
import org.techhouse.ops.resp.SaveProcedureResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureOperationHelperTest {
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
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
    }

    private static SaveProcedureRequest saveRequest(String script) {
        return new SaveProcedureRequest(TestGlobals.DB, "p", script);
    }

    private SaveProcedureResponse save(String script) throws Exception {
        final var response = ProcedureOperationHelper.executeSave(saveRequest(script), ACTOR);
        assertInstanceOf(SaveProcedureResponse.class, response, response.getMessage());
        return (SaveProcedureResponse) response;
    }

    @Test
    public void test_save_rejects_when_scripts_disabled() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        final var response = ProcedureOperationHelper.executeSave(saveRequest("return 1;"), ACTOR);
        assertEquals(OperationStatus.FORBIDDEN, response.getStatus());
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(), response.getErrorCode());
        assertEquals(OperationType.SAVE_PROCEDURE, response.getType());
    }

    @Test
    public void test_save_rejects_unknown_database() throws Exception {
        final var response = ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest("missingDb", "p", "return 1;"), ACTOR);
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("missingDb"));
    }

    @Test
    public void test_save_rejects_oversized_source() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 8L);
        final var response = ProcedureOperationHelper.executeSave(saveRequest("return 123456789;"), ACTOR);
        assertEquals(ErrorCode.SCRIPT_TOO_LARGE.getCode(), response.getErrorCode());
    }

    // The point of a stored procedure over a client-side string: a broken body is refused at save time
    @Test
    public void test_save_rejects_unparseable_source() throws Exception {
        final var response = ProcedureOperationHelper.executeSave(saveRequest("return (;"), ACTOR);
        assertEquals(ErrorCode.INVALID_PROCEDURE.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("line"), response.getMessage());
        assertNull(cache.getProcedure(TestGlobals.DB, "p"));
    }

    @Test
    public void test_save_assigns_version_one_then_increments() throws Exception {
        assertEquals(1L, save("return 1;").getVersion());
        assertEquals(2L, save("return 2;").getVersion());
        assertEquals(3L, save("return 3;").getVersion());
        assertEquals("return 3;", cache.getProcedure(TestGlobals.DB, "p").getSource());
    }

    @Test
    public void test_save_preserves_created_at_on_update() throws Exception {
        save("return 1;");
        final var createdAt = cache.getProcedure(TestGlobals.DB, "p").getCreatedAt();
        save("return 2;");
        assertEquals(createdAt, cache.getProcedure(TestGlobals.DB, "p").getCreatedAt());
    }

    @Test
    public void test_save_records_the_acting_user() throws Exception {
        save("return 1;");
        assertEquals(ACTOR, cache.getProcedure(TestGlobals.DB, "p").getUpdatedBy());
    }

    @Test
    public void test_save_with_stale_if_version_conflicts() throws Exception {
        save("return 1;");
        final var request = saveRequest("return 2;");
        request.setIfVersion(99L);
        final var response = ProcedureOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.PROCEDURE_VERSION_CONFLICT.getCode(), response.getErrorCode());
        assertEquals("return 1;", cache.getProcedure(TestGlobals.DB, "p").getSource());
    }

    @Test
    public void test_save_with_matching_if_version_succeeds() throws Exception {
        save("return 1;");
        final var request = saveRequest("return 2;");
        request.setIfVersion(1L);
        assertInstanceOf(SaveProcedureResponse.class, ProcedureOperationHelper.executeSave(request, ACTOR));
        assertEquals("return 2;", cache.getProcedure(TestGlobals.DB, "p").getSource());
    }

    // 0 means "must not exist yet", not "unconditional"
    @Test
    public void test_if_version_zero_is_not_treated_as_absent() throws Exception {
        final var create = saveRequest("return 1;");
        create.setIfVersion(0L);
        assertInstanceOf(SaveProcedureResponse.class, ProcedureOperationHelper.executeSave(create, ACTOR));
        final var again = saveRequest("return 2;");
        again.setIfVersion(0L);
        assertEquals(ErrorCode.PROCEDURE_VERSION_CONFLICT.getCode(),
                ProcedureOperationHelper.executeSave(again, ACTOR).getErrorCode());
    }

    // What keeps a re-executing peer byte-identical
    @Test
    public void test_save_stamps_request_for_deterministic_re_execution() throws Exception {
        final var request = saveRequest("return 1;");
        ProcedureOperationHelper.executeSave(request, ACTOR);
        final var stored = cache.getProcedure(TestGlobals.DB, "p");
        assertEquals(stored.getVersion(), request.getStampedVersion());
        assertEquals(stored.getUpdatedAt(), request.getStampedUpdatedAt());
        assertEquals(stored.getUpdatedBy(), request.getStampedUpdatedBy());
    }

    // Re-executing the stamped request writes the same record rather than bumping the version again
    @Test
    public void test_re_executing_a_stamped_request_is_idempotent() throws Exception {
        final var request = saveRequest("return 1;");
        ProcedureOperationHelper.executeSave(request, ACTOR);
        final var first = cache.getProcedure(TestGlobals.DB, "p");
        ProcedureOperationHelper.executeSave(request, "someone-else");
        final var second = cache.getProcedure(TestGlobals.DB, "p");
        assertEquals(first, second);
    }

    @Test
    public void test_delete_is_idempotent_when_absent() throws Exception {
        final var response = ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.DB, "never-existed"));
        assertEquals(OperationStatus.OK, response.getStatus());
    }

    @Test
    public void test_delete_removes_the_procedure() throws Exception {
        save("return 1;");
        assertEquals(OperationStatus.OK,
                ProcedureOperationHelper.executeDelete(new DeleteProcedureRequest(TestGlobals.DB, "p")).getStatus());
        assertNull(cache.getProcedure(TestGlobals.DB, "p"));
        assertTrue(fs.listProcedureNames(TestGlobals.DB).isEmpty());
    }

    @Test
    public void test_delete_rejects_unknown_database() throws Exception {
        final var response = ProcedureOperationHelper.executeDelete(new DeleteProcedureRequest("missingDb", "p"));
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_list_omits_source_unless_requested() throws Exception {
        save("return 1;");
        final var withoutSource = (ListProceduresResponse) ProcedureOperationHelper
                .executeList(new ListProceduresRequest(TestGlobals.DB));
        assertEquals(1, withoutSource.getProcedures().size());
        assertFalse(withoutSource.getProcedures().getFirst().has("source"));

        final var request = new ListProceduresRequest(TestGlobals.DB);
        request.setIncludeSource(true);
        final var withSource = (ListProceduresResponse) ProcedureOperationHelper.executeList(request);
        assertEquals("return 1;", withSource.getProcedures().getFirst().get("source").asJsonString().getValue());
    }

    @Test
    public void test_list_is_empty_for_a_database_with_no_procedures() {
        final var response = (ListProceduresResponse) ProcedureOperationHelper
                .executeList(new ListProceduresRequest(TestGlobals.DB));
        assertTrue(response.getProcedures().isEmpty());
    }

    @Test
    public void test_list_rejects_unknown_database() {
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(),
                ProcedureOperationHelper.executeList(new ListProceduresRequest("missingDb")).getErrorCode());
    }

    @Test
    public void test_disabled_flag_is_persisted() throws Exception {
        final var request = saveRequest("return 1;");
        request.setEnabled(false);
        ProcedureOperationHelper.executeSave(request, ACTOR);
        assertFalse(cache.getProcedure(TestGlobals.DB, "p").isEnabled());
    }

    @Test
    public void test_description_is_persisted() throws Exception {
        final var request = saveRequest("return 1;");
        request.setDescription("recalculates the totals");
        ProcedureOperationHelper.executeSave(request, ACTOR);
        assertEquals("recalculates the totals", cache.getProcedure(TestGlobals.DB, "p").getDescription());
    }
}
