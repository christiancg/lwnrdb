package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.analyze.AnalyzeResult;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

// A dedicated subclass rather than a nullable analyzeResult on AggregateResponse: the EJson reflection
// serializer emits every field including nulls, which would leak it onto every aggregation response.
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
