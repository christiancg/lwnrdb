package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.data.admin.AdminPageEntry;
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

    // The outcome of the index-only COUNT optimization: the {count:N} object to emit and the index
    // of the first pipeline step that still needs to run (everything up to and including the COUNT
    // has been answered from the indexes).
    public record FastCount(JsonObject result, int nextStepIndex) {
    }

    // Runs the COUNT step proper: counts the upstream stream when there is one, otherwise derives the
    // whole-collection count from the admin page metadata without reading documents.
    public static Stream<JsonObject> processCountStep(Stream<JsonObject> resultStream, String dbName, String collName) {
        final var result = new JsonObject();
        if (resultStream != null) {
            result.addProperty(COUNT_FIELD_NAME, resultStream.count());
        } else {
            result.addProperty(COUNT_FIELD_NAME, wholeCollectionCount(dbName, collName));
        }
        return Stream.of(result);
    }

    // Optimization: when the pipeline source is a collection (no upstream stream), a COUNT can be
    // answered from the indexes alone — without reading any documents — as long as every step before
    // it either filters via an index or leaves the document count unchanged:
    // - FILTER steps are resolved to id-sets via their indexes; sequential filters compose as AND, so
    //   the count is the size of the intersection of their id-sets.
    // - MAP, JOIN and SORT keep one output row per input row, so they do not affect the count and are
    //   skipped (a COUNT discards the transformed/augmented documents anyway). JOIN permissions are
    //   checked before execution, so skipping the step here does not bypass them.
    // - GROUP_BY, DISTINCT, LIMIT and SKIP change the count in data-dependent ways, so they disqualify
    //   the fast path.
    // A FILTER is only index-resolvable while it still sees the stored documents, so once a MAP or
    // JOIN has modified them no later FILTER can use its index. Returns null when the pipeline does
    // not qualify, in which case the caller runs every step normally.
    public static FastCount tryIndexOnlyCount(List<BaseAggregationStep> steps, String dbName, String collName)
            throws IOException {
        final var countIndex = indexOfFirstCount(steps);
        if (countIndex < 1) {
            // No COUNT, or COUNT is the first step (the whole-collection count path handles that).
            return null;
        }
        final var filterSets = new ArrayList<Set<String>>();
        var documentsModified = false;
        for (var i = 0; i < countIndex; i++) {
            final var step = steps.get(i);
            switch (step.getType()) {
                case FILTER -> {
                    if (documentsModified) {
                        return null; // a MAP/JOIN may have changed the field this FILTER tests
                    }
                    final var operator = ((FilterAggregationStep) step).getOperator();
                    final var ids = FilterOperatorHelper.resolveIdsViaIndex(operator, dbName, collName);
                    if (ids == null) {
                        return null; // a leaf is not index-resolvable
                    }
                    filterSets.add(ids);
                }
                case MAP, JOIN -> documentsModified = true; // count-preserving; transforms documents
                case SORT -> {
                    // count-preserving and non-modifying: nothing to do
                }
                default -> {
                    return null; // GROUP_BY, DISTINCT, LIMIT, SKIP change the count
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

    // Size of the intersection of the filter id-sets. Starts from the smallest set so the retainAll
    // passes shrink work as fast as possible, and stops early once the running intersection is empty.
    private static int intersectionSize(List<Set<String>> filterSets) {
        final var ordered = filterSets.stream().sorted(Comparator.comparingInt(Set::size)).toList();
        final var intersection = new HashSet<>(ordered.getFirst());
        for (var i = 1; i < ordered.size() && !intersection.isEmpty(); i++) {
            intersection.retainAll(ordered.get(i));
        }
        return intersection.size();
    }

    private static int wholeCollectionCount(String dbName, String collName) {
        final var adminPageEntries = cache.getAdminPageEntries(dbName, collName);
        return adminPageEntries != null ? adminPageEntries.stream().mapToInt(AdminPageEntry::getEntryCount).sum() : 0;
    }
}
