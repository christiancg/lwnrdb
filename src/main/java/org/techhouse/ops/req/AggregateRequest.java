package org.techhouse.ops.req;

import java.util.List;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public class AggregateRequest extends OperationRequest {
    private List<BaseAggregationStep> aggregationSteps;
    // Opt-in explain/analyze: when true the response carries an analyzeResult object describing how
    // the query ran (timing, index usage, documents scanned, locks acquired) plus suggestions.
    // Defaults to false, in which case the response is unchanged.
    private boolean analyze;

    public AggregateRequest(String databaseName, String collectionName) {
        super(OperationType.AGGREGATE, databaseName, collectionName);
    }

    public List<BaseAggregationStep> getAggregationSteps() {
        return aggregationSteps;
    }

    public void setAggregationSteps(List<BaseAggregationStep> aggregationSteps) {
        this.aggregationSteps = aggregationSteps;
    }

    public boolean isAnalyze() {
        return analyze;
    }

    public void setAnalyze(boolean analyze) {
        this.analyze = analyze;
    }
}
