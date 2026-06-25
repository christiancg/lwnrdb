package org.techhouse.ops.resp;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class GetDatabaseStatsResponse extends OperationResponse {
    private final JsonObject stats;

    public GetDatabaseStatsResponse(String message, JsonObject stats) {
        super(OperationType.GET_DATABASE_STATS, OperationStatus.OK, message);
        this.stats = stats;
    }

    public JsonObject getStats() {
        return stats;
    }
}
