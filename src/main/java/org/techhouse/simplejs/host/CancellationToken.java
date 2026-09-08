package org.techhouse.simplejs.host;

@FunctionalInterface
public interface CancellationToken {
    boolean isCancelled();
}
