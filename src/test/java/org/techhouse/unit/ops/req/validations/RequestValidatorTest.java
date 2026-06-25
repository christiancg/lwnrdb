package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CloseConnectionRequest;
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
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.validations.RequestValidator;

public class RequestValidatorTest {

    // CLOSE_CONNECTION and LIST_DATABASES are always valid
    @Test
    public void validate_closeConnection_returnsOk() {
        assertTrue(RequestValidator.validate(new CloseConnectionRequest()).isValid());
    }

    @Test
    public void validate_listDatabases_returnsOk() {
        assertTrue(RequestValidator.validate(new ListDatabasesRequest()).isValid());
    }

    // CREATE_DATABASE
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
    public void validate_createDatabase_exactlyThreeChars_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateDatabaseRequest("abc")).isValid());
    }

    @Test
    public void validate_createDatabase_exactly64Chars_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateDatabaseRequest("a".repeat(64))).isValid());
    }

    // DROP_DATABASE
    @Test
    public void validate_dropDatabase_validName_returnsOk() {
        assertTrue(RequestValidator.validate(new DropDatabaseRequest("myDatabase")).isValid());
    }

    @Test
    public void validate_dropDatabase_adminName_returnsFail() {
        assertFalse(RequestValidator.validate(new DropDatabaseRequest("admin")).isValid());
    }

    // LIST_COLLECTIONS
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
        // LIST_COLLECTIONS does not reject admin
        assertTrue(RequestValidator.validate(new ListCollectionsRequest("myDb")).isValid());
    }

    // CREATE_COLLECTION
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

    // DROP_COLLECTION
    @Test
    public void validate_dropCollection_validNames_returnsOk() {
        assertTrue(RequestValidator.validate(new DropCollectionRequest("myDb", "myColl")).isValid());
    }

    // SAVE
    @Test
    public void validate_save_validObjectNoId_returnsOk() {
        final var req = new SaveRequest("myDb", "myColl");
        req.setObject(new JsonObject());
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_validObjectWithValidId_returnsOk() {
        final var req = new SaveRequest("myDb", "myColl");
        req.setObject(new JsonObject());
        req.set_id("valid-id_123");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_nullObject_returnsFail() {
        final var req = new SaveRequest("myDb", "myColl");
        req.setObject(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_idWithInvalidChars_returnsFail() {
        final var req = new SaveRequest("myDb", "myColl");
        req.setObject(new JsonObject());
        req.set_id("id with space");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_idTooLong_returnsFail() {
        final var req = new SaveRequest("myDb", "myColl");
        req.setObject(new JsonObject());
        req.set_id("a".repeat(65));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_idExactlyOneChar_returnsOk() {
        final var req = new SaveRequest("myDb", "myColl");
        req.setObject(new JsonObject());
        req.set_id("a");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_adminDb_returnsFail() {
        final var req = new SaveRequest("admin", "myColl");
        req.setObject(new JsonObject());
        assertFalse(RequestValidator.validate(req).isValid());
    }

    // BULK_SAVE
    @Test
    public void validate_bulkSave_validObjects_returnsOk() {
        final var req = new BulkSaveRequest("myDb", "myColl");
        req.setObjects(List.of(new JsonObject()));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_bulkSave_nullObjects_returnsFail() {
        final var req = new BulkSaveRequest("myDb", "myColl");
        req.setObjects(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_bulkSave_emptyObjects_returnsFail() {
        final var req = new BulkSaveRequest("myDb", "myColl");
        req.setObjects(List.of());
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_bulkSave_objectWithInvalidId_returnsFail() {
        final var req = new BulkSaveRequest("myDb", "myColl");
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("id with space"));
        req.setObjects(List.of(obj));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_bulkSave_objectWithValidId_returnsOk() {
        final var req = new BulkSaveRequest("myDb", "myColl");
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("valid-id"));
        req.setObjects(List.of(obj));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    // FIND_BY_ID
    @Test
    public void validate_findById_validId_returnsOk() {
        final var req = new FindByIdRequest("myDb", "myColl");
        req.set_id("abc123");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_findById_nullId_returnsFail() {
        final var req = new FindByIdRequest("myDb", "myColl");
        req.set_id(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_findById_invalidIdChars_returnsFail() {
        final var req = new FindByIdRequest("myDb", "myColl");
        req.set_id("bad id!");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_findById_adminDbAllowed_returnsOk() {
        // FIND_BY_ID does not reject admin
        final var req = new FindByIdRequest("admin", "myColl");
        req.set_id("abc");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    // DELETE
    @Test
    public void validate_delete_validId_returnsOk() {
        final var req = new DeleteRequest("myDb", "myColl");
        req.set_id("abc123");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_delete_nullId_returnsFail() {
        final var req = new DeleteRequest("myDb", "myColl");
        req.set_id(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_delete_adminDb_returnsFail() {
        final var req = new DeleteRequest("admin", "myColl");
        req.set_id("abc");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    // AGGREGATE
    @Test
    public void validate_aggregate_nullSteps_returnsFail() {
        final var req = new AggregateRequest("myDb", "myColl");
        req.setAggregationSteps(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_emptySteps_returnsOk() {
        final var req = new AggregateRequest("myDb", "myColl");
        req.setAggregationSteps(List.of());
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_validSteps_returnsOk() {
        final var req = new AggregateRequest("myDb", "myColl");
        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("test")));
        req.setAggregationSteps(List.of(filter));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_invalidStep_returnsFail() {
        final var req = new AggregateRequest("myDb", "myColl");
        req.setAggregationSteps(List.of(new LimitAggregationStep(-1)));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_countAsLastStep_returnsOk() {
        final var req = new AggregateRequest("myDb", "myColl");
        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("test")));
        req.setAggregationSteps(List.of(filter, new CountAggregationStep()));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_countOnlyStep_returnsOk() {
        final var req = new AggregateRequest("myDb", "myColl");
        req.setAggregationSteps(List.of(new CountAggregationStep()));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_countNotLast_returnsFail() {
        final var req = new AggregateRequest("myDb", "myColl");
        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("test")));
        req.setAggregationSteps(List.of(filter, new CountAggregationStep(), filter));
        final var result = RequestValidator.validate(req);
        assertFalse(result.isValid());
        assertEquals("COUNT must be the last aggregation step", result.getErrorMessage());
    }

    // CREATE_INDEX
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

    // DROP_INDEX
    @Test
    public void validate_dropIndex_validFieldName_returnsOk() {
        assertTrue(RequestValidator.validate(new DropIndexRequest("myDb", "myColl", "myField")).isValid());
    }

    @Test
    public void validate_dropIndex_nullFieldName_returnsFail() {
        assertFalse(RequestValidator.validate(new DropIndexRequest("myDb", "myColl", null)).isValid());
    }

    // REINDEX
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
}
