package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ejson.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

import java.util.List;

@Getter
@Setter
public class AggregateResponse extends OperationResponse {
    public List<JsonObject> results;
    public AggregateResponse(OperationStatus status, String message, List<JsonObject> results) {
        super(OperationType.AGGREGATE, status, message);
        this.results = results;
    }
}
