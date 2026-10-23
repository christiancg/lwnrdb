package org.techhouse.ops;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.req.agg.BaseAggregationStep;

final class IndexedSource {
    private IndexedSource() {
    }

    record Fast(Stream<JsonObject> stream, int nextStepIndex) {
    }

    static Fast of(List<BaseAggregationStep> steps, String dbName, String collName, long boundAfterSecondStep,
            PipelineContext context) throws IOException {
        final var fastCount = CountOperatorHelper.tryIndexOnlyCount(steps, dbName, collName, context);
        if (fastCount != null) {
            return new Fast(Stream.of(fastCount.result()), fastCount.nextStepIndex());
        }
        return SortOperatorHelper.tryIndexedRangeTopK(steps, dbName, collName, boundAfterSecondStep, context);
    }
}
