package org.techhouse.simplejs.exceptions;

public class ScriptCancelledException extends ScriptAbortException {
    public ScriptCancelledException(String message) {
        super(message);
    }
}
