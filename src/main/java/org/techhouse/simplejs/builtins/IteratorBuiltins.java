package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.SameValueZero;

/**
 * ES2025 iterator helpers. The {@code Iterator} global exposes {@code Iterator.from} plus a
 * {@code prototype} carrying the helpers; the interpreter also routes helper names on generators
 * and on any iterator-like object (one with an own callable {@code next}) to {@link #helper}, so a
 * lazy helper's own result chains too. Helpers drive their receiver iterator directly through the
 * {@link InterpreterOps} seam (GetIteratorDirect — no {@code Symbol.iterator} re-invocation).
 */
public final class IteratorBuiltins {
    private static final Set<String> HELPERS = Set.of("map", "filter", "take", "drop", "flatMap", "reduce", "toArray",
            "forEach", "some", "every", "find", "chunks", "windows", "includes", "join");

    private static final Set<String> ZERO_ARG_HELPERS = Set.of("toArray");

    private static final Set<String> CALLBACK_HELPERS = Set.of("map", "filter", "flatMap", "reduce", "forEach", "some",
            "every", "find");

    private static final double MAX_SAFE_INTEGER = 9007199254740991d;
    private static final double MAX_WINDOW_SIZE = 4294967295d;
    private static final PropertyFlags HIDDEN = new PropertyFlags(true, false, true);
    private static final PropertyFlags TAG = new PropertyFlags(false, false, true);

    // The realm's %IteratorHelperPrototype% and %WrapForValidIteratorPrototype%, keyed by the realm's
    // Object.prototype: the interpreter routes a helper onto an arbitrary iterator-like object without
    // an Intrinsics reference, and a per-realm entry keeps one script's monkey-patch out of another's.
    private static final Map<JsObject, JsObject[]> REALM_PROTOS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<JsObject, HelperState> HELPER_STATE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<JsObject, WrapState> WRAP_STATE = Collections.synchronizedMap(new WeakHashMap<>());

    private IteratorBuiltins() {
    }

    public static boolean isHelperName(String name) {
        return HELPERS.contains(name);
    }

