package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.RangeBounds;

public class RangeBoundsTest {
    private static FieldOperator leaf(FieldOperatorType type, double value) {
        return new FieldOperator(type, "price", new JsonNumber(value));
    }

    @Test
    public void test_only_numeric_range_operators_are_ranges() {
        assertTrue(RangeBounds.isNumericRange(leaf(FieldOperatorType.GREATER_THAN, 1)));
        assertTrue(RangeBounds.isNumericRange(leaf(FieldOperatorType.SMALLER_THAN_EQUALS, 1)));
        assertFalse(RangeBounds.isNumericRange(leaf(FieldOperatorType.EQUALS, 1)));
        assertFalse(RangeBounds
                .isNumericRange(new FieldOperator(FieldOperatorType.GREATER_THAN, "price", new JsonString("a"))));
    }

    @Test
    public void test_every_leaf_becomes_a_bound_in_order() {
        final var bounds = RangeBounds.fromLeaves("price",
                List.of(leaf(FieldOperatorType.GREATER_THAN_EQUALS, 1), leaf(FieldOperatorType.SMALLER_THAN, 9)));

        assertEquals("price", bounds.fieldName());
        assertEquals(List.of(FieldOperatorType.GREATER_THAN_EQUALS, FieldOperatorType.SMALLER_THAN),
                bounds.bounds().stream().map(RangeBounds.Bound::operatorType).toList());
        assertEquals(9.0, bounds.bounds().get(1).value().doubleValue());
    }
}
