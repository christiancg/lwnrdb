package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arrayIndex;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arrayLikeElements;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.cannotReadProperties;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.orUndefined;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.stepResult;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.stringCodePoints;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.toErrorValue;

import java.util.List;
import org.techhouse.simplejs.builtins.AsyncIteratorBuiltins;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.builtins.IteratorBuiltins;
import org.techhouse.simplejs.builtins.JsIterators;
import org.techhouse.simplejs.builtins.MapBuiltins;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.builtins.SetBuiltins;
import org.techhouse.simplejs.builtins.SymbolBuiltins;
import org.techhouse.simplejs.builtins.TypedArrayBuiltins;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// Property access dispatch: string- and symbol-keyed member reads/writes across every runtime
// value type, plus the lazily-built method objects for promises, generators and async generators.
// Re-entry into the interpreter (calling getters/setters/methods) and proxy fallbacks route
// through the Interpreter and ProxyDispatch seams; promise/async-generator settlement uses the
// shared EventLoop.
public final class MemberEvaluator {
    private final Interpreter interp;
    private final EventLoop eventLoop;
    private final ProxyDispatch proxies;

    public enum AsyncStep {
        NEXT, RETURN, THROW
    }

    public MemberEvaluator(Interpreter interp, EventLoop eventLoop, ProxyDispatch proxies) {
        this.interp = interp;
        this.eventLoop = eventLoop;
        this.proxies = proxies;
    }

    public JsValue getSymbolMember(JsValue target, JsSymbol symbol) {
        return switch (target) {
            case JsMap map when symbol == JsSymbol.ITERATOR ->
                new JsNativeFunction("[Symbol.iterator]", (_, _) -> MapBuiltins.entriesIterator(map));
            case JsTypedArray typed when symbol == JsSymbol.ITERATOR -> new JsNativeFunction("[Symbol.iterator]",
                    (_, _) -> JsIterators.of(TypedArrayBuiltins.elements(typed).iterator()));
            case JsSet set when symbol == JsSymbol.ITERATOR ->
                new JsNativeFunction("[Symbol.iterator]", (_, _) -> SetBuiltins.valuesIterator(set));
            case JsArray array when symbol == JsSymbol.ITERATOR -> new JsNativeFunction("[Symbol.iterator]",
                    (_, _) -> JsIterators.of(arrayLikeElements(array).iterator()));
            case JsString string when symbol == JsSymbol.ITERATOR -> new JsNativeFunction("[Symbol.iterator]",
                    (_, _) -> JsIterators.of(stringCodePoints(string.getValue()).iterator()));
            case JsObject object -> objectSymbolMember(object, symbol);
            case JsClass cls -> classSymbolMember(cls, symbol);
            default -> JsUndefined.getInstance();
        };
    }

    private JsValue objectSymbolMember(JsObject object, JsSymbol symbol) {
        if (object.hasSymbol(symbol)) {
            return object.getSymbol(symbol);
        }
        final var cls = object.getKlass();
        if (cls != null) {
            final var getter = cls.findInstanceSymbolGetter(symbol);
            if (getter != null) {
                return interp.callFunction(getter, object, List.of());
            }
            final var method = cls.findInstanceSymbolMethod(symbol);
            if (method != null) {
                return method;
            }
        }
        for (var proto = object.getProto(); proto != null; proto = proto.getProto()) {
            if (proto.hasSymbol(symbol)) {
                return proto.getSymbol(symbol);
            }
        }
        return object.getSymbol(symbol);
    }

    private JsValue classSymbolMember(JsClass cls, JsSymbol symbol) {
        final var getter = cls.findStaticSymbolGetter(symbol);
        if (getter != null) {
            return interp.callFunction(getter, cls, List.of());
        }
        final var method = cls.findStaticSymbolMethod(symbol);
        if (method != null) {
            return method;
        }
        if (cls.hasStaticSymbolProp(symbol)) {
            return cls.getStaticSymbolProp(symbol);
        }
        return JsUndefined.getInstance();
    }

