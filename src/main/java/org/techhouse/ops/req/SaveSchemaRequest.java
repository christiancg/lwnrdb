package org.techhouse.ops.req;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

public class SaveSchemaRequest extends OperationRequest {
    private JsonObject schema;

    public SaveSchemaRequest(String databaseName, String collectionName, JsonObject schema) {
        super(OperationType.SAVE_SCHEMA, databaseName, collectionName);
        this.schema = schema;
    }

    public JsonObject getSchema() {
        return schema;
    }

    public void setSchema(JsonObject schema) {
        this.schema = schema;
    }
}
