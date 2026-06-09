package org.techhouse.ops.resp;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

import java.util.List;

public class AggregateResponse extends OperationResponse {
    public List<JsonObject> results;

    public AggregateResponse(OperationStatus status, String message, List<JsonObject> results) {
        super(OperationType.AGGREGATE, status, message);
        this.results = results;
    }

    public List<JsonObject> getResults() {
        return results;
    }

    public void setResults(List<JsonObject> results) {
        this.results = results;
    }
}