    public JsValue getMember(JsValue target, String key, JsValue receiver) {
        if (target instanceof JsObject object) {
            return getObjectMember(object, key, receiver);
        }
        return getMember(target, key);
    }

    public JsValue getMember(JsValue target, String key) {
        return switch (target) {
            case JsProxy proxy -> proxies.get(proxy, new JsString(key));
            case JsGlobalObject global -> getGlobalMember(global, key);
            case JsArguments arguments -> getArgumentsMember(arguments, key);
            case JsObject object -> getObjectMember(object, key);
            case JsClass cls -> interp.getStaticMember(cls, key);
            case JsArray array -> getArrayMember(array, key);
            case JsString string -> getStringMember(string, key);
            case JsNumber number -> numberMember(number, key);
            case JsSymbol symbol -> symbolMember(symbol, key);
            case JsGenerator generator -> generatorMethod(generator, key);
            case JsAsyncGenerator generator -> asyncGeneratorMethod(generator, key);
            case JsRegExp regexp -> regExpMember(regexp, key);
            case JsMap map -> mapMember(map, key);
            case JsSet set -> jsSetMember(set, key);
            case JsDate date -> dateMember(date, key);
            case JsTypedArray typed -> typedArrayMember(typed, key);
            case JsArrayBuffer buffer -> bufferMember(buffer, key);
            case JsDataView view -> dataViewMember(view, key);
            case JsPromise promise -> promiseMethod(promise, key);
            case JsBoolean bool -> intrinsicMember(bool, key);
            case JsBigInt bigInt -> intrinsicMember(bigInt, key);
            case JsFunction fn -> functionMember(fn, key);
            case JsNativeFunction nf -> functionMember(nf, key);
            case JsNull ignored -> throw cannotReadProperties(target, key);
            case JsUndefined ignored -> throw cannotReadProperties(target, key);
            default -> JsUndefined.getInstance();
        };
    }

    private JsValue getObjectMember(JsObject object, String key) {
        return getObjectMember(object, key, object);
    }

    private JsValue getObjectMember(JsObject object, String key, JsValue receiver) {
        if (!object.has(key)) {
            final var accessorGetter = object.getAccessorGetter(key);
            if (accessorGetter != null) {
                return interp.callValue(accessorGetter, receiver, List.of());
            }
            for (var proto = object.getProto(); proto != null; proto = proto.getProto()) {
                final var protoGetter = proto.getAccessorGetter(key);
                if (protoGetter != null) {
                    return interp.callValue(protoGetter, receiver, List.of());
                }
                if (proto.has(key)) {
                    return proto.get(key);
                }
            }
            final var intrinsic = intrinsicMember(object, key);
            if (!(intrinsic instanceof JsUndefined)) {
                return intrinsic;
            }
            if (AsyncIteratorBuiltins.isHelperName(key) && isAsyncIteratorLike(object)) {
                return AsyncIteratorBuiltins.helper(interp.ops(), eventLoop, key);
            }
            if (IteratorBuiltins.isHelperName(key) && isIteratorLike(object)) {
                return IteratorBuiltins.helper(interp.ops(), key);
            }
            if (object.getPrimitive() != null) {
                return getMember(object.getPrimitive(), key);
            }
        }
        return object.get(key);
    }

    private JsValue getGlobalMember(JsGlobalObject global, String key) {
        final var value = global.getEnv().tryGet(key);
        return value == null ? JsUndefined.getInstance() : value;
    }

    private JsValue getArgumentsMember(JsArguments arguments, String key) {
        if ("length".equals(key)) {
            return new JsNumber(arguments.length());
        }
        if ("callee".equals(key) || "caller".equals(key)) {
            throw new TypeErrorException("'" + key + "' may not be accessed on a strict mode arguments object");
        }
        final var index = arrayIndex(key);
        return index == null ? JsUndefined.getInstance() : arguments.get(index);
    }

