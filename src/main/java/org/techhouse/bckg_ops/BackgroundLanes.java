package org.techhouse.bckg_ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import org.techhouse.bckg_ops.events.CollectionScopedEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;

public final class BackgroundLanes {
    private final List<LinkedBlockingQueue<Event>> queues;
    private final AtomicLongArray lastServed;

    public BackgroundLanes(int count) {
        final var lanes = Math.max(1, count);
        queues = new ArrayList<>(lanes);
        for (var lane = 0; lane < lanes; lane++) {
            queues.add(new LinkedBlockingQueue<>());
        }
        lastServed = new AtomicLongArray(lanes);
        final var created = System.nanoTime();
        for (var lane = 0; lane < lanes; lane++) {
            lastServed.set(lane, created);
        }
    }

    public int count() {
        return queues.size();
    }

    public int laneOf(Event event) {
        final var collection = switch (event) {
            case CollectionScopedEvent scoped ->
                Cache.getCollectionIdentifier(scoped.getDbName(), scoped.getCollName());
            case ScriptRunHistoryEvent history when history.getRecord() != null ->
                Cache.getCollectionIdentifier(history.getRecord().database(), Globals.SCRIPT_RUNS_COLLECTION_NAME);
            default -> null;
        };
        return collection == null ? 0 : Math.floorMod(collection.hashCode(), queues.size());
    }

    public void add(Event event) {
        queues.get(laneOf(event)).add(event);
    }

    public void addAll(Collection<Event> events) {
        for (final var event : events) {
            add(event);
        }
    }

    public boolean isEmpty() {
        for (final var queue : queues) {
            if (!queue.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public int discardAll() {
        final var discarded = new ArrayList<Event>();
        for (final var queue : queues) {
            queue.drainTo(discarded);
        }
        return discarded.size();
    }

    Event poll(int lane) throws InterruptedException {
        final var event = queues.get(lane).poll(Globals.BACKGROUND_STEAL_AFTER_MS, TimeUnit.MILLISECONDS);
        lastServed.set(lane, System.nanoTime());
        return event;
    }

    void fill(int lane, List<Event> batch, int maxBatch) {
        final var queue = queues.get(lane);
        while (batch.size() < maxBatch) {
            final var next = queue.poll();
            if (next == null) {
                return;
            }
            batch.add(next);
        }
    }

    Event stealFor(int ownLane, List<Event> batch, int maxBatch) {
        final var now = System.nanoTime();
        final var unserved = TimeUnit.MILLISECONDS.toNanos(Globals.BACKGROUND_STEAL_AFTER_MS);
        for (var offset = 1; offset < queues.size(); offset++) {
            final var lane = (ownLane + offset) % queues.size();
            if (queues.get(lane).isEmpty() || now - lastServed.get(lane) < unserved) {
                continue;
            }
            final var first = queues.get(lane).poll();
            if (first != null) {
                lastServed.set(lane, now);
                batch.add(first);
                fill(lane, batch, maxBatch);
                return first;
            }
        }
        return null;
    }
}
