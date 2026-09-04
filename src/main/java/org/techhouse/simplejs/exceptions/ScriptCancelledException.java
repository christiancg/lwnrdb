package org.techhouse.simplejs.exceptions;

/**
 * Thrown when the host cancels a run. Extending {@link ScriptAbortException} is the whole point: the
 * interpreter neither lets user {@code try}/{@code catch} catch it nor runs {@code finally} blocks or
 * {@code using} disposers on the way out, so a script cannot trap its own cancellation.
 */
public class ScriptCancelledException extends ScriptAbortException {
    public ScriptCancelledException(String message) {
        super(message);
    }
}
