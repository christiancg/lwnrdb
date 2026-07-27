package org.techhouse.simplejs.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.ScriptTimeoutException;
import org.techhouse.simplejs.values.JsPromise;

public final class EventLoop {
    private static final class Timer {
        private final long id;
        private long dueNanos;
        private final long intervalNanos;
        private final boolean repeat;
        private final long seq;
        private final Runnable callback;

        private Timer(long id, long dueNanos, long intervalNanos, boolean repeat, long seq, Runnable callback) {
            this.id = id;
            this.dueNanos = dueNanos;
            this.intervalNanos = intervalNanos;
            this.repeat = repeat;
            this.seq = seq;
            this.callback = callback;
        }

        private long due() {
            return dueNanos;
        }

        private long seq() {
            return seq;
        }
    }

    private final ArrayDeque<Runnable> microtasks = new ArrayDeque<>();
    private final PriorityQueue<Timer> timers = new PriorityQueue<>(
            Comparator.comparingLong(Timer::due).thenComparingLong(Timer::seq));
    private final Set<Long> cancelled = new HashSet<>();
    private final List<JsPromise> promises = new ArrayList<>();
    private long nextTimerId = 1;
    private long nextSeq;

    public void queueMicrotask(Runnable task) {
        microtasks.add(task);
    }

    public void registerPromise(JsPromise promise) {
        promises.add(promise);
    }

    public List<JsPromise> promises() {
        return promises;
    }

    public long setTimer(Runnable callback, long delayMillis, boolean repeat) {
        final var delay = Math.max(0, delayMillis);
        final var intervalNanos = delay * 1_000_000L;
        final var id = nextTimerId++;
        timers.add(new Timer(id, System.nanoTime() + intervalNanos, intervalNanos, repeat, nextSeq++, callback));
        return id;
    }

    public void clearTimer(long id) {
        cancelled.add(id);
    }

    public void drain() {
        drain(-1);
    }

    public void drain(long deadlineNanos) {
        while (true) {
            while (!microtasks.isEmpty()) {
                microtasks.poll().run();
            }
            final var timer = pollNextLiveTimer();
            if (timer == null) {
                return;
            }
            awaitUntil(timer.dueNanos, deadlineNanos);
            if (timer.repeat) {
                timer.dueNanos = System.nanoTime() + timer.intervalNanos;
                timers.add(timer);
            }
            try {
                timer.callback.run();
            } catch (JsThrowException ignored) {
                // an uncaught throw in a timer callback does not abort the script,
                // mirroring the engine's unhandled-rejection policy
            }
        }
    }

    private Timer pollNextLiveTimer() {
        var timer = timers.poll();
        while (timer != null && cancelled.remove(timer.id)) {
            timer = timers.poll();
        }
        return timer;
    }

    private void awaitUntil(long dueNanos, long deadlineNanos) {
        final var target = deadlineNanos >= 0 ? Math.min(dueNanos, deadlineNanos) : dueNanos;
        long now;
        while ((now = System.nanoTime()) < target) {
            LockSupport.parkNanos(target - now);
        }
        if (deadlineNanos >= 0 && dueNanos > deadlineNanos) {
            throw new ScriptTimeoutException("Script exceeded its time limit");
        }
    }
}
