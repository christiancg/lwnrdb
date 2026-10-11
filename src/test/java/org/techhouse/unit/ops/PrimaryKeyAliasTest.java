package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.filter.FieldPredicateFactory;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.validations.AggregationStepValidator;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.test.TestGlobals;
import org.techhouse.utils.JsonUtils;

public class PrimaryKeyAliasTest {

    private static JsonObject documentWithId(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        return object;
    }

    private static boolean matches(String field, FieldOperatorType operation, String operand, String stored) {
        final var operator = new FieldOperator(operation, field, new JsonString(operand));
        return FieldPredicateFactory.getTester(operator, operation).test(documentWithId(stored), field);
    }

    @Test
    public void test_trailing_dots_do_not_hide_the_primary_key() {
        assertTrue(JsonUtils.isPrimaryKeyPath("_id"));
        assertTrue(JsonUtils.isPrimaryKeyPath("_id."));
        assertTrue(JsonUtils.isPrimaryKeyPath("_id.."));
        assertFalse(JsonUtils.isPrimaryKeyPath("_id.x"));
        assertFalse(JsonUtils.isPrimaryKeyPath("x._id"));
        assertFalse(JsonUtils.isPrimaryKeyPath("_ID"));
        assertFalse(JsonUtils.isPrimaryKeyPath(""));
        assertFalse(JsonUtils.isPrimaryKeyPath(null));
    }

    @Test
    public void test_stripping_trailing_dots_keeps_interior_dots() {
        assertEquals("a.b", JsonUtils.stripTrailingDots("a.b.."));
        assertEquals("", JsonUtils.stripTrailingDots("..."));
    }

    @Test
    public void test_the_alias_compares_the_primary_key_exactly() {
        for (final var field : new String[]{"_id", "_id.", "_id.."}) {
            assertFalse(matches(field, FieldOperatorType.EQUALS, "abc", "ABC"), field);
            assertTrue(matches(field, FieldOperatorType.EQUALS, "abc", "abc"), field);
            assertTrue(matches(field, FieldOperatorType.NOT_EQUALS, "abc", "ABC"), field);
            assertFalse(matches(field, FieldOperatorType.CONTAINS, "ab", "ABC"), field);
        }
    }

    @Test
    public void test_a_filter_on_a_trailing_dot_field_is_refused() {
        final var operator = new FieldOperator(FieldOperatorType.EQUALS, "_id.", new JsonString("abc"));
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(operator)).isValid());
        final var other = new FieldOperator(FieldOperatorType.EQUALS, "name.", new JsonString("abc"));
        assertFalse(AggregationStepValidator.validate(new FilterAggregationStep(other)).isValid());
    }

    @Test
    public void test_a_filter_on_the_plain_and_dotted_paths_is_still_accepted() {
        final var plain = new FieldOperator(FieldOperatorType.EQUALS, "_id", new JsonString("abc"));
        final var nested = new FieldOperator(FieldOperatorType.EQUALS, "a.b", new JsonString("abc"));
        assertTrue(AggregationStepValidator.validate(new FilterAggregationStep(plain)).isValid());
        assertTrue(AggregationStepValidator.validate(new FilterAggregationStep(nested)).isValid());
    }

    @Test
    public void test_an_index_on_the_primary_key_alias_is_refused() {
        for (final var field : new String[]{"_id", "_id.", "_id.."}) {
            assertFalse(RequestValidator.validate(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, field))
                    .isValid(), field);
        }
        assertTrue(
                RequestValidator.validate(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "_id.x")).isValid());
    }
}
