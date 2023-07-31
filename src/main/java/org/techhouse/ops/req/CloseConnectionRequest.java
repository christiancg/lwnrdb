package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter
public class CloseConnectionRequest extends OperationRequest {
    public CloseConnectionRequest() {
        super(OperationType.CLOSE_CONNECTION, null, null);
    }
}
