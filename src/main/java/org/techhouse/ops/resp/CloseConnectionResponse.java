package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.config.Globals;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class CloseConnectionResponse extends OperationResponse {
    public CloseConnectionResponse() {
        super(OperationType.CLOSE_CONNECTION, OperationStatus.OK, Globals.CLOSE_CONNECTION_MESSAGE);
    }
}
