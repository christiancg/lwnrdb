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
public final class DisposableStackBuiltins {
    public static final List<String> NAMES = List.of("use", "defer", "adopt", "dispose", "move");
    public static final List<String> ASYNC_NAMES = List.of("use", "defer", "adopt", "disposeAsync", "move");

    private static final JsSymbol ENTRIES = new JsSymbol("DisposableStack.entries");

    private DisposableStackBuiltins() {
    }

    public static JsSymbol entriesKey() {
        return ENTRIES;
    }

    public static JsNativeFunction create(JsObject proto, boolean async) {
        return new JsNativeFunction(async ? "AsyncDisposableStack" : "DisposableStack", (_, _) -> newStack(proto));
    }

    public static JsValue getMethod(JsObject receiver, String name, InterpreterOps ops, Invoker invoker,
            EventLoop eventLoop, boolean async) {
        return switch (name) {
            case "use" -> new JsNativeFunction("use", (_, args) -> use(receiver, arg(args, 0), ops, invoker, async));
            case "defer" -> new JsNativeFunction("defer", (_, args) -> defer(receiver, arg(args, 0), invoker));
            case "adopt" ->
                new JsNativeFunction("adopt", (_, args) -> adopt(receiver, arg(args, 0), arg(args, 1), invoker));
            case "dispose" -> new JsNativeFunction("dispose", (_, _) -> dispose(receiver, invoker));
            case "disposeAsync" ->
                new JsNativeFunction("disposeAsync", (_, _) -> disposeAsync(receiver, invoker, eventLoop));
            case "move" -> new JsNativeFunction("move", (_, _) -> move(receiver));
            default -> null;
        };
    }

    public static void installAccessors(JsObject proto, boolean async) {
        proto.defineAccessor("disposed",
                new JsNativeFunction("get disposed", (thisArg, _) -> JsBoolean
                        .of(!(thisArg instanceof JsObject stack) || !(stack.getSymbol(ENTRIES) instanceof JsArray))),
                null);
        proto.setFlags("disposed", new JsObject.PropertyFlags(true, false, true));
        Intrinsics.installSymbolMethod(proto, async ? JsSymbol.ASYNC_DISPOSE : JsSymbol.DISPOSE,
                new JsNativeFunction(async ? "[Symbol.asyncDispose]" : "[Symbol.dispose]",
                        (thisArg, _) -> proto.get(async ? "disposeAsync" : "dispose") instanceof JsNativeFunction fn
                                ? fn.invoke(thisArg, List.of())
                                : JsUndefined.getInstance()));
    }

    private static JsObject newStack(JsObject proto) {
        final var stack = new JsObject();
        stack.setProto(proto);
        // ENTRIES stands in for the [[DisposableState]] internal slot, so it must not enumerate.
        stack.setSymbol(ENTRIES, new JsArray());
        stack.setSymbolFlags(ENTRIES, new JsObject.PropertyFlags(true, false, false));
        return stack;
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

    private static JsValue move(JsObject stack) {
        final var entries = liveEntries(stack);
        final var moved = new JsObject();
        moved.setProto(stack.getProto());
        moved.setSymbol(ENTRIES, entries);
        stack.setSymbol(ENTRIES, JsNull.getInstance());
        return moved;
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
        step(take(stack), 0, null, promise, invoker);
        return promise;
    }

    // Each disposer may return a promise, so the next one only runs once the previous has settled.
    private static void step(List<JsValue> disposers, int index, RuntimeException error, JsPromise promise,
            Invoker invoker) {
        if (index >= disposers.size()) {
            if (error == null) {
                promise.resolve(JsUndefined.getInstance());
            } else {
                promise.reject(toErrorValue(error));
            }
            return;
        }
        final JsValue outcome;
        try {
            outcome = invoker.call(disposers.get(index), JsUndefined.getInstance(), List.of());
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException disposeError) {
            step(disposers, index + 1, suppress(error, disposeError), promise, invoker);
            return;
        }
        if (outcome instanceof JsPromise pending) {
            pending.subscribe(_ -> step(disposers, index + 1, error, promise, invoker), reason -> step(disposers,
                    index + 1, suppress(error, new JsThrowException(reason)), promise, invoker));
        } else {
            step(disposers, index + 1, error, promise, invoker);
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
