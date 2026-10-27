package org.techhouse.listen;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.ops.req.AggregateRequest;

final class ListenGroup {
    private static final int IDLE = 0;
    private static final int RUNNING = 1;
    private static final int RERUN = 2;

    private final String key;
    private final UUID id = UUID.randomUUID();
    private final AggregateRequest request;
    private final Set<String> collectionKeys;
    private final boolean ordered;
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();
    private final AtomicInteger state = new AtomicInteger(IDLE);

    ListenGroup(String key, AggregateRequest request, Set<String> collectionKeys, boolean ordered) {
        this.key = key;
        this.request = request;
        this.collectionKeys = collectionKeys;
        this.ordered = ordered;
    }

    String key() {
        return key;
    }

    UUID id() {
        return id;
    }

    AggregateRequest request() {
        return request;
    }

    Set<String> collectionKeys() {
        return collectionKeys;
    }

    boolean ordered() {
        return ordered;
    }

    Set<UUID> members() {
        return members;
    }

    boolean tryStartRun() {
        while (true) {
            final var current = state.get();
            if (current == IDLE && state.compareAndSet(IDLE, RUNNING)) {
                return true;
            }
            if (current == RERUN || current == RUNNING && state.compareAndSet(RUNNING, RERUN)) {
                return false;
            }
        }
    }

    boolean finishRun() {
        while (true) {
            if (state.compareAndSet(RUNNING, IDLE)) {
                return false;
            }
            if (state.compareAndSet(RERUN, IDLE)) {
                return true;
            }
        }
    }
}
