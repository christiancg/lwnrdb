package org.techhouse.simplejs.exceptions;

/**
 * The script's result is a promise that never settled. The event loop has already drained to quiescence by
 * the time the result contract is applied, so such a promise cannot settle later - reporting it is what
 * separates it from a script that deliberately produced null.
 */
public class ScriptPendingResultException extends SimpleJsRuntimeException {
    public ScriptPendingResultException(String message) {
        super(message);
    }
}
