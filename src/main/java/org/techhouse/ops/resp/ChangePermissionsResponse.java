package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ChangePermissionsResponse extends OperationResponse {
    public ChangePermissionsResponse(String message) {
        super(OperationType.CHANGE_PERMISSIONS, OperationStatus.OK, message);
    }
}
