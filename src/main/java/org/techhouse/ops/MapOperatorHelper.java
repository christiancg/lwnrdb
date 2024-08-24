package org.techhouse.ops;

import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.*;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.CastMidOperator;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

public class MapOperatorHelper {
    public static JsonObject processOperator(MapOperator operator,
                                             JsonObject toMap) {
        final var condition = operator.getCondition();
        boolean continueProcessing = true;
        if (condition != null) {
            continueProcessing = filterCondition(condition, toMap);
        }
        return continueProcessing ? switch (operator.getType()) {
            case ADD_FIELD -> addField((AddFieldMapOperator) operator, toMap);
            case REMOVE_FIELD -> removeField((RemoveFieldMapOperator) operator, toMap);
        } : toMap;
    }

    private static boolean filterCondition(BaseOperator condition, JsonObject toMap) {
        return switch (condition.getType()) {
            case CONJUNCTION -> processConjunctionOperator((ConjunctionOperator) condition, toMap);
            case FIELD -> processFieldOperator((FieldOperator) condition, toMap);
        };
    }

    private static boolean processConjunctionOperator(ConjunctionOperator operator,
                                                      JsonObject toMap) {
        List<Boolean> combinationResult = new ArrayList<>();
        for (var step : operator.getOperators()) {
            boolean partialResults;
            if (step.getType() == OperatorType.CONJUNCTION) {
                partialResults = processConjunctionOperator((ConjunctionOperator) step, toMap);
            } else {
                partialResults = processFieldOperator((FieldOperator) step, toMap);
            }
            combinationResult.add(partialResults);
        }
        return switch (operator.getConjunctionType()) {
            case AND -> andConjunction(combinationResult);
            case OR -> orConjunction(combinationResult);
            case XOR -> xorConjunction(combinationResult);
            case NOR, NAND -> false; //Not supported
        };
    }

    private static Boolean xorConjunction(List<Boolean> combinationResult) {
        return combinationResult.stream().filter(aBoolean -> aBoolean).count() == 1;
    }

    private static Boolean andConjunction(List<Boolean> combinationResult) {
        return combinationResult.stream().filter(aBoolean -> aBoolean).count() == combinationResult.size();
    }

    private static Boolean orConjunction(List<Boolean> combinationResult) {
        return combinationResult.stream().filter(aBoolean -> aBoolean).findFirst().orElse(false);
    }

    private static boolean processFieldOperator(FieldOperator operator,
                                                JsonObject toMap) {

        final var tester = FilterOperatorHelper.getTester(operator, operator.getFieldOperatorType());
        return internalBaseFiltering(tester, operator, toMap);
    }

    private static boolean internalBaseFiltering(BiPredicate<JsonObject, String> test,
                                                 FieldOperator operator,
                                                 JsonObject toMap) {
        final var fieldName = operator.getField();
        return test.test(toMap, fieldName);
    }

