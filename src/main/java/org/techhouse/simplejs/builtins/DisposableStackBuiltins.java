package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.toErrorValue;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// The stack's registered disposers live under a module-private symbol key rather than a new JsValue
// type or the JsObject primitive slot, which would leak the backing array's members onto the stack.
// A second private symbol carries the hint, so [[DisposableState]] and [[AsyncDisposableState]] are
// distinguishable brands: DisposableStack.prototype.dispose called on an AsyncDisposableStack is a
// TypeError even though both classes register their resources the same way.
public final class DisposableStackBuiltins {
    public static final List<String> NAMES = List.of("use", "defer", "adopt", "dispose", "move");
    public static final List<String> ASYNC_NAMES = List.of("use", "defer", "adopt", "disposeAsync", "move");

    private static final JsSymbol ENTRIES = new JsSymbol("DisposableStack.entries");
    private static final JsSymbol ASYNC_HINT = new JsSymbol("DisposableStack.asyncHint");
    private static final JsObject.PropertyFlags SLOT = new JsObject.PropertyFlags(true, false, false);
    // A resource added with no dispose method (an async `use(null)`) records only that DisposeResources
    // must still perform one Await(undefined) before it settles.
    private static final JsValue AWAIT_ONLY = JsNull.getInstance();

    private DisposableStackBuiltins() {
    }

    public static JsSymbol entriesKey() {
        return ENTRIES;
    }

    public static JsNativeFunction create(JsObject proto, boolean async) {
        final var name = async ? "AsyncDisposableStack" : "DisposableStack";
        final var constructor = new JsNativeFunction(name, (thisArg, _) -> {
            final var newTarget = JsNativeFunction.currentNewTarget();
            if ((newTarget == null || newTarget instanceof JsUndefined) && thisArg instanceof JsUndefined) {
                throw new TypeErrorException("Constructor " + name + " requires 'new'");
            }
            return newStack(proto, async, thisArg);
        });
        constructor.setLength(0);
        return constructor;
    }

    public static JsValue getMethod(JsObject receiver, String name, InterpreterOps ops, Invoker invoker,
            EventLoop eventLoop, boolean async) {
        if (!isStackOfKind(receiver, async)) {
            return null;
        }
        return switch (name) {
            case "use" -> new JsNativeFunction("use", (_, args) -> use(receiver, arg(args, 0), ops, invoker, async));
            case "defer" -> new JsNativeFunction("defer", (_, args) -> defer(receiver, arg(args, 0), invoker));
            case "adopt" ->
                new JsNativeFunction("adopt", (_, args) -> adopt(receiver, arg(args, 0), arg(args, 1), invoker));
            case "dispose" -> new JsNativeFunction("dispose", (_, _) -> dispose(receiver, invoker));
            case "disposeAsync" ->
                new JsNativeFunction("disposeAsync", (_, _) -> disposeAsync(receiver, invoker, eventLoop));
            case "move" -> new JsNativeFunction("move", (_, _) -> move(receiver, async));
            default -> null;
        };
    }

    // `disposed` and @@dispose are installed here rather than through the shared prototype builder
    // because the accessor is brand-checked and @@dispose must be the very same function object as
    // `dispose`, which a script can compare, replace or delete.
    public static void installAccessors(JsObject proto, boolean async) {
        final var getter = new JsNativeFunction("get disposed", (thisArg, _) -> disposed(thisArg, async));
        getter.setLength(0);
        proto.defineAccessor("disposed", getter, null);
        proto.setFlags("disposed", new JsObject.PropertyFlags(true, false, true));
        final var disposer = proto.get(async ? "disposeAsync" : "dispose");
        if (disposer != null) {
            Intrinsics.installSymbolMethod(proto, async ? JsSymbol.ASYNC_DISPOSE : JsSymbol.DISPOSE, disposer);
        }
    }

