package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ListProceduresResponse extends OperationResponse {
    private final List<JsonObject> procedures;

    public ListProceduresResponse(String message, List<JsonObject> procedures) {
        super(OperationType.LIST_PROCEDURES, OperationStatus.OK, message);
        this.procedures = procedures;
    }

    public List<JsonObject> getProcedures() {
        return procedures;
    }
}
