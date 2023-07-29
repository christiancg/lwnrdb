package org.techhouse.ops.req;

import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

import java.util.List;

@Getter
@Setter
public class AggregateRequest extends OperationRequest {
    public List<JsonObject> aggregationSteps;
    public AggregateRequest(String databaseName, String collectionName) {
        super(OperationType.AGGREGATE, databaseName, collectionName);
    }
}
