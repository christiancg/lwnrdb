package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.JsonElementWriter;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.utils.FieldPath;
import org.techhouse.utils.FieldPathMemo;
import org.techhouse.utils.JsonUtils;
import org.techhouse.utils.SaltedKey;

public final class AggregationOperationHelper {
    private AggregationOperationHelper() {
    }
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Configuration configuration = Configuration.getInstance();

    public static List<JsonObject> processStepsOnStream(List<BaseAggregationStep> steps,
            Stream<JsonObject> initialStream) throws IOException {
        return applySteps(steps, initialStream, "", "", null, aggregateTimeoutMs());
    }

    public static List<JsonObject> processAggregation(AggregateRequest request) throws IOException {
        return processAggregation(request, aggregateTimeoutMs());
    }

    public static List<JsonObject> processAggregation(AggregateRequest request, long timeoutMs) throws IOException {
        return applySteps(request.getAggregationSteps(), null, request.getDatabaseName(), request.getCollectionName(),
                null, timeoutMs);
    }

    public static List<JsonObject> processAggregation(AggregateRequest request, Stream<JsonObject> source)
            throws IOException {
        return processAggregation(request, source, null);
    }

    public static List<JsonObject> processAggregation(AggregateRequest request, Stream<JsonObject> source,
            Transaction transaction) throws IOException {
        return applySteps(request.getAggregationSteps(), source, request.getDatabaseName(), request.getCollectionName(),
                transaction, aggregateTimeoutMs());
    }

    public static List<JsonObject> processAggregation(AggregateRequest request, Stream<JsonObject> source,
            Transaction transaction, PipelineContext context) throws IOException {
        return applySteps(request.getAggregationSteps(), source, 0, request.getDatabaseName(),
                request.getCollectionName(), context, transaction);
    }

    public static List<JsonObject> processAggregation(AggregateRequest request, Stream<JsonObject> source,
            int firstStep, Transaction transaction, PipelineContext context) throws IOException {
        return applySteps(request.getAggregationSteps(), source, firstStep, request.getDatabaseName(),
                request.getCollectionName(), context, transaction);
    }

    private static long aggregateTimeoutMs() {
        return configuration.longValue(ConfigKey.AGGREGATE_TIMEOUT_MS);
    }

    public static List<String> aggregateLockSet(AggregateRequest request) {
        final var dbName = request.getDatabaseName();
        return aggregateCollections(request).stream().map(collName -> Cache.getCollectionIdentifier(dbName, collName))
                .toList();
    }

    public static List<String> aggregateCollections(AggregateRequest request) {
        final var collections = new ArrayList<String>();
        collections.add(request.getCollectionName());
        if (request.getAggregationSteps() != null) {
            for (var step : request.getAggregationSteps()) {
                if (step instanceof JoinAggregationStep joinStep) {
                    collections.add(joinStep.getJoinCollection());
                }
            }
        }
        return collections;
    }

    // A Stream is lazy, so a SCRIPT callable runs during toList(): the context must wrap the terminal op too.
    private static List<JsonObject> applySteps(List<BaseAggregationStep> steps, Stream<JsonObject> initialStream,
            String dbName, String collName, Transaction transaction, long timeoutMs) throws IOException {
        try (var context = PipelineContext.of(timeoutMs, PipelineContext.configuredMaxResultBytes(), null)) {
            return applySteps(steps, initialStream, 0, dbName, collName, context, transaction);
        }
    }

