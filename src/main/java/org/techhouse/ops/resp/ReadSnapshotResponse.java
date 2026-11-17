package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.JsonSizeEstimator;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ReadSnapshotResponse extends OperationResponse {
    private final List<JsonObject> results;
    private final String lastId;
    private final boolean done;

    public ReadSnapshotResponse(String message, List<JsonObject> results, String lastId, boolean done) {
        super(OperationType.READ_SNAPSHOT, OperationStatus.OK, message);
        this.results = results;
        this.lastId = lastId;
        this.done = done;
    }

    public List<JsonObject> getResults() {
        return results;
    }

    public String getLastId() {
        return lastId;
    }

    public boolean isDone() {
        return done;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(results));
    }
}
