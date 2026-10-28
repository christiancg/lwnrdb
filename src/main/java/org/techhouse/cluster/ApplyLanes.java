package org.techhouse.cluster;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.log.Logger;

final class ApplyLanes {
    private static final Logger logger = Logger.logFor(ApplyLanes.class);
    private final Executor executor;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, ArrayDeque<Runnable>> lanes = new HashMap<>();

    ApplyLanes(Executor executor) {
        this.executor = executor;
    }

    void submit(String key, Runnable task) {
        lock.lock();
        try {
            final var queued = lanes.get(key);
            if (queued != null) {
                queued.add(task);
                return;
            }
            final var lane = new ArrayDeque<Runnable>();
            lane.add(task);
            lanes.put(key, lane);
        } finally {
            lock.unlock();
        }
        executor.execute(() -> drain(key));
    }

    private void drain(String key) {
        var task = next(key);
        while (task != null) {
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                logger.error("A replicated apply on lane " + key + " failed; the lane goes on with the next one", e);
            }
            task = next(key);
        }
    }

    private Runnable next(String key) {
        lock.lock();
        try {
            final var lane = lanes.get(key);
            final var task = lane.poll();
            if (task == null) {
                lanes.remove(key);
                return null;
            }
            return task;
        } finally {
            lock.unlock();
        }
    }

    int activeLanes() {
        lock.lock();
        try {
            return lanes.size();
        } finally {
            lock.unlock();
        }
    }
}
