package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.stepResult;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.toErrorValue;

import org.techhouse.simplejs.builtins.AsyncIteratorBuiltins;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

final class AsyncGeneratorDriver {
    private final Interpreter interp;
    private final EventLoop eventLoop;
    private final BuiltinMemberLookup builtinMembers;

    AsyncGeneratorDriver(Interpreter interp, EventLoop eventLoop, BuiltinMemberLookup builtinMembers) {
        this.interp = interp;
        this.eventLoop = eventLoop;
        this.builtinMembers = builtinMembers;
    }

    JsValue asyncGeneratorMethod(JsAsyncGenerator generator, String key) {
        final var intrinsic = builtinMembers.intrinsicMember(generator, key);
        if (!(intrinsic instanceof JsUndefined) || !AsyncIteratorBuiltins.isHelperName(key)) {
            return intrinsic;
        }
        return AsyncIteratorBuiltins.helper(interp.ops(), eventLoop, key);
    }

    public JsValue driveAsyncGenerator(JsAsyncGenerator generator, MemberEvaluator.AsyncStep kind, JsValue arg) {
        final var promise = new JsPromise(eventLoop);
        generator.enqueue(new JsAsyncGenerator.Request(requestKind(kind), arg, promise));
        final var state = generator.getState();
        if (state != JsAsyncGenerator.State.EXECUTING && state != JsAsyncGenerator.State.AWAITING_RETURN) {
            drainAsyncGenerator(generator);
        }
        return promise;
    }

    JsAsyncGenerator.RequestKind requestKind(MemberEvaluator.AsyncStep kind) {
        return switch (kind) {
            case NEXT -> JsAsyncGenerator.RequestKind.NEXT;
            case RETURN -> JsAsyncGenerator.RequestKind.RETURN;
            case THROW -> JsAsyncGenerator.RequestKind.THROW;
        };
    }

    void drainAsyncGenerator(JsAsyncGenerator generator) {
        final var coroutine = generator.getCoroutine();
        var draining = true;
        while (draining && generator.hasRequests()) {
            final var state = generator.getState();
            final var request = generator.peekRequest();
            final var startOnly = state == JsAsyncGenerator.State.SUSPENDED_START
                    && request.kind() != JsAsyncGenerator.RequestKind.NEXT;
            if (state == JsAsyncGenerator.State.EXECUTING || state == JsAsyncGenerator.State.AWAITING_RETURN) {
                draining = false;
            } else if (state == JsAsyncGenerator.State.COMPLETED || coroutine.isDone() || startOnly) {
                draining = settleWithoutResuming(generator, request);
            } else {
                startRequest(generator, request);
                draining = false;
            }
        }
    }

    void startRequest(JsAsyncGenerator generator, JsAsyncGenerator.Request request) {
        generator.setState(JsAsyncGenerator.State.EXECUTING);
        if (request.kind() == JsAsyncGenerator.RequestKind.RETURN) {
            final JsPromise promise;
            try {
                promise = interp.toPromise(request.value());
            } catch (SimpleJsRuntimeException error) {
                resumeAsyncGenerator(generator, JsAsyncGenerator.RequestKind.THROW,
                        toErrorValue(error, interp.intrinsics()));
                return;
            }
            promise.subscribe(value -> resumeAsyncGenerator(generator, JsAsyncGenerator.RequestKind.RETURN, value),
                    reason -> resumeAsyncGenerator(generator, JsAsyncGenerator.RequestKind.THROW, reason));
            return;
        }
        resumeAsyncGenerator(generator, request.kind(), request.value());
    }

    boolean settleWithoutResuming(JsAsyncGenerator generator, JsAsyncGenerator.Request request) {
        if (request.kind() == JsAsyncGenerator.RequestKind.RETURN) {
            generator.setState(JsAsyncGenerator.State.AWAITING_RETURN);
            awaitReturnValue(generator, request);
            return false;
        }
        generator.setState(JsAsyncGenerator.State.COMPLETED);
        generator.pollRequest();
        if (request.kind() == JsAsyncGenerator.RequestKind.THROW) {
            request.capability().reject(request.value());
        } else {
            request.capability().resolve(linkResultProto(stepResult(JsUndefined.getInstance(), true)));
        }
        return true;
    }