    private static JsonObject addField(AddFieldMapOperator operator, JsonObject toMap) {
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
            case CONCAT -> concat((ArrayParamMidOperator) midOperator, addFieldName, toMap);
            case CAST -> cast((CastMidOperator) midOperator, addFieldName, toMap);
        };
    }

    private static JsonObject removeField(RemoveFieldMapOperator operator, JsonObject toMap) {
        final var fieldName = operator.getFieldName();
        toMap.remove(fieldName);
        return toMap;
    }

    private static JsonObject internalGenericArrayOperator(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj,
                                                           Integer startNumber,
                                                           BiFunction<Double, Double, Double> onNumber,
                                                           BiFunction<Double, Double, Double> onString) {
        final var operands = midOperator.getOperands();
        double result = startNumber;
        for (var maxStep : operands) {
            if (maxStep.isJsonPrimitive()) {
                if (maxStep.isJsonDouble()) {
                    final var primitiveAsDouble = maxStep.asJsonDouble().getValue();
                    result = onNumber.apply(result, primitiveAsDouble);
                } else if (maxStep.isJsonString()) {
                    final var fieldName = maxStep.asJsonString().getValue();
                    final var foundElement = JsonUtils.getFromPath(obj, fieldName);
                    if (!foundElement.isJsonNull() && foundElement.isJsonDouble()) {
                        final var foundPrimitiveAsDouble = foundElement.asJsonDouble().getValue();
                        if (onString != null) {
                            result = onString.apply(result, foundPrimitiveAsDouble);
                        } else {
                            result = onNumber.apply(result, foundPrimitiveAsDouble);
                        }
                    }
                }
            }
        }
        obj.addProperty(addFieldName, result);
        return obj;
    }

    private static JsonObject multiply(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        BiFunction<Double, Double, Double> onNumber = (Double result, Double number) -> result * number;
        BiFunction<Double, Double, Double> onString = (Double result, Double number) -> result == 0 ?
                result + number :
                result * number;
        return internalGenericArrayOperator(midOperator, addFieldName, obj, 0, onNumber, onString);
    }

    private static JsonObject divide(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        BiFunction<Double, Double, Double> onNumber = (Double result, Double number) -> result / number;
        BiFunction<Double, Double, Double> onString = (Double result, Double number) -> result == 0 ?
                result + number :
                result / number;
        return internalGenericArrayOperator(midOperator, addFieldName, obj,0, onNumber, onString);
    }

    private static JsonObject pow(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        BiFunction<Double, Double, Double> onNumber = Math::pow;
        BiFunction<Double, Double, Double> onString = (Double result, Double number) -> result == 0 ?
                result + number :
                Math.pow(result, number);
        return internalGenericArrayOperator(midOperator, addFieldName, obj, 0, onNumber, onString);
    }

    private static JsonObject root(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        BiFunction<Double, Double, Double> onNumber = (Double result, Double number) -> Math.pow(result, 1 / number);
        BiFunction<Double, Double, Double> onString = (Double result, Double number) -> result == 0 ?
                result + number :
                Math.pow(result, 1 / number);
        return internalGenericArrayOperator(midOperator, addFieldName, obj, 0, onNumber, onString);
    }

    private static JsonObject sum(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        BiFunction<Double, Double, Double> onNumber = Double::sum;
        BiFunction<Double, Double, Double> onString = Double::sum;
        return internalGenericArrayOperator(midOperator, addFieldName, obj, 0, onNumber, onString);
    }

    private static JsonObject subs(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        BiFunction<Double, Double, Double> onNumber = (Double result, Double number) -> result - number;
        BiFunction<Double, Double, Double> onString = (Double result, Double number) -> result == 0 ?
                result + number :
                result - number;
        return internalGenericArrayOperator(midOperator, addFieldName, obj, 0, onNumber, onString);
    }

    private static JsonObject avg(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        final var operands = midOperator.getOperands();
        int validSteps = 0;
        double result = 0;
        for (var avgStep : operands) {
            if (avgStep.isJsonPrimitive()) {
                if (avgStep.isJsonDouble()) {
                    result += avgStep.asJsonDouble().getValue();
                    validSteps++;
                } else if (avgStep.isJsonString()) {
                    final var fieldName = avgStep.asJsonString().getValue();
                    final var foundElement = JsonUtils.getFromPath(obj, fieldName);
                    if (!foundElement.isJsonNull() && foundElement.isJsonDouble()) {
                        result += foundElement.asJsonDouble().getValue();
                        validSteps++;
                    }
                }
            }
        }
        result /= validSteps;
        obj.addProperty(addFieldName, result);
        return obj;
    }

    private static JsonObject max(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        BiFunction<Double, Double, Double> onNumber = (Double result, Double number) -> number > result ? number : result;
        return internalGenericArrayOperator(midOperator, addFieldName, obj, Integer.MIN_VALUE, onNumber, null);
    }

    private static JsonObject min(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        BiFunction<Double, Double, Double> onNumber = (Double result, Double number) -> number < result ? number : result;
        return internalGenericArrayOperator(midOperator, addFieldName, obj, Integer.MAX_VALUE, onNumber, null);
    }

    private static JsonObject abs(OneParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        final var operand = midOperator.getOperand();
        final var element = JsonUtils.getFromPath(obj, operand);
        Double absValue = null;
        if (element.isJsonDouble()) {
            final var doubleValue = element.asJsonDouble().getValue();
            absValue = Math.abs(doubleValue);
        }
        obj.addProperty(addFieldName, absValue);
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
        obj.addProperty(addFieldName, size);
        return obj;
    }

    private static JsonObject concat(ArrayParamMidOperator midOperator, String addFieldName, JsonObject obj) {
        // TODO: implement concat for custom type here
        final var operands = midOperator.getOperands();
        StringBuilder result = new StringBuilder();
        for (var concatStep : operands) {
            if (concatStep.isJsonPrimitive()) {
                final var primitive = concatStep.asJsonPrimitive();
                if (primitive.isJsonString()) {
                    final var primitiveString = primitive.asJsonString().getValue();
                    var toAdd = "";
                    if (primitiveString.startsWith(Globals.STRING_LITERAL_PREFIX)) {
                        toAdd = primitiveString.replaceFirst("-", "");
                    } else {
                        final var fieldName = primitive.asJsonString().getValue();
                        final var element = JsonUtils.getFromPath(obj, fieldName);
                        if (element.isJsonString()) {
                            toAdd = element.asJsonString().getValue();
                        } else {
                            toAdd = TypeAdapterFactory.getAdapter(JsonBaseElement.class).toJson(element);
                        }
                    }
                    result.append(toAdd);
                } else {
                    result.append(TypeAdapterFactory.getAdapter(JsonBaseElement.class).toJson(concatStep));
                }
            } else if (concatStep.isJsonArray()) {
                for (var arrayElement : concatStep.asJsonArray()) {
                    if (arrayElement.isJsonPrimitive()) {
                        result.append(arrayElement);
                    }
                }
            } else if (concatStep.isJsonNull()) {
                result.append(concatStep);
            }
        }
        obj.addProperty(addFieldName, result.toString());
        return obj;
    }

    private static JsonObject cast(CastMidOperator midOperator, String addFieldName, JsonObject obj) {
        // TODO: implement cast to and from custom type here
        final var fieldName = midOperator.getFieldName();
        final var type = midOperator.getToType();
        final var field = JsonUtils.getFromPath(obj, fieldName);
        JsonBaseElement casted = JsonNull.INSTANCE;
        if (!field.isJsonNull() && field.isJsonPrimitive()) {
            final var primitive = field.asJsonPrimitive();
            casted = switch (type) {
                case NUMBER -> {
                    if (primitive.isJsonDouble()) {
                        yield primitive;
                    } else if (primitive.isJsonString()) {
                        try {
                            yield new JsonDouble(Double.parseDouble(primitive.asJsonString().getValue()));
                        } catch (Exception ignored) {
                        }
                    }
                    yield JsonNull.INSTANCE;
                }
                case STRING -> {
                    if (primitive.isJsonString()) {
                        yield primitive;
                    } else if (primitive.isJsonDouble()) {
                        final var value = primitive.asJsonDouble().getValue();
                        yield new JsonString(value % 1 == 0 ? Integer.toString(value.intValue()) : Double.toString(value));
                    } else if (primitive.isJsonBoolean()) {
                        yield new JsonString(Boolean.toString(primitive.asJsonBoolean().getValue()));
                    }
                    yield JsonNull.INSTANCE;
                }
                case BOOLEAN -> {
                    if (primitive.isJsonBoolean()) {
                        yield primitive;
                    } else if (primitive.isJsonString()) {
                        try {
                            yield new JsonBoolean(Boolean.parseBoolean(primitive.asJsonString().getValue()));
                        } catch (Exception ignored) {
                        }
                    } else if (primitive.isJsonDouble()) {
                        final var number = primitive.asJsonDouble().getValue();
                        yield new JsonBoolean(number != 0);
                    }
                    yield JsonNull.INSTANCE;
                }
            };
        }
        obj.add(addFieldName, casted);
        return obj;
    }
}
