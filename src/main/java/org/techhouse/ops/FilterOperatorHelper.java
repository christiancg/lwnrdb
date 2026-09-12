package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.filter.FieldPredicateFactory;
import org.techhouse.ops.index.PendingWriteReconciler;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.utils.JsonUtils;

public class FilterOperatorHelper {
    private static final Cache cache = IocContainer.get(Cache.class);

    public static Stream<JsonObject> processOperator(BaseOperator operator, Stream<JsonObject> resultStream,
            String dbName, String collName) throws IOException {
        return processOperator(operator, resultStream, dbName, collName, null);
    }

    public static Stream<JsonObject> processOperator(BaseOperator operator, Stream<JsonObject> resultStream,
            String dbName, String collName, PipelineScriptContext context) throws IOException {
        resultStream = switch (operator.getType()) {
            case CONJUNCTION ->
                processConjunctionOperator((ConjunctionOperator) operator, resultStream, dbName, collName, context);
            case FIELD -> processFieldOperator((FieldOperator) operator, resultStream, dbName, collName);
            case CUSTOM -> processCustomOperator((CustomOperator) operator, resultStream, dbName, collName);
            case SCRIPT -> processScriptOperator((ScriptOperator) operator, resultStream, dbName, collName, context);
        };
        return resultStream;
    }

    private static Stream<JsonObject> processConjunctionOperator(ConjunctionOperator operator,
            Stream<JsonObject> resultStream, String dbName, String collName, PipelineScriptContext context)
            throws IOException {
        final var buffered = resultStream == null ? null : resultStream.toList();
        List<Stream<JsonObject>> combinationResult = new ArrayList<>();
        for (var step : operator.getOperators()) {
            final var stepStream = buffered == null ? null : buffered.stream();
            final var partialResults = switch (step.getType()) {
                case CONJUNCTION ->
                    processConjunctionOperator((ConjunctionOperator) step, stepStream, dbName, collName, context);
                case FIELD -> processFieldOperator((FieldOperator) step, stepStream, dbName, collName);
                case CUSTOM -> processCustomOperator((CustomOperator) step, stepStream, dbName, collName);
                case SCRIPT -> processScriptOperator((ScriptOperator) step, stepStream, dbName, collName, context);
            };
            combinationResult.add(partialResults);
        }
        return switch (operator.getConjunctionType()) {
            case AND -> andXorConjunction(combinationResult, operator.getOperators().size());
            case OR -> orConjunction(combinationResult);
            case XOR -> andXorConjunction(combinationResult, 1);
            case NOR -> {
                final var combined = orConjunction(combinationResult);
                yield norNandAllStreamAggregation(combined, buffered == null ? null : buffered.stream(), dbName,
                        collName);
            }
            case NAND -> {
                final var combined = andXorConjunction(combinationResult, operator.getOperators().size());
                yield norNandAllStreamAggregation(combined, buffered == null ? null : buffered.stream(), dbName,
                        collName);
            }
        };
    }

    private static Stream<JsonObject> andXorConjunction(List<Stream<JsonObject>> combinationResult, int matches) {
        return combinationResult.stream().flatMap(jsonObjectStream -> jsonObjectStream)
                .collect(Collectors.groupingBy(jsonObject -> {
                    final var id = jsonObject.get(Globals.PK_FIELD);
                    if (id == null) {
                        throw new IllegalStateException("Document missing _id in conjunction grouping");
                    }
                    return id;
                })).entrySet().stream()
                .filter(jsonElementListEntry -> jsonElementListEntry.getValue().size() == matches)
                .flatMap(jsonElementListEntry -> jsonElementListEntry.getValue().stream()).distinct();
    }

    private static Stream<JsonObject> orConjunction(List<Stream<JsonObject>> combinationResult) {
        return combinationResult.stream().flatMap(jsonObjectStream -> jsonObjectStream).distinct();
    }

    private static Stream<JsonObject> norNandAllStreamAggregation(Stream<JsonObject> combined,
            Stream<JsonObject> resultStream, String dbName, String collName) {
        if (resultStream == null) {
            // Blocking step (documented exception): NOR/NAND must diff against the full collection.
            resultStream = cache.getWholeCollection(dbName, collName).values().stream().map(DbEntry::getData);
        }
        return Stream.concat(resultStream, combined).collect(Collectors.groupingBy(jsonObject -> {
            final var id = jsonObject.get(Globals.PK_FIELD);
            if (id == null) {
                throw new IllegalStateException("Document missing _id in conjunction grouping");
            }
            return id;
        })).entrySet().stream().filter(jsonElementListEntry -> jsonElementListEntry.getValue().size() == 1)
                .flatMap(jsonElementListEntry -> jsonElementListEntry.getValue().stream());
    }

