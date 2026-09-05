package org.techhouse.simplejs.host;

import java.util.List;
import org.techhouse.ejson.elements.JsonBaseElement;

public final class ScriptResult {
    private final JsonBaseElement value;
    private final String errorName;
    private final String errorMessage;
    private final boolean error;
    private final List<String> logs;
    private final boolean logsTruncated;
    private final List<String> errorStack;

    private ScriptResult(JsonBaseElement value, String errorName, String errorMessage, boolean error, List<String> logs,
            boolean logsTruncated, List<String> errorStack) {
        this.value = value;
        this.errorName = errorName;
        this.errorMessage = errorMessage;
        this.error = error;
        this.logs = logs == null ? List.of() : List.copyOf(logs);
        this.logsTruncated = logsTruncated;
        this.errorStack = errorStack == null ? null : List.copyOf(errorStack);
    }

    public static ScriptResult value(JsonBaseElement value) {
        return value(value, List.of(), false);
    }

    public static ScriptResult value(JsonBaseElement value, List<String> logs, boolean logsTruncated) {
        return new ScriptResult(value, null, null, false, logs, logsTruncated, null);
    }

    public static ScriptResult error(String name, String message) {
        return error(name, message, List.of(), false);
    }

    public static ScriptResult error(String name, String message, List<String> logs, boolean logsTruncated) {
        return new ScriptResult(null, name, message, true, logs, logsTruncated, null);
    }

    public static ScriptResult error(String name, String message, List<String> errorStack, List<String> logs,
            boolean logsTruncated) {
        return new ScriptResult(null, name, message, true, logs, logsTruncated, errorStack);
    }

    public boolean isError() {
        return error;
    }

    public JsonBaseElement getValue() {
        return value;
    }

    public String getErrorName() {
        return errorName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<String> getErrorStack() {
        return errorStack;
    }

    public List<String> getLogs() {
        return logs;
    }

    public boolean isLogsTruncated() {
        return logsTruncated;
    }
}
