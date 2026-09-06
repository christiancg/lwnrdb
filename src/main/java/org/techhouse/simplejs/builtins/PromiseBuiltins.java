package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isConstructor;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class PromiseBuiltins {
    public static final List<String> PROTO_NAMES = List.of("then", "catch", "finally");

    private PromiseBuiltins() {
    }

    // A spec PromiseCapability record: the promise plus the resolve/reject functions its executor
    // handed out. Everything that must work against a subclass or a foreign constructor goes
    // through one of these rather than through a JsPromise directly.
    public record Capability(JsValue promise, JsValue resolve, JsValue reject) {
    }

    private record Ctx(EventLoop loop, Invoker invoker, Intrinsics intrinsics) {
        InterpreterOps ops() {
            return loop.ops();
        }

        JsValue defaultConstructor() {
            return intrinsics.promiseProto().get("constructor");
        }
    }

    public static JsNativeFunction create(EventLoop eventLoop, Invoker invoker, Intrinsics intrinsics) {
        final var ctx = new Ctx(eventLoop, invoker, intrinsics);
        // Reached without `new` there is no new.target; a subclass's super() call arrives with the
        // instance under construction as thisArg, which is what keeps `class P extends Promise {}` working.
        final var promise = new JsNativeFunction("Promise", (thisArg, args) -> {
            final var newTarget = JsNativeFunction.currentNewTarget();
            if ((newTarget == null || newTarget instanceof JsUndefined) && !(thisArg instanceof JsObject)) {
                throw new TypeErrorException("Constructor Promise requires 'new'");
            }
            if (!isCallable(arg(args, 0))) {
                throw new TypeErrorException("Promise resolver is not a function");
            }
            // OrdinaryCreateFromConstructor's Get(newTarget, "prototype") is observable even though
            // this engine has no reachable use for a builtin's newTarget.prototype yet: a throwing
            // accessor there must still abort construction before the executor runs, but only after
            // the executor's own callability has already been checked (spec step order).
            if (newTarget != null && !(newTarget instanceof JsUndefined)) {
                ctx.ops().getMember(newTarget, new JsString("prototype"));
            }
            return construct(ctx, args);
        });
        promise.setProperty("resolve",
                new JsNativeFunction("resolve", (receiver, args) -> resolveStatic(ctx, receiver, arg(args, 0))));
        promise.setProperty("reject",
                new JsNativeFunction("reject", (receiver, args) -> rejectStatic(ctx, receiver, arg(args, 0))));
        promise.setProperty("all", combinator(ctx, "all", Variant.ALL, false));
        promise.setProperty("allSettled", combinator(ctx, "allSettled", Variant.ALL_SETTLED, false));
        promise.setProperty("any", combinator(ctx, "any", Variant.ANY, false));
        promise.setProperty("allKeyed", combinator(ctx, "allKeyed", Variant.ALL, true));
        promise.setProperty("allSettledKeyed", combinator(ctx, "allSettledKeyed", Variant.ALL_SETTLED, true));
        promise.setProperty("race",
                new JsNativeFunction("race", (receiver, args) -> race(ctx, receiver, arg(args, 0))));
        promise.setProperty("withResolvers",
                new JsNativeFunction("withResolvers", (receiver, _) -> withResolvers(ctx, receiver)));
        promise.setProperty("try", new JsNativeFunction("try", (receiver, args) -> tryCall(ctx, receiver, args)));
        return promise;
    }

    public static JsValue getMethod(JsValue receiver, String name, EventLoop eventLoop, Invoker invoker,
            Intrinsics intrinsics) {
        final var ctx = new Ctx(eventLoop, invoker, intrinsics);
        return switch (name) {
            case "then" -> new JsNativeFunction("then", (_, args) -> then(ctx, receiver, arg(args, 0), arg(args, 1)));
            case "catch" -> new JsNativeFunction("catch",
                    (_, args) -> invoke(ctx, receiver, List.of(JsUndefined.getInstance(), arg(args, 0))));
            case "finally" -> new JsNativeFunction("finally", (_, args) -> onFinally(ctx, receiver, arg(args, 0)));
            default -> null;
        };
    }

    // ---------------------------------------------------------------------
    // The constructor and the promise-capability plumbing
    // ---------------------------------------------------------------------

    private static JsValue construct(Ctx ctx, List<JsValue> args) {
        final var executor = arg(args, 0);
        if (!isCallable(executor)) {
            throw new TypeErrorException("Promise resolver is not a function");
        }
        final var promise = new JsPromise(ctx.loop());
        final var alreadyResolved = new boolean[]{false};
        final var resolve = settlingFunction(alreadyResolved, promise::resolve);
        final var reject = settlingFunction(alreadyResolved, promise::reject);
        try {
            ctx.invoker().call(executor, JsUndefined.getInstance(), List.of(resolve, reject));
        } catch (SimpleJsRuntimeException error) {
            if (!alreadyResolved[0]) {
                alreadyResolved[0] = true;
                promise.reject(errorValue(ctx, error));
            }
        }
        return promise;
    }

    private static JsNativeFunction settlingFunction(boolean[] alreadyResolved, Consumer<JsValue> settle) {
        final var fn = new JsNativeFunction("", (_, args) -> {
            if (alreadyResolved[0]) {
                return JsUndefined.getInstance();
            }
            alreadyResolved[0] = true;
            settle.accept(arg(args, 0));
            return JsUndefined.getInstance();
        });
        fn.setLength(1);
        return fn;
    }

    private static Capability newPromiseCapability(Ctx ctx, JsValue constructor) {
        if (!isConstructor(constructor)) {
            throw new TypeErrorException("Promise capability constructor is not a constructor");
        }
        final var slots = new JsValue[]{JsUndefined.getInstance(), JsUndefined.getInstance()};
        final var executor = new JsNativeFunction("", (_, args) -> {
            if (!(slots[0] instanceof JsUndefined) || !(slots[1] instanceof JsUndefined)) {
                throw new TypeErrorException("Promise capability functions already set");
            }
            slots[0] = arg(args, 0);
            slots[1] = arg(args, 1);
            return JsUndefined.getInstance();
        });
        executor.setLength(2);
        final var promise = ctx.ops().construct(constructor, List.of(executor));
        if (!isCallable(slots[0]) || !isCallable(slots[1])) {
            throw new TypeErrorException("Promise resolve or reject function is not callable");
        }
        return new Capability(promise, slots[0], slots[1]);
    }

    private static JsValue withResolvers(Ctx ctx, JsValue receiver) {
        final var capability = newPromiseCapability(ctx, receiver);
        final var result = new JsObject();
        result.setProto(ctx.intrinsics().objectProto());
        result.set("promise", capability.promise());
        result.set("resolve", capability.resolve());
        result.set("reject", capability.reject());
        return result;
    }

    private static JsValue tryCall(Ctx ctx, JsValue receiver, List<JsValue> args) {
        if (!isObjectLike(receiver)) {
            throw new TypeErrorException("Promise.try called on a non-object");
        }
        final var callback = arg(args, 0);
        final var rest = args.isEmpty() ? List.<JsValue>of() : args.subList(1, args.size());
        final JsValue result;
        try {
            result = ctx.invoker().call(callback, JsUndefined.getInstance(), rest);
        } catch (SimpleJsRuntimeException error) {
            final var capability = newPromiseCapability(ctx, receiver);
            call(ctx, capability.reject(), errorValue(ctx, error));
            return capability.promise();
        }
        return promiseResolve(ctx, receiver, result);
    }

    // ---------------------------------------------------------------------
    // PromiseResolve / Promise.resolve / Promise.reject
    // ---------------------------------------------------------------------

    private static JsValue resolveStatic(Ctx ctx, JsValue receiver, JsValue value) {
        if (!isObjectLike(receiver)) {
            throw new TypeErrorException("Promise.resolve called on a non-object");
        }
        return promiseResolve(ctx, receiver, value);
    }

    private static JsValue promiseResolve(Ctx ctx, JsValue constructor, JsValue value) {
        if (isPromise(value) && sameValue(ctx.ops().getMember(value, new JsString("constructor")), constructor)) {
            return value;
        }
        final var capability = newPromiseCapability(ctx, constructor);
        call(ctx, capability.resolve(), value);
        return capability.promise();
    }

    private static JsValue rejectStatic(Ctx ctx, JsValue receiver, JsValue reason) {
        final var capability = newPromiseCapability(ctx, receiver);
        call(ctx, capability.reject(), reason);
        return capability.promise();
    }

    // ---------------------------------------------------------------------
    // then / catch / finally
    // ---------------------------------------------------------------------

    private static JsValue then(Ctx ctx, JsValue receiver, JsValue onFulfilled, JsValue onRejected) {
        final var promise = requirePromise(receiver);
        final var species = speciesConstructor(ctx, receiver);
        final var capability = newPromiseCapability(ctx, species);
        performPromiseThen(ctx, promise, onFulfilled, onRejected, capability);
        return capability.promise();
    }

    private static void performPromiseThen(Ctx ctx, JsPromise promise, JsValue onFulfilled, JsValue onRejected,
            Capability capability) {
        promise.subscribe(value -> reaction(ctx, capability, onFulfilled, value, true),
                reason -> reaction(ctx, capability, onRejected, reason, false));
    }

    private static void reaction(Ctx ctx, Capability capability, JsValue handler, JsValue argument, boolean fulfilled) {
        if (!isCallable(handler)) {
            call(ctx, fulfilled ? capability.resolve() : capability.reject(), argument);
            return;
        }
        try {
            call(ctx, capability.resolve(), ctx.invoker().call(handler, JsUndefined.getInstance(), List.of(argument)));
        } catch (SimpleJsRuntimeException error) {
            call(ctx, capability.reject(), errorValue(ctx, error));
        }
    }

    private static JsValue onFinally(Ctx ctx, JsValue receiver, JsValue onFinally) {
        if (!isObjectLike(receiver)) {
            throw new TypeErrorException("Promise.prototype.finally called on a non-object");
        }
        final var species = speciesConstructor(ctx, receiver);
        if (!isCallable(onFinally)) {
            return invoke(ctx, receiver, List.of(onFinally, onFinally));
        }
        final var thenFinally = finallyHandler(ctx, species, onFinally, true);
        final var catchFinally = finallyHandler(ctx, species, onFinally, false);
        return invoke(ctx, receiver, List.of(thenFinally, catchFinally));
    }

    private static JsNativeFunction finallyHandler(Ctx ctx, JsValue species, JsValue onFinally, boolean fulfilled) {
        final var handler = new JsNativeFunction("", (_, args) -> {
            final var carried = arg(args, 0);
            final var result = ctx.invoker().call(onFinally, JsUndefined.getInstance(), List.of());
            final var settled = promiseResolve(ctx, species, result);
            final var valueThunk = new JsNativeFunction("", (_, _) -> {
                if (fulfilled) {
                    return carried;
                }
                throw new JsThrowException(carried);
            });
            valueThunk.setLength(0);
            return invoke(ctx, settled, List.of(valueThunk));
        });
        handler.setLength(1);
        return handler;
    }

    // ---------------------------------------------------------------------
    // The combinators
    // ---------------------------------------------------------------------

    private enum Variant {
        ALL, ALL_SETTLED, ANY
    }

    private static JsNativeFunction combinator(Ctx ctx, String name, Variant variant, boolean keyed) {
        final var fn = new JsNativeFunction(name,
                (receiver, args) -> combine(ctx, receiver, arg(args, 0), variant, keyed));
        fn.setLength(1);
        return fn;
    }

    private static JsValue combine(Ctx ctx, JsValue receiver, JsValue source, Variant variant, boolean keyed) {
        if (!isObjectLike(receiver)) {
            throw new TypeErrorException("Promise combinator called on a non-object");
        }
        final var capability = newPromiseCapability(ctx, receiver);
        try {
            final var promiseResolve = getPromiseResolve(ctx, receiver);
            if (keyed) {
                performKeyed(ctx, source, receiver, promiseResolve, capability, variant);
            } else {
                performIterated(ctx, source, receiver, promiseResolve, capability, variant);
            }
        } catch (SimpleJsRuntimeException error) {
            call(ctx, capability.reject(), errorValue(ctx, error));
        }
        return capability.promise();
    }

    private static JsValue race(Ctx ctx, JsValue receiver, JsValue source) {
        if (!isObjectLike(receiver)) {
            throw new TypeErrorException("Promise.race called on a non-object");
        }
        final var capability = newPromiseCapability(ctx, receiver);
        try {
            final var promiseResolve = getPromiseResolve(ctx, receiver);
            final var iterator = IteratorRecord.open(ctx, source);
            try {
                JsValue next;
                while ((next = iterator.stepValue(ctx)) != null) {
                    final var nextPromise = ctx.invoker().call(promiseResolve, receiver, List.of(next));
                    invoke(ctx, nextPromise, List.of(capability.resolve(), capability.reject()));
                }
            } catch (SimpleJsRuntimeException error) {
                throw iterator.closeOnAbrupt(ctx, error);
            }
        } catch (SimpleJsRuntimeException error) {
            call(ctx, capability.reject(), errorValue(ctx, error));
        }
        return capability.promise();
    }

    private static JsValue getPromiseResolve(Ctx ctx, JsValue constructor) {
        final var resolve = ctx.ops().getMember(constructor, new JsString("resolve"));
        if (!isCallable(resolve)) {
            throw new TypeErrorException("Promise resolve is not a function");
        }
        return resolve;
    }

    private static void performIterated(Ctx ctx, JsValue source, JsValue constructor, JsValue promiseResolve,
            Capability capability, Variant variant) {
        final var iterator = IteratorRecord.open(ctx, source);
        final var state = new CombineState(ctx, capability, variant, false);
        try {
            JsValue next;
            while ((next = iterator.stepValue(ctx)) != null) {
                state.addSlot(null);
                final var nextPromise = ctx.invoker().call(promiseResolve, constructor, List.of(next));
                state.subscribe(nextPromise);
            }
        } catch (SimpleJsRuntimeException error) {
            throw iterator.closeOnAbrupt(ctx, error);
        }
        state.finish();
    }

    private static void performKeyed(Ctx ctx, JsValue source, JsValue constructor, JsValue promiseResolve,
            Capability capability, Variant variant) {
        if (!isObjectLike(source)) {
            throw new TypeErrorException("Promise keyed combinator called on a non-object");
        }
        final var state = new CombineState(ctx, capability, variant, true);
        for (final var key : ctx.ops().ownKeys(source)) {
            final var descriptor = ctx.ops().getOwnPropertyDescriptor(source, key);
            if (!(descriptor instanceof JsObject object) || !JsCoercion.toBoolean(object.get("enumerable"))) {
                continue;
            }
            final var value = ctx.ops().getMember(source, key);
            state.addSlot(key);
            final var nextPromise = ctx.invoker().call(promiseResolve, constructor, List.of(value));
            state.subscribe(nextPromise);
        }
        state.finish();
    }

    // Shared bookkeeping for all/allSettled/any in both the iterated and the keyed shape: the
    // spec's [[Values]]/[[Errors]] list, its [[RemainingElements]] counter (which starts at 1 so the
    // loop itself counts as one outstanding element) and the per-element resolve functions carrying
    // [[AlreadyCalled]] and [[Index]].
    private static final class CombineState {
        private final Ctx ctx;
        private final Capability capability;
        private final Variant variant;
        private final List<JsValue> values = new ArrayList<>();
        private final List<JsValue> keys = new ArrayList<>();
        private final int[] remaining = new int[]{1};
        private final boolean keyed;
        private int index;

        private CombineState(Ctx ctx, Capability capability, Variant variant, boolean keyed) {
            this.ctx = ctx;
            this.capability = capability;
            this.variant = variant;
            this.keyed = keyed;
        }

        private void addSlot(JsValue key) {
            values.add(JsUndefined.getInstance());
            if (key != null) {
                keys.add(key);
            }
        }

        private void subscribe(JsValue nextPromise) {
            final var slot = index++;
            remaining[0]++;
            final var handlers = handlersFor(slot);
            invoke(ctx, nextPromise, handlers);
        }

        private List<JsValue> handlersFor(int slot) {
            final var alreadyCalled = new boolean[]{false};
            return switch (variant) {
                case ALL -> List.of(elementFunction(alreadyCalled, slot, value -> value), capability.reject());
                case ALL_SETTLED ->
                    List.of(elementFunction(alreadyCalled, slot, value -> outcome(ctx, "fulfilled", "value", value)),
                            elementFunction(alreadyCalled, slot, reason -> outcome(ctx, "rejected", "reason", reason)));
                case ANY -> List.of(capability.resolve(), elementFunction(alreadyCalled, slot, reason -> reason));
            };
        }

        private JsNativeFunction elementFunction(boolean[] alreadyCalled, int slot, UnaryOperator<JsValue> mapper) {
            final var fn = new JsNativeFunction("", (_, args) -> {
                if (alreadyCalled[0]) {
                    return JsUndefined.getInstance();
                }
                alreadyCalled[0] = true;
                values.set(slot, mapper.apply(arg(args, 0)));
                remaining[0]--;
                if (remaining[0] == 0) {
                    settle();
                }
                return JsUndefined.getInstance();
            });
            fn.setLength(1);
            return fn;
        }

        private void finish() {
            remaining[0]--;
            if (remaining[0] == 0) {
                settle();
            }
        }

        private void settle() {
            if (variant == Variant.ANY) {
                final var aggregate = ctx.intrinsics().makeError("AggregateError", "All promises were rejected");
                aggregate.set("errors", new JsArray(new ArrayList<>(values)));
                call(ctx, capability.reject(), aggregate);
                return;
            }
            call(ctx, capability.resolve(), keyed ? keyedResult() : new JsArray(new ArrayList<>(values)));
        }

        private JsValue keyedResult() {
            final var result = new JsObject();
            for (var i = 0; i < keys.size(); i++) {
                final var key = keys.get(i);
                if (key instanceof JsSymbol symbol) {
                    result.setSymbol(symbol, values.get(i));
                } else {
                    result.set(JsCoercion.toStr(key), values.get(i));
                }
            }
            return result;
        }
    }

    private static JsObject outcome(Ctx ctx, String status, String field, JsValue value) {
        final var entry = new JsObject();
        entry.setProto(ctx.intrinsics().objectProto());
        entry.set("status", new JsString(status));
        entry.set(field, value);
        return entry;
    }

    // ---------------------------------------------------------------------
    // GetIterator / IteratorStepValue / IteratorClose
    // ---------------------------------------------------------------------

    private static final class IteratorRecord {
        private final JsValue iterator;
        private final JsValue nextMethod;
        private boolean done;

        private IteratorRecord(JsValue iterator, JsValue nextMethod) {
            this.iterator = iterator;
            this.nextMethod = nextMethod;
        }

        private static IteratorRecord open(Ctx ctx, JsValue source) {
            final var method = ctx.ops().getMember(source, JsSymbol.ITERATOR);
            if (!isCallable(method)) {
                throw new TypeErrorException(JsCoercion.toStr(source) + " is not iterable");
            }
            final var iterator = ctx.invoker().call(method, source, List.of());
            if (!isObjectLike(iterator)) {
                throw new TypeErrorException("Result of Symbol.iterator method is not an object");
            }
            return new IteratorRecord(iterator, ctx.ops().getMember(iterator, new JsString("next")));
        }

        // Returns the next value, or null once the iterator is exhausted.
        private JsValue stepValue(Ctx ctx) {
            if (done) {
                return null;
            }
            final JsValue result;
            try {
                result = ctx.invoker().call(nextMethod, iterator, List.of());
            } catch (SimpleJsRuntimeException error) {
                done = true;
                throw error;
            }
            if (!isObjectLike(result)) {
                done = true;
                throw new TypeErrorException("Iterator result is not an object");
            }
            try {
                if (JsCoercion.toBoolean(ctx.ops().getMember(result, new JsString("done")))) {
                    done = true;
                    return null;
                }
                return ctx.ops().getMember(result, new JsString("value"));
            } catch (SimpleJsRuntimeException error) {
                done = true;
                throw error;
            }
        }

        private SimpleJsRuntimeException closeOnAbrupt(Ctx ctx, SimpleJsRuntimeException error) {
            if (done) {
                return error;
            }
            done = true;
            try {
                final var returnFn = ctx.ops().getMember(iterator, new JsString("return"));
                if (isCallable(returnFn)) {
                    ctx.invoker().call(returnFn, iterator, List.of());
                }
            } catch (SimpleJsRuntimeException ignored) {
                // the original completion wins over anything the iterator's `return` throws
            }
            return error;
        }
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private static JsValue speciesConstructor(Ctx ctx, JsValue receiver) {
        final var fallback = ctx.defaultConstructor();
        final var constructor = ctx.ops().getMember(receiver, new JsString("constructor"));
        if (constructor instanceof JsUndefined) {
            return fallback;
        }
        if (!isObjectLike(constructor)) {
            throw new TypeErrorException("Promise constructor property is not an object");
        }
        final var species = ctx.ops().getMember(constructor, JsSymbol.SPECIES);
        if (InterpreterUtils.isNullish(species)) {
            // %Promise%[Symbol.species] is an accessor returning its receiver, so a constructor that
            // inherits it - Promise itself or a subclass - selects itself, not the default.
            return inheritsPromiseSpecies(constructor, fallback) ? constructor : fallback;
        }
        if (!isConstructor(species)) {
            throw new TypeErrorException("Promise species is not a constructor");
        }
        return species;
    }

    private static boolean inheritsPromiseSpecies(JsValue constructor, JsValue promiseConstructor) {
        return constructor == promiseConstructor
                || constructor instanceof JsClass cls && cls.findNativeSuperClass() == promiseConstructor;
    }

    private static JsPromise requirePromise(JsValue receiver) {
        if (receiver instanceof JsPromise promise) {
            return promise;
        }
        if (receiver instanceof JsObject object && object.getPrimitive() instanceof JsPromise wrapped) {
            return wrapped;
        }
        throw new TypeErrorException(
                "Promise.prototype.then called on an incompatible receiver " + JsCoercion.toStr(receiver));
    }

    private static boolean isPromise(JsValue value) {
        return value instanceof JsPromise
                || value instanceof JsObject object && object.getPrimitive() instanceof JsPromise;
    }

    private static boolean sameValue(JsValue left, JsValue right) {
        return left == right;
    }

    private static JsValue invoke(Ctx ctx, JsValue target, List<JsValue> args) {
        final var fn = ctx.ops().getMember(target, new JsString("then"));
        if (!isCallable(fn)) {
            throw new TypeErrorException("then" + " is not a function");
        }
        return ctx.invoker().call(fn, target, args);
    }

    private static void call(Ctx ctx, JsValue fn, JsValue argument) {
        ctx.invoker().call(fn, JsUndefined.getInstance(), List.of(argument));
    }

    private static JsValue errorValue(Ctx ctx, SimpleJsRuntimeException error) {
        return InterpreterUtils.toErrorValue(error, ctx.intrinsics());
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return args.size() > index ? args.get(index) : JsUndefined.getInstance();
    }
}
