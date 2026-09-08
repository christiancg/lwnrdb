package org.techhouse.simplejs.exceptions;

import java.util.List;

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
