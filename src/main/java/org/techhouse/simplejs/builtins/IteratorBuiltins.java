package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg0;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.drop;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.filter;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.find;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.flatMap;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.forEach;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.map;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.matchAll;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.matchAny;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.reduce;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.take;
import static org.techhouse.simplejs.builtins.iterator.IteratorHelpers.toArray;
import static org.techhouse.simplejs.builtins.iterator.IteratorPrototypes.helperPrototype;
import static org.techhouse.simplejs.builtins.iterator.IteratorPrototypes.installDispose;
import static org.techhouse.simplejs.builtins.iterator.IteratorPrototypes.installIgnoringAccessor;
import static org.techhouse.simplejs.builtins.iterator.IteratorPrototypes.installIteratorSymbol;
import static org.techhouse.simplejs.builtins.iterator.IteratorPrototypes.wrapPrototype;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.allowPartial;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.chunks;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.concat;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.includes;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.join;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.separator;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.skipCount;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.windowSize;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.windows;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.zip;
import static org.techhouse.simplejs.builtins.iterator.IteratorZip.zipKeyed;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.values.JsLimits.MAX_SAFE_INTEGER;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import org.techhouse.simplejs.builtins.iterator.IteratorPrototypes.WrapState;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class IteratorBuiltins {
    public static final Set<String> HELPERS = Set.of("map", "filter", "take", "drop", "flatMap", "reduce", "toArray",
            "forEach", "some", "every", "find", "chunks", "windows", "includes", "join");

    public static final Set<String> ZERO_ARG_HELPERS = Set.of("toArray");

    public static final Set<String> CALLBACK_HELPERS = Set.of("map", "filter", "flatMap", "reduce", "forEach", "some",
            "every", "find");

    public static final Map<JsObject, JsObject[]> REALM_PROTOS = Collections.synchronizedMap(new WeakHashMap<>());
    public static final Map<JsObject, HelperState> HELPER_STATE = Collections.synchronizedMap(new WeakHashMap<>());
    public static final Map<JsObject, WrapState> WRAP_STATE = Collections.synchronizedMap(new WeakHashMap<>());

    private IteratorBuiltins() {
    }

    public static boolean isHelperName(String name) {
        return HELPERS.contains(name);
    }

    public static JsNativeFunction create(InterpreterOps ops, JsObject objectProto) {
        final var ctor = new JsNativeFunction("Iterator", (thisArg, _) -> {
            if (thisArg instanceof JsUndefined) {
                throw new TypeErrorException("Abstract class Iterator not directly constructable");
            }
            return thisArg;
        });
        final var prototype = new JsObject();
        prototype.setProto(objectProto);
        REALM_PROTOS.put(objectProto,
                new JsObject[]{helperPrototype(prototype, objectProto), wrapPrototype(ops, prototype, objectProto)});
        for (final var name : HELPERS) {
            Intrinsics.defineHidden(prototype, name, helper(ops, name, objectProto));
        }
        installIteratorSymbol(prototype);
        installDispose(ops, prototype);
        installIgnoringAccessor(prototype, "@@toStringTag", JsSymbol.TO_STRING_TAG, new JsString("Iterator"), ops);
        installIgnoringAccessor(prototype, "constructor", null, ctor, ops);
        ctor.setProperty("prototype", prototype);
        ctor.ownProperties().setFlags("prototype", new PropertyFlags(false, false, false));
        ctor.setPrototype(prototype);
        ctor.markConstructor();
        ctor.setProperty("from", new JsNativeFunction("from", (_, args) -> from(ops, arg0(args), objectProto)));
        ctor.setProperty("concat", new JsNativeFunction("concat", (thisArg, args) -> {
            if (thisArg instanceof JsUndefined) {
                throw new TypeErrorException("Iterator.concat is not a constructor");
            }
            return concat(ops, args, objectProto);
        }));
        ctor.setProperty("zip", new JsNativeFunction("zip", (thisArg, args) -> {
            if (thisArg instanceof JsUndefined) {
                throw new TypeErrorException("Iterator.zip is not a constructor");
            }
            return zip(ops, arg0(args), args.size() > 1 ? args.get(1) : JsUndefined.getInstance(), objectProto);
        }));
        ctor.setProperty("zipKeyed", new JsNativeFunction("zipKeyed", (thisArg, args) -> {
            if (thisArg instanceof JsUndefined) {
                throw new TypeErrorException("Iterator.zipKeyed is not a constructor");
            }
            return zipKeyed(ops, arg0(args), args.size() > 1 ? args.get(1) : JsUndefined.getInstance(), objectProto);
        }));
        return ctor;
    }

    public static JsNativeFunction helper(InterpreterOps ops, String name, JsObject objectProto) {
        final var fn = new JsNativeFunction(name, (thisArg, args) -> dispatch(ops, name, thisArg, args, objectProto));
        fn.setLength(ZERO_ARG_HELPERS.contains(name) ? 0 : 1);
        return fn;
    }

    public static JsValue dispatch(InterpreterOps ops, String name, JsValue thisArg, List<JsValue> args,
            JsObject objectProto) {
        final var iterator = requireIterator(thisArg);
        if (CALLBACK_HELPERS.contains(name)) {
            final var fn = validated(ops, iterator, () -> callback(args));
            return withCallback(ops, name, new Driver(ops, iterator), fn, args, objectProto);
        }
        return switch (name) {
            case "take" -> {
                final var count = validated(ops, iterator, () -> limit(ops, arg0(args)));
                yield take(new Driver(ops, iterator), count, objectProto);
            }
            case "drop" -> {
                final var count = validated(ops, iterator, () -> limit(ops, arg0(args)));
                yield drop(new Driver(ops, iterator), count, objectProto);
            }
            case "chunks" -> {
                final var size = validated(ops, iterator, () -> windowSize(arg0(args), "chunkSize"));
                yield chunks(new Driver(ops, iterator), size, objectProto);
            }
            case "windows" -> {
                final var size = validated(ops, iterator, () -> windowSize(arg0(args), "windowSize"));
                final var partial = validated(ops, iterator, () -> allowPartial(args));
                yield windows(new Driver(ops, iterator), size, partial, objectProto);
            }
            case "includes" -> {
                final var skip = validated(ops, iterator, () -> skipCount(args));
                yield JsBoolean.of(includes(new Driver(ops, iterator), arg0(args), skip));
            }
            case "toArray" -> toArray(new Driver(ops, iterator));
            case "join" -> {
                final var separator = validated(ops, iterator, () -> separator(ops, args));
                yield join(ops, new Driver(ops, iterator), separator);
            }
            default -> JsUndefined.getInstance();
        };
    }

    public static <T> T validated(InterpreterOps ops, JsValue iterator, Supplier<T> validation) {
        try {
            return validation.get();
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            closeQuietly(ops, iterator);
            throw error;
        }
    }

    public static void closeQuietly(InterpreterOps ops, JsValue iterator) {
        try {
            final var returnFn = ops.getMember(iterator, new JsString("return"));
            if (isCallable(returnFn)) {
                ops.call(returnFn, iterator, List.of());
            }
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException ignored) {
        }
    }

    public static JsValue withCallback(InterpreterOps ops, String name, Driver source, JsValue fn, List<JsValue> args,
            JsObject objectProto) {
        return switch (name) {
            case "map" -> map(ops, source, fn, objectProto);
            case "filter" -> filter(ops, source, fn, objectProto);
            case "flatMap" -> flatMap(ops, source, fn, objectProto);
            case "reduce" -> reduce(ops, source, fn, args);
            case "forEach" -> forEach(ops, source, fn);
            case "some" -> JsBoolean.of(matchAny(ops, source, fn));
            case "every" -> JsBoolean.of(matchAll(ops, source, fn));
            default -> find(ops, source, fn);
        };
    }

    public static JsValue from(InterpreterOps ops, JsValue value, JsObject objectProto) {
        final var iterator = flattenable(ops, value);
        if (isIteratorInstance(ops, iterator, objectProto)) {
            return iterator;
        }
        final var wrapper = new JsObject();
        wrapper.setProto(REALM_PROTOS.get(objectProto)[1]);
        WRAP_STATE.put(wrapper, new WrapState(iterator, ops.getMember(iterator, new JsString("next"))));
        return wrapper;
    }

    public static JsValue flattenable(InterpreterOps ops, JsValue value) {
        if (!InterpreterUtils.isObjectLike(value) && !(value instanceof JsString)) {
            throw new TypeErrorException("Iterator.from called on a non-object");
        }
        return iteratorOf(ops, value);
    }

    public static boolean isIteratorInstance(InterpreterOps ops, JsValue iterator, JsObject objectProto) {
        final var target = REALM_PROTOS.get(objectProto)[0].getProto();
        var proto = ops.getPrototypeOf(iterator);
        while (proto != null && !(proto instanceof JsUndefined) && !InterpreterUtils.isNullish(proto)) {
            if (proto == target) {
                return true;
            }
            proto = ops.getPrototypeOf(proto);
        }
        return false;
    }

    public static JsObject lazyIterator(Supplier<JsValue> nextValue, Runnable onClose, JsObject objectProto) {
        final var iterator = new JsObject();
        final var protos = REALM_PROTOS.get(objectProto);
        if (protos != null) {
            iterator.setProto(protos[0]);
        }
        HELPER_STATE.put(iterator, new HelperState(nextValue, onClose));
        return iterator;
    }

    public static JsValue stepOf(JsValue value, HelperState state, JsObject objectProto) {
        if (value == null) {
            state.closed = true;
        }
        return step(value, objectProto);
    }

    public static JsObject step(JsValue value, JsObject objectProto) {
        final var result = new JsObject();
        result.setProto(objectProto);
        result.set("value", value == null ? JsUndefined.getInstance() : value);
        result.set("done", JsBoolean.of(value == null));
        return result;
    }

    public static JsValue iteratorOf(InterpreterOps ops, JsValue value) {
        final var iterFn = ops.getMember(value, JsSymbol.ITERATOR);
        if (InterpreterUtils.isNullish(iterFn)) {
            return requireIterator(value);
        }
        if (!isCallable(iterFn)) {
            throw new TypeErrorException("Symbol.iterator is not a function");
        }
        final var iterator = ops.call(iterFn, value, List.of());
        if (!InterpreterUtils.isObjectLike(iterator)) {
            throw new TypeErrorException("Symbol.iterator did not return an object");
        }
        return iterator;
    }

    public static JsValue requireIterator(JsValue value) {
        if (value == null || !InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Iterator helper called on non-iterator");
        }
        return value;
    }

    public static JsValue callback(List<JsValue> args) {
        final var fn = arg0(args);
        if (!isCallable(fn)) {
            throw new TypeErrorException("Iterator helper callback is not a function");
        }
        return fn;
    }

    public static long limit(InterpreterOps ops, JsValue value) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number)) {
            throw new RangeErrorException("Iterator helper limit must not be NaN");
        }
        if (!Double.isInfinite(number) && number > MAX_SAFE_INTEGER) {
            throw new RangeErrorException("Iterator helper limit is out of range");
        }
        final var integral = number < 0 ? Math.ceil(number) : Math.floor(number);
        if (integral < 0) {
            throw new RangeErrorException("Iterator helper limit must be a non-negative number");
        }
        return integral > MAX_SAFE_INTEGER ? Long.MAX_VALUE : (long) integral;
    }

    public static final class HelperState {
        public final Supplier<JsValue> nextValue;
        public final Runnable onClose;
        public boolean closed;
        public boolean running;

        public HelperState(Supplier<JsValue> nextValue, Runnable onClose) {
            this.nextValue = nextValue;
            this.onClose = onClose;
        }

        public JsValue run(Supplier<JsValue> body) {
            enter();
            try {
                return body.get();
            } finally {
                running = false;
            }
        }

        public void enter() {
            if (running) {
                throw new TypeErrorException("Iterator Helper is already running");
            }
            running = true;
        }
    }

    public static final class Driver {
        public final InterpreterOps ops;
        public final JsValue iterator;
        public final JsValue nextMethod;
        public boolean done;

        public Driver(InterpreterOps ops, JsValue iterator) {
            this.ops = ops;
            this.iterator = iterator;
            this.nextMethod = ops.getMember(iterator, new JsString("next"));
        }

        public JsValue next() {
            if (done) {
                return null;
            }
            final var nextFn = nextMethod;
            if (!isCallable(nextFn)) {
                throw new TypeErrorException("iterator.next is not a function");
            }
            final var step = ops.call(nextFn, iterator, List.of());
            if (JsCoercion.toBoolean(ops.getMember(step, new JsString("done")))) {
                done = true;
                return null;
            }
            return ops.getMember(step, new JsString("value"));
        }

        public void close() {
            if (done) {
                return;
            }
            done = true;
            final var returnFn = ops.getMember(iterator, new JsString("return"));
            if (isCallable(returnFn)) {
                ops.call(returnFn, iterator, List.of());
            }
        }

        public void closeAfterThrow() {
            try {
                close();
            } catch (ScriptAbortException abort) {
                throw abort;
            } catch (RuntimeException ignored) {
            }
        }
    }
}