    private static List<JsonObject> applySteps(List<BaseAggregationStep> steps, Stream<JsonObject> initialStream,
            int firstStep, String dbName, String collName, PipelineContext context, Transaction transaction)
            throws IOException {
        Stream<JsonObject> resultStream = context.watch(initialStream);
        var startIndex = firstStep;
        if (resultStream == null) {
            final var fast = IndexedSource.of(steps, dbName, collName, sortBound(steps, 1), context);
            if (fast != null) {
                resultStream = fast.stream();
                startIndex = fast.nextStepIndex();
            }
        }
        var skippedBySort = -1;
        for (var i = startIndex; i < steps.size(); i++) {
            final var step = steps.get(i);
            resultStream = switch (step.getType()) {
                case FILTER ->
                    processFilterStep(step, resultStream, dbName, collName, context, boundAfterRowWise(steps, i));
                case MAP -> processMapStep(step, resultStream, dbName, collName, context);
                case GROUP_BY -> GroupByOperatorHelper.processGroupByStep((GroupByAggregationStep) step, resultStream,
                        dbName, collName, GroupByOperatorHelper.demandOf(stepAt(steps, i + 1)), context);
                case JOIN -> processJoinStep(step, resultStream, dbName, collName, transaction, context);
                case COUNT -> processCountStep(resultStream, dbName, collName);
                case DISTINCT -> processDistinctStep(step, resultStream, dbName, collName, context);
                case LIMIT -> processLimitStep(step, resultStream, dbName, collName, context);
                case SKIP -> processSkipStep(step, resultStream, dbName, collName, context, i == skippedBySort);
                case SORT -> {
                    final var sorted = SortOperatorHelper.processSortStep((SortAggregationStep) step, resultStream,
                            dbName, collName, sortBound(steps, i), skipAfter(steps, i), context);
                    skippedBySort = sorted.skipApplied() ? i + 1 : -1;
                    yield sorted.rows();
                }
                case REDUCE -> processReduceStep(step,
                        resultStream != null && needsFoldOrder(steps, i)
                                ? ReduceOperatorHelper.inFoldOrder(resultStream)
                                : resultStream,
                        dbName, collName, context);
            };
            resultStream = context.watch(resultStream);
        }
        try {
            if (resultStream == null) {
                if (collName == null || collName.isEmpty()) {
                    return new ArrayList<>();
                }
                resultStream = context.source(null, dbName, collName);
            }
            try (var stream = resultStream) {
                return materialise(stream, context);
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static List<JsonObject> materialise(Stream<JsonObject> stream, PipelineContext context) {
        final var rows = new ArrayList<JsonObject>();
        final var held = new HeldRows(context);
        stream.forEachOrdered(row -> {
            held.hold(row);
            rows.add(row);
        });
        return rows;
    }

    private static boolean needsFoldOrder(List<BaseAggregationStep> steps, int reduceIndex) {
        return reduceIndex > 0 && !ResultHasher.ordersResults(steps.subList(0, reduceIndex));
    }

    static long sortBound(List<BaseAggregationStep> steps, int sortIndex) {
        final var next = stepAt(steps, sortIndex + 1);
        if (next instanceof LimitAggregationStep limitStep) {
            return limitStep.getLimit();
        }
        if (next instanceof SkipAggregationStep skipStep
                && stepAt(steps, sortIndex + 2) instanceof LimitAggregationStep limitStep) {
            return skipStep.getSkip() + (long) limitStep.getLimit();
        }
        return -1;
    }

    static long boundAfterRowWise(List<BaseAggregationStep> steps, int sourceIndex) {
        var index = sourceIndex + 1;
        while (index < steps.size() && RowWiseSteps.isRowWise(steps.get(index))) {
            index++;
        }
        return sortBound(steps, index - 1);
    }

    static long skipAfter(List<BaseAggregationStep> steps, int index) {
        return stepAt(steps, index + 1) instanceof SkipAggregationStep skipStep ? skipStep.getSkip() : 0;
    }

    private static BaseAggregationStep stepAt(List<BaseAggregationStep> steps, int index) {
        return index < steps.size() ? steps.get(index) : null;
    }

    private static Stream<JsonObject> processCountStep(Stream<JsonObject> resultStream, String dbName,
            String collName) {
        return CountOperatorHelper.processCountStep(resultStream, dbName, collName);
    }

    private static Stream<JsonObject> processFilterStep(BaseAggregationStep baseFilterStep,
            Stream<JsonObject> resultStream, String dbName, String collName, PipelineContext context, long sourceBound)
            throws IOException {
        final var filterStep = (FilterAggregationStep) baseFilterStep;
        final var filterOperator = filterStep.getOperator();
        return FilterOperatorHelper.processOperator(filterOperator, resultStream, dbName, collName, context,
                sourceBound);
    }

    private static Stream<JsonObject> processMapStep(BaseAggregationStep baseMapStep, Stream<JsonObject> resultStream,
            String dbName, String collName, PipelineContext context) throws IOException {
        resultStream = context.source(resultStream, dbName, collName);
        final var operators = ((MapAggregationStep) baseMapStep).getOperators();
        final var writes = JsonUtils.writeTreeOf(operators.stream().map(MapOperator::getFieldName).toList());
        final var writtenRoots = writes.roots();
        final var conditions = new ArrayList<Predicate<JsonObject>>(operators.size());
        final var paths = new FieldPathMemo();
        for (var operator : operators) {
            conditions.add(MapOperatorHelper.conditionOf(operator, context, writtenRoots));
        }
        return resultStream.map(jsonObject -> {
            var mapped = jsonObject.copyForWrites(writes);
            final var budget = new MapResultBudget(context);
            for (var i = 0; i < operators.size(); i++) {
                mapped = MapOperatorHelper.processOperator(operators.get(i), mapped, context, budget, conditions.get(i),
                        writtenRoots, paths);
            }
            return mapped;
        });
    }

    private static Stream<JsonObject> processReduceStep(BaseAggregationStep baseReduceStep,
            Stream<JsonObject> resultStream, String dbName, String collName, PipelineContext context)
            throws IOException {
        return ReduceOperatorHelper.processReduceStep((ReduceAggregationStep) baseReduceStep, resultStream, dbName,
                collName, context);
    }

    private static Stream<JsonObject> processJoinStep(BaseAggregationStep baseJoinStep, Stream<JsonObject> resultStream,
            String dbName, String collName, Transaction transaction, PipelineContext context) throws IOException {
        final var joinStep = (JoinAggregationStep) baseJoinStep;
        final var joinCollectionName = joinStep.getJoinCollection();
        final var joinCollectionLocalField = joinStep.getLocalField();
        final var joinCollectionRemoteField = joinStep.getRemoteField();
        final var as = joinStep.getAsField();
        final List<JsonObject> leftEntries;
        try (var documents = context.source(resultStream, dbName, collName)) {
            leftEntries = context.watch(documents).toList();
        }
        final var joinedCollection = JoinLookup.build(dbName, joinCollectionName, joinCollectionRemoteField,
                leftEntries, joinCollectionLocalField, transaction, context);
        final var joinedBytes = new HashMap<SaltedKey, Long>();
        final var asWrites = JsonUtils.writeTreeOf(List.of(as));
        final var localPath = FieldPath.of(joinCollectionLocalField);
        final var asPath = FieldPath.of(as);
        return leftEntries.stream().map(jsonObject -> {
            final var localValue = localPath.resolve(jsonObject);
            if (localValue != null) {
                final var key = new SaltedKey(localValue);
                final var joined = joinedCollection.get(key);
                if (joined != null) {
                    chargeJoinedRow(joinedBytes.computeIfAbsent(key, _ -> serialisedBytes(joined)), context);
                }
                final var copy = jsonObject.copyForWrites(asWrites);
                asPath.set(copy, joined);
                return copy;
            }
            return jsonObject;
        });
    }

    private static long serialisedBytes(JsonArray joined) {
        return JsonElementWriter.utf8Length(joined);
    }

    private static void chargeJoinedRow(long joinedBytes, PipelineContext context) {
        if (EntrySizeGuard.exceedsMaxEntrySize(joinedBytes)) {
            throw new AggregateLimitException(ErrorCode.MAP_RESULT_TOO_LARGE);
        }
        context.chargeResult(joinedBytes);
    }

    private static Stream<JsonObject> processDistinctStep(BaseAggregationStep baseDistinctStep,
            Stream<JsonObject> resultStream, String dbName, String collName, PipelineContext context)
            throws IOException {
        final var distinctStep = (DistinctAggregationStep) baseDistinctStep;
        final var fieldName = distinctStep.getFieldName();
        if (resultStream == null && fieldName != null && !fieldName.isBlank()) {
            final var indexValues = IndexHelper.getDistinctIndexValues(dbName, collName, fieldName, context);
            if (indexValues != null) {
                final var path = FieldPath.of(fieldName);
                return distinctRows(indexValues.stream().map(indexValue -> {
                    final var json = new JsonObject();
                    path.set(json, IndexHelper.indexValueToElement(indexValue));
                    return json;
                }), context);
            }
        }
        resultStream = context.source(resultStream, dbName, collName);
        if (fieldName == null || fieldName.isBlank()) {
            final var seen = new HashSet<RowWithoutId>();
            final var held = new HeldRows(context);
            return resultStream.filter(jsonObject -> held.keptWhen(seen.add(new RowWithoutId(jsonObject)), jsonObject))
                    .map(jsonObject -> jsonObject.copyWithout(Globals.PK_FIELD));
        } else {
            final var path = FieldPath.of(fieldName);
            final var seen = new HashSet<SaltedKey>();
            final var held = new HeldRows(context);
            return resultStream.filter(path::isIn).map(path::valueIn).filter(value -> seen.add(new SaltedKey(value)))
                    .map(value -> {
                        final var json = new JsonObject();
                        path.set(json, value);
                        held.hold(json);
                        return json;
                    });
        }
    }

    private static Stream<JsonObject> distinctRows(Stream<JsonObject> rows, PipelineContext context) {
        final var seen = new HashSet<SaltedKey>();
        final var held = new HeldRows(context);
        return rows.filter(row -> held.keptWhen(seen.add(new SaltedKey(row)), row));
    }

    private static Stream<JsonObject> processLimitStep(BaseAggregationStep baseLimitStep,
            Stream<JsonObject> resultStream, String dbName, String collName, PipelineContext context)
            throws IOException {
        resultStream = context.source(resultStream, dbName, collName);
        final var limitStep = (LimitAggregationStep) baseLimitStep;
        return resultStream.limit(limitStep.getLimit());
    }

    private static Stream<JsonObject> processSkipStep(BaseAggregationStep baseSkipStep, Stream<JsonObject> resultStream,
            String dbName, String collName, PipelineContext context, boolean appliedBySort) throws IOException {
        if (appliedBySort) {
            return resultStream;
        }
        final var skip = ((SkipAggregationStep) baseSkipStep).getSkip();
        if (resultStream == null) {
            return context.scan(cache.streamCollectionSkipping(dbName, collName, skip)).map(DbEntry::getData);
        }
        return resultStream.skip(skip);
    }

    private static final class RowWithoutId {
        private final JsonObject row;
        private final int hash;

        private RowWithoutId(JsonObject row) {
            this.row = row;
            var membersHash = 0;
            for (var entry : row.entrySet()) {
                if (!Globals.PK_FIELD.equals(entry.getKey())) {
                    membersHash += SaltedKey.memberHash(entry.getKey(), entry.getValue());
                }
            }
            this.hash = membersHash;
        }

        private int sizeWithoutId() {
            return row.has(Globals.PK_FIELD) ? row.size() - 1 : row.size();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RowWithoutId that) || hash != that.hash || sizeWithoutId() != that.sizeWithoutId()) {
                return false;
            }
            for (var entry : row.entrySet()) {
                if (!Globals.PK_FIELD.equals(entry.getKey())
                        && !entry.getValue().equals(that.row.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
