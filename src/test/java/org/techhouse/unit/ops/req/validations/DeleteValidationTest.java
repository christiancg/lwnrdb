package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.test.TestUtils;

public class DeleteValidationTest {
    private final Configuration configuration = Configuration.getInstance();

    @AfterEach
    public void restore() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", ConfigKey.MAX_FILTER_OPERANDS.defaultValue());
    }

    private static DeleteRequest delete(String id, BaseOperator condition) {
        final var request = new DeleteRequest("testDb", "testColl");
        request.set_id(id);
        request.setCondition(condition);
        return request;
    }

    private static FieldOperator statusIs(String status) {
        return new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString(status));
    }

    @Test
    public void test_id_and_condition_together_is_400_1() {
        final var result = RequestValidator.validate(delete("o-1", statusIs("archived")));

        assertFalse(result.isValid());
        assertEquals(ErrorCode.VALIDATION_ERROR, result.getErrorCode());
        assertEquals("DELETE takes either an _id or a condition, not both", result.getErrorMessage());
    }

    @Test
    public void test_neither_id_nor_condition_is_400_1() {
        final var result = RequestValidator.validate(delete(null, null));

        assertFalse(result.isValid());
        assertEquals(ErrorCode.VALIDATION_ERROR, result.getErrorCode());
        assertEquals("DELETE request requires an _id or a condition", result.getErrorMessage());
    }

    @Test
    public void test_a_valid_condition_passes() {
        assertTrue(RequestValidator.validate(delete(null, statusIs("archived"))).isValid());
    }

    @Test
    public void test_an_id_alone_passes_and_a_malformed_one_is_refused() {
        assertTrue(RequestValidator.validate(delete("o-1", null)).isValid());
        assertFalse(RequestValidator.validate(delete("not an id!", null)).isValid());
    }

    @Test
    public void test_a_condition_with_an_operator_missing_its_field_is_400_1() {
        final var result = RequestValidator
                .validate(delete(null, new FieldOperator(FieldOperatorType.EQUALS, null, new JsonNumber(1))));

        assertFalse(result.isValid());
        assertEquals(ErrorCode.VALIDATION_ERROR, result.getErrorCode());
    }

    @Test
    public void test_a_condition_past_max_filter_operands_is_400_27() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", 4);
        final var operands = new JsonArray();
        for (var i = 0; i < 5; i++) {
            operands.add(new JsonNumber(i));
        }

        final var result = RequestValidator
                .validate(delete(null, new FieldOperator(FieldOperatorType.IN, "n", operands)));

        assertEquals(ErrorCode.FILTER_OPERAND_LIMIT, result.getErrorCode());
    }

    @Test
    public void test_a_conjunction_past_max_filter_operands_nodes_is_400_27() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", 4);
        final var legs = new ArrayList<BaseOperator>();
        for (var i = 0; i < 5; i++) {
            legs.add(statusIs("s" + i));
        }

        final var result = RequestValidator
                .validate(delete(null, new ConjunctionOperator(ConjunctionOperatorType.OR, legs)));

        assertEquals(ErrorCode.FILTER_OPERAND_LIMIT, result.getErrorCode());
    }
}
