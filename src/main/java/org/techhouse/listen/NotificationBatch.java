package org.techhouse.listen;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public final class NotificationBatch implements DirtySink, AutoCloseable {
    private final ListenManager manager;
    private final NotificationBatch root;
    private final Set<String> applying = new LinkedHashSet<>();
    private final Set<String> pending = new LinkedHashSet<>();
    private boolean closed;

    private NotificationBatch(ListenManager manager, NotificationBatch root) {
        this.manager = manager;
        this.root = root;
    }

    static NotificationBatch open(ListenManager manager, Collection<String> collectionKeys) {
        final var batch = new NotificationBatch(manager, null);
        batch.begin(collectionKeys);
        return batch;
    }

    public NotificationBatch nest(Collection<String> collectionKeys) {
        final var owner = root == null ? this : root;
        if (owner.closed) {
            return open(manager, collectionKeys);
        }
        owner.begin(collectionKeys);
        return new NotificationBatch(manager, owner);
    }

    private void begin(Collection<String> collectionKeys) {
        final var newlyApplying = new LinkedHashSet<>(collectionKeys);
        newlyApplying.removeAll(applying);
        applying.addAll(newlyApplying);
        manager.beginApply(newlyApplying);
    }

    @Override
    public void markDirty(String dbName, String collName) {
        if (root != null) {
            root.markDirty(dbName, collName);
        } else if (closed) {
            manager.markDirty(dbName, collName);
        } else {
            pending.add(ListenManager.keyOf(dbName, collName));
        }
    }

    @Override
    public void close() {
        if (root != null || closed) {
            return;
        }
        closed = true;
        manager.endApply(applying);
        for (final var key : pending) {
            manager.enqueueDirty(key);
        }
        manager.releaseHeldBack();
    }
}
