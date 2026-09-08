package org.techhouse.simplejs.exceptions;

public class ScriptTimeoutException extends ScriptAbortException {
    public ScriptTimeoutException(String message) {
        super(message);
    }
}
