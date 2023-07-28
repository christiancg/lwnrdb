package org.techhouse.ops.req;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.techhouse.ops.OperationType;

@Getter
@Setter(AccessLevel.PROTECTED)
@RequiredArgsConstructor
public abstract class OperationRequest {
    private final OperationType type;
    private final String databaseName;
    private final String collectionName;
}