    private static JsValue disposed(JsValue receiver, boolean async) {
        if (!(receiver instanceof JsObject stack) || !stack.hasSymbol(ENTRIES) || isAsyncStack(stack) != async) {
            throw new TypeErrorException((async ? "AsyncDisposableStack" : "DisposableStack")
                    + ".prototype.disposed called on an incompatible receiver");
        }
        return JsBoolean.of(!(stack.getSymbol(ENTRIES) instanceof JsArray));
    }

    private static boolean isStackOfKind(JsObject receiver, boolean async) {
        return receiver.hasSymbol(ENTRIES) && isAsyncStack(receiver) == async;
    }

    private static boolean isAsyncStack(JsObject stack) {
        return stack.getSymbol(ASYNC_HINT) instanceof JsBoolean flag && flag.getValue();
    }

    // A subclass's super() call arrives with the instance under construction as thisArg; the internal
    // slots have to land on that object, since applyNativeSuper only copies string-keyed properties.
    private static JsObject newStack(JsObject proto, boolean async, JsValue thisArg) {
        final var stack = thisArg instanceof JsObject instance ? instance : new JsObject();
        if (stack.getProto() == null) {
            stack.setProto(proto);
        }
        installSlots(stack, async, new JsArray());
        return stack;
    }

    private static void installSlots(JsObject stack, boolean async, JsValue entries) {
        stack.setSymbol(ENTRIES, entries);
        stack.setSymbolFlags(ENTRIES, SLOT);
        stack.setSymbol(ASYNC_HINT, JsBoolean.of(async));
        stack.setSymbolFlags(ASYNC_HINT, SLOT);
    }

    private static JsArray liveEntries(JsObject stack) {
        if (stack.getSymbol(ENTRIES) instanceof JsArray entries) {
            return entries;
        }
        throw new ReferenceErrorException("Disposable stack is already disposed");
    }

    private static JsValue use(JsObject stack, JsValue resource, InterpreterOps ops, Invoker invoker, boolean async) {
        final var entries = liveEntries(stack);
        if (isNullish(resource)) {
            // AddDisposableResource returns unused for a sync hint, but an async hint still records a
            // resource whose dispose method is undefined - which forces one Await before disposeAsync
            // settles.
            if (async) {
                entries.push(AWAIT_ONLY);
            }
            return resource;
        }
        final var method = disposeMethod(resource, ops, async);
        if (!isCallable(method)) {
            throw new TypeErrorException("Object is not disposable");
        }
        entries.push(new JsNativeFunction("dispose", (_, _) -> invoker.call(method, resource, List.of())));
        return resource;
    }

    private static JsValue disposeMethod(JsValue resource, InterpreterOps ops, boolean async) {
        if (async) {
            final var asyncMethod = ops.getMember(resource, JsSymbol.ASYNC_DISPOSE);
            if (isCallable(asyncMethod)) {
                return asyncMethod;
            }
        }
        return ops.getMember(resource, JsSymbol.DISPOSE);
    }

    private static JsValue defer(JsObject stack, JsValue onDispose, Invoker invoker) {
        final var entries = liveEntries(stack);
        if (!isCallable(onDispose)) {
            throw new TypeErrorException("onDispose is not a function");
        }
        entries.push(new JsNativeFunction("dispose",
                (_, _) -> invoker.call(onDispose, JsUndefined.getInstance(), List.of())));
        return JsUndefined.getInstance();
    }

    private static JsValue adopt(JsObject stack, JsValue resource, JsValue onDispose, Invoker invoker) {
        final var entries = liveEntries(stack);
        if (!isCallable(onDispose)) {
            throw new TypeErrorException("onDispose is not a function");
        }
        entries.push(new JsNativeFunction("dispose",
                (_, _) -> invoker.call(onDispose, JsUndefined.getInstance(), List.of(resource))));
        return resource;
    }

    // The moved-to stack is always an ordinary instance of the intrinsic class, never of the
    // receiver's subclass: the proposal reads %DisposableStack.prototype% rather than the receiver's.
    private static JsValue move(JsObject stack, boolean async) {
        final var entries = liveEntries(stack);
        final var moved = new JsObject();
        moved.setProto(intrinsicProtoOf(stack));
        installSlots(moved, async, entries);
        stack.setSymbol(ENTRIES, JsNull.getInstance());
        return moved;
    }

