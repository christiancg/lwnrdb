package org.techhouse.ops;

import java.util.Collections;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Predicate;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.JsonElementWriter;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.ScriptMidOperator;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.utils.JsonUtils;

public final class MapOperatorHelper {
    private MapOperatorHelper() {
    }

    public static JsonObject processOperator(MapOperator operator, JsonObject toMap) {
        return processOperator(operator, toMap, null);
    }

    public static JsonObject processOperator(MapOperator operator, JsonObject toMap, PipelineContext context) {
        final var writtenRoots = JsonUtils.writeTreeOf(Collections.singletonList(operator.getFieldName())).roots();
        return processOperator(operator, toMap, context, new MapResultBudget(context),
                conditionOf(operator, context, writtenRoots), writtenRoots);
    }

    static Predicate<JsonObject> conditionOf(MapOperator operator, PipelineContext context, Set<String> writtenRoots) {
        final var condition = operator.getCondition();
        return condition == null ? null : OperatorPredicate.of(condition, context, writtenRoots);
    }

    static JsonObject processOperator(MapOperator operator, JsonObject toMap, PipelineContext context,
            MapResultBudget budget, Predicate<JsonObject> condition, Set<String> writtenRoots) {
        if (condition != null && !condition.test(toMap)) {
            return toMap;
        }
        return switch (operator.getType()) {
            case ADD_FIELD -> addField((AddFieldMapOperator) operator, toMap, context, budget, writtenRoots);
            case REMOVE_FIELD -> removeField((RemoveFieldMapOperator) operator, toMap);
        };
    }

    private static JsonObject addField(AddFieldMapOperator operator, JsonObject toMap, PipelineContext context,
            MapResultBudget budget, Set<String> writtenRoots) {
        final var addFieldName = operator.getFieldName();
        final var midOperator = operator.getOperator();
        return switch (midOperator.getType()) {
            case AVG -> avg((ArrayParamMidOperator) midOperator, addFieldName, toMap);
            case SUM -> sum((ArrayParamMidOperator) midOperator, addFieldName, toMap);
            case SUBS -> subs((ArrayParamMidOperator) midOperator, addFieldName, toMap);
            case MAX -> max((ArrayParamMidOperator) midOperator, addFieldName, toMap);
            case MIN -> min((ArrayParamMidOperator) midOperator, addFieldName, toMap);
            case MULTIPLY -> multiply((ArrayParamMidOperator) midOperator, addFieldName, toMap);
            case DIVIDE -> divide((ArrayParamMidOperator) midOperator, addFieldName, toMap);
            case POW -> pow((ArrayParamMidOperator) midOperator, addFieldName, toMap);
            case ROOT -> root((ArrayParamMidOperator) midOperator, addFieldName, toMap);
            case ABS -> abs((OneParamMidOperator) midOperator, addFieldName, toMap);
            case SIZE -> size((OneParamMidOperator) midOperator, addFieldName, toMap);
            case CONCAT -> concat((ArrayParamMidOperator) midOperator, addFieldName, toMap, budget);
            case CAST -> cast((CastMidOperator) midOperator, addFieldName, toMap, budget);
            case SCRIPT -> script((ScriptMidOperator) midOperator, addFieldName, toMap, context, budget, writtenRoots);
        };
    }

    private static JsonObject script(ScriptMidOperator midOperator, String addFieldName, JsonObject toMap,
            PipelineContext context, MapResultBudget budget, Set<String> writtenRoots) {
        final var value = FilterOperatorHelper.callScript(midOperator.getSource(), toMap, writtenRoots, context);
        if (value != null) {
            context.guardResultValue(EJsonInterop.estimatedBytes(value));
            budget.charge(JsonElementWriter.utf8Length(value));
            JsonUtils.setPath(toMap, addFieldName, value);
        }
        return toMap;
    }

    private static JsonObject removeField(RemoveFieldMapOperator operator, JsonObject toMap) {
        final var fieldName = operator.getFieldName();
        JsonUtils.removePath(toMap, fieldName);
        return toMap;
    }

    private static JsonBaseElement numberOrNull(Number value) {
        return value == null ? JsonNull.INSTANCE : new JsonNumber(value);
    }

    private record Accumulator(Double value) {
        static final Accumulator EMPTY = new Accumulator(null);

        boolean isEmpty() {
            return value == null;
        }
    }

