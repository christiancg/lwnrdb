package org.techhouse.simplejs.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Intrinsics;
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
    private final ConcurrentLinkedQueue<Runnable> asyncCompletions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingAsyncJobs = new AtomicInteger();
    private volatile Thread drainThread;
    private long nextTimerId = 1;
    private long nextSeq;
    private InterpreterOps ops;
    private Intrinsics intrinsics;

    // Wired once by the owning Interpreter so values like JsPromise (constructed all over the
    // builtins/internal layers, never with direct interpreter access) can call back into it for
    // duck-typed member access (e.g. thenable assimilation) without threading these through every
    // call site.
    public void wireInterpreter(InterpreterOps ops, Intrinsics intrinsics) {
        this.ops = ops;
        this.intrinsics = intrinsics;
    }

    public InterpreterOps ops() {
        return ops;
    }

    public Intrinsics intrinsics() {
        return intrinsics;
    }

    public void queueMicrotask(Runnable task) {
        microtasks.add(task);
    }

    // Off-thread async work (e.g. fetch): beginAsyncJob keeps the loop alive while the work runs on
    // another thread; completeAsyncJob hands a settlement back to the loop thread and wakes it.
    public void beginAsyncJob() {
        pendingAsyncJobs.incrementAndGet();
    }

    public void completeAsyncJob(Runnable onLoopThread) {
        asyncCompletions.add(onLoopThread);
        pendingAsyncJobs.decrementAndGet();
        final var thread = drainThread;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
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
        drainThread = Thread.currentThread();
        try {
            drainLoop(deadlineNanos);
        } finally {
            drainThread = null;
        }
    }

    private void drainLoop(long deadlineNanos) {
        while (true) {
            while (!microtasks.isEmpty()) {
                microtasks.poll().run();
            }
            final var completion = asyncCompletions.poll();
            if (completion != null) {
                completion.run();
                continue;
            }
            final var timer = pollNextLiveTimer();
            if (timer == null) {
                if (pendingAsyncJobs.get() > 0) {
                    awaitAsyncCompletion(deadlineNanos);
                    continue;
                }
                // An async job may have completed (queuing its completion and decrementing the
                // counter) between the poll() above and the counter check just now; re-check the
                // queue before giving up so that completion is not silently dropped.
                if (!asyncCompletions.isEmpty()) {
                    continue;
                }
                return;
            }
            if (!awaitUntil(timer.dueNanos, deadlineNanos)) {
                timers.add(timer);
                continue;
            }
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

    private void awaitAsyncCompletion(long deadlineNanos) {
        while (asyncCompletions.isEmpty()) {
            if (deadlineNanos >= 0) {
                final var remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new ScriptTimeoutException("Script exceeded its time limit");
                }
                LockSupport.parkNanos(remaining);
            } else {
                LockSupport.park();
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

    // Returns true once the timer's due time is reached; false if an async completion arrived first
    // (so the caller re-queues the timer and processes the completion).
    private boolean awaitUntil(long dueNanos, long deadlineNanos) {
        final var target = deadlineNanos >= 0 ? Math.min(dueNanos, deadlineNanos) : dueNanos;
        long now;
        while ((now = System.nanoTime()) < target) {
            if (!asyncCompletions.isEmpty()) {
                return false;
            }
            LockSupport.parkNanos(target - now);
        }
        if (deadlineNanos >= 0 && dueNanos > deadlineNanos) {
            throw new ScriptTimeoutException("Script exceeded its time limit");
        }
        return true;
    }
}
