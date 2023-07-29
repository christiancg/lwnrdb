package org.techhouse.ops.resp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

@Getter
@Setter(AccessLevel.PROTECTED)
@RequiredArgsConstructor
public class OperationResponse {
    private final OperationType type;
    private final OperationStatus status;
    private final String message;
}
