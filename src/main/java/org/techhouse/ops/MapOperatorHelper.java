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
import org.techhouse.utils.FieldPath;
import org.techhouse.utils.FieldPathMemo;
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
                conditionOf(operator, context, writtenRoots), writtenRoots, new FieldPathMemo());
    }

    static Predicate<JsonObject> conditionOf(MapOperator operator, PipelineContext context, Set<String> writtenRoots) {
        final var condition = operator.getCondition();
        return condition == null ? null : OperatorPredicate.of(condition, context, writtenRoots);
    }

    static JsonObject processOperator(MapOperator operator, JsonObject toMap, PipelineContext context,
            MapResultBudget budget, Predicate<JsonObject> condition, Set<String> writtenRoots, FieldPathMemo paths) {
        if (condition != null && !condition.test(toMap)) {
            return toMap;
        }
        return switch (operator.getType()) {
            case ADD_FIELD -> addField((AddFieldMapOperator) operator, toMap, context, budget, writtenRoots, paths);
            case REMOVE_FIELD -> removeField((RemoveFieldMapOperator) operator, toMap, paths);
        };
    }

    private static JsonObject addField(AddFieldMapOperator operator, JsonObject toMap, PipelineContext context,
            MapResultBudget budget, Set<String> writtenRoots, FieldPathMemo paths) {
        final var target = paths.of(operator.getFieldName());
        final var midOperator = operator.getOperator();
        return switch (midOperator.getType()) {
            case AVG -> avg((ArrayParamMidOperator) midOperator, target, toMap, paths);
            case SUM -> sum((ArrayParamMidOperator) midOperator, target, toMap, paths);
            case SUBS -> subs((ArrayParamMidOperator) midOperator, target, toMap, paths);
            case MAX -> max((ArrayParamMidOperator) midOperator, target, toMap, paths);
            case MIN -> min((ArrayParamMidOperator) midOperator, target, toMap, paths);
            case MULTIPLY -> multiply((ArrayParamMidOperator) midOperator, target, toMap, paths);
            case DIVIDE -> divide((ArrayParamMidOperator) midOperator, target, toMap, paths);
            case POW -> pow((ArrayParamMidOperator) midOperator, target, toMap, paths);
            case ROOT -> root((ArrayParamMidOperator) midOperator, target, toMap, paths);
            case ABS -> abs((OneParamMidOperator) midOperator, target, toMap, paths);
            case SIZE -> size((OneParamMidOperator) midOperator, target, toMap, paths);
            case CONCAT -> concat((ArrayParamMidOperator) midOperator, target, toMap, paths, budget);
            case CAST -> cast((CastMidOperator) midOperator, target, toMap, paths, budget);
            case SCRIPT -> script((ScriptMidOperator) midOperator, target, toMap, context, budget, writtenRoots);
        };
    }

    private static JsonObject script(ScriptMidOperator midOperator, FieldPath target, JsonObject toMap,
            PipelineContext context, MapResultBudget budget, Set<String> writtenRoots) {
        final var value = FilterOperatorHelper.callScript(midOperator.getSource(), toMap, writtenRoots, context);
        if (value != null) {
            context.guardResultValue(EJsonInterop.estimatedBytes(value));
            budget.charge(JsonElementWriter.utf8Length(value));
            target.set(toMap, value);
        }
        return toMap;
    }

    private static JsonObject removeField(RemoveFieldMapOperator operator, JsonObject toMap, FieldPathMemo paths) {
        paths.of(operator.getFieldName()).remove(toMap);
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

    private static JsonObject internalGenericArrayOperator(ArrayParamMidOperator midOperator, FieldPath target,
            JsonObject obj, FieldPathMemo paths, DoubleBinaryOperator fold) {
        var accumulator = Accumulator.EMPTY;
        for (final var operand : midOperator.getOperands()) {
            final var operandValue = numericOperand(operand, obj, paths);
            if (operandValue == null) {
                continue;
            }
            accumulator = accumulator.isEmpty()
                    ? new Accumulator(operandValue)
                    : new Accumulator(fold.applyAsDouble(accumulator.value(), operandValue));
        }
        target.set(obj, numberOrNull(finiteOrNull(accumulator.value())));
        return obj;
    }

    private static Double numericOperand(JsonBaseElement operand, JsonObject obj, FieldPathMemo paths) {
        if (!operand.isJsonPrimitive()) {
            return null;
        }
        if (operand.isJsonNumber()) {
            return operand.asJsonNumber().doubleValue();
        }
        if (!operand.isJsonString()) {
            return null;
        }
        final var resolved = paths.of(operand.asJsonString().getValue()).valueIn(obj);
        return !resolved.isJsonNull() && resolved.isJsonNumber() ? resolved.asJsonNumber().doubleValue() : null;
    }

    private static Double finiteOrNull(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static JsonObject multiply(ArrayParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        return internalGenericArrayOperator(midOperator, target, obj, paths, (left, right) -> left * right);
    }

    private static JsonObject divide(ArrayParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        return internalGenericArrayOperator(midOperator, target, obj, paths, (left, right) -> left / right);
    }

    private static JsonObject pow(ArrayParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        return internalGenericArrayOperator(midOperator, target, obj, paths, Math::pow);
    }

    private static JsonObject root(ArrayParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        return internalGenericArrayOperator(midOperator, target, obj, paths,
                (left, right) -> Math.pow(left, 1 / right));
    }

    private static JsonObject sum(ArrayParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        return internalGenericArrayOperator(midOperator, target, obj, paths, Double::sum);
    }

    private static JsonObject subs(ArrayParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        return internalGenericArrayOperator(midOperator, target, obj, paths, (left, right) -> left - right);
    }

    private static JsonObject avg(ArrayParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        var validSteps = 0;
        var total = 0d;
        for (final var operand : midOperator.getOperands()) {
            final var operandValue = numericOperand(operand, obj, paths);
            if (operandValue != null) {
                total += operandValue;
                validSteps++;
            }
        }
        final Double average = validSteps == 0 ? null : total / validSteps;
        target.set(obj, numberOrNull(finiteOrNull(average)));
        return obj;
    }

    private static JsonObject max(ArrayParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        return internalGenericArrayOperator(midOperator, target, obj, paths, Math::max);
    }

    private static JsonObject min(ArrayParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        return internalGenericArrayOperator(midOperator, target, obj, paths, Math::min);
    }

    private static JsonObject abs(OneParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        final var operand = midOperator.getOperand();
        final var element = paths.of(operand).valueIn(obj);
        Double absValue = null;
        if (element.isJsonNumber()) {
            final var doubleValue = element.asJsonNumber().doubleValue();
            absValue = Math.abs(doubleValue);
        }
        target.set(obj, numberOrNull(absValue));
        return obj;
    }

    private static JsonObject size(OneParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths) {
        final var operand = midOperator.getOperand();
        final var element = paths.of(operand).valueIn(obj);
        Integer size = null;
        if (element.isJsonString()) {
            final var stringValue = element.asJsonString().getValue();
            size = stringValue.length();
        } else if (element.isJsonArray()) {
            final var arrayValue = element.asJsonArray();
            size = arrayValue.size();
        }
        target.set(obj, numberOrNull(size));
        return obj;
    }

    private static JsonObject concat(ArrayParamMidOperator midOperator, FieldPath target, JsonObject obj,
            FieldPathMemo paths, MapResultBudget budget) {
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
                        result.append(concatTextOf(paths.of(primitiveString).valueIn(obj)));
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
        target.set(obj, new JsonString(result.toString()));
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

    private static JsonObject cast(CastMidOperator midOperator, FieldPath target, JsonObject obj, FieldPathMemo paths,
            MapResultBudget budget) {
        final var fieldName = midOperator.getFieldName();
        final var type = midOperator.getToType();
        final var field = paths.of(fieldName).valueIn(obj);
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
        target.set(obj, casted);
        return obj;
    }
}
