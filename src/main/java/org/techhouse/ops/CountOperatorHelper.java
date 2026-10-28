package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.PendingWriteReconciler;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.validations.AggregationStepValidator;
import org.techhouse.utils.JsonUtils;

public final class CountOperatorHelper {
    private CountOperatorHelper() {
    }

    private static final String COUNT_FIELD_NAME = "count";
    private static final Cache cache = IocContainer.get(Cache.class);

    public record FastCount(JsonObject result, int nextStepIndex) {
    }

    public static Stream<JsonObject> processCountStep(Stream<JsonObject> resultStream, String dbName, String collName) {
        final var result = new JsonObject();
        if (resultStream != null) {
            try (var documents = resultStream) {
                result.addProperty(COUNT_FIELD_NAME, documents.mapToLong(_ -> 1L).sum());
            }
        } else {
            result.addProperty(COUNT_FIELD_NAME, wholeCollectionCount(dbName, collName));
        }
        return Stream.of(result);
    }

    public static FastCount tryIndexOnlyCount(List<BaseAggregationStep> steps, String dbName, String collName,
            PipelineContext context) throws IOException {
        final var countIndex = indexOfFirstCount(steps);
        if (countIndex < 1 || AggregationStepValidator.canFailWhileRunning(steps.subList(0, countIndex))) {
            return null;
        }
        final var rangeCount = countOfSingleRange(steps, countIndex, dbName, collName, context);
        if (rangeCount != null) {
            return rangeCount;
        }
        final var equalsCount = countOfSingleEquals(steps, countIndex, dbName, collName, context);
        if (equalsCount != null) {
            return equalsCount;
        }
        final var filterSets = new ArrayList<Set<String>>();
        var documentsModified = false;
        for (var i = 0; i < countIndex; i++) {
            final var step = steps.get(i);
            switch (step.getType()) {
                case FILTER -> {
                    if (documentsModified) {
                        return null;
                    }
                    final var operator = ((FilterAggregationStep) step).getOperator();
                    final var ids = FilterOperatorHelper.resolveIdsViaIndex(operator, dbName, collName, context);
                    if (ids == null) {
                        return null;
                    }
                    filterSets.add(ids);
                }
                case MAP, JOIN -> documentsModified = true;
                case SORT -> {
                }
                default -> {
                    return null;
                }
            }
        }
        final var count = filterSets.isEmpty() ? wholeCollectionCount(dbName, collName) : intersectionSize(filterSets);
        final var result = new JsonObject();
        result.addProperty(COUNT_FIELD_NAME, (long) count);
        return new FastCount(result, countIndex + 1);
    }

    private static FastCount countOfSingleRange(List<BaseAggregationStep> steps, int countIndex, String dbName,
            String collName, PipelineContext context) throws IOException {
        final var filters = stepsBesidesSorts(steps, countIndex);
        final var range = filters.size() == 1 ? SingleRange.of(filters.getFirst()) : null;
        if (range == null || cache.hasNoIndex(dbName, collName, range.bounds().fieldName())) {
            return null;
        }
        final var bounds = range.bounds();
        return countWhileNothingPending(dbName, collName, bounds.fieldName(), countIndex, context,
                () -> cache.countInRange(dbName, collName, bounds));
    }

    private static FastCount countOfSingleEquals(List<BaseAggregationStep> steps, int countIndex, String dbName,
            String collName, PipelineContext context) throws IOException {
        final var filters = stepsBesidesSorts(steps, countIndex);
        final var operator = filters.size() == 1 ? singleScalarEquals(filters.getFirst()) : null;
        if (operator == null || cache.hasNoIndex(dbName, collName, operator.getField())) {
            return null;
        }
        final var fieldName = operator.getField();
        final var value = scalarOperand(operator.getValue());
        return countWhileNothingPending(dbName, collName, fieldName, countIndex, context,
                () -> cache.countEquals(dbName, collName, fieldName, value));
    }

    private static List<BaseAggregationStep> stepsBesidesSorts(List<BaseAggregationStep> steps, int countIndex) {
        return steps.subList(0, countIndex).stream().filter(step -> step.getType() != AggregationStepType.SORT)
                .toList();
    }

    private static FieldOperator singleScalarEquals(BaseAggregationStep step) {
        if (step instanceof FilterAggregationStep filter && filter.getOperator() instanceof FieldOperator field
                && field.getFieldOperatorType() == FieldOperatorType.EQUALS
                && !JsonUtils.isPrimaryKeyPath(field.getField()) && scalarOperand(field.getValue()) != null) {
            return field;
        }
        return null;
    }

    static Object scalarOperand(JsonBaseElement operand) {
        return switch (operand) {
            case JsonBoolean bool -> bool.getValue();
            case JsonNumber number -> number.getValue();
            case JsonCustom<?> custom -> custom;
            case JsonString string -> string.getValue();
            case null, default -> null;
        };
    }

    @FunctionalInterface
    private interface IndexCount {
        OptionalLong read() throws IOException;
    }

    private static FastCount countWhileNothingPending(String dbName, String collName, String fieldName, int countIndex,
            PipelineContext context, IndexCount read) throws IOException {
        final var pendingBefore = PendingWriteReconciler.pendingIds(dbName, collName);
        if (!pendingBefore.isEmpty()) {
            return null;
        }
        final var count = read.read();
        if (count.isEmpty() || !PendingWriteReconciler.pendingIdsAround(pendingBefore, dbName, collName).isEmpty()) {
            return null;
        }
        context.indexUsed(fieldName, AnalyzeContext.fieldLockId(dbName, collName, fieldName));
        final var result = new JsonObject();
        result.addProperty(COUNT_FIELD_NAME, count.getAsLong());
        return new FastCount(result, countIndex + 1);
    }

    private static int indexOfFirstCount(List<BaseAggregationStep> steps) {
        for (var i = 0; i < steps.size(); i++) {
            if (steps.get(i).getType() == AggregationStepType.COUNT) {
                return i;
            }
        }
        return -1;
    }

    private static int intersectionSize(List<Set<String>> filterSets) {
        if (filterSets.size() == 1) {
            return filterSets.getFirst().size();
        }
        final var ordered = filterSets.stream().sorted(Comparator.comparingInt(Set::size)).toList();
        final var intersection = new HashSet<>(ordered.getFirst());
        for (var i = 1; i < ordered.size() && !intersection.isEmpty(); i++) {
            intersection.retainAll(ordered.get(i));
        }
        return intersection.size();
    }

    // The PK index is maintained synchronously on save/delete, so its size is the exact document count.
    private static int wholeCollectionCount(String dbName, String collName) {
        return cache.pkIndexSize(dbName, collName);
    }
}
