package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

// Models a failure as well as a success, for the reason RunScriptResponse does: SimpleJs captures console
// output on every exit path, and a failed call is exactly when those lines matter to the caller.
public class CallProcedureResponse extends OperationResponse {
    private JsonBaseElement result;
    private List<String> logs;
    private boolean logsTruncated;
    private String runId;
    private List<String> stack;
    private JsonObject metrics;

    public CallProcedureResponse(String message, JsonBaseElement result, List<String> logs, boolean logsTruncated,
            String runId) {
        super(OperationType.CALL_PROCEDURE, OperationStatus.OK, message);
        this.result = result;
        this.logs = logs;
        this.logsTruncated = logsTruncated;
        this.runId = runId;
    }

    public CallProcedureResponse(String message, ErrorCode errorCode, List<String> logs, boolean logsTruncated,
            String runId) {
        this(message, errorCode, logs, logsTruncated, runId, null);
    }

    public CallProcedureResponse(String message, ErrorCode errorCode, List<String> logs, boolean logsTruncated,
            String runId, List<String> stack) {
        super(OperationType.CALL_PROCEDURE, message, errorCode);
        this.logs = logs;
        this.logsTruncated = logsTruncated;
        this.runId = runId;
        this.stack = stack;
    }

    public JsonBaseElement getResult() {
        return result;
    }

    public void setResult(JsonBaseElement result) {
        this.result = result;
    }

    public List<String> getLogs() {
        return logs;
    }

    public void setLogs(List<String> logs) {
        this.logs = logs;
    }

    public boolean isLogsTruncated() {
        return logsTruncated;
    }

    public void setLogsTruncated(boolean logsTruncated) {
        this.logsTruncated = logsTruncated;
    }

    public String getRunId() {
        return runId;
    }

    public JsonObject getMetrics() {
        return metrics;
    }

    public void setMetrics(JsonObject metrics) {
        this.metrics = metrics;
    }

    public List<String> getStack() {
        return stack;
    }

    public void setStack(List<String> stack) {
        this.stack = stack;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }
}
