package org.techhouse.ops;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.filter.FieldPredicateFactory;
import org.techhouse.ops.index.IndexWindowWalk;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.utils.JsonUtils;

final class IndexedFilterLimit {
    private static final int FIRST_PAGE_LIMIT = 256;
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private IndexedFilterLimit() {
    }

    private record MatchingIds(Iterator<String> ids, boolean exact) {
    }

    static IndexedSource.Fast tryLazy(List<BaseAggregationStep> steps, String dbName, String collName,
            PipelineContext context) throws IOException {
        if (steps.isEmpty() || !(steps.getFirst() instanceof FilterAggregationStep filter)
                || !(filter.getOperator() instanceof FieldOperator operator)
                || JsonUtils.isPrimaryKeyPath(operator.getField())) {
            return null;
        }
        final var bound = AggregationOperationHelper.boundAfterRowWise(steps, 0);
        if (bound <= 0 || !locks.holdsCollectionLock(dbName, collName)) {
            return null;
        }
        final var matching = matchingIds(operator, dbName, collName, bound, context);
        if (matching == null) {
            return null;
        }
        final var skip = directSkip(steps);
        final var dropsSkip = skip > 0 && matching.exact();
        if (dropsSkip) {
            drop(matching.ids(), skip, context);
        }
        final var fieldName = operator.getField();
        final var test = FieldPredicateFactory.getTester(operator, operator.getFieldOperatorType());
        return new IndexedSource.Fast(context.watch(SortOperatorHelper
                .fetchInOrder(matching.ids(), dbName, collName, dropsSkip ? bound - skip : bound, context)
                .filter(document -> test.test(document, fieldName))), dropsSkip ? 2 : 1);
    }

    private static long directSkip(List<BaseAggregationStep> steps) {
        return steps.get(1) instanceof SkipAggregationStep skipStep ? skipStep.getSkip() : 0;
    }

    private static void drop(Iterator<String> ids, long count, PipelineContext context) {
        for (var dropped = 0L; dropped < count && ids.hasNext(); dropped++) {
            ids.next();
            context.tick(1);
        }
    }

    private static MatchingIds matchingIds(FieldOperator operator, String dbName, String collName, long bound,
            PipelineContext context) throws IOException {
        final var walk = IndexWindowWalk.open(operator, dbName, collName, (int) Math.min(bound, FIRST_PAGE_LIMIT),
                context);
        if (walk != null) {
            return new MatchingIds(walk, walk.startedWithoutPendingIds());
        }
        final var ids = FilterOperatorHelper.indexMatchingIds(operator, dbName, collName, context);
        return ids == null
                ? null
                : new MatchingIds(ids.iterator(), !FilterOperatorHelper.hitsAreUnconfirmedCandidates(operator));
    }
}