    // The last dispatch step for every value type: walk the realm's intrinsic prototype chain, so a
    // monkey-patched or user-added member on e.g. Array.prototype is what a receiver resolves to.
    private JsValue intrinsicMember(JsValue target, String key) {
        for (var proto = interp.intrinsics().protoFor(target); proto != null; proto = proto.getProto()) {
            final var getter = proto.getAccessorGetter(key);
            if (getter != null) {
                return interp.callValue(getter, target, List.of());
            }
            if (proto.has(key)) {
                return proto.get(key);
            }
        }
        return JsUndefined.getInstance();
    }

    private JsValue functionMember(JsValue function, String key) {
        if (function instanceof JsCallableProperties callable && callable.hasProperty(key)) {
            return callable.getProperty(key);
        }
        if (function instanceof JsFunction fn && "prototype".equals(key)) {
            return fn.getPrototype();
        }
        if (function instanceof JsNativeFunction nf && "prototype".equals(key)) {
            return orUndefined(nf.getPrototype());
        }
        if (function instanceof JsCallableProperties callable && callable.isMetadataDeleted(key)) {
            return JsUndefined.getInstance();
        }
        final var metadata = FunctionProtoBuiltins.metadata(function, key);
        if (metadata != null) {
            return metadata;
        }
        return intrinsicMember(function, key);
    }

    private JsValue mapMember(JsMap map, String key) {
        if ("size".equals(key)) {
            return new JsNumber(map.size());
        }
        return intrinsicMember(map, key);
    }

    private JsValue jsSetMember(JsSet set, String key) {
        if ("size".equals(key)) {
            return new JsNumber(set.size());
        }
        return intrinsicMember(set, key);
    }

    private JsValue dateMember(JsDate date, String key) {
        return intrinsicMember(date, key);
    }

    private JsValue bufferMember(JsArrayBuffer buffer, String key) {
        if (TypedArrayBuiltins.isBufferAccessor(key)) {
            return orUndefined(TypedArrayBuiltins.bufferMethod(buffer, key));
        }
        return intrinsicMember(buffer, key);
    }

    private JsValue dataViewMember(JsDataView view, String key) {
        if (TypedArrayBuiltins.isViewAccessor(key)) {
            return orUndefined(TypedArrayBuiltins.dataViewMethod(view, key));
        }
        return intrinsicMember(view, key);
    }

    private JsValue typedArrayMember(JsTypedArray typed, String key) {
        switch (key) {
            case "length" -> {
                return new JsNumber(typed.length());
            }
            case "byteLength" -> {
                return new JsNumber(typed.byteLength());
            }
            case "byteOffset" -> {
                return new JsNumber(typed.byteOffset());
            }
            case "buffer" -> {
                return typed.getBuffer();
            }
            case "BYTES_PER_ELEMENT" -> {
                return new JsNumber(typed.kind().bytesPerElement());
            }
            default -> {
            }
        }
        final var index = arrayIndex(key);
        if (index != null) {
            return typed.getElement(index);
        }
        return intrinsicMember(typed, key);
    }

    private JsValue numberMember(JsNumber number, String key) {
        return intrinsicMember(number, key);
    }

    private JsValue symbolMember(JsSymbol symbol, String key) {
        final var property = SymbolBuiltins.getProperty(symbol, key);
        if (property != null) {
            return property;
        }
        return intrinsicMember(symbol, key);
    }

    private JsValue getArrayMember(JsArray array, String key) {
        if ("length".equals(key)) {
            return new JsNumber(array.length());
        }
        final var index = arrayIndex(key);
        if (index != null) {
            return array.get(index);
        }
        if (array.hasProperty(key)) {
            return array.getProperty(key);
        }
        return intrinsicMember(array, key);
    }

    private JsValue getStringMember(JsString string, String key) {
        if ("length".equals(key)) {
            return new JsNumber(string.getValue().length());
        }
        final var index = arrayIndex(key);
        if (index != null) {
            return index < string.getValue().length()
                    ? new JsString(String.valueOf(string.getValue().charAt(index)))
                    : JsUndefined.getInstance();
        }
        return intrinsicMember(string, key);
    }

