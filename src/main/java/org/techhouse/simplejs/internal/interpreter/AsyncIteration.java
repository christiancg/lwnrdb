package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.List;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// GetIterator(obj, async) plus the step loop it feeds, shared by `for await` and async `yield*`.
// A real async iterator returns a promise *of* the step object; a sync iterator is wrapped in the
// spec's %AsyncFromSyncIteratorPrototype% behaviour, where the step object is produced synchronously
// and AsyncFromSyncIteratorContinuation awaits only its `value` (closing the sync iterator when that
// await rejects on a not-done step).
public final class AsyncIteration {
    private final Interpreter interp;
    private final JsValue iterator;
    private final JsValue nextMethod;
    private final boolean fromSync;
    private boolean done;

    private AsyncIteration(Interpreter interp, JsValue iterator, JsValue nextMethod, boolean fromSync) {
        this.interp = interp;
        this.iterator = iterator;
        this.nextMethod = nextMethod;
        this.fromSync = fromSync;
    }

    // GetMethod is not the same as a plain member read: a present-but-non-callable @@asyncIterator
    // is a TypeError, and @@iterator must not be touched at all when @@asyncIterator is present.
    public static AsyncIteration open(Interpreter interp, JsValue source) {
        final var asyncMethod = interp.getMemberByKey(source, JsSymbol.ASYNC_ITERATOR);
        if (!isNullish(asyncMethod)) {
            final var opened = openWith(interp, source, asyncMethod, "Symbol.asyncIterator");
            return new AsyncIteration(interp, opened, interp.getMember(opened, "next"), false);
        }
        final var syncMethod = interp.getMemberByKey(source, JsSymbol.ITERATOR);
        if (isNullish(syncMethod)) {
            throw new TypeErrorException(JsCoercion.toStr(source) + " is not async iterable");
        }
        final var opened = openWith(interp, source, syncMethod, "Symbol.iterator");
        return new AsyncIteration(interp, opened, interp.getMember(opened, "next"), true);
    }

    private static JsValue openWith(Interpreter interp, JsValue source, JsValue method, String label) {
        if (!isCallable(method)) {
            throw new TypeErrorException(label + " is not a function");
        }
        final var opened = interp.callValue(method, source, List.of());
        if (!isObjectLike(opened)) {
            throw new TypeErrorException("Result of " + label + " method is not an object");
        }
        return opened;
    }

    public record Step(boolean done, JsValue value) {
    }

    // `for await` runs AsyncIteratorStepValue, which calls `next` with no argument at all — a `next`
    // counting its arguments can observe the difference from the delegating form below.
    public Step step(Coroutine coroutine, JsValue sent) {
        return step(coroutine, sent instanceof JsUndefined ? List.of() : List.of(sent));
    }

    private Step step(Coroutine coroutine, List<JsValue> args) {
        if (done) {
            return new Step(true, JsUndefined.getInstance());
        }
        if (!isCallable(nextMethod)) {
            throw new TypeErrorException("iterator.next is not a function");
        }
        final var raw = interp.callValue(nextMethod, iterator, args);
        final var step = fromSync ? continuation(coroutine, raw) : awaitStep(coroutine, raw);
        if (!step.done()) {
            coroutine.markDelegatedYield();
        }
        return step;
    }

    private Step awaitStep(Coroutine coroutine, JsValue raw) {
        final var settled = coroutine.await(interp.toPromise(raw));
        if (!isObjectLike(settled)) {
            throw new TypeErrorException("Iterator result is not an object");
        }
        final var complete = JsCoercion.toBoolean(interp.getMember(settled, "done"));
        done = done || complete;
        return new Step(complete, interp.getMember(settled, "value"));
    }