    private static Stream<JsonObject> processFieldOperator(FieldOperator operator, Stream<JsonObject> resultStream,
            String dbName, String collName) throws IOException {

        final var tester = FieldPredicateFactory.getTester(operator, operator.getFieldOperatorType());
        return internalBaseFiltering(tester, operator, resultStream, dbName, collName);
    }

    private static Stream<JsonObject> processScriptOperator(ScriptOperator operator, Stream<JsonObject> resultStream,
            String dbName, String collName, PipelineScriptContext context) throws IOException {
        final var stream = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        return stream.filter(data -> testScript(operator, data, context));
    }

    // JS truthiness, not a strict boolean: 0/""/null/undefined exclude a document.
    static boolean testScript(ScriptOperator operator, JsonObject document, PipelineScriptContext context) {
        return isTruthy(callScript(operator.getSource(), document, context));
    }

    private static boolean isTruthy(JsonBaseElement value) {
        if (value == null || value.isJsonNull()) {
            return false;
        }
        if (value.isJsonBoolean()) {
            return value.asJsonBoolean().getValue();
        }
        if (value.isJsonNumber()) {
            final var number = value.asJsonNumber().getValue().doubleValue();
            return number != 0 && !Double.isNaN(number);
        }
        if (value.isJsonString()) {
            return !value.asJsonString().getValue().isEmpty();
        }
        return true;
    }

    static JsonBaseElement callScript(String source, JsonObject document, PipelineScriptContext context) {
        if (context == null) {
            throw new ScriptCallableException("InternalError", "No script context available for this pipeline");
        }
        final var analyze = AnalyzeContext.current();
        final var start = analyze == null ? 0 : System.nanoTime();
        final var value = context.callableFor(source).apply(document);
        if (analyze != null) {
            analyze.recordScriptInvocation(System.nanoTime() - start);
        }
        return value;
    }

    private static Stream<JsonObject> processCustomOperator(CustomOperator operator, Stream<JsonObject> resultStream,
            String dbName, String collName) throws IOException {
        if (CustomTypeFactory.isRankingOperator(operator.getCustomOperatorName())) {
            return processRankingOperator(operator, resultStream, dbName, collName);
        }
        final var tester = getCustomTester(operator);
        final var fieldName = operator.getField();
        if (resultStream != null) {
            return resultStream.filter(data -> tester.test(data, fieldName));
        }
        // Candidate ids from the geo pre-filter are unconfirmed; they are fetched and re-tested exactly below.
        final var candidateIds = GeoSpatialIndexHelper.candidateIds(operator, dbName, collName);
        if (candidateIds != null) {
            return cache.getEntriesByIds(dbName, collName, candidateIds).stream().map(DbEntry::getData)
                    .filter(data -> tester.test(data, fieldName));
        }
        return cache.streamCollection(dbName, collName).map(DbEntry::getData)
                .filter(data -> tester.test(data, fieldName));
    }

    public static BiPredicate<JsonObject, String> getCustomTester(CustomOperator operator) {
        final var operatorName = operator.getCustomOperatorName();
        final var args = new HashMap<String, JsonBaseElement>();
        for (var entry : operator.getArgs().entrySet()) {
            args.put(entry.getKey(), entry.getValue());
        }
        return (JsonObject toTest, String fieldName) -> {
            if (!JsonUtils.hasInPath(toTest, fieldName)) {
                return false;
            }
            final var element = JsonUtils.getFromPath(toTest, fieldName);
            if (element == null || !element.isJsonCustom()) {
                return false;
            }
            final var custom = element.asJsonCustom();
            if (!custom.customOperatorNames().contains(operatorName)) {
                return false;
            }
            return custom.applyCustomOperator(operatorName, args);
        };
    }

