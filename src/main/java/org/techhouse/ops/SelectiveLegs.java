package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.JsonUtils;
import org.techhouse.utils.RangeBounds;

final class SelectiveLegs {
    static final long RESOLVE_WITHIN = 16;
    private static final Cache cache = IocContainer.get(Cache.class);

    interface Resolver {
        Set<String> resolve(BaseOperator leg) throws IOException;
    }

    private interface Deferred {
        Set<String> resolve() throws IOException;
    }

    private record Counted(String field, long count, Deferred deferred) {
    }

    private SelectiveLegs() {
    }

    static List<Set<String>> sets(ConjunctionOperator operator, String dbName, String collName, PipelineContext context,
            Resolver resolver) throws IOException {
        final var sets = new ArrayList<Set<String>>();
        final var counted = new ArrayList<Counted>();
        final var rangesByField = new LinkedHashMap<String, List<FieldOperator>>();
        for (final var leg : operator.getOperators()) {
            if (leg instanceof FieldOperator field && ConjunctionCandidates.isMergeableRange(field)) {
                rangesByField.computeIfAbsent(field.getField(), _ -> new ArrayList<>()).add(field);
                continue;
            }
            if (leg instanceof FieldOperator equality) {
                final var count = equalsCount(equality, dbName, collName);
                if (count.isPresent()) {
                    counted.add(new Counted(equality.getField(), count.getAsLong(), () -> resolver.resolve(leg)));
                    continue;
                }
            }
            addIfResolved(sets, resolver.resolve(leg));
        }
        for (final var range : rangesByField.entrySet()) {
            final var bounds = RangeBounds.fromLeaves(range.getKey(), range.getValue());
            final Deferred deferred = () -> ConjunctionCandidates.rangeMatchingIds(bounds, range.getValue(), dbName,
                    collName, context);
            final var count = cache.hasNoIndex(dbName, collName, range.getKey())
                    ? OptionalLong.empty()
                    : cache.countInRange(dbName, collName, bounds);
            if (count.isPresent()) {
                counted.add(new Counted(range.getKey(), count.getAsLong(), deferred));
            } else {
                addIfResolved(sets, deferred.resolve());
            }
        }
        resolveMostSelective(sets, counted, dbName, collName, context);
        return sets;
    }

    private static void resolveMostSelective(List<Set<String>> sets, List<Counted> counted, String dbName,
            String collName, PipelineContext context) throws IOException {
        if (counted.isEmpty()) {
            return;
        }
        counted.sort(Comparator.comparingLong(Counted::count));
        var reference = counted.getFirst().count();
        for (final var set : sets) {
            reference = Math.min(reference, set.size());
        }
        for (final var leg : counted) {
            if (!sets.isEmpty() && leg.count() > reference * RESOLVE_WITHIN) {
                context.indexUsed(leg.field(), AnalyzeContext.fieldLockId(dbName, collName, leg.field()));
            } else {
                addIfResolved(sets, leg.deferred().resolve());
            }
        }
    }

    private static OptionalLong equalsCount(FieldOperator field, String dbName, String collName) throws IOException {
        if (field.getFieldOperatorType() != FieldOperatorType.EQUALS || JsonUtils.isPrimaryKeyPath(field.getField())
                || cache.hasNoIndex(dbName, collName, field.getField())) {
            return OptionalLong.empty();
        }
        final var value = CountOperatorHelper.scalarOperand(field.getValue());
        return value == null ? OptionalLong.empty() : cache.countEquals(dbName, collName, field.getField(), value);
    }

    private static void addIfResolved(List<Set<String>> sets, Set<String> ids) {
        if (ids != null) {
            sets.add(ids);
        }
    }
}
