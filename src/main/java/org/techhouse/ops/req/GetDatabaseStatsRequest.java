package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class GetDatabaseStatsRequest extends OperationRequest {
    public GetDatabaseStatsRequest() {
        super(OperationType.GET_DATABASE_STATS, null, null);
    }
}
