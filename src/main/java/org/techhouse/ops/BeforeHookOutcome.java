package org.techhouse.ops;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.resp.OperationResponse;

/**
 * What a before trigger decided about one document: the document to write (the original when the hook
 * accepted it, the hook's own when it replaced it), or the response that refuses the write.
 *
 * <p>
 * A refusal is carried rather than thrown because the write handlers already return an
 * {@code OperationResponse} from inside their locked block, so returning one keeps the lock's scope and
 * the handler's shape unchanged.
 */
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
