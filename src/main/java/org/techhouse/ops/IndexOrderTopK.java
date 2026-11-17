package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.PendingWriteReconciler;
import org.techhouse.ops.index.RangeIdWalk;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.utils.JsonUtils;
import org.techhouse.utils.RangeBounds;

final class IndexOrderTopK {
    private static final int MAX_LIMIT = 4096;
    private static final long WALK_PER_CANDIDATE = 16;
    private static final long UNINDEXED_WALK_PER_ROW = 64;
    private static final long UNINDEXED_WALK_PK_DIVISOR = 64;
    private static final int MIN_FETCH = 32;
    private static final int MAX_FETCH = 256;
    private static final int MIN_PAGE = 64;
    private static final int MAX_FIRST_PAGE = 4096;
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private IndexOrderTopK() {
    }

    private record Split(RangeBounds walkRange, BaseOperator others) {
    }

    private record Candidates(Set<String> ids, boolean exact) {
    }

    private record Plan(String dbName, String collName, Candidates candidates, Predicate<JsonObject> test, long skip,
            int limit, long budget) {
    }

    static IndexedSource.Fast tryWalk(List<BaseAggregationStep> steps, SortAggregationStep sort, String dbName,
            String collName, long bound, PipelineContext context) throws IOException {
        final var sortField = sort.getFieldName();
        if (!(steps.getFirst() instanceof FilterAggregationStep filter) || !walkable(filter.getOperator())
                || JsonUtils.isPrimaryKeyPath(sortField) || !locks.holdsCollectionLock(dbName, collName)
                || cache.hasNoIndex(dbName, collName, sortField)
                || cache.reloadsLargeFieldIndex(dbName, collName, sortField)
                || !PendingWriteReconciler.pendingIds(dbName, collName).isEmpty()) {
            return null;
        }
        final var skip = AggregationOperationHelper.skipAfter(steps, 1);
        final var next = steps.get(2) instanceof SkipAggregationStep ? 3 : 2;
        if (bound - skip > MAX_LIMIT) {
            return null;
        }
        final var split = split(filter.getOperator(), sortField);
        if (split.others() == null) {
            return null;
        }
        final var resolved = candidatesOf(split.others(), dbName, collName, context);
        final var candidates = resolved == null ? new Candidates(null, false) : resolved;
        final long matched = resolved == null ? Long.MAX_VALUE : resolved.ids().size();
        if (matched == 0) {
            return new IndexedSource.Fast(Stream.empty(), next);
        }
        final var documents = Math.max(cache.pkIndexSize(dbName, collName), 1);
        final var expectedWalk = (double) bound * documents / matched;
        if (expectedWalk > (double) WALK_PER_CANDIDATE * matched) {
            return null;
        }
        final var unindexed = resolved == null && split.walkRange() == null;
        final var budget = resolved != null
                ? 2 * WALK_PER_CANDIDATE * matched + bound
                : unindexedBudget(documents, bound);
        final var firstPage = unindexed
                ? Math.min(budget + 1, MAX_FIRST_PAGE)
                : (long) Math.clamp(Math.max(expectedWalk, bound) * 2, MIN_PAGE, MAX_FIRST_PAGE);
        final var ids = split.walkRange() == null
                ? SortedIdWalk.open(dbName, collName, sortField, sort.getAscending(), firstPage, context)
                : RangeIdWalk.open(dbName, collName, split.walkRange(), sort.getAscending(), firstPage, context);
        if (ids == null) {
            return null;
        }
        final var plan = new Plan(dbName, collName, candidates, OperatorPredicate.of(filter.getOperator(), context),
                skip, (int) (bound - skip), budget);
        final var rows = walk(ids, plan, context);
        return rows == null ? null : new IndexedSource.Fast(rows.stream(), next);
    }

    private static long unindexedBudget(long documents, long bound) {
        return Math.max(UNINDEXED_WALK_PER_ROW * bound, documents / UNINDEXED_WALK_PK_DIVISOR);
    }

