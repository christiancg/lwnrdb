package org.techhouse.simplejs.host;

/**
 * Asked by the interpreter whether the run it is executing has been cancelled. Polled at the same points the
 * instruction budget and the wall clock are checked, so cancellation is cooperative: a run blocked in a host
 * call ends at the next tick or event-loop poll, not instantly.
 */
@FunctionalInterface
public interface CancellationToken {
    boolean isCancelled();
}
