package org.techhouse.simplejs.internal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class Coroutine {
    @FunctionalInterface
    public interface Body {
        JsValue run();
    }

    @FunctionalInterface
    public interface ResumeObserver {
        void afterResume(RuntimeException escaped);
    }

    public enum PauseReason {
        YIELD, AWAIT
    }

    public record StepResult(JsValue value, boolean done) {
    }

    // A `return` completion injected at a suspended yield. `yield*` catches it to forward the
    // completion to the inner iterator and re-raises it with the inner iterator's return value.
    public static final class ReturnSignal extends RuntimeException {
        private final transient JsValue value;

        public ReturnSignal(JsValue value) {
            super(null, null, false, false);
            this.value = value;
        }

        public JsValue value() {
            return value;
        }
    }

    private static final class CancelSignal extends RuntimeException {
        private CancelSignal() {
            super(null, null, false, false);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition turn = lock.newCondition();
    private boolean bodyTurn;
    // Claimed exactly while the body owns the turn. The only way the claim can fail is a re-entrant
    // drive from the body's own thread, which would otherwise block on a hand-off that can never come.
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean started;
    private volatile boolean done;

    private JsValue sent = JsUndefined.getInstance();
    private RuntimeException inject;
    private boolean injectReturn;
    private JsValue injectReturnValue = JsUndefined.getInstance();

    private JsValue yielded = JsUndefined.getInstance();
    private JsValue completed = JsUndefined.getInstance();
    private RuntimeException escaped;

    private Body body;
    private PauseReason pauseReason = PauseReason.YIELD;
    private volatile boolean delegatedYield;
    private ResumeObserver resumeObserver;
    private volatile boolean async;
    private volatile boolean yieldAllowed;

    public boolean isDone() {
        return done;
    }

    public boolean isAsync() {
        return async;
    }

    public void markAsync() {
        this.async = true;
    }

    public boolean isYieldAllowed() {
        return yieldAllowed;
    }

    public void markGenerator() {
        this.yieldAllowed = true;
    }

    public PauseReason pauseReason() {
        return pauseReason;
    }

    public JsValue yieldedValue() {
        return yielded;
    }

    public JsValue completedValue() {
        return completed;
    }

    public void setResumeObserver(ResumeObserver observer) {
        this.resumeObserver = observer;
    }

    public void prime(Body value) {
        this.body = value;
    }

    public void startAsync(Body value) {
        this.body = value;
        resume();
    }

    public StepResult resumeNext(JsValue value) {
        if (done) {
            return new StepResult(JsUndefined.getInstance(), true);
        }
        this.sent = value;
        this.inject = null;
        this.injectReturn = false;
        resume();
        return done ? new StepResult(completed, true) : new StepResult(yielded, false);
    }

    public StepResult resumeReturn(JsValue value) {
        if (done || !started) {
            done = true;
            return new StepResult(value, true);
        }
        this.injectReturn = true;
        this.injectReturnValue = value;
        this.inject = null;
        resume();
        return done ? new StepResult(completed, true) : new StepResult(yielded, false);
    }

    public StepResult resumeThrow(JsValue error) {
        if (done || !started) {
            done = true;
            throw new JsThrowException(error);
        }
        this.inject = new JsThrowException(error);
        this.injectReturn = false;
        resume();
        return done ? new StepResult(completed, true) : new StepResult(yielded, false);
    }

    public void cancel() {
        if (done || !started) {
            done = true;
            return;
        }
        this.inject = new CancelSignal();
        this.injectReturn = false;
        resume();
    }

    // `yield*` over an async iterator must hand the delegated value through untouched, while a plain
    // `yield x` in an async generator awaits its operand. The delegating step marks the coroutine so
    // the very next yieldOut is reported as delegated and the driver skips that await.
    public void markDelegatedYield() {
        this.delegatedYield = true;
    }

    public boolean isDelegatedYield() {
        return delegatedYield;
    }

    public JsValue yieldOut(JsValue value) {
        this.yielded = value;
        this.pauseReason = PauseReason.YIELD;
        final var result = pause();
        this.delegatedYield = false;
        return result;
    }

    public JsValue await(JsPromise promise) {
        this.pauseReason = PauseReason.AWAIT;
        this.delegatedYield = false;
        promise.subscribe(this::resumeValue, reason -> resumeError(new JsThrowException(reason)));
        return pause();
    }

    private void resumeValue(JsValue value) {
        this.sent = value;
        this.inject = null;
        this.injectReturn = false;
        resume();
    }

    private void resumeError(RuntimeException error) {
        this.inject = error;
        this.injectReturn = false;
        resume();
    }

    // The interpreter uses this to give a coroutine its own call-stack segment for the duration of a
    // resumption: a suspended generator's frames must not show up in the trace of whoever resumed it.
    @FunctionalInterface
    public interface AroundResume {
        void around(Runnable resume);
    }

    private AroundResume aroundResume;

    public void setAroundResume(AroundResume value) {
        this.aroundResume = value;
    }

    private void resume() {
        if (aroundResume == null) {
            resumeBody();
            return;
        }
        aroundResume.around(this::resumeBody);
    }

    private void resumeBody() {
        if (!running.compareAndSet(false, true)) {
            throw new TypeErrorException("Generator is already running");
        }
        if (!started) {
            started = true;
            Thread.ofVirtual().start(this::threadMain);
        }
        RuntimeException esc;
        lock.lock();
        try {
            bodyTurn = true;
            turn.signalAll();
            while (bodyTurn) {
                turn.await();
            }
            esc = escaped;
            escaped = null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Coroutine driver interrupted", interrupted);
        } finally {
            lock.unlock();
            running.set(false);
        }
        if (resumeObserver != null) {
            resumeObserver.afterResume(esc);
            return;
        }
        if (esc != null) {
            throw esc;
        }
    }

    private JsValue pause() {
        boolean doReturn;
        JsValue returnValue;
        RuntimeException error;
        JsValue resumeWith;
        lock.lock();
        try {
            bodyTurn = false;
            turn.signalAll();
            while (!bodyTurn) {
                turn.await();
            }
            doReturn = injectReturn;
            returnValue = injectReturnValue;
            error = inject;
            resumeWith = sent;
            injectReturn = false;
            inject = null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancelSignal();
        } finally {
            lock.unlock();
        }
        if (doReturn) {
            throw new ReturnSignal(returnValue);
        }
        if (error != null) {
            throw error;
        }
        return resumeWith;
    }

    private void threadMain() {
        lock.lock();
        try {
            while (!bodyTurn) {
                turn.await();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            lock.unlock();
        }
        RuntimeException escapedError = null;
        var result = (JsValue) JsUndefined.getInstance();
        try {
            result = body.run();
        } catch (ReturnSignal signal) {
            result = signal.value();
        } catch (CancelSignal ignored) {
            // cancellation unwinds the body silently; the result stays undefined
        } catch (RuntimeException runtime) {
            escapedError = runtime;
        }
        lock.lock();
        try {
            done = true;
            completed = result;
            escaped = escapedError;
            bodyTurn = false;
            turn.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