    private static boolean walkable(BaseOperator operator) {
        return !OperatorPredicate.holdsRanking(operator) && !OperatorPredicate.holdsScript(operator);
    }

    private static Split split(BaseOperator operator, String sortField) {
        if (!(operator instanceof ConjunctionOperator conjunction)
                || conjunction.getConjunctionType() != ConjunctionOperatorType.AND) {
            return new Split(null, operator);
        }
        final var ranges = new ArrayList<FieldOperator>();
        final var others = new ArrayList<BaseOperator>();
        for (final var leg : conjunction.getOperators()) {
            if (leg instanceof FieldOperator field && sortField.equals(field.getField())
                    && ConjunctionCandidates.isMergeableRange(field)) {
                ranges.add(field);
            } else {
                others.add(leg);
            }
        }
        if (ranges.isEmpty()) {
            return new Split(null, operator);
        }
        final var range = RangeBounds.fromLeaves(sortField, ranges);
        return switch (others.size()) {
            case 0 -> new Split(range, null);
            case 1 -> new Split(range, others.getFirst());
            default -> new Split(range, new ConjunctionOperator(ConjunctionOperatorType.AND, others));
        };
    }

    private static Candidates candidatesOf(BaseOperator operator, String dbName, String collName,
            PipelineContext context) throws IOException {
        final Set<String> ids = switch (operator) {
            case FieldOperator field -> FilterOperatorHelper.indexMatchingIds(field, dbName, collName, context);
            case ConjunctionOperator conjunction -> ConjunctionCandidates.of(conjunction, dbName, collName, context);
            case CustomOperator custom -> GeoSpatialIndexHelper.candidateIds(custom, dbName, collName, context);
            default -> null;
        };
        if (ids == null) {
            return null;
        }
        return new Candidates(ids,
                operator instanceof FieldOperator field && !FilterOperatorHelper.hitsAreUnconfirmedCandidates(field));
    }

    private static List<JsonObject> walk(Iterator<String> ids, Plan plan, PipelineContext context) {
        final var rows = new ArrayList<JsonObject>(Math.min(plan.limit(), MAX_FETCH));
        final var buffer = new ArrayList<String>();
        final var skipped = new long[]{0};
        final var exact = plan.candidates().exact();
        var walked = 0L;
        while (rows.size() < plan.limit() && ids.hasNext()) {
            final var id = ids.next();
            context.tick(1);
            if (++walked > plan.budget()) {
                return null;
            }
            if (plan.candidates().ids() != null && !plan.candidates().ids().contains(id)) {
                continue;
            }
            if (exact && skipped[0] < plan.skip()) {
                skipped[0]++;
                continue;
            }
            buffer.add(id);
            if (buffer.size() >= fetchSize(plan, rows.size(), skipped[0])) {
                drain(buffer, plan, rows, skipped, context);
            }
        }
        if (ids instanceof SortedIdWalk sorted && sorted.declined()) {
            return null;
        }
        if (rows.size() < plan.limit()) {
            drain(buffer, plan, rows, skipped, context);
        }
        return rows;
    }

    private static int fetchSize(Plan plan, int found, long skipped) {
        final var needed = plan.limit() - found;
        if (plan.candidates().exact()) {
            return needed;
        }
        return Math.clamp((needed + plan.skip() - skipped) * 2, MIN_FETCH, MAX_FETCH);
    }

    private static void drain(List<String> buffer, Plan plan, List<JsonObject> rows, long[] skipped,
            PipelineContext context) {
        if (buffer.isEmpty()) {
            return;
        }
        try (var fetched = SortOperatorHelper.fetchInOrder(buffer.iterator(), plan.dbName(), plan.collName(),
                buffer.size(), context)) {
            fetched.forEachOrdered(document -> {
                if (rows.size() >= plan.limit() || !plan.test().test(document)) {
                    return;
                }
                if (skipped[0] < plan.skip()) {
                    skipped[0]++;
                } else {
                    rows.add(document);
                }
            });
        }
        buffer.clear();
    }
}
