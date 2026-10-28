package org.techhouse.ops;

import java.util.function.Predicate;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.utils.RangeBounds;

record SingleRange(RangeBounds bounds, Predicate<JsonObject> matches) {
    static SingleRange of(BaseAggregationStep step) {
        if (!(step instanceof FilterAggregationStep filter)) {
            return null;
        }
        final var leaves = ConjunctionCandidates.singleRangeLeaves(filter.getOperator());
        if (leaves == null) {
            return null;
        }
        final var fieldName = leaves.getFirst().getField();
        final var everyLeaf = ConjunctionCandidates.everyLeaf(leaves);
        return new SingleRange(RangeBounds.fromLeaves(fieldName, leaves),
                document -> everyLeaf.test(document, fieldName));
    }
}
