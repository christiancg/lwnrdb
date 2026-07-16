package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class SaveSchemaResponse extends OperationResponse {
    private final List<String> warnings;

    public SaveSchemaResponse(String message, List<String> warnings) {
        super(OperationType.SAVE_SCHEMA, OperationStatus.OK, message);
        this.warnings = warnings;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
