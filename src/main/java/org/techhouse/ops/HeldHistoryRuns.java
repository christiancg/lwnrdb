package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.config.Globals;

final class HeldHistoryRuns {
    private static final int MAX_HELD_ROWS = 256;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, List<ScriptRunHistoryEvent>> heldByDatabase = new HashMap<>();
    private final Set<ScriptRunHistoryEvent> carriers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicInteger rows = new AtomicInteger();

    private enum Held {
        NO, YES, FIRST
    }

    boolean hold(ScriptRunHistoryEvent event, ScheduledExecutorService scheduler,
            Consumer<ScriptRunHistoryEvent> resubmit) {
        final var dbName = event.getRecord().database();
        if (scheduler == null || dbName == null || event.getRetries() > 0) {
            return false;
        }
        final var held = enqueue(dbName, event);
        if (held == Held.FIRST) {
            try {
                scheduler.schedule(() -> release(dbName, resubmit), Globals.SCRIPT_HISTORY_COALESCE_MS,
                        TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException stopping) {
                release(dbName, resubmit);
            }
        }
        return held != Held.NO;
    }

    private Held enqueue(String dbName, ScriptRunHistoryEvent event) {
        lock.lock();
        try {
            if (carriers.remove(event)) {
                return Held.NO;
            }
            final var waiting = heldByDatabase.get(dbName);
            if (waiting != null && waiting.size() >= MAX_HELD_ROWS) {
                return Held.NO;
            }
            heldByDatabase.computeIfAbsent(dbName, _ -> new ArrayList<>()).add(event);
            rows.incrementAndGet();
            return waiting == null ? Held.FIRST : Held.YES;
        } finally {
            lock.unlock();
        }
    }

    private void release(String dbName, Consumer<ScriptRunHistoryEvent> resubmit) {
        final var carrier = takeCarrier(dbName);
        if (carrier != null) {
            resubmit.accept(carrier);
        }
    }

    private ScriptRunHistoryEvent takeCarrier(String dbName) {
        lock.lock();
        try {
            final var waiting = heldByDatabase.get(dbName);
            if (waiting == null) {
                return null;
            }
            if (waiting.isEmpty()) {
                heldByDatabase.remove(dbName);
                return null;
            }
            final var carrier = waiting.removeFirst();
            rows.decrementAndGet();
            carriers.add(carrier);
            return carrier;
        } finally {
            lock.unlock();
        }
    }

    List<ScriptRunHistoryEvent> takeWith(List<ScriptRunHistoryEvent> batch) {
        final var dbName = batch.getFirst().getRecord().database();
        lock.lock();
        try {
            for (final var event : batch) {
                carriers.remove(event);
            }
            final var waiting = dbName == null ? null : heldByDatabase.remove(dbName);
            if (waiting == null || waiting.isEmpty()) {
                return batch;
            }
            rows.addAndGet(-waiting.size());
            final var merged = new ArrayList<ScriptRunHistoryEvent>(waiting.size() + batch.size());
            merged.addAll(waiting);
            merged.addAll(batch);
            return merged;
        } finally {
            lock.unlock();
        }
    }

    List<List<ScriptRunHistoryEvent>> takeAll() {
        lock.lock();
        try {
            final var taken = new ArrayList<List<ScriptRunHistoryEvent>>();
            for (final var waiting : heldByDatabase.values()) {
                if (!waiting.isEmpty()) {
                    taken.add(waiting);
                }
            }
            heldByDatabase.clear();
            carriers.clear();
            rows.set(0);
            return taken;
        } finally {
            lock.unlock();
        }
    }

    int rows() {
        return rows.get();
    }
}