    public boolean setMember(JsValue target, String key, JsValue value, JsValue receiver) {
        if (target instanceof JsObject object) {
            return setObjectMember(object, key, value, receiver);
        }
        return setMember(target, key, value);
    }

    private boolean setObjectMember(JsObject object, String key, JsValue value, JsValue receiver) {
        if (!object.has(key)) {
            for (var current = object; current != null; current = current.getProto()) {
                final var accessorSetter = current.getAccessorSetter(key);
                if (accessorSetter != null) {
                    interp.callValue(accessorSetter, receiver, List.of(value));
                    return true;
                }
                if (current.hasAccessor(key)) {
                    return false;
                }
            }
        }
        return object.set(key, value);
    }

    public boolean setMember(JsValue target, String key, JsValue value) {
        return switch (target) {
            case JsProxy proxy -> {
                proxies.set(proxy, new JsString(key), value);
                yield true;
            }
            case JsGlobalObject global -> {
                global.getEnv().setGlobal(key, value);
                yield true;
            }
            case JsArguments arguments -> {
                final var index = arrayIndex(key);
                if (index != null) {
                    arguments.set(index, value);
                }
                yield true;
            }
            case JsObject object -> setObjectMember(object, key, value, object);
            case JsClass cls -> {
                final var setter = cls.findStaticSetter(key);
                if (setter != null) {
                    interp.callFunction(setter, cls, List.of(value));
                } else {
                    cls.setStaticProp(key, value);
                }
                yield true;
            }
            case JsArray array -> setArrayMember(array, key, value);
            case JsTypedArray typed -> {
                final var index = arrayIndex(key);
                if (index != null) {
                    typed.setElement(index, value);
                }
                yield true;
            }
            case JsRegExp regexp -> {
                if ("lastIndex".equals(key)) {
                    final var next = JsCoercion.toNumber(value);
                    regexp.setLastIndex(Double.isNaN(next) ? 0 : (int) next);
                }
                yield true;
            }
            case JsNull ignored -> throw new TypeErrorException(
                    "Cannot set properties of " + JsCoercion.toStr(target) + " (setting '" + key + "')");
            case JsUndefined ignored -> throw new TypeErrorException(
                    "Cannot set properties of " + JsCoercion.toStr(target) + " (setting '" + key + "')");
            case JsCallableProperties callable -> {
                if (isNonWritableMetadata(callable, key)) {
                    yield false;
                }
                if (!"prototype".equals(key)) {
                    callable.setEnumerableProperty(key, value);
                }
                yield true;
            }
            default -> true;
        };
    }

    private static boolean isNonWritableMetadata(JsCallableProperties callable, String key) {
        return ("name".equals(key) || "length".equals(key)) && !callable.hasProperty(key)
                && !callable.isMetadataDeleted(key);
    }

    public static String writeRejectionMessage(JsValue target, JsValue key) {
        final var name = JsCoercion.toStr(key);
        if (target instanceof JsObject object) {
            if (object.hasAccessor(name) && object.getAccessorSetter(name) == null) {
                return "Cannot set property " + name + " of #<Object> which has only a getter";
            }
            if (object.has(name)) {
                return "Cannot assign to read only property '" + name + "' of object";
            }
        }
        if (target instanceof JsCallableProperties callable && isNonWritableMetadata(callable, name)) {
            return "Cannot assign to read only property '" + name + "' of object";
        }
        if (target instanceof JsArray array && array.isFrozen()) {
            return "Cannot assign to read only property '" + name + "' of object";
        }
        return "Cannot add property " + name + ", object is not extensible";
    }

    private static boolean setArrayMember(JsArray array, String key, JsValue value) {
        if ("length".equals(key)) {
            return array.setLength(requireLength(value));
        }
        final var index = arrayIndex(key);
        return index == null ? array.setProperty(key, value) : array.set(index, value);
    }

