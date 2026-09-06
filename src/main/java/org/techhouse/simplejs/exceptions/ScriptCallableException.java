package org.techhouse.simplejs.exceptions;

import java.util.List;

/**
 * The single failure a {@code ScriptCallable} reports. A callable is invoked once per document from inside a
 * pipeline step, where a {@code ScriptResult} would have to be unpacked on every row; carrying the error name
 * and message on the exception keeps the hot path a plain value return and lets the caller map the name to a
 * wire error code exactly as {@code ScriptOperationHelper} maps a {@code ScriptResult}'s.
 */
public class ScriptCallableException extends RuntimeException {
    private final String errorName;
    private final transient List<String> errorStack;

    public ScriptCallableException(String errorName, String message) {
        this(errorName, message, null);
    }

    public ScriptCallableException(String errorName, String message, List<String> errorStack) {
        super(message);
        this.errorName = errorName;
        this.errorStack = errorStack;
    }

    public String getErrorName() {
        return errorName;
    }

    public List<String> getErrorStack() {
        return errorStack;
    }
}
