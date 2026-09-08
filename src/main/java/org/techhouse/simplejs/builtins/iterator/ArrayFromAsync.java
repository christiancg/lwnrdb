package org.techhouse.simplejs.builtins.iterator;

import static org.techhouse.simplejs.builtins.AsyncIteratorBuiltins.guarded;
import static org.techhouse.simplejs.builtins.AsyncIteratorBuiltins.syncToAsync;
import static org.techhouse.simplejs.builtins.AsyncIteratorBuiltins.toPromise;
import static org.techhouse.simplejs.builtins.BuiltinArgs.arg0;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.values.JsLimits.MAX_ARRAY_LENGTH;
import static org.techhouse.simplejs.values.JsLimits.MAX_SAFE_INTEGER;

import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ArrayFromAsync {
    public static JsValue fromAsync(InterpreterOps ops, EventLoop loop, JsValue receiver, List<JsValue> args) {
        final var out = new JsPromise(loop);
        try {
            final var items = arg0(args);
            final var mapFn = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
            final var mapThis = args.size() > 2 ? args.get(2) : JsUndefined.getInstance();
            if (!(mapFn instanceof JsUndefined) && !isCallable(mapFn)) {
                throw new TypeErrorException("Array.fromAsync mapfn is not a function");
            }
            final var mapper = mapFn instanceof JsUndefined ? null : mapFn;
            final var iterator = openForFromAsync(ops, loop, items);
            if (iterator == null) {
                fromArrayLike(ops, loop, receiver, items, mapper, mapThis, out);
            } else {
                final var target = InterpreterUtils.isConstructor(receiver)
                        ? ops.construct(receiver, List.of())
                        : new JsArray();
                fromIterator(ops, loop, new AsyncDriver(ops, loop, iterator), target, mapper, mapThis, new long[]{0},
                        out);
            }
        } catch (SimpleJsRuntimeException error) {
            out.reject(InterpreterUtils.toErrorValue(error, out.intrinsics()));
        }
        return out;
    }

    public static JsValue openForFromAsync(InterpreterOps ops, EventLoop loop, JsValue items) {
        final var asyncMethod = ops.getMember(items, JsSymbol.ASYNC_ITERATOR);
        if (!InterpreterUtils.isNullish(asyncMethod)) {
            if (!isCallable(asyncMethod)) {
                throw new TypeErrorException("Symbol.asyncIterator is not a function");
            }
            return ops.call(asyncMethod, items, List.of());
        }
        final var syncMethod = ops.getMember(items, JsSymbol.ITERATOR);
        if (InterpreterUtils.isNullish(syncMethod)) {
            return null;
        }
        if (!isCallable(syncMethod)) {
            throw new TypeErrorException("Symbol.iterator is not a function");
        }
        return syncToAsync(ops, loop, ops.call(syncMethod, items, List.of()));
    }

    public static void fromIterator(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue target,
            JsValue mapper, JsValue mapThis, long[] index, JsPromise out) {
        source.step().subscribe(result -> {
            if (source.isDone(result)) {
                finishFromAsync(ops, target, index[0], out);
                return;
            }
            final var value = source.valueOf(result);
            mapThenStore(ops, loop, source, target, mapper, mapThis, index, value, out,
                    () -> fromIterator(ops, loop, source, target, mapper, mapThis, index, out));
        }, reason -> closeAndReject(source, out, reason));
    }

    public static void fromArrayLike(InterpreterOps ops, EventLoop loop, JsValue receiver, JsValue items,
            JsValue mapper, JsValue mapThis, JsPromise out) {
        if (InterpreterUtils.isNullish(items)) {
            throw new TypeErrorException("Array.fromAsync requires an array-like or iterable object");
        }
        final var length = arrayLikeLength(ops, items);
        final JsValue target;
        if (InterpreterUtils.isConstructor(receiver)) {
            target = ops.construct(receiver, List.of(new JsNumber(length)));
        } else {
            if (length > MAX_ARRAY_LENGTH) {
                throw new RangeErrorException("Invalid array length");
            }
            target = new JsArray();
        }
        arrayLikeStep(ops, loop, target, items, mapper, mapThis, new long[]{0}, length, out);
    }

    public static void arrayLikeStep(InterpreterOps ops, EventLoop loop, JsValue target, JsValue items, JsValue mapper,
            JsValue mapThis, long[] index, long length, JsPromise out) {
        if (index[0] >= length) {
            finishFromAsync(ops, target, length, out);
            return;
        }
        guarded(out, () -> {
            final var raw = ops.getMember(items, new JsString(Long.toString(index[0])));
            toPromise(loop, raw).subscribe(
                    value -> mapThenStore(ops, loop, null, target, mapper, mapThis, index, value, out,
                            () -> arrayLikeStep(ops, loop, target, items, mapper, mapThis, index, length, out)),
                    out::reject);
        });
    }

    public static void mapThenStore(InterpreterOps ops, EventLoop loop, AsyncDriver source, JsValue target,
            JsValue mapper, JsValue mapThis, long[] index, JsValue value, JsPromise out, Runnable next) {
        if (mapper == null) {
            if (storeElement(ops, target, index, value, source, out)) {
                next.run();
            }
            return;
        }
        final JsValue mapped;
        try {
            mapped = ops.call(mapper, mapThis, List.of(value, new JsNumber(index[0])));
        } catch (SimpleJsRuntimeException error) {
            closeAndReject(source, out, InterpreterUtils.toErrorValue(error, out.intrinsics()));
            return;
        }
        toPromise(loop, mapped).subscribe(awaited -> {
            if (storeElement(ops, target, index, awaited, source, out)) {
                next.run();
            }
        }, reason -> closeAndReject(source, out, reason));
    }

    public static boolean storeElement(InterpreterOps ops, JsValue target, long[] index, JsValue value,
            AsyncDriver source, JsPromise out) {
        try {
            createDataPropertyOrThrow(ops, target, index[0], value);
        } catch (SimpleJsRuntimeException error) {
            closeAndReject(source, out, InterpreterUtils.toErrorValue(error, out.intrinsics()));
            return false;
        }
        index[0]++;
        return true;
    }

    public static long arrayLikeLength(InterpreterOps ops, JsValue items) {
        final var raw = JsCoercion.toNumber(ops.getMember(items, new JsString("length")), ops);
        if (Double.isNaN(raw) || raw <= 0) {
            return 0;
        }
        return (long) Math.min(raw, MAX_SAFE_INTEGER);
    }

    public static void closeAndReject(AsyncDriver source, JsPromise out, JsValue reason) {
        if (source != null) {
            source.close();
        }
        out.reject(reason);
    }

    public static void finishFromAsync(InterpreterOps ops, JsValue target, long length, JsPromise out) {
        guarded(out, () -> {
            if (!ops.setMember(target, new JsString("length"), new JsNumber(length))) {
                throw new TypeErrorException("Cannot assign to read only property 'length'");
            }
            out.resolve(target);
        });
    }

    public static void createDataPropertyOrThrow(InterpreterOps ops, JsValue target, long index, JsValue value) {
        final var key = new JsString(Long.toString(index));
        if (target instanceof JsArray array) {
            if (!array.set((int) index, value)) {
                throw new TypeErrorException("Cannot define property " + index + ", object is not extensible");
            }
            return;
        }
        final var descriptor = new JsObject();
        descriptor.set("value", value);
        descriptor.set("writable", JsBoolean.of(true));
        descriptor.set("enumerable", JsBoolean.of(true));
        descriptor.set("configurable", JsBoolean.of(true));
        if (!ops.defineProperty(target, key, descriptor)) {
            throw new TypeErrorException("Cannot define property " + index);
        }
    }

    private ArrayFromAsync() {
    }
}
