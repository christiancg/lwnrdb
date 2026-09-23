package org.techhouse.ops.filter;

import java.util.function.BiPredicate;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.JsonUtils;

public final class FieldPredicateFactory {
    private FieldPredicateFactory() {
    }

    @SuppressWarnings("unchecked")
    private static Integer compareCustom(JsonCustom<?> operator, JsonCustom<?> toTestWith) {
        final var customClass = operator.getClass();
        return customClass.cast(operator).compare(customClass.cast(toTestWith).getCustomValue());
    }

    public static BiPredicate<JsonObject, String> getTester(FieldOperator operator, FieldOperatorType operation) {
        return (JsonObject toTest, String fieldName) -> {
            final var toTestElement = JsonUtils.resolvePath(toTest, fieldName);
            if (toTestElement == null) {
                return false;
            }
            final var operatorElement = operator.getValue();
            if (operatorElement.isJsonNull()) {
                return nullOperandMatches(toTestElement, operation);
            }
            if (operatorElement.isJsonPrimitive()) {
                return primitiveOperandMatches(operatorElement, toTestElement, operation,
                        Globals.PK_FIELD.equals(fieldName));
            }
            if (operatorElement.isJsonArray()) {
                return arrayOperandMatches(operatorElement, toTestElement, operation);
            }
            if (operatorElement.isJsonObject()) {
                return objectOperandMatches(operatorElement, toTestElement, operation);
            }
            return false;
        };
    }

    private static boolean primitiveOperandMatches(JsonBaseElement operatorElement, JsonBaseElement toTestElement,
            FieldOperatorType operation, boolean exactStrings) {
        if (!toTestElement.isJsonPrimitive()) {
            return operation == FieldOperatorType.CONTAINS && toTestElement.isJsonArray()
                    && toTestElement.asJsonArray().contains(operatorElement);
        }
        final var operatorPrimitive = operatorElement.asJsonPrimitive();
        final var toTestPrimitive = toTestElement.asJsonPrimitive();
        if (operatorPrimitive.isJsonBoolean() && toTestPrimitive.isJsonBoolean()) {
            return booleanMatches(operatorPrimitive.asJsonBoolean().getValue(),
                    toTestPrimitive.asJsonBoolean().getValue(), operation);
        }
        if (operatorPrimitive.isJsonNumber() && toTestPrimitive.isJsonNumber()) {
            return numberMatches(operatorElement.asJsonNumber().getValue(), toTestElement.asJsonNumber().getValue(),
                    operation);
        }
        if (operatorPrimitive.isJsonCustom() && toTestPrimitive.isJsonCustom()
                && operatorPrimitive.getClass().equals(toTestPrimitive.getClass())) {
            return customMatches(operatorPrimitive.asJsonCustom(), toTestPrimitive.asJsonCustom(), operation);
        }
        if (!operatorPrimitive.isJsonCustom() && !toTestPrimitive.isJsonCustom() && operatorPrimitive.isJsonString()
                && toTestPrimitive.isJsonString()) {
            return stringMatches(operatorElement.asJsonString().getValue(), toTestElement.asJsonString().getValue(),
                    operation, exactStrings);
        }
        return operatorPrimitive.isJsonNull() && toTestPrimitive.isJsonNull();
    }

    private static boolean nullOperandMatches(JsonBaseElement toTestElement, FieldOperatorType operation) {
        final var stored = toTestElement.isJsonNull();
        return switch (operation) {
            case EQUALS -> stored;
            case NOT_EQUALS -> !stored;
            case GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS, IN, NOT_IN, CONTAINS -> false;
        };
    }

    private static boolean booleanMatches(boolean operand, boolean stored, FieldOperatorType operation) {
        return switch (operation) {
            case EQUALS -> operand == stored;
            case NOT_EQUALS -> operand != stored;
            default -> false;
        };
    }

    private static boolean numberMatches(Number operand, Number stored, FieldOperatorType operation) {
        return switch (operation) {
            case EQUALS -> FieldIndexEntry.compareIndexedNumbers(operand, stored) == 0;
            case NOT_EQUALS -> FieldIndexEntry.compareIndexedNumbers(operand, stored) != 0;
            case GREATER_THAN -> operand.doubleValue() < stored.doubleValue();
            case GREATER_THAN_EQUALS -> operand.doubleValue() <= stored.doubleValue();
            case SMALLER_THAN -> operand.doubleValue() > stored.doubleValue();
            case SMALLER_THAN_EQUALS -> operand.doubleValue() >= stored.doubleValue();
            case IN, NOT_IN, CONTAINS -> false;
        };
    }

    private static boolean customMatches(JsonCustom<?> operand, JsonCustom<?> stored, FieldOperatorType operation) {
        return switch (operation) {
            case EQUALS -> compareCustom(operand, stored) == 0;
            case NOT_EQUALS -> compareCustom(operand, stored) != 0;
            case GREATER_THAN -> compareCustom(operand, stored) < 0;
            case GREATER_THAN_EQUALS -> compareCustom(operand, stored) <= 0;
            case SMALLER_THAN -> compareCustom(operand, stored) > 0;
            case SMALLER_THAN_EQUALS -> compareCustom(operand, stored) >= 0;
            case IN, NOT_IN, CONTAINS -> false;
        };
    }

    private static boolean stringMatches(String operand, String stored, FieldOperatorType operation,
            boolean exactStrings) {
        return switch (operation) {
            case EQUALS -> stringsAreEqual(operand, stored, exactStrings);
            case NOT_EQUALS -> !stringsAreEqual(operand, stored, exactStrings);
            case CONTAINS -> stored.contains(operand);
            case GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS, IN, NOT_IN -> false;
        };
    }

    private static boolean stringsAreEqual(String operand, String stored, boolean exactStrings) {
        return exactStrings ? operand.equals(stored) : operand.equalsIgnoreCase(stored);
    }

    // JsonArray.contains uses element equality, so IN/NOT_IN also matches object/array field values
    // against candidate objects/arrays, mirroring the index path's element-match resolution.
    private static boolean arrayOperandMatches(JsonBaseElement operatorElement, JsonBaseElement toTestElement,
            FieldOperatorType operation) {
        if (operation == FieldOperatorType.EQUALS || operation == FieldOperatorType.NOT_EQUALS) {
            if (toTestElement == null || !toTestElement.isJsonArray()) {
                return false;
            }
            final var equal = operatorElement.asJsonArray().equals(toTestElement.asJsonArray());
            return (operation == FieldOperatorType.EQUALS) == equal;
        }
        if ((operation == FieldOperatorType.IN || operation == FieldOperatorType.NOT_IN) && toTestElement != null
                && !toTestElement.isJsonNull()) {
            return (operation == FieldOperatorType.IN) == operatorElement.asJsonArray().contains(toTestElement);
        }
        return false;
    }

    private static boolean objectOperandMatches(JsonBaseElement operatorElement, JsonBaseElement toTestElement,
            FieldOperatorType operation) {
        if ((operation == FieldOperatorType.EQUALS || operation == FieldOperatorType.NOT_EQUALS)
                && toTestElement != null && toTestElement.isJsonObject()) {
            final var equal = operatorElement.asJsonObject().equals(toTestElement.asJsonObject());
            return (operation == FieldOperatorType.EQUALS) == equal;
        }
        return false;
    }
}
