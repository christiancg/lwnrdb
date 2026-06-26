package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class AggregateResponse extends OperationResponse {
    public List<JsonObject> results;

    public AggregateResponse(String message, List<JsonObject> results) {
        super(OperationType.AGGREGATE, OperationStatus.OK, message);
        this.results = results;
    }

    public List<JsonObject> getResults() {
        return results;
    }

    public void setResults(List<JsonObject> results) {
        this.results = results;
    }
}
