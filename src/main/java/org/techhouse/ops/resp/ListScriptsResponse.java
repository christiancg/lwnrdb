package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ListScriptsResponse extends OperationResponse {
    private final List<JsonObject> scripts;

    public ListScriptsResponse(String message, List<JsonObject> scripts) {
        super(OperationType.LIST_SCRIPTS, OperationStatus.OK, message);
        this.scripts = scripts;
    }

    public List<JsonObject> getScripts() {
        return scripts;
    }
}
