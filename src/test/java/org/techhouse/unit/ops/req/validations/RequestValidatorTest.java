package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CloseConnectionRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.validations.RequestValidator;

public class RequestValidatorTest {
    @Test
    public void validate_closeConnection_returnsOk() {
        assertTrue(RequestValidator.validate(new CloseConnectionRequest()).isValid());
    }

    @Test
    public void validate_resolveTransaction_valid() {
        final var request = new org.techhouse.ops.req.ResolveTransactionRequest();
        request.setDtxId("11111111-1111-1111-1111-111111111111");
        request.setDecision(org.techhouse.ops.req.ResolveTransactionRequest.DECISION_COMMIT);
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_resolveTransaction_blankDtxId_fails() {
        final var request = new org.techhouse.ops.req.ResolveTransactionRequest();
        request.setDecision(org.techhouse.ops.req.ResolveTransactionRequest.DECISION_ABORT);
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_resolveTransaction_badDecision_fails() {
        final var request = new org.techhouse.ops.req.ResolveTransactionRequest();
        request.setDtxId("11111111-1111-1111-1111-111111111111");
        request.setDecision("maybe");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_listTransactions_returnsOk() {
        assertTrue(RequestValidator.validate(new org.techhouse.ops.req.ListTransactionsRequest()).isValid());
    }

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
        final var req = new FindByIdRequest("admin", "myColl");
        req.set_id("abc");
        assertTrue(RequestValidator.validate(req).isValid());
    }

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
}
