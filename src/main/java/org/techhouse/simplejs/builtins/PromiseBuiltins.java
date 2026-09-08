package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.promise.PromiseCombinators.combinator;
import static org.techhouse.simplejs.builtins.promise.PromiseCombinators.race;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isConstructor;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.List;
import java.util.function.Consumer;
import org.techhouse.simplejs.builtins.promise.Variant;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
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

    public record Capability(JsValue promise, JsValue resolve, JsValue reject) {
    }

    public record Ctx(EventLoop loop, Invoker invoker, Intrinsics intrinsics) {
        public InterpreterOps ops() {
            return loop.ops();
        }

        public JsValue defaultConstructor() {
            return intrinsics.promiseProto.get("constructor");
        }
    }

    public static JsNativeFunction create(EventLoop eventLoop, Invoker invoker, Intrinsics intrinsics) {
        final var ctx = new Ctx(eventLoop, invoker, intrinsics);
        final var promise = new JsNativeFunction("Promise", (thisArg, args) -> {
            final var newTarget = JsNativeFunction.currentNewTarget();
            if ((newTarget == null || newTarget instanceof JsUndefined) && !(thisArg instanceof JsObject)) {
                throw new TypeErrorException("Constructor Promise requires 'new'");
            }
            if (!isCallable(arg(args, 0))) {
                throw new TypeErrorException("Promise resolver is not a function");
            }
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

    public static Capability newPromiseCapability(Ctx ctx, JsValue constructor) {
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
        result.setProto(ctx.intrinsics().objectProto);
        result.set("promise", capability.promise());
        result.set("resolve", capability.resolve());
        result.set("reject", capability.reject());
        return result;
    }

    public static JsValue tryCall(Ctx ctx, JsValue receiver, List<JsValue> args) {
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

    private static JsValue resolveStatic(Ctx ctx, JsValue receiver, JsValue value) {
        if (!isObjectLike(receiver)) {
            throw new TypeErrorException("Promise.resolve called on a non-object");
        }
        return promiseResolve(ctx, receiver, value);
    }

    public static JsValue promiseResolve(Ctx ctx, JsValue constructor, JsValue value) {
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

    public static JsValue invoke(Ctx ctx, JsValue target, List<JsValue> args) {
        final var fn = ctx.ops().getMember(target, new JsString("then"));
        if (!isCallable(fn)) {
            throw new TypeErrorException("then" + " is not a function");
        }
        return ctx.invoker().call(fn, target, args);
    }

    public static void call(Ctx ctx, JsValue fn, JsValue argument) {
        ctx.invoker().call(fn, JsUndefined.getInstance(), List.of(argument));
    }

    public static JsValue errorValue(Ctx ctx, SimpleJsRuntimeException error) {
        return InterpreterUtils.toErrorValue(error, ctx.intrinsics());
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return args.size() > index ? args.get(index) : JsUndefined.getInstance();
    }
}
