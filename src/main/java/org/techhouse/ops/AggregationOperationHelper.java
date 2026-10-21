package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
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
import org.techhouse.utils.JsonUtils;
import org.techhouse.utils.SaltedKey;

public final class AggregationOperationHelper {
    private AggregationOperationHelper() {
    }
    private static final String GROUP_FIELD_NAME = "group";
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
        return applySteps(request.getAggregationSteps(), source, request.getDatabaseName(), request.getCollectionName(),
                context, transaction);
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
            return applySteps(steps, initialStream, dbName, collName, context, transaction);
        }
    }

    private static List<JsonObject> applySteps(List<BaseAggregationStep> steps, Stream<JsonObject> initialStream,
            String dbName, String collName, PipelineContext context, Transaction transaction) throws IOException {
        Stream<JsonObject> resultStream = initialStream;
        var startIndex = 0;
        if (resultStream == null) {
            final var fastCount = CountOperatorHelper.tryIndexOnlyCount(steps, dbName, collName, context);
            if (fastCount != null) {
                resultStream = Stream.of(fastCount.result());
                startIndex = fastCount.nextStepIndex();
            }
        }
        for (var i = startIndex; i < steps.size(); i++) {
            final var step = steps.get(i);
            resultStream = switch (step.getType()) {
                case FILTER -> processFilterStep(step, resultStream, dbName, collName, context);
                case MAP -> processMapStep(step, resultStream, dbName, collName, context);
                case GROUP_BY -> processGroupByStep(step, resultStream, dbName, collName, context);
                case JOIN -> processJoinStep(step, resultStream, dbName, collName, transaction, context);
                case COUNT -> processCountStep(resultStream, dbName, collName);
                case DISTINCT -> processDistinctStep(step, resultStream, dbName, collName, context);
                case LIMIT -> processLimitStep(step, resultStream, dbName, collName, context);
                case SKIP -> processSkipStep(step, resultStream, dbName, collName, context);
                case SORT -> SortOperatorHelper.processSortStep((SortAggregationStep) step, resultStream, dbName,
                        collName, sortBound(steps, i), context);
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
        stream.forEachOrdered(row -> {
            context.chargeResult(1);
            rows.add(row);
        });
        return rows;
    }

    private static boolean needsFoldOrder(List<BaseAggregationStep> steps, int reduceIndex) {
        return reduceIndex > 0 && !ResultHasher.ordersResults(steps.subList(0, reduceIndex));
    }

    private static long sortBound(List<BaseAggregationStep> steps, int sortIndex) {
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

    private static BaseAggregationStep stepAt(List<BaseAggregationStep> steps, int index) {
        return index < steps.size() ? steps.get(index) : null;
    }

    private static Stream<JsonObject> processCountStep(Stream<JsonObject> resultStream, String dbName,
            String collName) {
        return CountOperatorHelper.processCountStep(resultStream, dbName, collName);
    }

    private static Stream<JsonObject> processFilterStep(BaseAggregationStep baseFilterStep,
            Stream<JsonObject> resultStream, String dbName, String collName, PipelineContext context)
            throws IOException {
        final var filterStep = (FilterAggregationStep) baseFilterStep;
        final var filterOperator = filterStep.getOperator();
        return FilterOperatorHelper.processOperator(filterOperator, resultStream, dbName, collName, context);
    }

    private static Stream<JsonObject> processMapStep(BaseAggregationStep baseMapStep, Stream<JsonObject> resultStream,
            String dbName, String collName, PipelineContext context) throws IOException {
        resultStream = context.source(resultStream, dbName, collName);
        final var mapStep = (MapAggregationStep) baseMapStep;
        final var operators = mapStep.getOperators();
        final var writtenRoots = writtenRoots(operators);
        return resultStream.map(jsonObject -> {
            var mapped = jsonObject.copyForWrites(writtenRoots);
            final var budget = new MapResultBudget(context);
            for (var step : operators) {
                mapped = MapOperatorHelper.processOperator(step, mapped, context, budget);
            }
            return mapped;
        });
    }

    private static Set<String> writtenRoots(List<MapOperator> operators) {
        final var roots = new HashSet<String>();
        for (var operator : operators) {
            addRoot(roots, operator.getFieldName());
        }
        return roots;
    }

    private static Set<String> writtenRoot(String path) {
        final var roots = new HashSet<String>();
        addRoot(roots, path);
        return roots;
    }

    private static void addRoot(Set<String> roots, String path) {
        final var root = path == null ? null : JsonUtils.rootOf(path);
        if (root != null) {
            roots.add(root);
        }
    }

    private static Stream<JsonObject> processReduceStep(BaseAggregationStep baseReduceStep,
            Stream<JsonObject> resultStream, String dbName, String collName, PipelineContext context)
            throws IOException {
        return ReduceOperatorHelper.processReduceStep((ReduceAggregationStep) baseReduceStep, resultStream, dbName,
                collName, context);
    }

    private static Stream<JsonObject> processGroupByStep(BaseAggregationStep baseGroupByStep,
            Stream<JsonObject> resultStream, String dbName, String collName, PipelineContext context)
            throws IOException {
        final var groupByStep = (GroupByAggregationStep) baseGroupByStep;
        final var fieldName = groupByStep.getFieldName();
        if (resultStream == null) {
            final var indexEntries = IndexHelper.getIndexEntriesForField(dbName, collName, fieldName, context);
            if (indexEntries != null) {
                return groupByViaIndex(indexEntries, dbName, collName, fieldName, context);
            }
        }
        final Map<SaltedKey, List<JsonObject>> grouped;
        try (var documents = context.source(resultStream, dbName, collName)) {
            grouped = documents.filter(jsonObject -> JsonUtils.hasInPath(jsonObject, fieldName)).collect(
                    Collectors.groupingBy(jsonObject -> new SaltedKey(JsonUtils.getFromPath(jsonObject, fieldName))));
        }
        return grouped.entrySet().stream().map(jsonElementListEntry -> {
            final var groupedEntry = new JsonObject();
            JsonUtils.setPath(groupedEntry, fieldName, jsonElementListEntry.getKey().element());
            groupedEntry.add(GROUP_FIELD_NAME, inIdOrder(jsonElementListEntry.getValue()));
            return groupedEntry;
        });
    }

    private static String documentId(JsonObject document) {
        final var id = document.get(Globals.PK_FIELD);
        return id instanceof JsonString jsonString ? jsonString.getValue() : "";
    }

    private static JsonArray inIdOrder(List<JsonObject> documents) {
        final var ordered = new ArrayList<>(documents);
        ordered.sort(Comparator.comparing(AggregationOperationHelper::documentId));
        final var array = new JsonArray();
        ordered.forEach(array::add);
        return array;
    }

    private static Stream<JsonObject> groupByViaIndex(List<FieldIndexEntry<?>> indexEntries, String dbName,
            String collName, String fieldName, PipelineContext context) throws IOException {
        final var allIds = new HashSet<String>();
        for (var indexEntry : indexEntries) {
            allIds.addAll(indexEntry.getIds());
        }
        final var docById = new HashMap<String, JsonObject>();
        final var fetched = cache.getEntriesByIds(dbName, collName, allIds);
        context.scanned(fetched.size());
        for (var dbEntry : fetched) {
            docById.put(dbEntry.get_id(), dbEntry.getData());
        }
        final var grouped = new ArrayList<JsonObject>();
        for (var indexEntry : indexEntries) {
            final var groupDocuments = new ArrayList<JsonObject>();
            for (var id : indexEntry.getIds()) {
                final var doc = docById.get(id);
                if (doc != null) {
                    groupDocuments.add(doc);
                }
            }
            if (groupDocuments.isEmpty()) {
                continue;
            }
            final var groupedEntry = new JsonObject();
            JsonUtils.setPath(groupedEntry, fieldName, IndexHelper.indexValueToElement(indexEntry.getValue()));
            groupedEntry.add(GROUP_FIELD_NAME, inIdOrder(groupDocuments));
            grouped.add(groupedEntry);
        }
        return grouped.stream();
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
        final var joinedCollection = buildJoinLookup(dbName, joinCollectionName, joinCollectionRemoteField, leftEntries,
                joinCollectionLocalField, transaction, context);
        final var joinedBytes = new HashMap<SaltedKey, Long>();
        final var asRoot = writtenRoot(as);
        return leftEntries.stream().map(jsonObject -> {
            final var localValue = JsonUtils.resolvePath(jsonObject, joinCollectionLocalField);
            if (localValue != null) {
                final var key = new SaltedKey(localValue);
                final var joined = joinedCollection.get(key);
                if (joined != null) {
                    chargeJoinedRow(joinedBytes.computeIfAbsent(key, _ -> serialisedBytes(joined)), context);
                }
                final var copy = jsonObject.copyForWrites(asRoot);
                JsonUtils.setPath(copy, as, joined);
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

    private static Map<SaltedKey, JsonArray> buildJoinLookup(String dbName, String joinCollectionName,
            String remoteField, List<JsonObject> leftEntries, String localField, Transaction transaction,
            PipelineContext context) throws IOException {
        final var localValues = new SaltedElementSet();
        for (var left : leftEntries) {
            final var localValue = JsonUtils.resolvePath(left, localField);
            if (localValue != null) {
                localValues.add(localValue);
            }
        }
        final var joinCollId = Cache.getCollectionIdentifier(dbName, joinCollectionName);
        final var overlay = transaction != null ? transaction.overlayFor(joinCollId) : null;
        if (overlay != null && !overlay.isEmpty()) {
            return groupByRemoteField(TransactionOperationHelper.applyOverlayToStream(transaction, joinCollId,
                    context.source(null, dbName, joinCollectionName)), remoteField, context);
        }
        final var matchingIds = IndexHelper.getMatchingIdsForJoin(dbName, joinCollectionName, remoteField, localValues,
                context);
        if (matchingIds == null) {
            final var joinCollectionMap = cache.getWholeCollection(dbName, joinCollectionName);
            context.scanned(joinCollectionMap.size());
            return groupByRemoteField(joinCollectionMap.values().stream().map(DbEntry::getData), remoteField, context);
        }
        final var matchedDocs = cache.getEntriesByIds(dbName, joinCollectionName, matchingIds);
        context.scanned(matchedDocs.size());
        final var matchedByKey = new HashMap<SaltedKey, List<JsonObject>>();
        for (var dbEntry : matchedDocs) {
            final var data = dbEntry.getData();
            final var key = JsonUtils.resolvePath(data, remoteField);
            if (key != null) {
                matchedByKey.computeIfAbsent(new SaltedKey(key), _ -> new ArrayList<>()).add(data);
            }
        }
        final var lookup = new HashMap<SaltedKey, JsonArray>();
        matchedByKey.forEach((key, documents) -> lookup.put(key, inIdOrder(documents)));
        return lookup;
    }

    private static Map<SaltedKey, JsonArray> groupByRemoteField(Stream<JsonObject> documents, String remoteField,
            PipelineContext context) {
        try (var remoteDocuments = context.watch(documents)) {
            return remoteDocuments.filter(jsonObject -> JsonUtils.hasInPath(jsonObject, remoteField))
                    .collect(Collectors.groupingBy(
                            jsonObject -> new SaltedKey(JsonUtils.getFromPath(jsonObject, remoteField)), HashMap::new,
                            Collectors.collectingAndThen(Collectors.toList(), AggregationOperationHelper::inIdOrder)));
        }
    }

    private static Stream<JsonObject> processDistinctStep(BaseAggregationStep baseDistinctStep,
            Stream<JsonObject> resultStream, String dbName, String collName, PipelineContext context)
            throws IOException {
        final var distinctStep = (DistinctAggregationStep) baseDistinctStep;
        final var fieldName = distinctStep.getFieldName();
        if (resultStream == null && fieldName != null && !fieldName.isBlank()) {
            final var indexEntries = IndexHelper.getIndexEntriesForField(dbName, collName, fieldName, context);
            if (indexEntries != null) {
                return distinctRows(indexEntries.stream().map(indexEntry -> {
                    final var json = new JsonObject();
                    JsonUtils.setPath(json, fieldName, IndexHelper.indexValueToElement(indexEntry.getValue()));
                    return json;
                }));
            }
        }
        resultStream = context.source(resultStream, dbName, collName);
        if (fieldName == null || fieldName.isBlank()) {
            final var seen = new HashSet<RowWithoutId>();
            return resultStream.filter(jsonObject -> seen.add(new RowWithoutId(jsonObject)))
                    .map(jsonObject -> jsonObject.copyWithout(Globals.PK_FIELD));
        } else {
            return distinctRows(
                    resultStream.filter(jsonObject -> JsonUtils.hasInPath(jsonObject, fieldName)).map(jsonObject -> {
                        final var json = new JsonObject();
                        JsonUtils.setPath(json, fieldName, JsonUtils.getFromPath(jsonObject, fieldName));
                        return json;
                    }));
        }
    }

    private static Stream<JsonObject> distinctRows(Stream<JsonObject> rows) {
        final var seen = new HashSet<SaltedKey>();
        return rows.filter(row -> seen.add(new SaltedKey(row)));
    }

    private static Stream<JsonObject> processLimitStep(BaseAggregationStep baseLimitStep,
            Stream<JsonObject> resultStream, String dbName, String collName, PipelineContext context)
            throws IOException {
        resultStream = context.source(resultStream, dbName, collName);
        final var limitStep = (LimitAggregationStep) baseLimitStep;
        return resultStream.limit(limitStep.getLimit());
    }

    private static Stream<JsonObject> processSkipStep(BaseAggregationStep baseSkipStep, Stream<JsonObject> resultStream,
            String dbName, String collName, PipelineContext context) throws IOException {
        resultStream = context.source(resultStream, dbName, collName);
        final var skipStep = (SkipAggregationStep) baseSkipStep;
        return resultStream.skip(skipStep.getSkip());
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