    private static Stream<JsonObject> processRankingOperator(CustomOperator operator, Stream<JsonObject> resultStream,
            String dbName, String collName) throws IOException {
        final var k = operator.getArgs().get("k").asJsonNumber().getValue().intValue();
        final var fieldName = operator.getField();
        if (resultStream != null) {
            return topK(resultStream, operator, fieldName, k);
        }
        final var candidateIds = VectorSimilarityIndexHelper.candidateIds(operator, dbName, collName);
        final Stream<JsonObject> candidates;
        if (candidateIds != null) {
            candidates = cache.getEntriesByIds(dbName, collName, candidateIds).stream().map(DbEntry::getData);
        } else {
            candidates = cache.streamCollection(dbName, collName).map(DbEntry::getData);
        }
        return topK(candidates, operator, fieldName, k);
    }

    private static Stream<JsonObject> topK(Stream<JsonObject> candidates, CustomOperator operator, String fieldName,
            int k) {
        if (k <= 0) {
            candidates.close();
            return Stream.empty();
        }
        final var operatorName = operator.getCustomOperatorName();
        final var args = new HashMap<String, JsonBaseElement>();
        for (var entry : operator.getArgs().entrySet()) {
            args.put(entry.getKey(), entry.getValue());
        }
        final var heap = new PriorityQueue<>(Comparator.comparingDouble(ScoredDocument::score));
        candidates.forEach(document -> {
            final var score = scoreDocument(document, fieldName, operatorName, args);
            if (score == null) {
                return;
            }
            if (heap.size() < k) {
                heap.offer(new ScoredDocument(document, score));
            } else if (heap.peek().score() < score) {
                heap.poll();
                heap.offer(new ScoredDocument(document, score));
            }
        });
        return heap.stream().sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .map(ScoredDocument::document);
    }

    private static Double scoreDocument(JsonObject document, String fieldName, String operatorName,
            Map<String, JsonBaseElement> args) {
        if (!JsonUtils.hasInPath(document, fieldName)) {
            return null;
        }
        final var element = JsonUtils.getFromPath(document, fieldName);
        if (element == null || !element.isJsonCustom()) {
            return null;
        }
        final var custom = element.asJsonCustom();
        if (!custom.customRankingOperatorNames().contains(operatorName)) {
            return null;
        }
        final var score = custom.applyCustomRankingOperator(operatorName, args);
        // An undefined score (e.g. mismatched-dimension or zero vector) does not rank, like a missing field.
        return Double.isNaN(score) ? null : score;
    }

    private record ScoredDocument(JsonObject document, double score) {
    }

    public static BiPredicate<JsonObject, String> getTester(FieldOperator operator, FieldOperatorType operation) {
        return FieldPredicateFactory.getTester(operator, operation);
    }

    private static Stream<JsonObject> internalBaseFiltering(BiPredicate<JsonObject, String> test,
            FieldOperator operator, Stream<JsonObject> resultStream, String dbName, String collName)
            throws IOException {
        final var fieldName = operator.getField();
        if (resultStream != null) {
            return resultStream.filter(data -> test.test(data, fieldName));
        }
        final var matchingValues = indexMatchingIds(operator, dbName, collName);
        if (matchingValues != null) {
            // Index hits are candidates only: an object/array element-match index stores just a SHA-256
            // fingerprint, and an entry can be stale, so re-test every fetched document against the operator.
            return cache.getEntriesByIds(dbName, collName, matchingValues).stream().map(DbEntry::getData)
                    .filter(data -> test.test(data, fieldName));
        }
        return cache.streamCollection(dbName, collName).map(DbEntry::getData)
                .filter(data -> test.test(data, fieldName));
    }

    public static Set<String> resolveIdsViaIndex(BaseOperator operator, String dbName, String collName)
            throws IOException {
        return switch (operator.getType()) {
            case FIELD -> {
                final var fieldOperator = (FieldOperator) operator;
                // A hash index hit is only a candidate and the index-only COUNT cannot confirm it, so
                // disqualify it and let the caller fall back to the document-reading COUNT.
                if (usesHashIndex(fieldOperator)) {
                    yield null;
                }
                yield indexMatchingIds(fieldOperator, dbName, collName);
            }
            case CONJUNCTION -> resolveConjunctionIds((ConjunctionOperator) operator, dbName, collName);
            // A custom operator's hits are unconfirmed candidates too, so COUNT must fall back to documents.
            case CUSTOM -> null;
            case SCRIPT -> null;
        };
    }

