package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

import java.util.List;

@Getter
@Setter
public class FindResponse extends OperationResponse {
    public List<String> results;
    public FindResponse(OperationStatus status, String message) {
        super(OperationType.FIND, status, message);
    }
}
