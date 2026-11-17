package org.techhouse.ops.tx;

import java.io.IOException;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.data.Transaction;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.PipelineContext;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;

final class ConditionMatches {
    private ConditionMatches() {
    }

    static List<JsonObject> of(String dbName, String collName, BaseOperator condition, Transaction transaction)
            throws IOException {
        final var filter = new AggregateRequest(dbName, collName);
        filter.setAggregationSteps(List.of(new FilterAggregationStep(condition)));
        final var overlay = transaction.overlayFor(Cache.getCollectionIdentifier(dbName, collName));
        try (var context = PipelineContext.fromConfiguration(null)) {
            return overlay == null || overlay.isEmpty()
                    ? AggregationOperationHelper.processAggregation(filter, null, transaction, context)
                    : OverlayRead.runPipeline(filter, transaction, context);
        }
    }
}
