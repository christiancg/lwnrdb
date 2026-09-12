package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.req.ListDatabasesRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.validations.RequestValidator;

public class DatabaseRequestValidatorTest {
    @Test
    public void validate_listDatabases_returnsOk() {
        assertTrue(RequestValidator.validate(new ListDatabasesRequest()).isValid());
    }

    @Test
    public void validate_createDatabase_validName_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateDatabaseRequest("myDatabase")).isValid());
    }

    @Test
    public void validate_createDatabase_nullName_returnsFail() {
        final var result = RequestValidator.validate(new CreateDatabaseRequest(null));
        assertFalse(result.isValid());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    public void validate_createDatabase_tooShortName_returnsFail() {
        final var result = RequestValidator.validate(new CreateDatabaseRequest("ab"));
        assertFalse(result.isValid());
    }

    @Test
    public void validate_createDatabase_tooLongName_returnsFail() {
        final var result = RequestValidator.validate(new CreateDatabaseRequest("a".repeat(65)));
        assertFalse(result.isValid());
    }

    @Test
    public void validate_createDatabase_invalidChars_returnsFail() {
        final var result = RequestValidator.validate(new CreateDatabaseRequest("my db!"));
        assertFalse(result.isValid());
    }

    @Test
    public void validate_createDatabase_adminName_returnsFail() {
        final var result = RequestValidator.validate(new CreateDatabaseRequest("admin"));
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("reserved"));
    }

    @Test
    public void validate_createDatabase_adminPagesName_returnsFail() {
        final var result = RequestValidator
                .validate(new CreateDatabaseRequest(org.techhouse.config.Globals.ADMIN_PAGES_DB_NAME));
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("reserved"));
    }

    @Test
    public void validate_createDatabase_exactlyThreeChars_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateDatabaseRequest("abc")).isValid());
    }

    @Test
    public void validate_createDatabase_exactly64Chars_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateDatabaseRequest("a".repeat(64))).isValid());
    }

    @Test
    public void validate_dropDatabase_validName_returnsOk() {
        assertTrue(RequestValidator.validate(new DropDatabaseRequest("myDatabase")).isValid());
    }

    @Test
    public void validate_dropDatabase_adminName_returnsFail() {
        assertFalse(RequestValidator.validate(new DropDatabaseRequest("admin")).isValid());
    }

    @Test
    public void validate_listCollections_validDbName_returnsOk() {
        assertTrue(RequestValidator.validate(new ListCollectionsRequest("myDb")).isValid());
    }

    @Test
    public void validate_listCollections_nullDbName_returnsFail() {
        assertFalse(RequestValidator.validate(new ListCollectionsRequest(null)).isValid());
    }

    @Test
    public void validate_listCollections_adminAllowed_returnsOk() {
        assertTrue(RequestValidator.validate(new ListCollectionsRequest("myDb")).isValid());
    }

    @Test
    public void validate_createCollection_validNames_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateCollectionRequest("myDb", "myColl")).isValid());
    }

    @Test
    public void validate_createCollection_nullCollectionName_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateCollectionRequest("myDb", null)).isValid());
    }

    @Test
    public void validate_createCollection_adminDb_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateCollectionRequest("admin", "myColl")).isValid());
    }

    @Test
    public void validate_createCollection_invalidCollectionName_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateCollectionRequest("myDb", "ab")).isValid());
    }

    @Test
    public void validate_dropCollection_validNames_returnsOk() {
        assertTrue(RequestValidator.validate(new DropCollectionRequest("myDb", "myColl")).isValid());
    }

    @Test
    public void validate_createIndex_validFieldName_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateIndexRequest("myDb", "myColl", "myField")).isValid());
    }

    @Test
    public void validate_createIndex_blankFieldName_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateIndexRequest("myDb", "myColl", "  ")).isValid());
    }

    @Test
    public void validate_createIndex_nullFieldName_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateIndexRequest("myDb", "myColl", null)).isValid());
    }

    @Test
    public void validate_dropIndex_validFieldName_returnsOk() {
        assertTrue(RequestValidator.validate(new DropIndexRequest("myDb", "myColl", "myField")).isValid());
    }

    @Test
    public void validate_dropIndex_nullFieldName_returnsFail() {
        assertFalse(RequestValidator.validate(new DropIndexRequest("myDb", "myColl", null)).isValid());
    }

    @Test
    public void validate_reindex_noFieldNames_returnsOk() {
        assertTrue(RequestValidator.validate(new ReindexRequest("myDb", "myColl", null)).isValid());
    }

    @Test
    public void validate_reindex_withValidFieldNames_returnsOk() {
        assertTrue(
                RequestValidator.validate(new ReindexRequest("myDb", "myColl", List.of("email", "status"))).isValid());
    }

    @Test
    public void validate_reindex_blankEntryInFieldNames_returnsFail() {
        final var result = RequestValidator.validate(new ReindexRequest("myDb", "myColl", List.of("email", " ")));
        assertFalse(result.isValid());
        assertEquals("REINDEX fieldNames must not contain blank entries", result.getErrorMessage());
    }

    @Test
    public void validate_reindex_nullEntryInFieldNames_returnsFail() {
        final var names = new java.util.ArrayList<String>();
        names.add("email");
        names.add(null);
        assertFalse(RequestValidator.validate(new ReindexRequest("myDb", "myColl", names)).isValid());
    }

    @Test
    public void validate_reindex_nullDatabaseName_returnsFail() {
        assertFalse(RequestValidator.validate(new ReindexRequest(null, "myColl", null)).isValid());
    }

    @Test
    public void validate_reindex_adminDatabase_returnsFail() {
        assertFalse(RequestValidator.validate(new ReindexRequest("admin", "myColl", null)).isValid());
    }

    @Test
    public void validate_runScript_missingDatabaseName_returnsFail() {
        assertFalse(RequestValidator.validate(new org.techhouse.ops.req.RunScriptRequest(null, "return 1;", null))
                .isValid());
    }

    @Test
    public void validate_runScript_invalidDatabaseName_returnsFail() {
        assertFalse(RequestValidator.validate(new org.techhouse.ops.req.RunScriptRequest("a", "return 1;", null))
                .isValid());
    }

    @Test
    public void validate_runScript_adminDatabase_returnsFail() {
        assertFalse(RequestValidator.validate(new org.techhouse.ops.req.RunScriptRequest("admin", "return 1;", null))
                .isValid());
    }

    // The history collection is the server's to write. Reads stay open - it exists to be queried.
    @Test
    public void validate_save_toReservedScriptRunsCollection_returnsFail() {
        final var request = new SaveRequest("myDb", "script_runs");
        request.setObject(new JsonObject());
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_bulkSave_toReservedScriptRunsCollection_returnsFail() {
        final var request = new BulkSaveRequest("myDb", "script_runs");
        request.setObjects(List.of(new JsonObject()));
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_delete_fromReservedScriptRunsCollection_returnsFail() {
        final var request = new DeleteRequest("myDb", "script_runs");
        request.set_id("abc");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_createCollection_withReservedScriptRunsName_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateCollectionRequest("myDb", "script_runs")).isValid());
    }

    @Test
    public void validate_dropCollection_ofReservedScriptRunsCollection_returnsFail() {
        assertFalse(RequestValidator.validate(new DropCollectionRequest("myDb", "script_runs")).isValid());
    }

    @Test
    public void validate_aggregate_overReservedScriptRunsCollection_returnsOk() {
        final var request = new AggregateRequest("myDb", "script_runs");
        request.setAggregationSteps(List.of(new CountAggregationStep()));
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_findById_inReservedScriptRunsCollection_returnsOk() {
        final var request = new FindByIdRequest("myDb", "script_runs");
        request.set_id("abc");
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_createIndex_onReservedScriptRunsCollection_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateIndexRequest("myDb", "script_runs", "outcome")).isValid());
    }
}
