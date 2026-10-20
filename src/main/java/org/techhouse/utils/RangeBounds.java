package org.techhouse.utils;

import java.util.List;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;

public record RangeBounds(String fieldName, List<Bound> bounds) {
    public record Bound(Number value, FieldOperatorType operatorType) {
        boolean isLower() {
            return operatorType == FieldOperatorType.GREATER_THAN
                    || operatorType == FieldOperatorType.GREATER_THAN_EQUALS;
        }
    }

    public RangeBounds {
        bounds = List.copyOf(bounds);
    }

    public static boolean isNumericRange(FieldOperator operator) {
        return switch (operator.getFieldOperatorType()) {
            case GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS ->
                operator.getValue() instanceof JsonNumber;
            default -> false;
        };
    }

    public static RangeBounds fromLeaves(String fieldName, List<FieldOperator> leaves) {
        return new RangeBounds(fieldName,
                leaves.stream()
                        .map(leaf -> new Bound(((JsonNumber) leaf.getValue()).getValue(), leaf.getFieldOperatorType()))
                        .toList());
    }
}
