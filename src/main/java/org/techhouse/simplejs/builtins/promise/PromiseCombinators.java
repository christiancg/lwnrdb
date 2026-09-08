package org.techhouse.simplejs.builtins.promise;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.PromiseBuiltins.call;
import static org.techhouse.simplejs.builtins.PromiseBuiltins.errorValue;
import static org.techhouse.simplejs.builtins.PromiseBuiltins.invoke;
import static org.techhouse.simplejs.builtins.PromiseBuiltins.newPromiseCapability;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.List;
import org.techhouse.simplejs.builtins.PromiseBuiltins.Capability;
import org.techhouse.simplejs.builtins.PromiseBuiltins.Ctx;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsValue;

public final class PromiseCombinators {
    public static JsNativeFunction combinator(Ctx ctx, String name, Variant variant, boolean keyed) {
        final var fn = new JsNativeFunction(name,
                (receiver, args) -> combine(ctx, receiver, arg(args, 0), variant, keyed));
        fn.setLength(1);
        return fn;
    }

    public static JsValue combine(Ctx ctx, JsValue receiver, JsValue source, Variant variant, boolean keyed) {
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

    public static JsValue race(Ctx ctx, JsValue receiver, JsValue source) {
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

    public static JsValue getPromiseResolve(Ctx ctx, JsValue constructor) {
        final var resolve = ctx.ops().getMember(constructor, new JsString("resolve"));
        if (!isCallable(resolve)) {
            throw new TypeErrorException("Promise resolve is not a function");
        }
        return resolve;
    }

    public static void performIterated(Ctx ctx, JsValue source, JsValue constructor, JsValue promiseResolve,
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

    public static void performKeyed(Ctx ctx, JsValue source, JsValue constructor, JsValue promiseResolve,
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

    public static JsObject outcome(Ctx ctx, String status, String field, JsValue value) {
        final var entry = new JsObject();
        entry.setProto(ctx.intrinsics().objectProto);
        entry.set("status", new JsString(status));
        entry.set(field, value);
        return entry;
    }

    private PromiseCombinators() {
    }
}
