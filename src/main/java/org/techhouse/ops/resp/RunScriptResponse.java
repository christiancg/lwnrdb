package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

// Unlike the other response subclasses this one also models a failure: SimpleJs captures console
// output on every exit path, and a failed run is exactly when those lines matter to the caller.
public class RunScriptResponse extends OperationResponse {
    private JsonBaseElement result;
    private List<String> logs;
    private boolean logsTruncated;

    public RunScriptResponse(String message, JsonBaseElement result, List<String> logs, boolean logsTruncated) {
        super(OperationType.RUN_SCRIPT, OperationStatus.OK, message);
        this.result = result;
        this.logs = logs;
        this.logsTruncated = logsTruncated;
    }

    public RunScriptResponse(String message, ErrorCode errorCode, List<String> logs, boolean logsTruncated) {
        super(OperationType.RUN_SCRIPT, message, errorCode);
        this.logs = logs;
        this.logsTruncated = logsTruncated;
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
}
