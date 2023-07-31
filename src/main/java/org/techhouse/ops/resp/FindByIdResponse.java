package org.techhouse.ops.resp;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

import java.util.List;

@Getter
@Setter
public class FindByIdResponse extends OperationResponse {
    public List<String> results;
    public FindByIdResponse(OperationStatus status, String message) {
        super(OperationType.FIND_BY_ID, status, message);
    }
}
