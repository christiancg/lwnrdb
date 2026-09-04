package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonBaseElement;
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
        super(OperationType.CALL_PROCEDURE, message, errorCode);
        this.logs = logs;
        this.logsTruncated = logsTruncated;
        this.runId = runId;
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

    public void setRunId(String runId) {
        this.runId = runId;
    }
}
