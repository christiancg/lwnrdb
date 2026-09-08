package org.techhouse.ops;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.resp.OperationResponse;

public record BeforeHookOutcome(JsonObject document, OperationResponse rejection) {
    public static BeforeHookOutcome accepted(JsonObject document) {
        return new BeforeHookOutcome(document, null);
    }

    public static BeforeHookOutcome rejected(OperationResponse response) {
        return new BeforeHookOutcome(null, response);
    }

    public boolean isRejected() {
        return rejection != null;
    }
}
