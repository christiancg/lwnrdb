package org.techhouse.cluster;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cluster.msg.ClusterMessage;

public class ClusterRpc {
    private final Map<String, CompletableFuture<ClusterMessage>> pending = new ConcurrentHashMap<>();

    public String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    public CompletableFuture<ClusterMessage> register(String correlationId) {
        final var future = new CompletableFuture<ClusterMessage>();
        pending.put(correlationId, future);
        return future;
    }

    public boolean complete(String correlationId, ClusterMessage message) {
        final var future = pending.remove(correlationId);
        if (future != null) {
            future.complete(message);
            return true;
        }
        return false;
    }

    public void fail(String correlationId, Throwable throwable) {
        final var future = pending.remove(correlationId);
        if (future != null) {
            future.completeExceptionally(throwable);
        }
    }

    public void failAll(Throwable throwable) {
        for (var entry : Map.copyOf(pending).entrySet()) {
            fail(entry.getKey(), throwable);
        }
    }

    public int pendingCount() {
        return pending.size();
    }
}
