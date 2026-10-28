package org.techhouse.ops.resp;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class GetSchemaResponse extends OperationResponse {
    private final JsonObject schema;

    public GetSchemaResponse(String message, JsonObject schema) {
        super(OperationType.GET_SCHEMA, OperationStatus.OK, message);
        this.schema = schema;
    }

    public JsonObject getSchema() {
        return schema;
    }
}