    public static JsNativeFunction create(InterpreterOps ops, JsObject objectProto) {
        // Direct construction passes JsUndefined as thisArg; a super() call from a subclass passes
        // the instance under construction instead (see ClassEvaluator.applyNativeSuper), which is the
        // only signal that distinguishes the spec-legal super() case from a direct `new Iterator()`.
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
        // The dedicated field (not just the "prototype" own property) is what `instanceof`/`new`
        // consult for a JsNativeFunction (see ClassEvaluator.evalInstanceof/Interpreter.constructValue)
        // - GlobalScope must not overwrite this with the unrelated Generator.prototype intrinsic.
        ctor.setPrototype(prototype);
        ctor.markConstructor();
        ctor.setProperty("from", new JsNativeFunction("from", (_, args) -> from(ops, arg0(args), objectProto)));
        ctor.setProperty("concat", new JsNativeFunction("concat", (thisArg, args) -> {
            // `constructNative`'s fallback path passes JsUndefined as thisArg specifically to signal
            // a `new` call (there being no other construct-vs-call distinction it can make for a
            // plain utility native function), the same signal the Iterator/TypedArray abstract
            // constructors above use - Iterator.concat is a non-constructor per spec.
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

    private static void installIteratorSymbol(JsObject prototype) {
        final var iterator = new JsNativeFunction("[Symbol.iterator]", (thisArg, _) -> thisArg);
        iterator.setLength(0);
        prototype.setSymbol(JsSymbol.ITERATOR, iterator);
        prototype.setSymbolFlags(JsSymbol.ITERATOR, HIDDEN);
    }

    // %IteratorPrototype%[@@dispose]: GetMethod(this, "return") then call it, discarding its result.
    private static void installDispose(InterpreterOps ops, JsObject prototype) {
        final var dispose = new JsNativeFunction("[Symbol.dispose]", (thisArg, _) -> {
            final var returnFn = ops.getMember(thisArg, new JsString("return"));
            if (!InterpreterUtils.isNullish(returnFn)) {
                if (!isCallable(returnFn)) {
                    throw new TypeErrorException("iterator.return is not a function");
                }
                ops.call(returnFn, thisArg, List.of());
            }
            return JsUndefined.getInstance();
        });
        dispose.setLength(0);
        prototype.setSymbol(JsSymbol.DISPOSE, dispose);
        prototype.setSymbolFlags(JsSymbol.DISPOSE, HIDDEN);
    }

    // SetterThatIgnoresPrototypeProperties: `Iterator.prototype.constructor` and its @@toStringTag are
    // accessors whose setter refuses to write through to the home object but still defines the property
    // on a derived receiver, so a subclass prototype can shadow them without patching the intrinsic.
    private static void installIgnoringAccessor(JsObject home, String label, JsSymbol symbolKey, JsValue value,
            InterpreterOps ops) {
        final var getter = new JsNativeFunction("get " + label, (_, _) -> value);
        getter.setLength(0);
        final var setter = new JsNativeFunction("set " + label, (thisArg, args) -> {
            if (!InterpreterUtils.isObjectLike(thisArg)) {
                throw new TypeErrorException("Cannot set " + label + " on a non-object");
            }
            if (thisArg == home) {
                throw new TypeErrorException(label + " is not writable on Iterator.prototype");
            }
            final var key = symbolKey == null ? new JsString(label) : symbolKey;
            final var incoming = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
            if (ops.getOwnPropertyDescriptor(thisArg, key) instanceof JsUndefined) {
                ops.defineProperty(thisArg, key, descriptor(incoming));
            } else {
                ops.setMember(thisArg, key, incoming);
            }
            return JsUndefined.getInstance();
        });
        setter.setLength(1);
        if (symbolKey == null) {
            home.defineAccessor(label, getter, setter);
            home.setFlags(label, HIDDEN);
        } else {
            home.defineSymbolAccessor(symbolKey, getter, setter);
            home.setSymbolFlags(symbolKey, HIDDEN);
        }
    }

    private static JsObject descriptor(JsValue value) {
        final var descriptor = new JsObject();
        descriptor.set("value", value);
        descriptor.set("writable", JsBoolean.TRUE);
        descriptor.set("enumerable", JsBoolean.TRUE);
        descriptor.set("configurable", JsBoolean.TRUE);
        return descriptor;
    }

    // %IteratorHelperPrototype%: next/return live here (not on the instance) and brand-check their
    // receiver, and the running flag is what turns a helper re-entered from its own callback into the
    // spec's "generator is already running" TypeError instead of unbounded recursion.
    private static JsObject helperPrototype(JsObject iteratorProto, JsObject objectProto) {
        final var proto = new JsObject();
        proto.setProto(iteratorProto);
        final var next = new JsNativeFunction("next", (thisArg, _) -> {
            final var state = requireHelper(thisArg);
            return state.run(() -> stepOf(state.closed ? null : state.nextValue.get(), state, objectProto));
        });
        next.setLength(0);
        Intrinsics.defineHidden(proto, "next", next);
        final var close = new JsNativeFunction("return", (thisArg, _) -> {
            final var state = requireHelper(thisArg);
            return state.run(() -> {
                if (!state.closed) {
                    state.closed = true;
                    state.onClose.run();
                }
                return step(null, objectProto);
            });
        });
        close.setLength(0);
        Intrinsics.defineHidden(proto, "return", close);
        proto.setSymbol(JsSymbol.TO_STRING_TAG, new JsString("Iterator Helper"));
        proto.setSymbolFlags(JsSymbol.TO_STRING_TAG, TAG);
        return proto;
    }

    // %WrapForValidIteratorPrototype%: Iterator.from's wrapper forwards to the iterator record it
    // captured, so `next` uses the method read at wrap time while `return` is looked up per call.
    private static JsObject wrapPrototype(InterpreterOps ops, JsObject iteratorProto, JsObject objectProto) {
        final var proto = new JsObject();
        proto.setProto(iteratorProto);
        final var next = new JsNativeFunction("next", (thisArg, _) -> {
            final var state = requireWrap(thisArg);
            if (!isCallable(state.nextMethod)) {
                throw new TypeErrorException("iterator.next is not a function");
            }
            return ops.call(state.nextMethod, state.iterator, List.of());
        });
        next.setLength(0);
        Intrinsics.defineHidden(proto, "next", next);
        final var close = new JsNativeFunction("return", (thisArg, _) -> {
            final var state = requireWrap(thisArg);
            final var returnFn = ops.getMember(state.iterator, new JsString("return"));
            if (InterpreterUtils.isNullish(returnFn)) {
                return step(null, objectProto);
            }
            if (!isCallable(returnFn)) {
                throw new TypeErrorException("iterator.return is not a function");
            }
            return ops.call(returnFn, state.iterator, List.of());
        });
        close.setLength(0);
        Intrinsics.defineHidden(proto, "return", close);
        return proto;
    }

    private static HelperState requireHelper(JsValue receiver) {
        final var state = receiver instanceof JsObject object ? HELPER_STATE.get(object) : null;
        if (state == null) {
            throw new TypeErrorException("Iterator Helper method called on an incompatible receiver");
        }
        return state;
    }

    private static WrapState requireWrap(JsValue receiver) {
        final var state = receiver instanceof JsObject object ? WRAP_STATE.get(object) : null;
        if (state == null) {
            throw new TypeErrorException("Iterator wrapper method called on an incompatible receiver");
        }
        return state;
    }

    public static JsNativeFunction helper(InterpreterOps ops, String name, JsObject objectProto) {
        final var fn = new JsNativeFunction(name, (thisArg, args) -> dispatch(ops, name, thisArg, args, objectProto));
        fn.setLength(ZERO_ARG_HELPERS.contains(name) ? 0 : 1);
        return fn;
    }

    // Spec order: the receiver is checked, then the argument (whose coercion may throw, and must be
    // observable *before* anything is read off the iterator), and only then does GetIteratorDirect
    // read `next` - which is why every arm evaluates its argument before constructing the Driver.
    // An abrupt argument validation closes the receiver first (IfAbruptCloseIterator).
    private static JsValue dispatch(InterpreterOps ops, String name, JsValue thisArg, List<JsValue> args,
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

    private static <T> T validated(InterpreterOps ops, JsValue iterator, Supplier<T> validation) {
        try {
            return validation.get();
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            closeQuietly(ops, iterator);
            throw error;
        }
    }

    private static void closeQuietly(InterpreterOps ops, JsValue iterator) {
        try {
            final var returnFn = ops.getMember(iterator, new JsString("return"));
            if (isCallable(returnFn)) {
                ops.call(returnFn, iterator, List.of());
            }
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException ignored) {
            // discarded on purpose: the original validation error is the one that propagates
        }
    }

    private static JsValue withCallback(InterpreterOps ops, String name, Driver source, JsValue fn, List<JsValue> args,
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

    // Iterator.from: GetIteratorFlattenable with iterate-string-primitives, then the wrapper is only
    // built when the result is not already an Iterator instance.
    private static JsValue from(InterpreterOps ops, JsValue value, JsObject objectProto) {
        final var iterator = flattenable(ops, value);
        if (isIteratorInstance(ops, iterator, objectProto)) {
            return iterator;
        }
        final var wrapper = new JsObject();
        wrapper.setProto(REALM_PROTOS.get(objectProto)[1]);
        WRAP_STATE.put(wrapper, new WrapState(iterator, ops.getMember(iterator, new JsString("next"))));
        return wrapper;
    }

    // Iterator.from is the one GetIteratorFlattenable caller that also accepts a string primitive.
    private static JsValue flattenable(InterpreterOps ops, JsValue value) {
        if (!InterpreterUtils.isObjectLike(value) && !(value instanceof JsString)) {
            throw new TypeErrorException("Iterator.from called on a non-object");
        }
        return iteratorOf(ops, value);
    }

    private static boolean isIteratorInstance(InterpreterOps ops, JsValue iterator, JsObject objectProto) {
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

    // Iterator.concat(...items): each item's Symbol.iterator method is fetched and validated
    // eagerly, in argument order, before any iteration starts - but the method is only *called*
    // (opening the actual inner iterator) lazily, item by item, as the result is driven.
    private static JsValue concat(InterpreterOps ops, List<JsValue> items, JsObject objectProto) {
        final var openMethods = new ArrayList<JsValue[]>();
        for (final var item : items) {
            if (!InterpreterUtils.isObjectLike(item)) {
                throw new TypeErrorException("Iterator.concat argument must be an object");
            }
            final var method = ops.getMember(item, JsSymbol.ITERATOR);
            if (!isCallable(method)) {
                throw new TypeErrorException("Iterator.concat argument is not iterable");
            }
            openMethods.add(new JsValue[]{item, method});
        }
        final var index = new int[]{0};
        final var current = new Driver[]{null};
        return lazyIterator(() -> {
            while (true) {
                if (current[0] == null) {
                    if (index[0] >= openMethods.size()) {
                        return null;
                    }
                    final var pair = openMethods.get(index[0]++);
                    current[0] = new Driver(ops, ops.call(pair[1], pair[0], List.of()));
                }
                final var value = current[0].next();
                if (value != null) {
                    return value;
                }
                current[0] = null;
            }
        }, () -> {
            if (current[0] != null) {
                current[0].close();
            }
            index[0] = openMethods.size();
        }, objectProto);
    }

    // Iterator.zip(iterables, options): every inner iterable is opened eagerly (unlike concat,
    // where opening is lazy) since a divergent length must be observable before the first `next()`.
    private static JsValue zip(InterpreterOps ops, JsValue iterablesArg, JsValue optionsArg, JsObject objectProto) {
        // Step 1 of the spec algorithm - checked before options is even read, so a badOptions
        // object with throwing getters must never be touched for an invalid iterables argument.
        if (!InterpreterUtils.isObjectLike(iterablesArg)) {
            throw new TypeErrorException("Iterator.zip argument must be an object");
        }
        final var mode = resolveZipMode(ops, optionsArg);
        final var padding = resolveZipPadding(ops, optionsArg, mode);
        final var entries = new ArrayList<Driver>();
        final var outerMethod = ops.getMember(iterablesArg, JsSymbol.ITERATOR);
        if (!isCallable(outerMethod)) {
            throw new TypeErrorException("Iterator.zip argument must be iterable");
        }
        final var outerDriver = new Driver(ops, ops.call(outerMethod, iterablesArg, List.of()));
        JsValue item;
        while ((item = outerDriver.next()) != null) {
            entries.add(openFlattenable(ops, item, entries));
        }
        final var pads = resolvePads(ops, padding, entries.size());
        return lazyIterator(zipRound(entries, mode, pads, values -> {
            final var array = new JsArray();
            for (final var value : values) {
                array.push(value);
            }
            return array;
        }), () -> entries.forEach(Driver::close), objectProto);
    }

    // Iterator.zipKeyed(iterables, options): like zip, but "iterables" is a plain object whose own
    // enumerable keys (string and symbol) map to iterables, and each round yields an object keyed
    // the same way instead of an array.
    private static JsValue zipKeyed(InterpreterOps ops, JsValue iterablesArg, JsValue optionsArg,
            JsObject objectProto) {
        // Step 1 of the spec algorithm - checked before options is even read, so a badOptions
        // object with throwing getters must never be touched for an invalid iterables argument.
        if (!InterpreterUtils.isObjectLike(iterablesArg)) {
            throw new TypeErrorException("Iterator.zipKeyed argument must be an object");
        }
        final var mode = resolveZipMode(ops, optionsArg);
        final var padding = resolveZipPadding(ops, optionsArg, mode);
        final var keys = new ArrayList<JsValue>();
        final var entries = new ArrayList<Driver>();
        for (final var key : ops.ownKeys(iterablesArg)) {
            final var descriptor = ops.getOwnPropertyDescriptor(iterablesArg, key);
            if (!(descriptor instanceof JsObject descObj) || !JsCoercion.toBoolean(descObj.get("enumerable"))) {
                continue;
            }
            final var value = ops.getMember(iterablesArg, key);
            if (value instanceof JsUndefined) {
                continue;
            }
            keys.add(key);
            entries.add(openFlattenable(ops, value, entries));
        }
        final var pads = resolvePads(ops, padding, entries.size());
        return lazyIterator(zipRound(entries, mode, pads, values -> {
            // Per spec this round object is OrdinaryObjectCreate(null) - a null-proto object, not
            // one linked to %Object.prototype% (unlike the {value,done} IteratorResult wrapping it).
            final var obj = new JsObject();
            for (var i = 0; i < keys.size(); i++) {
                if (keys.get(i) instanceof JsSymbol symbol) {
                    obj.setSymbol(symbol, values.get(i));
                } else {
                    obj.set(JsCoercion.toStr(keys.get(i)), values.get(i));
                }
            }
            return obj;
        }), () -> entries.forEach(Driver::close), objectProto);
    }

    // The shared per-round stepping logic for both zip and zipKeyed: steps every still-live entry,
    // substitutes its pad once exhausted (mode "longest"), stops as soon as any entry exhausts
    // (mode "shortest", the default) and rejects a length mismatch outright (mode "strict").
    private static Supplier<JsValue> zipRound(List<Driver> entries, String mode, JsValue[] pads,
            Function<List<JsValue>, JsValue> build) {
        final var exhausted = new boolean[entries.size()];
        return () -> {
            var allDone = true;
            var anyDone = false;
            final var values = new ArrayList<JsValue>(entries.size());
            for (var i = 0; i < entries.size(); i++) {
                if (exhausted[i]) {
                    anyDone = true;
                    values.add(pads[i]);
                    continue;
                }
                final var value = entries.get(i).next();
                if (value == null) {
                    exhausted[i] = true;
                    anyDone = true;
                    values.add(pads[i]);
                } else {
                    allDone = false;
                    values.add(value);
                }
            }
            if (allDone) {
                return null;
            }
            if (anyDone && !"longest".equals(mode)) {
                entries.forEach(Driver::close);
                if ("strict".equals(mode)) {
                    throw new TypeErrorException("Iterator.zip: iterables of different lengths in strict mode");
                }
                return null;
            }
            return build.apply(values);
        };
    }

    private static Driver openFlattenable(InterpreterOps ops, JsValue item, List<Driver> alreadyOpened) {
        if (!InterpreterUtils.isObjectLike(item)) {
            alreadyOpened.forEach(Driver::close);
            throw new TypeErrorException("Iterator.zip: each iterable must be an object");
        }
        final var method = ops.getMember(item, JsSymbol.ITERATOR);
        final JsValue iterObj;
        if (method instanceof JsUndefined) {
            iterObj = item;
        } else if (isCallable(method)) {
            iterObj = ops.call(method, item, List.of());
            if (!InterpreterUtils.isObjectLike(iterObj)) {
                alreadyOpened.forEach(Driver::close);
                throw new TypeErrorException("Iterator.zip: iterator method did not return an object");
            }
        } else {
            alreadyOpened.forEach(Driver::close);
            throw new TypeErrorException("Iterator.zip: Symbol.iterator is not a function");
        }
        return new Driver(ops, iterObj);
    }

    private static String resolveZipMode(InterpreterOps ops, JsValue optionsArg) {
        final var modeValue = ops.getMember(resolveOptionsObject(optionsArg), new JsString("mode"));
        if (modeValue instanceof JsUndefined) {
            return "shortest";
        }
        if (modeValue instanceof JsString js && Set.of("shortest", "longest", "strict").contains(js.getValue())) {
            return js.getValue();
        }
        throw new TypeErrorException("Invalid Iterator.zip mode");
    }

    private static JsValue resolveZipPadding(InterpreterOps ops, JsValue optionsArg, String mode) {
        if (!"longest".equals(mode)) {
            return JsUndefined.getInstance();
        }
        final var padding = ops.getMember(resolveOptionsObject(optionsArg), new JsString("padding"));
        if (!(padding instanceof JsUndefined) && !InterpreterUtils.isObjectLike(padding)) {
            throw new TypeErrorException("Iterator.zip padding must be an object");
        }
        return padding;
    }

    private static JsValue resolveOptionsObject(JsValue optionsArg) {
        if (optionsArg instanceof JsUndefined) {
            return new JsObject();
        }
        if (InterpreterUtils.isObjectLike(optionsArg)) {
            return optionsArg;
        }
        throw new TypeErrorException("Iterator.zip options must be an object");
    }

    private static JsValue[] resolvePads(InterpreterOps ops, JsValue paddingArg, int count) {
        final var pads = new JsValue[count];
        Arrays.fill(pads, JsUndefined.getInstance());
        if (paddingArg instanceof JsUndefined) {
            return pads;
        }
        final var paddingDriver = new Driver(ops, iteratorOf(ops, paddingArg));
        for (var i = 0; i < count; i++) {
            final var value = paddingDriver.next();
            if (value == null) {
                break;
            }
            pads[i] = value;
        }
        paddingDriver.close();
        return pads;
    }

    private static JsValue map(InterpreterOps ops, Driver source, JsValue fn, JsObject objectProto) {
        final var index = new long[]{0};
        return lazyIterator(() -> {
            final var value = source.next();
            if (value == null) {
                return null;
            }
            return callOrClose(ops, source, fn, List.of(value, new JsNumber(index[0]++)));
        }, source::close, objectProto);
    }

    private static JsValue filter(InterpreterOps ops, Driver source, JsValue fn, JsObject objectProto) {
        final var index = new long[]{0};
        return lazyIterator(() -> {
            JsValue value;
            while ((value = source.next()) != null) {
                if (JsCoercion.toBoolean(callOrClose(ops, source, fn, List.of(value, new JsNumber(index[0]++))))) {
                    return value;
                }
            }
            return null;
        }, source::close, objectProto);
    }

    private static JsValue take(Driver source, long count, JsObject objectProto) {
        final var remaining = new long[]{count};
        return lazyIterator(() -> {
            if (remaining[0] <= 0) {
                source.close();
                return null;
            }
            remaining[0]--;
            return source.next();
        }, source::close, objectProto);
    }

    private static JsValue drop(Driver source, long count, JsObject objectProto) {
        final var remaining = new long[]{count};
        return lazyIterator(() -> {
            while (remaining[0] > 0) {
                remaining[0]--;
                if (source.next() == null) {
                    return null;
                }
            }
            return source.next();
        }, source::close, objectProto);
    }

    private static JsValue flatMap(InterpreterOps ops, Driver source, JsValue fn, JsObject objectProto) {
        final var index = new long[]{0};
        final var inner = new Driver[]{null};
        return lazyIterator(() -> {
            while (true) {
                if (inner[0] != null) {
                    final var innerValue = inner[0].next();
                    if (innerValue != null) {
                        return innerValue;
                    }
                    inner[0] = null;
                }
                final var value = source.next();
                if (value == null) {
                    return null;
                }
                final var mapped = callOrClose(ops, source, fn, List.of(value, new JsNumber(index[0]++)));
                inner[0] = new Driver(ops, flattenOrClose(ops, source, mapped));
            }
        }, () -> {
            if (inner[0] != null) {
                inner[0].close();
            }
            source.close();
        }, objectProto);
    }

    private static JsValue reduce(InterpreterOps ops, Driver source, JsValue fn, List<JsValue> args) {
        var accumulator = args.size() > 1 ? args.get(1) : source.next();
        if (accumulator == null) {
            throw new TypeErrorException("Reduce of empty iterator with no initial value");
        }
        var index = args.size() > 1 ? 0L : 1L;
        JsValue value;
        while ((value = source.next()) != null) {
            accumulator = callOrClose(ops, source, fn, List.of(accumulator, value, new JsNumber(index++)));
        }
        return accumulator;
    }

    private static JsValue toArray(Driver source) {
        final var array = new JsArray();
        JsValue value;
        while ((value = source.next()) != null) {
            array.push(value);
        }
        return array;
    }

    private static JsValue forEach(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            callOrClose(ops, source, fn, List.of(value, new JsNumber(index++)));
        }
        return JsUndefined.getInstance();
    }

    private static boolean matchAny(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            if (JsCoercion.toBoolean(callOrClose(ops, source, fn, List.of(value, new JsNumber(index++))))) {
                source.close();
                return true;
            }
        }
        return false;
    }

    private static boolean matchAll(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            if (!JsCoercion.toBoolean(callOrClose(ops, source, fn, List.of(value, new JsNumber(index++))))) {
                source.close();
                return false;
            }
        }
        return true;
    }

    private static JsValue find(InterpreterOps ops, Driver source, JsValue fn) {
        var index = 0L;
        JsValue value;
        while ((value = source.next()) != null) {
            if (JsCoercion.toBoolean(callOrClose(ops, source, fn, List.of(value, new JsNumber(index++))))) {
                source.close();
                return value;
            }
        }
        return JsUndefined.getInstance();
    }

    // IfAbruptCloseIterator: a callback that throws closes the underlying iterator before the error
    // propagates, and a `return` that throws in turn is discarded so the callback's error wins.
    private static JsValue callOrClose(InterpreterOps ops, Driver source, JsValue fn, List<JsValue> args) {
        try {
            return ops.call(fn, JsUndefined.getInstance(), args);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            source.closeAfterThrow();
            throw error;
        }
    }

    // GetIteratorFlattenable(value, reject-primitives): unlike Iterator.from, flatMap never accepts a
    // primitive, so a mapped string is a TypeError rather than an iteration over its code points.
    private static JsValue flattenOrClose(InterpreterOps ops, Driver source, JsValue mapped) {
        try {
            if (!InterpreterUtils.isObjectLike(mapped)) {
                throw new TypeErrorException("flatMap mapper did not return an object");
            }
            return iteratorOf(ops, mapped);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            source.closeAfterThrow();
            throw error;
        }
    }

    private static JsObject lazyIterator(Supplier<JsValue> nextValue, Runnable onClose, JsObject objectProto) {
        final var iterator = new JsObject();
        final var protos = REALM_PROTOS.get(objectProto);
        if (protos != null) {
            iterator.setProto(protos[0]);
        }
        HELPER_STATE.put(iterator, new HelperState(nextValue, onClose));
        return iterator;
    }

    private static JsValue stepOf(JsValue value, HelperState state, JsObject objectProto) {
        if (value == null) {
            state.closed = true;
        }
        return step(value, objectProto);
    }

    private static JsObject step(JsValue value, JsObject objectProto) {
        final var result = new JsObject();
        result.setProto(objectProto);
        result.set("value", value == null ? JsUndefined.getInstance() : value);
        result.set("done", JsBoolean.of(value == null));
        return result;
    }

    // GetIteratorFlattenable's object branch: a nullish @@iterator means the value is already the
    // iterator, while a present-but-not-callable one is a TypeError rather than the same fallback.
    private static JsValue iteratorOf(InterpreterOps ops, JsValue value) {
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

    private static JsValue requireIterator(JsValue value) {
        if (value == null || !InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Iterator helper called on non-iterator");
        }
        return value;
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    private static JsValue callback(List<JsValue> args) {
        final var fn = arg0(args);
        if (!isCallable(fn)) {
            throw new TypeErrorException("Iterator helper callback is not a function");
        }
        return fn;
    }

    // chunks/windows take an integral size in [1, 2^32-1]: a non-integral or NaN size is a
    // TypeError (not a RangeError), which is what separates it from `take`/`drop`'s limit.
    private static int windowSize(JsValue value, String label) {
        if (!(value instanceof JsNumber size)) {
            throw new TypeErrorException(label + " must be a Number");
        }
        final var number = size.getValue();
        if (Double.isNaN(number) || number != Math.floor(number) || Double.isInfinite(number)) {
            throw new TypeErrorException(label + " must be an integral Number");
        }
        if (number < 1 || number > MAX_WINDOW_SIZE) {
            throw new RangeErrorException(label + " is out of range");
        }
        return (int) Math.min(number, Integer.MAX_VALUE);
    }

    private static boolean allowPartial(List<JsValue> args) {
        final var undersized = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        if (undersized instanceof JsUndefined || isText(undersized, "only-full")) {
            return false;
        }
        if (isText(undersized, "allow-partial")) {
            return true;
        }
        throw new TypeErrorException("undersized must be \"only-full\" or \"allow-partial\"");
    }

    private static boolean isText(JsValue value, String expected) {
        return value instanceof JsString text && expected.equals(text.getValue());
    }

    private static long skipCount(List<JsValue> args) {
        if (args.size() < 2 || args.get(1) instanceof JsUndefined) {
            return 0;
        }
        if (!(args.get(1) instanceof JsNumber skip)) {
            throw new TypeErrorException("skipCount must be a Number");
        }
        final var number = skip.getValue();
        if (Double.isNaN(number) || (number != Math.floor(number) && !Double.isInfinite(number))) {
            throw new TypeErrorException("skipCount must be an integral Number");
        }
        if (number < 0) {
            throw new RangeErrorException("skipCount is out of range");
        }
        if (!Double.isInfinite(number) && number > MAX_SAFE_INTEGER) {
            throw new RangeErrorException("skipCount is out of range");
        }
        return Double.isInfinite(number) ? Long.MAX_VALUE : (long) number;
    }

    private static JsValue chunks(Driver source, int size, JsObject objectProto) {
        final var buffer = new ArrayList<JsValue>();
        return lazyIterator(() -> {
            for (var value = source.next(); value != null; value = source.next()) {
                buffer.add(value);
                if (buffer.size() == size) {
                    final var chunk = new JsArray(new ArrayList<>(buffer));
                    buffer.clear();
                    return chunk;
                }
            }
            if (buffer.isEmpty()) {
                return null;
            }
            final var tail = new JsArray(new ArrayList<>(buffer));
            buffer.clear();
            return tail;
        }, source::close, objectProto);
    }

    private static JsValue windows(Driver source, int size, boolean allowPartial, JsObject objectProto) {
        final var buffer = new ArrayList<JsValue>();
        final var partialYielded = new boolean[]{false};
        return lazyIterator(() -> {
            for (var value = source.next(); value != null; value = source.next()) {
                if (buffer.size() == size) {
                    buffer.removeFirst();
                }
                buffer.add(value);
                if (buffer.size() == size) {
                    return new JsArray(new ArrayList<>(buffer));
                }
            }
            if (!allowPartial || partialYielded[0] || buffer.isEmpty() || buffer.size() >= size) {
                return null;
            }
            partialYielded[0] = true;
            return new JsArray(new ArrayList<>(buffer));
        }, source::close, objectProto);
    }

    private static boolean includes(Driver source, JsValue searched, long skip) {
        var index = 0L;
        for (var value = source.next(); value != null; value = source.next()) {
            if (index++ < skip) {
                continue;
            }
            if (SameValueZero.equal(value, searched)) {
                source.close();
                return true;
            }
        }
        return false;
    }

    private static String separator(InterpreterOps ops, List<JsValue> args) {
        return args.isEmpty() || args.getFirst() instanceof JsUndefined ? "," : JsCoercion.toStr(args.getFirst(), ops);
    }

    private static JsValue join(InterpreterOps ops, Driver source, String separator) {
        final var result = new StringBuilder();
        var first = true;
        for (var value = source.next(); value != null; value = source.next()) {
            if (!first) {
                result.append(separator);
            }
            first = false;
            if (!InterpreterUtils.isNullish(value)) {
                result.append(coerceOrClose(ops, source, value));
            }
        }
        return new JsString(result.toString());
    }

    private static String coerceOrClose(InterpreterOps ops, Driver source, JsValue value) {
        try {
            return JsCoercion.toStr(value, ops);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            source.closeAfterThrow();
            throw error;
        }
    }

    // ToNumber then ToIntegerOrInfinity, with NaN, a negative result and a finite value beyond
    // 2^53-1 all rejected as a RangeError; +Infinity is a legal, unbounded limit.
    private static long limit(InterpreterOps ops, JsValue value) {
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

    private static JsValue arg0(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    private static final class HelperState {
        private final Supplier<JsValue> nextValue;
        private final Runnable onClose;
        private boolean closed;
        private boolean running;

        private HelperState(Supplier<JsValue> nextValue, Runnable onClose) {
            this.nextValue = nextValue;
            this.onClose = onClose;
        }

        private JsValue run(Supplier<JsValue> body) {
            enter();
            try {
                return body.get();
            } finally {
                running = false;
            }
        }

        private void enter() {
            if (running) {
                throw new TypeErrorException("Iterator Helper is already running");
            }
            running = true;
        }
    }

    private record WrapState(JsValue iterator, JsValue nextMethod) {
    }

    private static final class Driver {
        private final InterpreterOps ops;
        private final JsValue iterator;
        // GetIteratorDirect reads `next` once and stores it in the Iterator Record; re-reading it per
        // step lets a `next` getter that returns a fresh iterator restart the source forever.
        private final JsValue nextMethod;
        private boolean done;

        private Driver(InterpreterOps ops, JsValue iterator) {
            this.ops = ops;
            this.iterator = iterator;
            this.nextMethod = ops.getMember(iterator, new JsString("next"));
        }

        private JsValue next() {
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

        private void close() {
            if (done) {
                return;
            }
            done = true;
            final var returnFn = ops.getMember(iterator, new JsString("return"));
            if (isCallable(returnFn)) {
                ops.call(returnFn, iterator, List.of());
            }
        }

        private void closeAfterThrow() {
            try {
                close();
            } catch (ScriptAbortException abort) {
                throw abort;
            } catch (RuntimeException ignored) {
                // discarded on purpose: the original throw completion wins
            }
        }
    }
}
