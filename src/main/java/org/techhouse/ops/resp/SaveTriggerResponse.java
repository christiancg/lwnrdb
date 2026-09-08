package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class SaveTriggerResponse extends OperationResponse {
    private final long version;
    private final String definer;

    public SaveTriggerResponse(String message, long version, String definer) {
        super(OperationType.SAVE_TRIGGER, OperationStatus.OK, message);
        this.version = version;
        this.definer = definer;
    }

    public long getVersion() {
        return version;
    }

    public String getDefiner() {
        return definer;
    }
}
