package org.techhouse.ops.filter;

import java.util.function.BiPredicate;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonArray;
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
                        JsonUtils.isPrimaryKeyPath(fieldName));
            }
            if (operatorElement.isJsonArray()) {
                return arrayOperandMatches(operatorElement, toTestElement, operation,
                        JsonUtils.isPrimaryKeyPath(fieldName));
            }
            if (operatorElement.isJsonObject()) {
                return objectOperandMatches(operatorElement, toTestElement, operation,
                        JsonUtils.isPrimaryKeyPath(fieldName));
            }
            return false;
        };
    }

    private static boolean primitiveOperandMatches(JsonBaseElement operatorElement, JsonBaseElement toTestElement,
            FieldOperatorType operation, boolean exactStrings) {
        if (operation == FieldOperatorType.CONTAINS && toTestElement.isJsonArray()) {
            return holdsMatchingElement(toTestElement.asJsonArray(), operatorElement);
        }
        if (!toTestElement.isJsonPrimitive()) {
            return kindMismatchMatches(operation, exactStrings);
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
        return kindMismatchMatches(operation, exactStrings);
    }

    private static boolean kindMismatchMatches(FieldOperatorType operation, boolean exactStrings) {
        return !exactStrings && operation == FieldOperatorType.NOT_EQUALS;
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

    private static boolean arrayOperandMatches(JsonBaseElement operatorElement, JsonBaseElement toTestElement,
            FieldOperatorType operation, boolean exactStrings) {
        if (operation == FieldOperatorType.EQUALS || operation == FieldOperatorType.NOT_EQUALS) {
            if (toTestElement == null || !toTestElement.isJsonArray()) {
                return kindMismatchMatches(operation, exactStrings);
            }
            final var equal = arraysMatch(operatorElement.asJsonArray(), toTestElement.asJsonArray());
            return (operation == FieldOperatorType.EQUALS) == equal;
        }
        if (operation == FieldOperatorType.IN || operation == FieldOperatorType.NOT_IN) {
            if (toTestElement == null || toTestElement.isJsonNull()) {
                return operation == FieldOperatorType.NOT_IN;
            }
            return (operation == FieldOperatorType.IN) == containsEquivalent(operatorElement.asJsonArray(),
                    toTestElement, exactStrings);
        }
        return false;
    }

    private static boolean containsEquivalent(JsonArray operands, JsonBaseElement stored, boolean exactStrings) {
        for (final var operand : operands) {
            if (elementsMatch(operand, stored, exactStrings)) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsMatchingElement(JsonArray stored, JsonBaseElement operand) {
        for (final var candidate : stored) {
            if (elementsMatch(operand, candidate, true)) {
                return true;
            }
        }
        return false;
    }

    private static boolean elementsMatch(JsonBaseElement operand, JsonBaseElement stored, boolean exactStrings) {
        if (operand.isJsonPrimitive() && stored.isJsonPrimitive()) {
            return primitiveOperandMatches(operand, stored, FieldOperatorType.EQUALS, exactStrings);
        }
        if (operand.isJsonArray() && stored.isJsonArray()) {
            return arraysMatch(operand.asJsonArray(), stored.asJsonArray());
        }
        if (operand.isJsonObject() && stored.isJsonObject()) {
            return objectsMatch(operand.asJsonObject(), stored.asJsonObject());
        }
        return operand.equals(stored);
    }

    private static boolean arraysMatch(JsonArray operand, JsonArray stored) {
        if (operand.size() != stored.size()) {
            return false;
        }
        for (var i = 0; i < operand.size(); i++) {
            if (!elementsMatch(operand.get(i), stored.get(i), true)) {
                return false;
            }
        }
        return true;
    }

    private static boolean objectsMatch(JsonObject operand, JsonObject stored) {
        if (operand.size() != stored.size()) {
            return false;
        }
        for (final var member : operand.entrySet()) {
            if (!stored.has(member.getKey()) || !elementsMatch(member.getValue(), stored.get(member.getKey()), true)) {
                return false;
            }
        }
        return true;
    }

    private static boolean objectOperandMatches(JsonBaseElement operatorElement, JsonBaseElement toTestElement,
            FieldOperatorType operation, boolean exactStrings) {
        if (operation == FieldOperatorType.EQUALS || operation == FieldOperatorType.NOT_EQUALS) {
            if (toTestElement == null || !toTestElement.isJsonObject()) {
                return kindMismatchMatches(operation, exactStrings);
            }
            final var equal = objectsMatch(operatorElement.asJsonObject(), toTestElement.asJsonObject());
            return (operation == FieldOperatorType.EQUALS) == equal;
        }
        return false;
    }
}
