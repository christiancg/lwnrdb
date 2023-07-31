package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class CloseConnectionResponse extends OperationResponse {
    private static final String CLOSE_CONNECTION_MESSAGE = "Bye!";
    public CloseConnectionResponse() {
        super(OperationType.CLOSE_CONNECTION, OperationStatus.OK, CLOSE_CONNECTION_MESSAGE);
    }
}
