package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class TestTriggerResponse extends OperationResponse {
    public static final String DECISION_ACCEPT = "accept";
    public static final String DECISION_REPLACE = "replace";
    public static final String DECISION_REJECT = "reject";

    private final String decision;
    private final JsonObject document;
    private final String reason;
    private final List<String> logs;
    private final boolean logsTruncated;
    private final List<String> stack;

    public TestTriggerResponse(String message, String decision, JsonObject document, String reason, List<String> logs,
            boolean logsTruncated, List<String> stack) {
        super(OperationType.TEST_TRIGGER, OperationStatus.OK, message);
        this.decision = decision;
        this.document = document;
        this.reason = reason;
        this.logs = logs;
        this.logsTruncated = logsTruncated;
        this.stack = stack;
    }

    public String getDecision() {
        return decision;
    }

    public JsonObject getDocument() {
        return document;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getLogs() {
        return logs;
    }

    public boolean isLogsTruncated() {
        return logsTruncated;
    }

    public List<String> getStack() {
        return stack;
    }
}
