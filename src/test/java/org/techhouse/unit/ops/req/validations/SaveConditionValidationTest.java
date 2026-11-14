package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.ops.req.validations.ValidationResult;
import org.techhouse.test.TestUtils;

public class SaveConditionValidationTest {
    private static final String BOTH_MESSAGE = "SAVE takes either an _id or a condition, not both";
    private final Configuration configuration = Configuration.getInstance();

    @AfterEach
    public void restore() throws Exception {
        TestUtils.setPrivateField(configuration, "maxFilterOperands", ConfigKey.MAX_FILTER_OPERANDS.defaultValue());
    }

    private static SaveRequest save(String id, JsonObject object, BaseOperator condition) {
        final var request = new SaveRequest("testDb", "testColl");
        request.set_id(id);
        request.setObject(object);
        request.setCondition(condition);
        return request;
    }

    private static JsonObject patch(String field, String value) {
        final var object = new JsonObject();
        object.add(field, new JsonString(value));
        return object;
    }

    private static FieldOperator statusIsPending() {
        return new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("pending"));
    }

    private static void assertRefused(ValidationResult result, String message) {
        assertFalse(result.isValid());
        assertEquals(ErrorCode.VALIDATION_ERROR, result.getErrorCode());
        assertEquals(message, result.getErrorMessage());
    }

    @Test
    public void test_a_condition_with_a_patch_passes() {
        assertTrue(RequestValidator.validate(save(null, patch("status", "expired"), statusIsPending())).isValid());
    }

    @Test
    public void test_a_request_id_beside_a_condition_is_400_1() {
        assertRefused(RequestValidator.validate(save("o-1", patch("status", "x"), statusIsPending())), BOTH_MESSAGE);
    }

    @Test
    public void test_an_id_inside_the_patch_beside_a_condition_is_400_1() {
        final var object = patch("status", "x");
        object.add(Globals.PK_FIELD, new JsonNumber(5));

        assertRefused(RequestValidator.validate(save(null, object, statusIsPending())), BOTH_MESSAGE);
    }

    @Test
    public void test_an_empty_patch_beside_a_condition_is_400_1() {
        assertRefused(RequestValidator.validate(save(null, new JsonObject(), statusIsPending())),
                "SAVE with a condition requires at least one field to set");
    }

    @Test
    public void test_a_missing_object_is_still_refused() {
        assertRefused(RequestValidator.validate(save(null, null, statusIsPending())),
                "SAVE request requires an object");
    }

    @Test
    public void test_a_patch_nested_too_deep_is_refused() {
        var nested = new JsonObject();
        for (var depth = 0; depth <= Globals.MAX_REQUEST_NESTING_DEPTH; depth++) {
            final var parent = new JsonObject();
            parent.add("n", nested);
            nested = parent;
        }

        assertFalse(RequestValidator.validate(save(null, nested, statusIsPending())).isValid());
    }

    @Test
    public void test_a_condition_with_an_operator_missing_its_field_is_400_1() {
        final var result = RequestValidator.validate(
                save(null, patch("a", "b"), new FieldOperator(FieldOperatorType.EQUALS, null, new JsonNumber(1))));

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
                .validate(save(null, patch("a", "b"), new FieldOperator(FieldOperatorType.IN, "n", operands)));

        assertEquals(ErrorCode.FILTER_OPERAND_LIMIT, result.getErrorCode());
    }

    @Test
    public void test_a_plain_save_is_unchanged() {
        assertTrue(RequestValidator.validate(save("o-1", patch("a", "b"), null)).isValid());
        assertFalse(RequestValidator.validate(save("not an id!", patch("a", "b"), null)).isValid());
    }
}