    // AsyncFromSyncIteratorContinuation, PLUS the outer `Await(nextResult)` that ForIn/OfBodyEvaluation
    // (13.7.5.13) applies unconditionally whenever iteratorKind is async. These are two distinct
    // promises: `%AsyncFromSyncIteratorPrototype%.next()` always returns a freshly constructed
    // promise capability, wrapping (once the inner `value` await settles) a genuine IterResultObject
    // - it is that wrapper promise, not `value` itself, that the loop's own Await(nextResult) awaits a
    // second time. Collapsing both into one await (as a prior version of this method did) undercounts
    // both the tick delay and the `constructor` lookups by one: the outer await's PromiseResolve
    // always does a `constructor` Get (the wrapper is always a real Promise), while the inner one only
    // does it when `value` itself is already a promise.
    private Step continuation(Coroutine coroutine, JsValue raw) {
        if (!isObjectLike(raw)) {
            done = true;
            throw new TypeErrorException("Iterator result is not an object");
        }
        final boolean complete;
        final JsValue value;
        try {
            complete = JsCoercion.toBoolean(interp.getMember(raw, "done"));
            value = interp.getMember(raw, "value");
        } catch (SimpleJsRuntimeException error) {
            done = true;
            throw error;
        }
        done = done || complete;

        final JsValue nextResult;
        try {
            final var awaitedValue = coroutine.await(interp.toPromise(value));
            nextResult = interp.toPromise(InterpreterUtils.stepResult(awaitedValue, complete));
        } catch (SimpleJsRuntimeException error) {
            // IfAbruptRejectPromise: computing (or awaiting) the inner value wrapper failed before a
            // result object could even be built, so the outer promise capability settles straight to
            // rejected with that same reason - modelled by awaiting a promise poisoned to reject with
            // it, which still costs exactly the one remaining tick the outer Await(nextResult) would.
            if (!complete) {
                closeSyncQuietly();
                done = true;
            }
            awaitRejected(coroutine, error);
            throw error;
        }
        try {
            final var settled = coroutine.await(interp.toPromise(nextResult));
            if (!isObjectLike(settled)) {
                throw new TypeErrorException("Iterator result is not an object");
            }
            return new Step(complete, interp.getMember(settled, "value"));
        } catch (SimpleJsRuntimeException error) {
            if (!complete) {
                closeSyncQuietly();
                done = true;
            }
            throw error;
        }
    }

    // Awaits a promise that rejects with `error` the moment something reads its "then" property -
    // exactly what `interp.toPromise` does internally when resolving a thenable whose "then" getter
    // throws - so the caller gets the same single-tick-deferred throw a genuinely rejected wrapper
    // promise would produce, without needing raw EventLoop access to construct one directly. The
    // coroutine resumes by throwing `error` right back out of `coroutine.await`; that is swallowed
    // here (it is always the same exception object the caller already has) so the caller's own
    // `throw error` afterward is the one real, reachable throw instead of dead code following a
    // call that can never return normally.
    private void awaitRejected(Coroutine coroutine, SimpleJsRuntimeException error) {
        final var poison = new JsObject();
        poison.defineAccessor("then", new JsNativeFunction("then", (_, _) -> {
            throw error;
        }), null);
        try {
            coroutine.await(interp.toPromise(poison));
        } catch (SimpleJsRuntimeException ignored) {
            // expected: the poisoned thenable always rejects with `error`
        }
    }

    // AsyncIteratorClose under a normal completion: GetMethod rejects a present-but-non-callable
    // `return`, and that TypeError is the caller's.
    public void close() {
        if (done) {
            return;
        }
        done = true;
        final var returnFn = interp.getMember(iterator, "return");
        if (isNullish(returnFn)) {
            return;
        }
        if (!isCallable(returnFn)) {
            throw new TypeErrorException("iterator.return is not a function");
        }
        interp.callValue(returnFn, iterator, List.of());
    }

    private void closeSyncQuietly() {
        try {
            final var returnFn = interp.getMember(iterator, "return");
            if (isCallable(returnFn)) {
                interp.callValue(returnFn, iterator, List.of());
            }
        } catch (SimpleJsRuntimeException ignored) {
            // the original completion wins over anything the iterator's `return` throws
        }
    }
}