    // Mirrors the dispatch in UserCache.doGetIdsFromIndex / getIdsFromInList: object operands and array
    // operands (EQUALS/NOT_EQUALS, IN/NOT_IN over object/array elements) are hash-resolved, scalars are not.
    private static boolean usesHashIndex(FieldOperator operator) {
        final var value = operator.getValue();
        final var opType = operator.getFieldOperatorType();
        if (value.isJsonObject()) {
            return opType == FieldOperatorType.EQUALS || opType == FieldOperatorType.NOT_EQUALS;
        }
        if (value.isJsonArray()) {
            final var arr = value.asJsonArray();
            return switch (opType) {
                case EQUALS, NOT_EQUALS -> true;
                case IN, NOT_IN -> !arr.isEmpty() && (arr.get(0).isJsonObject() || arr.get(0).isJsonArray());
                default -> false;
            };
        }
        return false;
    }

    // Documents committed but not yet indexed have untrustworthy index membership, so they are dropped and
    // re-added by re-testing the operator against the current document, keeping the result exact.
    private static Set<String> indexMatchingIds(FieldOperator operator, String dbName, String collName)
            throws IOException {
        final var raw = rawIndexMatchingIds(operator, dbName, collName);
        if (raw == null) {
            return null;
        }
        final var analyzeContext = AnalyzeContext.current();
        if (analyzeContext != null) {
            analyzeContext.addIndexUsed(operator.getField());
            analyzeContext.addLock(AnalyzeContext.fieldLockId(dbName, collName, operator.getField()));
        }
        final var pendingIds = PendingWriteReconciler.pendingIds(dbName, collName);
        if (pendingIds.isEmpty()) {
            return raw;
        }
        return PendingWriteReconciler.correctIds(raw, dbName, collName, pendingIds, operator.getField(),
                FieldPredicateFactory.getTester(operator, operator.getFieldOperatorType()));
    }

    private static Set<String> rawIndexMatchingIds(FieldOperator operator, String dbName, String collName)
            throws IOException {
        final var fieldName = operator.getField();
        final var value = operator.getValue();
        return switch (value) {
            case JsonObject jsonObject -> cache.getIdsFromIndex(dbName, collName, fieldName, operator, jsonObject);
            case JsonArray jsonArray -> cache.getIdsFromIndex(dbName, collName, fieldName, operator, jsonArray);
            case JsonBoolean jsonBoolean ->
                cache.getIdsFromIndex(dbName, collName, fieldName, operator, jsonBoolean.getValue());
            case JsonNumber jsonNumber ->
                cache.getIdsFromIndex(dbName, collName, fieldName, operator, jsonNumber.getValue());
            case JsonCustom<?> jsonCustom -> cache.getIdsFromIndex(dbName, collName, fieldName, operator, jsonCustom);
            case JsonString jsonString ->
                cache.getIdsFromIndex(dbName, collName, fieldName, operator, jsonString.getValue());
            default -> null;
        };
    }

    private static Set<String> resolveConjunctionIds(ConjunctionOperator operator, String dbName, String collName)
            throws IOException {
        final var childSets = new ArrayList<Set<String>>();
        for (var child : operator.getOperators()) {
            final var ids = resolveIdsViaIndex(child, dbName, collName);
            if (ids == null) {
                return null;
            }
            childSets.add(ids);
        }
        return switch (operator.getConjunctionType()) {
            case AND -> occurringExactly(childSets, childSets.size());
            case XOR -> occurringExactly(childSets, 1);
            case OR -> union(childSets);
            case NOR -> complement(union(childSets), dbName, collName);
            case NAND -> complement(occurringExactly(childSets, childSets.size()), dbName, collName);
        };
    }

    private static Set<String> union(List<Set<String>> sets) {
        final var result = new HashSet<String>();
        for (var set : sets) {
            result.addAll(set);
        }
        return result;
    }

    private static Set<String> occurringExactly(List<Set<String>> sets, int times) {
        final var counts = new HashMap<String, Integer>();
        for (var set : sets) {
            for (var id : set) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().filter(entry -> entry.getValue() == times).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static Set<String> complement(Set<String> matched, String dbName, String collName) throws IOException {
        final var pkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var result = new HashSet<String>();
        for (var entry : pkIndex) {
            final var id = entry.getValue();
            if (!matched.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }
}
