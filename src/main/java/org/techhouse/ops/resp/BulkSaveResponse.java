package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

import java.util.List;

@Getter
@Setter
public class BulkSaveResponse extends OperationResponse {
    public List<String> inserted;
    public List<String> updated;
    public BulkSaveResponse(OperationStatus status, String message, List<String> inserted, List<String> updated) {
        super(OperationType.BULK_SAVE, status, message);
        this.inserted = inserted;
        this.updated = updated;
    }
}
