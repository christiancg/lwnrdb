package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;

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
            result.addProperty(COUNT_FIELD_NAME, resultStream.count());
        } else {
            result.addProperty(COUNT_FIELD_NAME, wholeCollectionCount(dbName, collName));
        }
        return Stream.of(result);
    }

    // A FILTER is only index-resolvable while it still sees the stored documents, so no FILTER after a
    // MAP/JOIN may use its index. Skipping JOIN is safe: its permissions are checked before execution.
    public static FastCount tryIndexOnlyCount(List<BaseAggregationStep> steps, String dbName, String collName)
            throws IOException {
        final var countIndex = indexOfFirstCount(steps);
        if (countIndex < 1) {
            return null;
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
                    final var ids = FilterOperatorHelper.resolveIdsViaIndex(operator, dbName, collName);
                    if (ids == null) {
                        return null;
                    }
                    filterSets.add(ids);
                }
                case MAP, JOIN -> documentsModified = true;
                case SORT -> {
                    // count-preserving and non-modifying: nothing to do
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

    private static int indexOfFirstCount(List<BaseAggregationStep> steps) {
        for (var i = 0; i < steps.size(); i++) {
            if (steps.get(i).getType() == AggregationStepType.COUNT) {
                return i;
            }
        }
        return -1;
    }

    private static int intersectionSize(List<Set<String>> filterSets) {
        final var ordered = filterSets.stream().sorted(Comparator.comparingInt(Set::size)).toList();
        final var intersection = new HashSet<>(ordered.getFirst());
        for (var i = 1; i < ordered.size() && !intersection.isEmpty(); i++) {
            intersection.retainAll(ordered.get(i));
        }
        return intersection.size();
    }

    // The PK index is maintained synchronously on save/delete, so its size is the exact document count.
    private static int wholeCollectionCount(String dbName, String collName) {
        try {
            return cache.getPkIndexAndLoadIfNecessary(dbName, collName).size();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
