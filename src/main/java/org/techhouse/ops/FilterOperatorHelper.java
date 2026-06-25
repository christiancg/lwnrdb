package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.JsonUtils;

public class FilterOperatorHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    public static Stream<JsonObject> processOperator(BaseOperator operator, Stream<JsonObject> resultStream,
            String dbName, String collName) throws IOException {
        resultStream = switch (operator.getType()) {
            case CONJUNCTION ->
                processConjunctionOperator((ConjunctionOperator) operator, resultStream, dbName, collName);
            case FIELD -> processFieldOperator((FieldOperator) operator, resultStream, dbName, collName);
        };
        return resultStream;
    }

    private static Stream<JsonObject> processConjunctionOperator(ConjunctionOperator operator,
            Stream<JsonObject> resultStream, String dbName, String collName) throws IOException {
        List<Stream<JsonObject>> combinationResult = new ArrayList<>();
        for (var step : operator.getOperators()) {
            Stream<JsonObject> partialResults;
            if (step.getType() == OperatorType.CONJUNCTION) {
                partialResults = processConjunctionOperator((ConjunctionOperator) step, resultStream, dbName, collName);
            } else {
                partialResults = processFieldOperator((FieldOperator) step, resultStream, dbName, collName);
            }
            combinationResult.add(partialResults);
        }
        return switch (operator.getConjunctionType()) {
            case AND -> andXorConjunction(combinationResult, operator.getOperators().size());
            case OR -> orConjunction(combinationResult);
            case XOR -> andXorConjunction(combinationResult, 1);
            case NOR -> {
                final var combined = orConjunction(combinationResult);
                yield norNandAllStreamAggregation(combined, resultStream, dbName, collName);
            }
            case NAND -> {
                final var combined = andXorConjunction(combinationResult, operator.getOperators().size());
                yield norNandAllStreamAggregation(combined, resultStream, dbName, collName);
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
            // Blocking step (documented exception): NOR/NAND must diff against the full
            // collection, so it loads the whole collection rather than streaming.
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

        final var tester = getTester(operator, operator.getFieldOperatorType());
        return internalBaseFiltering(tester, operator, resultStream, dbName, collName);
    }

    @SuppressWarnings("unchecked")
    private static Integer compareCustom(JsonCustom<?> operator, JsonCustom<?> toTestWith) {
        final var customClass = operator.getClass();
        // The following line throws a warning but should be fine as we are checking that it is the same class
        return customClass.cast(operator).compare(customClass.cast(toTestWith).getCustomValue());
    }

    public static BiPredicate<JsonObject, String> getTester(FieldOperator operator, FieldOperatorType operation) {
        return (JsonObject toTest, String fieldName) -> {
            final var operatorElement = operator.getValue();
            if (JsonUtils.hasInPath(toTest, fieldName)) {
                final var toTestElement = JsonUtils.getFromPath(toTest, fieldName);
                if (operatorElement.isJsonPrimitive()) {
                    if (toTestElement.isJsonPrimitive()) {
                        final var operatorPrimitive = operatorElement.asJsonPrimitive();
                        final var toTestPrimitive = toTestElement.asJsonPrimitive();
                        if (operatorPrimitive.isJsonBoolean() && toTestPrimitive.isJsonBoolean()) {
                            if (operation == FieldOperatorType.EQUALS) {
                                return operatorPrimitive.asJsonBoolean().getValue() == toTestPrimitive.asJsonBoolean()
                                        .getValue();
                            } else if (operation == FieldOperatorType.NOT_EQUALS) {
                                return operatorPrimitive.asJsonBoolean().getValue() != toTestPrimitive.asJsonBoolean()
                                        .getValue();
                            }
                            return false;
                        } else if (operatorPrimitive.isJsonNumber() && toTestPrimitive.isJsonNumber()) {
                            final var operatorDouble = operatorElement.asJsonNumber().getValue();
                            final var toTestDouble = toTestElement.asJsonNumber().getValue();
                            return switch (operation) {
                                case EQUALS -> Objects.equals(operatorDouble, toTestDouble);
                                case NOT_EQUALS -> !Objects.equals(operatorDouble, toTestDouble);
                                case GREATER_THAN -> operatorDouble.doubleValue() < toTestDouble.doubleValue();
                                case GREATER_THAN_EQUALS -> operatorDouble.doubleValue() <= toTestDouble.doubleValue();
                                case SMALLER_THAN -> operatorDouble.doubleValue() > toTestDouble.doubleValue();
                                case SMALLER_THAN_EQUALS -> operatorDouble.doubleValue() >= toTestDouble.doubleValue();
                                case IN, NOT_IN, CONTAINS -> false;
                            };
                        } else if (operatorPrimitive.isJsonCustom() && toTestPrimitive.isJsonCustom()
                                && operatorPrimitive.getClass().equals(toTestPrimitive.getClass())) {
                            final var operatorCustom = operatorPrimitive.asJsonCustom();
                            final var toTestCustom = toTestPrimitive.asJsonCustom();
                            return switch (operation) {
                                case EQUALS -> compareCustom(operatorCustom, toTestCustom) == 0;
                                case NOT_EQUALS -> compareCustom(operatorCustom, toTestCustom) != 0;
                                case GREATER_THAN -> compareCustom(operatorCustom, toTestCustom) < 0;
                                case GREATER_THAN_EQUALS -> compareCustom(operatorCustom, toTestCustom) <= 0;
                                case SMALLER_THAN -> compareCustom(operatorCustom, toTestCustom) > 0;
                                case SMALLER_THAN_EQUALS -> compareCustom(operatorCustom, toTestCustom) >= 0;
                                case IN, NOT_IN, CONTAINS -> false;
                            };
                        } else if (!operatorPrimitive.isJsonCustom() && !toTestPrimitive.isJsonCustom()
                                && operatorPrimitive.isJsonString() && toTestPrimitive.isJsonString()) {
                            final var operatorString = operatorElement.asJsonString().getValue();
                            final var toTestString = toTestElement.asJsonString().getValue();
                            return switch (operation) {
                                case EQUALS -> operatorString.equalsIgnoreCase(toTestString);
                                case NOT_EQUALS -> !operatorString.equalsIgnoreCase(toTestString);
                                case CONTAINS -> toTestString.contains(operatorString);
                                case GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS, IN, NOT_IN ->
                                    false;
                            };
                        } else {
                            return operatorPrimitive.isJsonNull() && toTestPrimitive.isJsonNull();
                        }
                    }
                } else if (operatorElement.isJsonArray()) {
                    if (operation == FieldOperatorType.EQUALS || operation == FieldOperatorType.NOT_EQUALS) {
                        if (toTestElement != null && toTestElement.isJsonArray()) {
                            final var equal = operatorElement.asJsonArray().equals(toTestElement.asJsonArray());
                            return (operation == FieldOperatorType.EQUALS) == equal;
                        }
                        return false;
                    }
                    // IN / NOT_IN: membership of the field value in the candidate list. JsonArray.contains
                    // uses element equality, so this also matches object/array field values against a list
                    // of candidate objects/arrays (mirroring the index path's element-match resolution).
                    if ((operation == FieldOperatorType.IN || operation == FieldOperatorType.NOT_IN)
                            && toTestElement != null && !toTestElement.isJsonNull()) {
                        final var jsonArray = operatorElement.asJsonArray();
                        final var result = jsonArray.contains(toTestElement);
                        return (operation == FieldOperatorType.IN) == result;
                    }
                } else if (operatorElement.isJsonObject()) {
                    if ((operation == FieldOperatorType.EQUALS || operation == FieldOperatorType.NOT_EQUALS)
                            && toTestElement != null && toTestElement.isJsonObject()) {
                        final var equal = operatorElement.asJsonObject().equals(toTestElement.asJsonObject());
                        return (operation == FieldOperatorType.EQUALS) == equal;
                    }
                    return false;
                } else if (operatorElement.isJsonNull()) {
                    return toTestElement.isJsonNull();
                }
            }
            return false;
        };
    }

    private static Stream<JsonObject> internalBaseFiltering(BiPredicate<JsonObject, String> test,
            FieldOperator operator, Stream<JsonObject> resultStream, String dbName, String collName)
            throws IOException {
        final var fieldName = operator.getField();
        if (resultStream != null) {
            // An upstream stream already exists, so the index saves no scan: apply the predicate to
            // the actual documents directly (which also avoids trusting a possibly-stale index).
            return resultStream.filter(data -> test.test(data, fieldName));
        }
        final var matchingValues = indexMatchingIds(operator, dbName, collName);
        if (matchingValues != null) {
            // Index hits are candidates. Scalar/custom index entries store the exact value, but an
            // object/array element-match index stores only a SHA-256 fingerprint, so a hit must be
            // confirmed against the document (it can be stale after a background-processing failure, or
            // — vanishingly — a hash collision). The candidate documents are fetched here regardless, so
            // re-testing them against the operator is free and makes the result exact for every index kind.
            return cache.getEntriesByIds(dbName, collName, matchingValues).stream().map(DbEntry::getData)
                    .filter(data -> test.test(data, fieldName));
        }
        // No index: scan the collection page-by-page (memory-aware) rather than materializing it all.
        return cache.streamCollection(dbName, collName).map(DbEntry::getData)
                .filter(data -> test.test(data, fieldName));
    }

    // Resolves the matching document ids for a filter operator using ONLY indexes (and, for
    // NOR/NAND, the PK index as the full id universe). Returns null if any leaf field operator is
    // not index-resolvable, signaling the caller to fall back to the document-reading path. No
    // documents are read, so a COUNT that immediately follows an index-resolvable FILTER can be
    // answered from the size of this set.
    public static Set<String> resolveIdsViaIndex(BaseOperator operator, String dbName, String collName)
            throws IOException {
        return switch (operator.getType()) {
            case FIELD -> {
                final var fieldOperator = (FieldOperator) operator;
                // A hash (object/array) index hit is only a candidate; the index-only COUNT path counts
                // ids without reading documents, so it cannot confirm the hit. Disqualify hash-resolved
                // operators here so the caller falls back to the document-reading COUNT, which re-tests
                // each candidate in internalBaseFiltering and is therefore exact.
                if (usesHashIndex(fieldOperator)) {
                    yield null;
                }
                yield indexMatchingIds(fieldOperator, dbName, collName);
            }
            case CONJUNCTION -> resolveConjunctionIds((ConjunctionOperator) operator, dbName, collName);
        };
    }

    // True when this field operator resolves through an object/array element-match (hash) index, whose
    // hits are unconfirmed candidates. Mirrors the dispatch in UserCache.doGetIdsFromIndex /
    // getIdsFromInList: an object operand (EQUALS/NOT_EQUALS) and an array operand (EQUALS/NOT_EQUALS, or
    // IN/NOT_IN over object/array elements) are hash-resolved; scalar/custom operands are not.
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

    // Resolves the ids matching a single field operator via the index, then reconciles documents that
    // are committed but not yet indexed (the PendingIndexWrites overlay): their index membership is
    // untrustworthy, so they are dropped and re-added by re-testing the operator against the current
    // document. The result is therefore exact (no stale false positives, no missing not-yet-indexed
    // matches). Returns null when the operator is not index-resolvable, so the caller falls back to a
    // scan. Empty when there are no recent writes, so this is a no-op on the steady-state read path.
    private static Set<String> indexMatchingIds(FieldOperator operator, String dbName, String collName)
            throws IOException {
        final var raw = rawIndexMatchingIds(operator, dbName, collName);
        if (raw == null) {
            return null;
        }
        // Snapshot pending ids AFTER the index lookup so a write that committed before the lookup is
        // either already indexed (index accurate) or still pending (reconciled here).
        final var pendingIds = pendingIndexWrites.idsFor(dbName, collName);
        if (pendingIds.isEmpty()) {
            return raw;
        }
        final var fieldName = operator.getField();
        final var corrected = new HashSet<>(raw);
        corrected.removeAll(pendingIds);
        final var tester = getTester(operator, operator.getFieldOperatorType());
        for (var dbEntry : cache.getEntriesByIds(dbName, collName, pendingIds)) {
            if (tester.test(dbEntry.getData(), fieldName)) {
                corrected.add(dbEntry.get_id());
            }
        }
        return corrected;
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
                return null; // a leaf isn't index-resolvable -> caller falls back to reading documents
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

    // Tallies how many child sets each id appears in and keeps the ids whose count equals times.
    // Models AND (times == number of operators) and XOR (times == 1) exactly like the stream-based
    // andXorConjunction does.
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

    // The complement of matched relative to every id in the collection. The full id set comes from
    // the PK index (metadata only, no documents read), matching the stream-based NOR/NAND path that
    // diffs against the whole collection.
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
