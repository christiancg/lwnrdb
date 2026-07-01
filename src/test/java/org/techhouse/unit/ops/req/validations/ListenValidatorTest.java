package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.StopListenRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.validations.RequestValidator;

public class ListenValidatorTest {

    // Valid LISTEN request passes validation
    @Test
    public void validate_listen_validRequest_returnsOk() {
        final var req = new ListenRequest("myDb", "myColl");
        req.setAggregationSteps(List.of());

        assertTrue(RequestValidator.validate(req).isValid());
    }

    // LISTEN with null databaseName fails validation
    @Test
    public void validate_listen_nullDb_returnsFail() {
        final var req = new ListenRequest(null, "coll");
        req.setAggregationSteps(List.of());

        assertFalse(RequestValidator.validate(req).isValid());
    }

    // LISTEN with null collectionName fails validation
    @Test
    public void validate_listen_nullColl_returnsFail() {
        final var req = new ListenRequest("db", null);
        req.setAggregationSteps(List.of());

        assertFalse(RequestValidator.validate(req).isValid());
    }

    // LISTEN with null aggregationSteps fails validation
    @Test
    public void validate_listen_nullSteps_returnsFail() {
        final var req = new ListenRequest("myDb", "myColl");

        assertFalse(RequestValidator.validate(req).isValid());
    }

    // LISTEN with valid filter step passes validation
    @Test
    public void validate_listen_withFilterStep_returnsOk() {
        final var req = new ListenRequest("myDb", "myColl");
        final var op = new FieldOperator(FieldOperatorType.EQUALS, "status", null);
        req.setAggregationSteps(List.of(new FilterAggregationStep(op)));

        assertTrue(RequestValidator.validate(req).isValid());
    }

    // STOP_LISTEN with valid UUID passes validation
    @Test
    public void validate_stopListen_validUUID_returnsOk() {
        final var req = new StopListenRequest();
        req.setListenId(UUID.randomUUID().toString());

        assertTrue(RequestValidator.validate(req).isValid());
    }

    // STOP_LISTEN with null listenId fails
    @Test
    public void validate_stopListen_nullId_returnsFail() {
        final var req = new StopListenRequest();

        assertFalse(RequestValidator.validate(req).isValid());
    }

    // STOP_LISTEN with blank listenId fails
    @Test
    public void validate_stopListen_blankId_returnsFail() {
        final var req = new StopListenRequest();
        req.setListenId("   ");

        assertFalse(RequestValidator.validate(req).isValid());
    }

    // STOP_LISTEN with non-UUID string fails
    @Test
    public void validate_stopListen_nonUuidId_returnsFail() {
        final var req = new StopListenRequest();
        req.setListenId("not-a-uuid");

        assertFalse(RequestValidator.validate(req).isValid());
    }
}
