package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.analyze.AnalyzeResult;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

/**
 * AGGREGATE response variant used only when {@code analyze=true}. It mirrors
 * {@link AggregateResponse} but adds the {@code analyzeResult} object. A dedicated subclass (rather
 * than a nullable field on {@link AggregateResponse}) is required because the EJson reflection
 * serializer emits every field, including nulls — so a nullable field would leak {@code analyzeResult}
 * onto every aggregation response.
 */
public class AggregateAnalyzeResponse extends OperationResponse {
    public List<JsonObject> results;
    public AnalyzeResult analyzeResult;

    public AggregateAnalyzeResponse(String message, List<JsonObject> results, AnalyzeResult analyzeResult) {
        super(OperationType.AGGREGATE, OperationStatus.OK, message);
        this.results = results;
        this.analyzeResult = analyzeResult;
    }

    public List<JsonObject> getResults() {
        return results;
    }

    public void setResults(List<JsonObject> results) {
        this.results = results;
    }

    public AnalyzeResult getAnalyzeResult() {
        return analyzeResult;
    }

    public void setAnalyzeResult(AnalyzeResult analyzeResult) {
        this.analyzeResult = analyzeResult;
    }
}