    // The receiver's prototype chain ends at whichever of the two intrinsic prototypes owns
    // `disposed`, so a subclass instance still yields the intrinsic one.
    private static JsValue intrinsicProtoOf(JsObject stack) {
        var proto = stack.getProto();
        while (proto instanceof JsObject candidate) {
            if (candidate.hasAccessor("disposed")) {
                return candidate;
            }
            proto = candidate.getProto();
        }
        return stack.getProto();
    }

    private static List<JsValue> take(JsObject stack) {
        if (!(stack.getSymbol(ENTRIES) instanceof JsArray entries)) {
            return List.of();
        }
        final var reversed = new ArrayList<>(entries.getElements());
        java.util.Collections.reverse(reversed);
        stack.setSymbol(ENTRIES, JsNull.getInstance());
        return reversed;
    }

    private static JsValue dispose(JsObject stack, Invoker invoker) {
        RuntimeException error = null;
        for (final var disposer : take(stack)) {
            if (disposer == AWAIT_ONLY) {
                continue;
            }
            try {
                invoker.call(disposer, JsUndefined.getInstance(), List.of());
            } catch (ScriptAbortException abort) {
                throw abort;
            } catch (RuntimeException disposeError) {
                error = suppress(error, disposeError);
            }
        }
        if (error != null) {
            throw error;
        }
        return JsUndefined.getInstance();
    }

    private static JsValue disposeAsync(JsObject stack, Invoker invoker, EventLoop eventLoop) {
        final var promise = new JsPromise(eventLoop);
        step(new AsyncDisposal(take(stack), promise, invoker, eventLoop), 0, null, false);
        return promise;
    }

    private record AsyncDisposal(List<JsValue> disposers, JsPromise promise, Invoker invoker, EventLoop eventLoop) {
    }

    // Each disposer may return a promise, so the next one only runs once the previous has settled.
    // DisposeResources ends with one Await(undefined) when an async resource had no dispose method and
    // nothing else has awaited, which is what keeps disposeAsync one microtask behind its caller.
    private static void step(AsyncDisposal disposal, int index, RuntimeException error, boolean hasAwaited) {
        if (index >= disposal.disposers().size()) {
            settle(disposal, error, hasAwaited);
            return;
        }
        final var disposer = disposal.disposers().get(index);
        if (disposer == AWAIT_ONLY) {
            step(disposal, index + 1, error, hasAwaited);
            return;
        }
        final JsValue outcome;
        try {
            outcome = disposal.invoker().call(disposer, JsUndefined.getInstance(), List.of());
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException disposeError) {
            step(disposal, index + 1, suppress(error, disposeError), hasAwaited);
            return;
        }
        if (outcome instanceof JsPromise pending) {
            pending.subscribe(_ -> step(disposal, index + 1, error, true),
                    reason -> step(disposal, index + 1, suppress(error, new JsThrowException(reason)), true));
        } else {
            step(disposal, index + 1, error, hasAwaited);
        }
    }

    private static void settle(AsyncDisposal disposal, RuntimeException error, boolean hasAwaited) {
        final var needsAwait = disposal.disposers().stream().anyMatch(entry -> entry == AWAIT_ONLY);
        if (needsAwait && !hasAwaited) {
            disposal.eventLoop().queueMicrotask(() -> resolveOrReject(disposal.promise(), error));
            return;
        }
        resolveOrReject(disposal.promise(), error);
    }

    private static void resolveOrReject(JsPromise promise, RuntimeException error) {
        if (error == null) {
            promise.resolve(JsUndefined.getInstance());
        } else {
            promise.reject(toErrorValue(error));
        }
    }

    private static RuntimeException suppress(RuntimeException existing, RuntimeException latest) {
        if (existing == null) {
            return latest;
        }
        return new JsThrowException(ErrorBuiltins.makeSuppressedError(toErrorValue(latest), toErrorValue(existing),
                "An error was suppressed during disposal"));
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
