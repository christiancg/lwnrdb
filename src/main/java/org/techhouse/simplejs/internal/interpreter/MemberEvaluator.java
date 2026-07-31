package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arg0;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arg1;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arrayIndex;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arrayLikeElements;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.cannotReadProperties;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.orUndefined;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.stepResult;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.toErrorValue;

import java.util.List;
import org.techhouse.simplejs.builtins.ArrayBuiltins;
import org.techhouse.simplejs.builtins.DateBuiltins;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.builtins.IteratorBuiltins;
import org.techhouse.simplejs.builtins.JsIterators;
import org.techhouse.simplejs.builtins.MapBuiltins;
import org.techhouse.simplejs.builtins.NumberBuiltins;
import org.techhouse.simplejs.builtins.ObjectProtoBuiltins;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.builtins.SetBuiltins;
import org.techhouse.simplejs.builtins.StringBuiltins;
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
                    (_, _) -> JsIterators.of(arrayLikeElements(string).iterator()));
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
            case JsGenerator generator -> generatorMethod(generator, key);
            case JsAsyncGenerator generator -> asyncGeneratorMethod(generator, key);
            case JsRegExp regexp -> regExpMember(regexp, key);
            case JsMap map -> mapMember(map, key);
            case JsSet set -> jsSetMember(set, key);
            case JsDate date -> dateMember(date, key);
            case JsTypedArray typed -> typedArrayMember(typed, key);
            case JsArrayBuffer buffer -> orUndefined(TypedArrayBuiltins.bufferMethod(buffer, key));
            case JsDataView view -> orUndefined(TypedArrayBuiltins.dataViewMethod(view, key));
            case JsPromise promise -> promiseMethod(promise, key);
            case JsNativeFunction fn when fn.hasProperty(key) -> fn.getProperty(key);
            case JsFunction fn -> functionMember(fn, key);
            case JsNativeFunction nf -> functionMember(nf, key);
            case JsNull ignored -> throw cannotReadProperties(target, key);
            case JsUndefined ignored -> throw cannotReadProperties(target, key);
            default -> JsUndefined.getInstance();
        };
    }

    private JsValue getObjectMember(JsObject object, String key) {
        final var cls = object.getKlass();
        if (cls != null && !object.has(key)) {
            final var getter = cls.findInstanceGetter(key);
            if (getter != null) {
                return interp.callFunction(getter, object, List.of());
            }
            final var method = cls.findInstanceMethod(key);
            if (method != null) {
                return method;
            }
        }
        if (!object.has(key)) {
            final var accessorGetter = object.getAccessorGetter(key);
            if (accessorGetter != null) {
                return interp.callValue(accessorGetter, object, List.of());
            }
            for (var proto = object.getProto(); proto != null; proto = proto.getProto()) {
                final var protoGetter = proto.getAccessorGetter(key);
                if (protoGetter != null) {
                    return interp.callValue(protoGetter, object, List.of());
                }
                if (proto.has(key)) {
                    return proto.get(key);
                }
            }
            final var builtin = ObjectProtoBuiltins.getMethod(object, key, interp.ops());
            if (builtin != null) {
                return builtin;
            }
            if (IteratorBuiltins.isHelperName(key) && isIteratorLike(object)) {
                return IteratorBuiltins.helper(interp.ops(), key);
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
        final var index = arrayIndex(key);
        return index == null ? JsUndefined.getInstance() : arguments.get(index);
    }

    private JsValue functionMember(JsValue function, String key) {
        if (function instanceof JsFunction fn && "prototype".equals(key)) {
            return fn.getPrototype();
        }
        final var method = FunctionProtoBuiltins.getMethod(function, key, interp::callValue);
        return method == null ? JsUndefined.getInstance() : method;
    }

    private JsValue mapMember(JsMap map, String key) {
        final var method = MapBuiltins.getMethod(map, key, interp::callValue);
        return method == null ? JsUndefined.getInstance() : method;
    }

    private JsValue jsSetMember(JsSet set, String key) {
        final var method = SetBuiltins.getMethod(set, key, interp::callValue);
        return method == null ? JsUndefined.getInstance() : method;
    }

    private JsValue dateMember(JsDate date, String key) {
        final var method = DateBuiltins.getMethod(date, key);
        return method == null ? JsUndefined.getInstance() : method;
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
        return orUndefined(TypedArrayBuiltins.getMethod(typed, key, interp::callValue));
    }

    private JsValue numberMember(JsNumber number, String key) {
        final var method = NumberBuiltins.getMethod(number, key);
        return method == null ? JsUndefined.getInstance() : method;
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
        final var method = ArrayBuiltins.getMethod(array, key, interp::callValue);
        return method == null ? JsUndefined.getInstance() : method;
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
        final var method = StringBuiltins.getMethod(string, key, interp::callValue, interp.ops());
        return method == null ? JsUndefined.getInstance() : method;
    }

    public void setMember(JsValue target, String key, JsValue value) {
        switch (target) {
            case JsProxy proxy -> proxies.set(proxy, new JsString(key), value);
            case JsGlobalObject global -> global.getEnv().setGlobal(key, value);
            case JsArguments arguments -> {
                final var index = arrayIndex(key);
                if (index != null) {
                    arguments.set(index, value);
                }
            }
            case JsObject object -> {
                final var cls = object.getKlass();
                if (cls != null && !object.has(key)) {
                    final var setter = cls.findInstanceSetter(key);
                    if (setter != null) {
                        interp.callFunction(setter, object, List.of(value));
                        return;
                    }
                }
                if (!object.has(key)) {
                    final var accessorSetter = object.getAccessorSetter(key);
                    if (accessorSetter != null) {
                        interp.callValue(accessorSetter, object, List.of(value));
                        return;
                    }
                    if (object.hasAccessor(key)) {
                        return;
                    }
                }
                object.set(key, value);
            }
            case JsClass cls -> {
                final var setter = cls.findStaticSetter(key);
                if (setter != null) {
                    interp.callFunction(setter, cls, List.of(value));
                } else {
                    cls.setStaticProp(key, value);
                }
            }
            case JsArray array -> {
                final var index = arrayIndex(key);
                if (index != null) {
                    array.set(index, value);
                }
            }
            case JsTypedArray typed -> {
                final var index = arrayIndex(key);
                if (index != null) {
                    typed.setElement(index, value);
                }
            }
            case JsRegExp regexp -> {
                if ("lastIndex".equals(key)) {
                    final var next = JsCoercion.toNumber(value);
                    regexp.setLastIndex(Double.isNaN(next) ? 0 : (int) next);
                }
            }
            case JsNull ignored -> throw new TypeErrorException(
                    "Cannot set properties of " + JsCoercion.toStr(target) + " (setting '" + key + "')");
            case JsUndefined ignored -> throw new TypeErrorException(
                    "Cannot set properties of " + JsCoercion.toStr(target) + " (setting '" + key + "')");
            default -> {
            }
        }
    }

    private JsValue generatorMethod(JsGenerator generator, String key) {
        final var coroutine = generator.getCoroutine();
        return switch (key) {
            case "next" -> new JsNativeFunction("next", (_, args) -> stepResult(coroutine.resumeNext(arg0(args))));
            case "return" ->
                new JsNativeFunction("return", (_, args) -> stepResult(coroutine.resumeReturn(arg0(args))));
            case "throw" -> new JsNativeFunction("throw", (_, args) -> stepResult(coroutine.resumeThrow(arg0(args))));
            default -> IteratorBuiltins.isHelperName(key)
                    ? IteratorBuiltins.helper(interp.ops(), key)
                    : JsUndefined.getInstance();
        };
    }

    private boolean isIteratorLike(JsObject object) {
        return object.has("next") && isCallable(object.get("next"));
    }

    private JsValue asyncGeneratorMethod(JsAsyncGenerator generator, String key) {
        return switch (key) {
            case "next" ->
                new JsNativeFunction("next", (_, args) -> driveAsyncGenerator(generator, AsyncStep.NEXT, arg0(args)));
            case "return" -> new JsNativeFunction("return",
                    (_, args) -> driveAsyncGenerator(generator, AsyncStep.RETURN, arg0(args)));
            case "throw" ->
                new JsNativeFunction("throw", (_, args) -> driveAsyncGenerator(generator, AsyncStep.THROW, arg0(args)));
            default -> JsUndefined.getInstance();
        };
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
            promise.reject(toErrorValue(error));
        } else {
            throw error;
        }
    }

    private JsValue regExpMember(JsRegExp regexp, String key) {
        final var member = RegexBuiltins.getMethod(regexp, key);
        return member == null ? JsUndefined.getInstance() : member;
    }

    private JsValue promiseMethod(JsPromise promise, String key) {
        return switch (key) {
            case "then" -> new JsNativeFunction("then", (_, args) -> promiseThen(promise, arg0(args), arg1(args)));
            case "catch" ->
                new JsNativeFunction("catch", (_, args) -> promiseThen(promise, JsUndefined.getInstance(), arg0(args)));
            case "finally" -> new JsNativeFunction("finally", (_, args) -> promiseFinally(promise, arg0(args)));
            default -> JsUndefined.getInstance();
        };
    }

    private JsValue promiseThen(JsPromise promise, JsValue onFulfilled, JsValue onRejected) {
        final var derived = new JsPromise(eventLoop);
        promise.subscribe(value -> settleThen(derived, onFulfilled, value, true),
                reason -> settleThen(derived, onRejected, reason, false));
        return derived;
    }

    private void settleThen(JsPromise derived, JsValue handler, JsValue input, boolean fulfilled) {
        if (!(handler instanceof JsFunction) && !(handler instanceof JsNativeFunction)) {
            if (fulfilled) {
                derived.resolve(input);
            } else {
                derived.reject(input);
            }
            return;
        }
        try {
            derived.resolve(interp.callValue(handler, JsUndefined.getInstance(), List.of(input)));
        } catch (JsThrowException | TypeErrorException | ReferenceErrorException | RangeErrorException
                | SyntaxErrorException error) {
            derived.reject(toErrorValue(error));
        }
    }

    private JsValue promiseFinally(JsPromise promise, JsValue onFinally) {
        final var derived = new JsPromise(eventLoop);
        promise.subscribe(value -> {
            runFinally(onFinally);
            derived.resolve(value);
        }, reason -> {
            runFinally(onFinally);
            derived.reject(reason);
        });
        return derived;
    }

    private void runFinally(JsValue onFinally) {
        if (onFinally instanceof JsFunction || onFinally instanceof JsNativeFunction) {
            interp.callValue(onFinally, JsUndefined.getInstance(), List.of());
        }
    }
}
