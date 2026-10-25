package org.techhouse.ops;

import java.io.IOException;
import java.util.List;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.filter.FieldPredicateFactory;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.utils.JsonUtils;

final class IndexedFilterLimit {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private IndexedFilterLimit() {
    }

    static IndexedSource.Fast tryLazy(List<BaseAggregationStep> steps, String dbName, String collName,
            PipelineContext context) throws IOException {
        if (steps.isEmpty() || !(steps.getFirst() instanceof FilterAggregationStep filter)
                || !(filter.getOperator() instanceof FieldOperator operator)
                || JsonUtils.isPrimaryKeyPath(operator.getField())) {
            return null;
        }
        final var bound = AggregationOperationHelper.sortBound(steps, 0);
        if (bound <= 0 || !locks.holdsCollectionLock(dbName, collName)) {
            return null;
        }
        final var ids = FilterOperatorHelper.indexMatchingIds(operator, dbName, collName, context);
        if (ids == null) {
            return null;
        }
        final var fieldName = operator.getField();
        final var test = FieldPredicateFactory.getTester(operator, operator.getFieldOperatorType());
        return new IndexedSource.Fast(
                context.watch(SortOperatorHelper.fetchInOrder(ids.iterator(), dbName, collName, bound, context)
                        .filter(document -> test.test(document, fieldName))),
                1);
    }
}