    void awaitReturnValue(JsAsyncGenerator generator, JsAsyncGenerator.Request request) {
        final JsPromise promise;
        try {
            promise = interp.toPromise(request.value());
        } catch (SimpleJsRuntimeException error) {
            generator.setState(JsAsyncGenerator.State.COMPLETED);
            generator.pollRequest();
            request.capability().reject(toErrorValue(error, interp.intrinsics()));
            drainAsyncGenerator(generator);
            return;
        }
        promise.subscribe(value -> {
            generator.setState(JsAsyncGenerator.State.COMPLETED);
            generator.pollRequest();
            request.capability().resolve(linkResultProto(stepResult(value, true)));
            drainAsyncGenerator(generator);
        }, reason -> {
            generator.setState(JsAsyncGenerator.State.COMPLETED);
            generator.pollRequest();
            request.capability().reject(reason);
            drainAsyncGenerator(generator);
        });
    }

    void resumeAsyncGenerator(JsAsyncGenerator generator, JsAsyncGenerator.RequestKind kind, JsValue value) {
        final var coroutine = generator.getCoroutine();
        try {
            switch (kind) {
                case RETURN -> coroutine.resumeReturn(value);
                case THROW -> coroutine.resumeThrow(value);
                default -> coroutine.resumeNext(value);
            }
        } catch (SimpleJsRuntimeException error) {
            generator.setState(JsAsyncGenerator.State.COMPLETED);
            completeStep(generator, error);
        }
    }

    public void observeAsyncGenerator(JsAsyncGenerator generator, RuntimeException escaped) {
        final var coroutine = generator.getCoroutine();
        if (escaped != null) {
            generator.setState(JsAsyncGenerator.State.COMPLETED);
            completeStep(generator, escaped);
            return;
        }
        if (coroutine.isDone()) {
            generator.setState(JsAsyncGenerator.State.COMPLETED);
            completeResolve(generator, coroutine.completedValue(), true);
            return;
        }
        if (coroutine.pauseReason() == Coroutine.PauseReason.YIELD) {
            yieldStep(generator, coroutine.yieldedValue());
        }
    }

    void yieldStep(JsAsyncGenerator generator, JsValue value) {
        if (generator.getCoroutine().isDelegatedYield()) {
            generator.setState(JsAsyncGenerator.State.SUSPENDED_YIELD);
            completeResolve(generator, value, false);
            return;
        }
        generator.setState(JsAsyncGenerator.State.EXECUTING);
        interp.toPromise(value).subscribe(settled -> {
            generator.setState(JsAsyncGenerator.State.SUSPENDED_YIELD);
            completeResolve(generator, settled, false);
        }, reason -> {
            generator.setState(JsAsyncGenerator.State.EXECUTING);
            resumeAsyncGenerator(generator, JsAsyncGenerator.RequestKind.THROW, reason);
        });
    }

    void completeResolve(JsAsyncGenerator generator, JsValue value, boolean done) {
        final var request = generator.pollRequest();
        if (request != null) {
            request.capability().resolve(linkResultProto(stepResult(value, done)));
        }
        drainAsyncGenerator(generator);
    }

    JsValue linkResultProto(JsValue result) {
        if (result instanceof JsObject object && object.getProto() == null) {
            object.setProto(interp.intrinsics().objectProto);
        }
        return result;
    }

    void completeStep(JsAsyncGenerator generator, RuntimeException error) {
        if (!(error instanceof SimpleJsRuntimeException)) {
            throw error;
        }
        final var request = generator.pollRequest();
        if (request != null) {
            request.capability().reject(toErrorValue(error, interp.intrinsics()));
        }
        drainAsyncGenerator(generator);
    }

}