    private static int requireLength(JsValue value) {
        final var length = JsCoercion.toNumber(value);
        if (Double.isNaN(length) || length < 0 || length != Math.floor(length)) {
            throw new RangeErrorException("Invalid array length");
        }
        return (int) length;
    }

    private JsValue generatorMethod(JsGenerator generator, String key) {
        final var intrinsic = intrinsicMember(generator, key);
        if (!(intrinsic instanceof JsUndefined) || !IteratorBuiltins.isHelperName(key)) {
            return intrinsic;
        }
        return IteratorBuiltins.helper(interp.ops(), key);
    }

    private boolean isIteratorLike(JsObject object) {
        return object.has("next") && isCallable(object.get("next"));
    }

    private boolean isAsyncIteratorLike(JsObject object) {
        return isIteratorLike(object) && object.hasSymbol(JsSymbol.ASYNC_ITERATOR);
    }

    private JsValue asyncGeneratorMethod(JsAsyncGenerator generator, String key) {
        final var intrinsic = intrinsicMember(generator, key);
        if (!(intrinsic instanceof JsUndefined) || !AsyncIteratorBuiltins.isHelperName(key)) {
            return intrinsic;
        }
        return AsyncIteratorBuiltins.helper(interp.ops(), eventLoop, key);
    }

    public JsValue driveAsyncGenerator(JsAsyncGenerator generator, AsyncStep kind, JsValue arg) {
        final var coroutine = generator.getCoroutine();
        final var promise = new JsPromise(eventLoop);
        if (coroutine.isDone()) {
            if (kind == AsyncStep.THROW) {
                promise.reject(arg);
            } else {
                promise.resolve(stepResult(kind == AsyncStep.RETURN ? arg : JsUndefined.getInstance(), true));
            }
            return promise;
        }
        generator.setPending(promise);
        try {
            final var step = switch (kind) {
                case NEXT -> coroutine.resumeNext(arg);
                case RETURN -> coroutine.resumeReturn(arg);
                case THROW -> coroutine.resumeThrow(arg);
            };
            if (!coroutine.isDone() && coroutine.pauseReason() == Coroutine.PauseReason.AWAIT) {
                return promise;
            }
            resolveStep(generator, step.value(), step.done());
        } catch (JsThrowException | TypeErrorException | ReferenceErrorException | RangeErrorException
                | SyntaxErrorException error) {
            rejectStep(generator, error);
        }
        return promise;
    }

    public void observeAsyncGenerator(JsAsyncGenerator generator, RuntimeException escaped) {
        final var coroutine = generator.getCoroutine();
        if (escaped != null) {
            rejectStep(generator, escaped);
        } else if (coroutine.isDone()) {
            resolveStep(generator, coroutine.completedValue(), true);
        } else if (coroutine.pauseReason() == Coroutine.PauseReason.YIELD) {
            resolveStep(generator, coroutine.yieldedValue(), false);
        }
    }

    private void resolveStep(JsAsyncGenerator generator, JsValue value, boolean done) {
        final var promise = generator.clearPending();
        if (promise != null) {
            promise.resolve(stepResult(value, done));
        }
    }

    private void rejectStep(JsAsyncGenerator generator, RuntimeException error) {
        final var promise = generator.clearPending();
        if (promise == null) {
            return;
        }
        if (error instanceof JsThrowException || error instanceof TypeErrorException
                || error instanceof ReferenceErrorException || error instanceof RangeErrorException
                || error instanceof SyntaxErrorException) {
            promise.reject(toErrorValue(error, interp.intrinsics()));
        } else {
            throw error;
        }
    }

    private JsValue regExpMember(JsRegExp regexp, String key) {
        if (RegexBuiltins.isAccessor(key)) {
            return orUndefined(RegexBuiltins.getMethod(regexp, key));
        }
        return intrinsicMember(regexp, key);
    }

    private JsValue promiseMethod(JsPromise promise, String key) {
        return intrinsicMember(promise, key);
    }
}
