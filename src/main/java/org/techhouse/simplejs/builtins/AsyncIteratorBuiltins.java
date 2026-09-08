package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg0;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.drop;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.filter;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.find;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.flatMap;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.forEach;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.map;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.matchAll;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.matchAny;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.reduce;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.take;
import static org.techhouse.simplejs.builtins.iterator.AsyncIteratorHelpers.toArray;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.techhouse.simplejs.builtins.iterator.AsyncDriver;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class AsyncIteratorBuiltins {
    private static final Set<String> HELPERS = Set.of("map", "filter", "take", "drop", "flatMap", "reduce", "toArray",
            "forEach", "some", "every", "find");

    private AsyncIteratorBuiltins() {
    }

    public static boolean isHelperName(String name) {
        return HELPERS.contains(name);
    }

    public static JsNativeFunction create(InterpreterOps ops, EventLoop loop) {
        final var ctor = new JsNativeFunction("AsyncIterator", (_, _) -> {
            throw new TypeErrorException("Abstract class AsyncIterator not directly constructable");
        });
        final var prototype = new JsObject();
        for (final var name : HELPERS) {
            Intrinsics.defineHidden(prototype, name, helper(ops, loop, name));
        }
        prototype.setSymbol(JsSymbol.ASYNC_ITERATOR,
                new JsNativeFunction("[Symbol.asyncIterator]", (thisArg, _) -> thisArg));
        prototype.setSymbolFlags(JsSymbol.ASYNC_ITERATOR, JsObject.PropertyFlags.HIDDEN);
        Intrinsics.installTag(prototype, "AsyncIterator");
        final var asyncDispose = new JsNativeFunction("[Symbol.asyncDispose]",
                (thisArg, _) -> asyncDispose(ops, loop, thisArg));
        asyncDispose.setLength(0);
        prototype.setSymbol(JsSymbol.ASYNC_DISPOSE, asyncDispose);
        prototype.setSymbolFlags(JsSymbol.ASYNC_DISPOSE, JsObject.PropertyFlags.HIDDEN);
        prototype.defineValue("constructor", ctor);
        prototype.setFlags("constructor", JsObject.PropertyFlags.HIDDEN);
        ctor.setProperty("prototype", prototype);
        ctor.setProperty("from", new JsNativeFunction("from", (_, args) -> getAsyncIterator(ops, loop, arg0(args))));
        ctor.setPrototype(prototype);
        ctor.markConstructor();
        return ctor;
    }

    public static JsNativeFunction helper(InterpreterOps ops, EventLoop loop, String name) {
        return new JsNativeFunction(name, (thisArg, args) -> dispatch(ops, loop, name, thisArg, args));
    }

    private static JsValue dispatch(InterpreterOps ops, EventLoop loop, String name, JsValue thisArg,
            List<JsValue> args) {
        final var source = new AsyncDriver(ops, loop, requireIterator(thisArg));
        return switch (name) {
            case "map" -> map(ops, loop, source, callback(args));
            case "filter" -> filter(ops, loop, source, callback(args));
            case "take" -> take(loop, source, limit(arg0(args)));
            case "drop" -> drop(loop, source, limit(arg0(args)));
            case "flatMap" -> flatMap(ops, loop, source, callback(args));
            case "reduce" -> reduce(ops, loop, source, callback(args), args);
            case "toArray" -> toArray(loop, source);
            case "forEach" -> forEach(ops, loop, source, callback(args));
            case "some" -> matchAny(ops, loop, source, callback(args));
            case "every" -> matchAll(ops, loop, source, callback(args));
            case "find" -> find(ops, loop, source, callback(args));
            default -> JsUndefined.getInstance();
        };
    }

    public static JsObject asyncIterator(Supplier<JsPromise> nextStep) {
        final var iterator = new JsObject();
        iterator.set("next", new JsNativeFunction("next", (_, _) -> nextStep.get()));
        iterator.setSymbol(JsSymbol.ASYNC_ITERATOR, new JsNativeFunction("[Symbol.asyncIterator]", (_, _) -> iterator));
        return iterator;
    }

    public static JsValue getAsyncIterator(InterpreterOps ops, EventLoop loop, JsValue value) {
        final var asyncIterFn = ops.getMember(value, JsSymbol.ASYNC_ITERATOR);
        if (isCallable(asyncIterFn)) {
            return ops.call(asyncIterFn, value, List.of());
        }
        final var syncIterFn = ops.getMember(value, JsSymbol.ITERATOR);
        if (isCallable(syncIterFn)) {
            return syncToAsync(ops, loop, ops.call(syncIterFn, value, List.of()));
        }
        return requireIterator(value);
    }

    public static JsValue syncToAsync(InterpreterOps ops, EventLoop loop, JsValue syncIterator) {
        final var wrapper = asyncIterator(() -> {
            final var out = new JsPromise(loop);
            final var nextFn = ops.getMember(syncIterator, new JsString("next"));
            if (!isCallable(nextFn)) {
                out.reject(InterpreterUtils.toErrorValue(new TypeErrorException("iterator.next is not a function"),
                        out.intrinsics()));
                return out;
            }
            guarded(out, () -> {
                final var result = ops.call(nextFn, syncIterator, List.of());
                if (JsCoercion.toBoolean(ops.getMember(result, new JsString("done")))) {
                    out.resolve(step(JsUndefined.getInstance(), true));
                } else {
                    toPromise(loop, ops.getMember(result, new JsString("value")))
                            .subscribe(value -> out.resolve(step(value, false)), out::reject);
                }
            });
            return out;
        });
        wrapper.set("return", new JsNativeFunction("return", (_, _) -> {
            final var returnFn = ops.getMember(syncIterator, new JsString("return"));
            if (isCallable(returnFn)) {
                ops.call(returnFn, syncIterator, List.of());
            }
            final var out = new JsPromise(loop);
            out.resolve(step(JsUndefined.getInstance(), true));
            return out;
        }));
        return wrapper;
    }

    private static JsValue asyncDispose(InterpreterOps ops, EventLoop loop, JsValue receiver) {
        final var out = new JsPromise(loop);
        guarded(out, () -> {
            final var returnMethod = ops.getMember(receiver, new JsString("return"));
            if (returnMethod instanceof JsUndefined || returnMethod instanceof JsNull) {
                out.resolve(JsUndefined.getInstance());
                return;
            }
            if (!isCallable(returnMethod)) {
                throw new TypeErrorException("The iterator's 'return' property is not callable");
            }
            toPromise(loop, ops.call(returnMethod, receiver, List.of()))
                    .subscribe(_ -> out.resolve(JsUndefined.getInstance()), out::reject);
        });
        return out;
    }

    public static JsPromise toPromise(EventLoop loop, JsValue value) {
        final var promise = new JsPromise(loop);
        promise.resolve(value);
        return promise;
    }

    public static JsObject step(JsValue value, boolean done) {
        final var result = new JsObject();
        result.set("value", value == null ? JsUndefined.getInstance() : value);
        result.set("done", JsBoolean.of(done));
        return result;
    }

    public static void guarded(JsPromise out, Runnable body) {
        try {
            body.run();
        } catch (SimpleJsRuntimeException error) {
            out.reject(InterpreterUtils.toErrorValue(error, out.intrinsics()));
        }
    }

    public static JsValue requireIterator(JsValue value) {
        if (value instanceof JsUndefined || value == null) {
            throw new TypeErrorException("Async iterator helper called on non-iterator");
        }
        return value;
    }

    public static JsValue callback(List<JsValue> args) {
        final var fn = arg0(args);
        if (!isCallable(fn)) {
            throw new TypeErrorException("Async iterator helper callback is not a function");
        }
        return fn;
    }

    public static long limit(JsValue value) {
        final var number = JsCoercion.toNumber(value);
        if (Double.isNaN(number) || number < 0) {
            throw new RangeErrorException("Async iterator helper limit must be a non-negative number");
        }
        return (long) number;
    }

}
