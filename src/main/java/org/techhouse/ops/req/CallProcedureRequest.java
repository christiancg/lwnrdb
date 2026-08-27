package org.techhouse.ops.req;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

public class CallProcedureRequest extends OperationRequest {
    private String procedureName;
    private JsonObject args;

    public CallProcedureRequest() {
        super(OperationType.CALL_PROCEDURE, null, null);
    }

    public CallProcedureRequest(String databaseName, String procedureName, JsonObject args) {
        super(OperationType.CALL_PROCEDURE, databaseName, null);
        this.procedureName = procedureName;
        this.args = args;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public void setProcedureName(String procedureName) {
        this.procedureName = procedureName;
    }

    public JsonObject getArgs() {
        return args == null ? new JsonObject() : args;
    }

    public void setArgs(JsonObject args) {
        this.args = args;
    }
}