    private static JsonObject internalGenericArrayOperator(ArrayParamMidOperator midOperator, String addFieldName,
            JsonObject obj, DoubleBinaryOperator fold) {
        var accumulator = Accumulator.EMPTY;
        for (final var operand : midOperator.getOperands()) {
            final var operandValue = numericOperand(operand, obj);
            if (operandValue == null) {
                continue;
            }
            accumulator = accumulator.isEmpty()
                    ? new Accumulator(operandValue)
                    : new Accumulator(fold.applyAsDouble(accumulator.value(), operandValue));
        }
        JsonUtils.setPath(obj, addFieldName, numberOrNull(finiteOrNull(accumulator.value())));
        return obj;
    }

    private static Double numericOperand(JsonBaseElement operand, JsonObject obj) {
        if (!operand.isJsonPrimitive()) {
            return null;
        }
        if (operand.isJsonNumber()) {
            return operand.asJsonNumber().doubleValue();
        }
        if (!operand.isJsonString()) {
            return null;
        }
        final var resolved = JsonUtils.getFromPath(obj, operand.asJsonString().getValue());
        return !resolved.isJsonNull() && resolved.isJsonNumber() ? resolved.asJsonNumber().doubleValue() : null;
    }

    private static Double finiteOrNull(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static JsonObject multiply(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        return internalGenericArrayOperator(midOperator, addFieldName, obj, (left, right) -> left * right);
    }

    private static JsonObject divide(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        return internalGenericArrayOperator(midOperator, addFieldName, obj, (left, right) -> left / right);
    }

    private static JsonObject pow(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        return internalGenericArrayOperator(midOperator, addFieldName, obj, Math::pow);
    }

    private static JsonObject root(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        return internalGenericArrayOperator(midOperator, addFieldName, obj, (left, right) -> Math.pow(left, 1 / right));
    }

    private static JsonObject sum(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        return internalGenericArrayOperator(midOperator, addFieldName, obj, Double::sum);
    }

    private static JsonObject subs(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        return internalGenericArrayOperator(midOperator, addFieldName, obj, (left, right) -> left - right);
    }

    private static JsonObject avg(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        var validSteps = 0;
        var total = 0d;
        for (final var operand : midOperator.getOperands()) {
            final var operandValue = numericOperand(operand, obj);
            if (operandValue != null) {
                total += operandValue;
                validSteps++;
            }
        }
        final Double average = validSteps == 0 ? null : total / validSteps;
        JsonUtils.setPath(obj, addFieldName, numberOrNull(finiteOrNull(average)));
        return obj;
    }

    private static JsonObject max(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        return internalGenericArrayOperator(midOperator, addFieldName, obj, Math::max);
    }

    private static JsonObject min(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        return internalGenericArrayOperator(midOperator, addFieldName, obj, Math::min);
    }

    private static JsonObject abs(OneParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        final var operand = midOperator.getOperand();
        final var element = JsonUtils.getFromPath(obj, operand);
        Double absValue = null;
        if (element.isJsonNumber()) {
            final var doubleValue = element.asJsonNumber().doubleValue();
            absValue = Math.abs(doubleValue);
        }
        JsonUtils.setPath(obj, addFieldName, numberOrNull(absValue));
        return obj;
    }

    private static JsonObject size(OneParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        final var operand = midOperator.getOperand();
        final var element = JsonUtils.getFromPath(obj, operand);
        Integer size = null;
        if (element.isJsonString()) {
            final var stringValue = element.asJsonString().getValue();
            size = stringValue.length();
        } else if (element.isJsonArray()) {
            final var arrayValue = element.asJsonArray();
            size = arrayValue.size();
        }
        JsonUtils.setPath(obj, addFieldName, numberOrNull(size));
        return obj;
    }

    private static JsonObject concat(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj,
            MapResultBudget budget) {
        final var operands = midOperator.getOperands();
        StringBuilder result = new StringBuilder();
        for (var concatStep : operands) {
            if (concatStep.isJsonPrimitive()) {
                final var primitive = concatStep.asJsonPrimitive();
                if (primitive.isJsonString()) {
                    final var primitiveString = primitive.asJsonString().getValue();
                    if (primitiveString.startsWith(Globals.STRING_LITERAL_PREFIX)) {
                        result.append(primitiveString.substring(Globals.STRING_LITERAL_PREFIX.length()));
                    } else {
                        result.append(concatTextOf(JsonUtils.getFromPath(obj, primitiveString)));
                    }
                } else {
                    result.append(concatTextOf(concatStep));
                }
            } else if (concatStep.isJsonArray()) {
                for (var arrayElement : concatStep.asJsonArray()) {
                    if (arrayElement.isJsonPrimitive() || arrayElement.isJsonNull()) {
                        result.append(concatTextOf(arrayElement));
                    }
                }
            } else if (concatStep.isJsonNull()) {
                result.append(concatTextOf(concatStep));
            }
            if (EntrySizeGuard.exceedsMaxEntrySize(result)) {
                throw new AggregateLimitException(ErrorCode.MAP_RESULT_TOO_LARGE);
            }
        }
        budget.charge(result);
        JsonUtils.setPath(obj, addFieldName, new JsonString(result.toString()));
        return obj;
    }

    private static String concatTextOf(JsonBaseElement element) {
        if (element instanceof JsonCustom<?> custom) {
            return custom.stringDataValue();
        }
        if (element.isJsonString()) {
            return element.asJsonString().getValue();
        }
        return TypeAdapterFactory.getAdapter(JsonBaseElement.class).toJson(element);
    }

    private static JsonBaseElement numberFromString(String text) {
        if (!isJsonNumberText(text)) {
            return JsonNull.INSTANCE;
        }
        try {
            return Double.isFinite(Double.parseDouble(text)) ? new JsonNumber(text) : JsonNull.INSTANCE;
        } catch (NumberFormatException ignored) {
            return JsonNull.INSTANCE;
        }
    }

    private static boolean isJsonNumberText(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (var i = 0; i < text.length(); i++) {
            final var current = text.charAt(i);
            final var digitOrSeparator = (current >= '0' && current <= '9') || current == '-' || current == '.'
                    || current == 'e' || current == 'E';
            final var exponentSign = current == '+' && i > 0 && isExponentMarker(text.charAt(i - 1));
            if (!digitOrSeparator && !exponentSign) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExponentMarker(char c) {
        return c == 'e' || c == 'E';
    }

    private static JsonBaseElement booleanFromString(String text) {
        if ("true".equalsIgnoreCase(text)) {
            return JsonBoolean.of(true);
        }
        return "false".equalsIgnoreCase(text) ? JsonBoolean.of(false) : JsonNull.INSTANCE;
    }

    private static JsonObject cast(CastMidOperator midOperator, String addFieldName, JsonObject obj,
            MapResultBudget budget) {
        final var fieldName = midOperator.getFieldName();
        final var type = midOperator.getToType();
        final var field = JsonUtils.getFromPath(obj, fieldName);
        JsonBaseElement casted = JsonNull.INSTANCE;
        if (!field.isJsonNull() && field.isJsonPrimitive()) {
            final var primitive = field.asJsonPrimitive();
            casted = switch (type) {
                case NUMBER -> {
                    if (primitive.isJsonNumber()) {
                        yield Double.isFinite(primitive.asJsonNumber().doubleValue()) ? primitive : JsonNull.INSTANCE;
                    }
                    yield primitive.isJsonString()
                            ? numberFromString(primitive.asJsonString().getValue())
                            : JsonNull.INSTANCE;
                }
                case STRING -> {
                    if (primitive instanceof JsonCustom<?> custom) {
                        yield new JsonString(custom.stringDataValue());
                    } else if (primitive.isJsonString()) {
                        yield primitive;
                    } else if (primitive.isJsonNumber()) {
                        yield new JsonString(NumberFormatter.toJsString(primitive.asJsonNumber().doubleValue()));
                    } else if (primitive.isJsonBoolean()) {
                        yield new JsonString(Boolean.toString(primitive.asJsonBoolean().getValue()));
                    }
                    yield JsonNull.INSTANCE;
                }
                case BOOLEAN -> {
                    if (primitive.isJsonBoolean()) {
                        yield primitive;
                    }
                    if (primitive.isJsonNumber()) {
                        yield JsonBoolean.of(primitive.asJsonNumber().doubleValue() != 0);
                    }
                    yield primitive.isJsonString()
                            ? booleanFromString(primitive.asJsonString().getValue())
                            : JsonNull.INSTANCE;
                }
                case JSON_CUSTOM -> {
                    final var customTypeName = midOperator.getCustomTypeName();
                    if (primitive instanceof JsonCustom<?> existing
                            && existing.getCustomTypeName().equals(customTypeName)) {
                        yield primitive;
                    } else if (primitive.isJsonString() && !(primitive instanceof JsonCustom<?>)) {
                        try {
                            final var wireFormat = "#" + customTypeName + "(" + primitive.asJsonString().getValue()
                                    + ")";
                            yield CustomTypeFactory.getCustomTypeInstance(wireFormat);
                        } catch (Exception ignored) {
                        }
                    }
                    yield JsonNull.INSTANCE;
                }
            };
        }
        if (casted instanceof JsonCustom<?> || casted.isJsonString()) {
            budget.charge(concatTextOf(casted));
        }
        JsonUtils.setPath(obj, addFieldName, casted);
        return obj;
    }
}
