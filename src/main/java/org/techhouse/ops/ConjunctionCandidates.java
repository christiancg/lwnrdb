package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.filter.FieldPredicateFactory;
import org.techhouse.ops.index.PendingWriteReconciler;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.JsonUtils;
import org.techhouse.utils.RangeBounds;

final class ConjunctionCandidates {
    private static final Cache cache = IocContainer.get(Cache.class);

    private interface LegResolver {
        Set<String> resolve(BaseOperator leg) throws IOException;
    }

    private ConjunctionCandidates() {
    }

    static Set<String> of(ConjunctionOperator operator, String dbName, String collName, PipelineContext context)
            throws IOException {
        if (holdsRanking(operator)) {
            return null;
        }
        final LegResolver filterLeg = leg -> switch (leg.getType()) {
            case FIELD -> FilterOperatorHelper.indexMatchingIds((FieldOperator) leg, dbName, collName, context);
            case CONJUNCTION -> of((ConjunctionOperator) leg, dbName, collName, context);
            case CUSTOM, SCRIPT -> null;
        };
        return switch (operator.getConjunctionType()) {
            case AND -> intersection(legSets(operator, dbName, collName, context, filterLeg, false));
            case OR, XOR -> unionOfEvery(legSets(operator, dbName, collName, context, filterLeg, true));
            case NOR, NAND -> null;
        };
    }

    static Set<String> exactAndIds(ConjunctionOperator operator, String dbName, String collName,
            PipelineContext context) throws IOException {
        final LegResolver exactLeg = leg -> FilterOperatorHelper.resolveIdsViaIndex(leg, dbName, collName, context);
        final var sets = legSets(operator, dbName, collName, context, exactLeg, true);
        return sets.contains(null) ? null : intersection(sets);
    }

    static RangeBounds singleRange(BaseOperator operator) {
        final var leaves = new ArrayList<FieldOperator>();
        if (operator.getType() == OperatorType.FIELD) {
            leaves.add((FieldOperator) operator);
        } else if (operator instanceof ConjunctionOperator conjunction
                && conjunction.getConjunctionType() == ConjunctionOperatorType.AND) {
            for (final var leg : conjunction.getOperators()) {
                if (leg.getType() != OperatorType.FIELD) {
                    return null;
                }
                leaves.add((FieldOperator) leg);
            }
        }
        if (leaves.isEmpty() || !leaves.stream().allMatch(ConjunctionCandidates::isMergeableRange)
                || leaves.stream().map(FieldOperator::getField).distinct().count() != 1) {
            return null;
        }
        return RangeBounds.fromLeaves(leaves.getFirst().getField(), leaves);
    }

    static Set<String> rangeMatchingIds(RangeBounds bounds, List<FieldOperator> leaves, String dbName, String collName,
            PipelineContext context) throws IOException {
        final var fieldName = bounds.fieldName();
        if (cache.hasNoIndex(dbName, collName, fieldName)) {
            return null;
        }
        final var pendingBefore = PendingWriteReconciler.pendingIds(dbName, collName);
        final var raw = cache.getIdsInRange(dbName, collName, bounds);
        if (raw == null) {
            return null;
        }
        context.indexUsed(fieldName, AnalyzeContext.fieldLockId(dbName, collName, fieldName));
        final var pendingIds = PendingWriteReconciler.pendingIdsAround(pendingBefore, dbName, collName);
        if (pendingIds.isEmpty()) {
            return raw;
        }
        return PendingWriteReconciler.correctIds(raw, dbName, collName, pendingIds, fieldName, everyLeaf(leaves),
                context);
    }

    private static List<Set<String>> legSets(ConjunctionOperator operator, String dbName, String collName,
            PipelineContext context, LegResolver resolver, boolean keepUnresolved) throws IOException {
        final var sets = new ArrayList<Set<String>>();
        final var rangesByField = new LinkedHashMap<String, List<FieldOperator>>();
        final var mergesRanges = operator.getConjunctionType() == ConjunctionOperatorType.AND;
        for (final var leg : operator.getOperators()) {
            if (mergesRanges && leg instanceof FieldOperator field && isMergeableRange(field)) {
                rangesByField.computeIfAbsent(field.getField(), _ -> new ArrayList<>()).add(field);
                continue;
            }
            addResolved(sets, resolver.resolve(leg), keepUnresolved);
        }
        for (final Map.Entry<String, List<FieldOperator>> range : rangesByField.entrySet()) {
            final var bounds = RangeBounds.fromLeaves(range.getKey(), range.getValue());
            addResolved(sets, rangeMatchingIds(bounds, range.getValue(), dbName, collName, context), keepUnresolved);
        }
        return sets;
    }

    private static void addResolved(List<Set<String>> sets, Set<String> ids, boolean keepUnresolved) {
        if (ids != null || keepUnresolved) {
            sets.add(ids);
        }
    }

    private static boolean isMergeableRange(FieldOperator leaf) {
        return RangeBounds.isNumericRange(leaf) && !JsonUtils.isPrimaryKeyPath(leaf.getField());
    }

    private static Set<String> intersection(List<Set<String>> sets) {
        if (sets.isEmpty()) {
            return null;
        }
        final var smallestFirst = sets.stream().sorted(Comparator.comparingInt(Set::size)).toList();
        final var survivors = new HashSet<>(smallestFirst.getFirst());
        for (var i = 1; i < smallestFirst.size() && !survivors.isEmpty(); i++) {
            survivors.retainAll(smallestFirst.get(i));
        }
        return survivors;
    }

    private static Set<String> unionOfEvery(List<Set<String>> sets) {
        if (sets.contains(null)) {
            return null;
        }
        final var union = new HashSet<String>();
        sets.forEach(union::addAll);
        return union;
    }

    private static BiPredicate<JsonObject, String> everyLeaf(List<FieldOperator> leaves) {
        final var testers = leaves.stream()
                .map(leaf -> FieldPredicateFactory.getTester(leaf, leaf.getFieldOperatorType())).toList();
        return (document, fieldName) -> testers.stream().allMatch(tester -> tester.test(document, fieldName));
    }

    private static boolean holdsRanking(BaseOperator operator) {
        return switch (operator) {
            case CustomOperator custom -> CustomTypeFactory.isRankingOperator(custom.getCustomOperatorName());
            case ConjunctionOperator conjunction ->
                conjunction.getOperators().stream().anyMatch(ConjunctionCandidates::holdsRanking);
            default -> false;
        };
    }
}
