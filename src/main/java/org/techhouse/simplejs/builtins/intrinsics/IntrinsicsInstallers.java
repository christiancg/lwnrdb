package org.techhouse.simplejs.builtins.intrinsics;

import static org.techhouse.simplejs.builtins.Intrinsics.UNSCOPABLE_ARRAY_METHODS;
import static org.techhouse.simplejs.builtins.Intrinsics.installMethod;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.incompatible;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireAsyncGenerator;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireMap;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireSet;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireStack;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireSymbol;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTemporalPlainTime;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.requireTypedArray;
import static org.techhouse.simplejs.builtins.intrinsics.IntrinsicsBrands.unwrap;
import static org.techhouse.simplejs.builtins.regex.RegexSymbolMethods.symbolMatchAll;
import static org.techhouse.simplejs.values.JsObject.PropertyFlags.HIDDEN;

import java.util.List;
import org.techhouse.simplejs.builtins.DisposableStackBuiltins;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.builtins.GeneratorBuiltins;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.JsIterators;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.builtins.SymbolBuiltins;
import org.techhouse.simplejs.builtins.TemporalPlainTimeBuiltins;
import org.techhouse.simplejs.builtins.regex.RegexSymbolMethods;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class IntrinsicsInstallers {
    public final Intrinsics intrinsics;

    public IntrinsicsInstallers(Intrinsics intrinsics) {
        this.intrinsics = intrinsics;
    }

    public void installToStringTags() {
        defineToStringTag(intrinsics.iteratorProto, "Generator");
        defineToStringTag(intrinsics.mapProto, "Map");
        defineToStringTag(intrinsics.weakMapProto, "WeakMap");
        defineToStringTag(intrinsics.setProto, "Set");
        defineToStringTag(intrinsics.weakSetProto, "WeakSet");
        defineToStringTag(intrinsics.bigintProto, "BigInt");
        defineToStringTag(intrinsics.arrayBufferProto, "ArrayBuffer");
        defineToStringTag(intrinsics.dataViewProto, "DataView");
        defineToStringTag(intrinsics.disposableStackProto, "DisposableStack");
        defineToStringTag(intrinsics.asyncDisposableStackProto, "AsyncDisposableStack");
        defineToStringTag(intrinsics.temporalDurationProto, "Temporal.Duration");
        defineToStringTag(intrinsics.temporalPlainDateProto, "Temporal.PlainDate");
        defineToStringTag(intrinsics.temporalInstantProto, "Temporal.Instant");
        defineToStringTag(intrinsics.temporalPlainDateTimeProto, "Temporal.PlainDateTime");
        defineToStringTag(intrinsics.temporalZonedDateTimeProto, "Temporal.ZonedDateTime");
        final var getter = new JsNativeFunction("get [Symbol.toStringTag]", (thisArg, _) -> typedArrayTag(thisArg));
        getter.setLength(0);
        intrinsics.typedArrayProto.defineSymbolAccessor(JsSymbol.TO_STRING_TAG, getter, null);
        intrinsics.typedArrayProto.setSymbolFlags(JsSymbol.TO_STRING_TAG, PropertyFlags.TAG);
    }

    public JsValue typedArrayTag(JsValue receiver) {
        final var target = receiver instanceof JsTypedArray ? receiver : unwrap(receiver);
        return target instanceof JsTypedArray typed ? new JsString(typed.kind().ctorName()) : JsUndefined.getInstance();
    }

    public void installArrayUnscopables() {
        final var unscopables = new JsObject();
        unscopables.setProto(null);
        for (final var name : UNSCOPABLE_ARRAY_METHODS) {
            unscopables.set(name, JsBoolean.TRUE);
        }
        intrinsics.arrayProto.setSymbol(JsSymbol.UNSCOPABLES, unscopables);
        intrinsics.arrayProto.setSymbolFlags(JsSymbol.UNSCOPABLES, PropertyFlags.TAG);
    }

    public void installIteratorSymbols() {
        defineSymbol(intrinsics.arrayProto, JsSymbol.ITERATOR, intrinsics.arrayProto.get("values"));
        defineSymbol(intrinsics.mapProto, JsSymbol.ITERATOR, intrinsics.mapProto.get("entries"));
        defineSymbol(intrinsics.setProto, JsSymbol.ITERATOR, intrinsics.setProto.get("values"));
        defineSymbol(intrinsics.stringProto, JsSymbol.ITERATOR,
                new JsNativeFunction("[Symbol.iterator]",
                        (thisArg, _) -> JsIterators.linkPrototype(JsIterators.of(InterpreterUtils
                                .stringCodePoints(JsCoercion.toStr(
                                        intrinsics.requireStringValue(thisArg, "[Symbol.iterator]"), intrinsics.ops))
                                .iterator()), intrinsics.stringIteratorProto)));
        defineSymbol(intrinsics.iteratorProto, JsSymbol.ITERATOR,
                new JsNativeFunction("[Symbol.iterator]", (thisArg, _) -> thisArg));
        defineSymbol(intrinsics.asyncIteratorProto, JsSymbol.ASYNC_ITERATOR,
                new JsNativeFunction("[Symbol.asyncIterator]", (thisArg, _) -> thisArg));
        defineToStringTag(intrinsics.promiseProto, "Promise");
        defineToStringTag(intrinsics.asyncIteratorProto, "AsyncGenerator");
        intrinsics.defaultArrayIterator = intrinsics.arrayProto.getSymbol(JsSymbol.ITERATOR);
        intrinsics.defaultStringIterator = intrinsics.stringProto.getSymbol(JsSymbol.ITERATOR);
        intrinsics.defaultTypedArrayIterator = intrinsics.typedArrayProto.getSymbol(JsSymbol.ITERATOR);
    }

    public void installSymbolAccessors(JsObject proto) {
        final var getter = new JsNativeFunction("get description",
                (thisArg, _) -> SymbolBuiltins.descriptionOf(requireSymbol(thisArg, "description")));
        getter.setLength(0);
        proto.defineAccessor("description", getter, null);
        proto.setFlags("description", HIDDEN);
        final var toPrimitive = new JsNativeFunction("[Symbol.toPrimitive]",
                (thisArg, _) -> requireSymbol(thisArg, "[Symbol.toPrimitive]"));
        toPrimitive.setLength(1);
        proto.setSymbol(JsSymbol.TO_PRIMITIVE, toPrimitive);
        proto.setSymbolFlags(JsSymbol.TO_PRIMITIVE, PropertyFlags.TAG);
        proto.setSymbol(JsSymbol.TO_STRING_TAG, new JsString("Symbol"));
        proto.setSymbolFlags(JsSymbol.TO_STRING_TAG, PropertyFlags.TAG);
    }

    public void installFunctionHasInstance(JsObject proto) {
        final var hasInstance = new JsNativeFunction("[Symbol.hasInstance]",
                (thisArg, args) -> FunctionProtoBuiltins.ordinaryHasInstance(thisArg,
                        args.isEmpty() ? JsUndefined.getInstance() : args.getFirst(), intrinsics.ops));
        hasInstance.setLength(1);
        proto.setSymbol(JsSymbol.HAS_INSTANCE, hasInstance);
        proto.setSymbolFlags(JsSymbol.HAS_INSTANCE, new PropertyFlags(false, false, false));
        intrinsics.defaultHasInstance = hasInstance;
    }

    public boolean isDefaultHasInstance(JsValue candidate) {
        return candidate == intrinsics.defaultHasInstance;
    }

    public void defineSymbol(JsObject target, JsSymbol key, JsValue value) {
        target.setSymbol(key, value);
        target.setSymbolFlags(key, HIDDEN);
    }

    public static void defineToStringTag(JsObject target, String tag) {
        target.setSymbol(JsSymbol.TO_STRING_TAG, new JsString(tag));
        target.setSymbolFlags(JsSymbol.TO_STRING_TAG, PropertyFlags.TAG);
    }

    public boolean isDefaultIterator(JsValue target, JsValue candidate) {
        final var expected = switch (target) {
            case JsArray ignored -> intrinsics.defaultArrayIterator;
            case JsArguments ignored -> intrinsics.defaultArrayIterator;
            case JsString ignored -> intrinsics.defaultStringIterator;
            case JsTypedArray ignored -> intrinsics.defaultTypedArrayIterator;
            default -> null;
        };
        return expected != null && candidate == expected;
    }

    public void installRegExpAccessors(JsObject proto) {
        for (final var name : RegexBuiltins.PROTO_ACCESSORS) {
            final var getter = new JsNativeFunction("get " + name,
                    (thisArg, _) -> RegexBuiltins.protoAccessor(regExpReceiver(thisArg), name, proto, intrinsics.ops));
            proto.defineAccessor(name, getter, null);
            proto.setFlags(name, HIDDEN);
        }
    }

    public JsValue regExpReceiver(JsValue receiver) {
        return receiver instanceof JsRegExp ? receiver : orSelf(unwrap(receiver), receiver);
    }

    public JsValue orSelf(JsValue unwrapped, JsValue receiver) {
        return unwrapped instanceof JsRegExp ? unwrapped : receiver;
    }

    public void installRegExpSymbolMethods(JsObject proto) {
        final var match = new JsNativeFunction("[Symbol.match]",
                (thisArg, args) -> RegexSymbolMethods.symbolMatch(thisArg, argStr(args), intrinsics.ops));
        match.setLength(1);
        proto.setSymbol(JsSymbol.MATCH, match);
        final var search = new JsNativeFunction("[Symbol.search]",
                (thisArg, args) -> RegexSymbolMethods.symbolSearch(thisArg, argStr(args), intrinsics.ops));
        search.setLength(1);
        proto.setSymbol(JsSymbol.SEARCH, search);
        final var replace = new JsNativeFunction("[Symbol.replace]",
                (thisArg, args) -> RegexSymbolMethods.symbolReplace(thisArg, argStr(args),
                        args.size() > 1 ? args.get(1) : JsUndefined.getInstance(), intrinsics.ops, intrinsics.invoker));
        replace.setLength(2);
        proto.setSymbol(JsSymbol.REPLACE, replace);
        final var split = new JsNativeFunction("[Symbol.split]",
                (thisArg, args) -> RegexSymbolMethods.symbolSplit(thisArg, argStr(args),
                        args.size() > 1 ? args.get(1) : JsUndefined.getInstance(), proto, intrinsics.ops));
        split.setLength(2);
        proto.setSymbol(JsSymbol.SPLIT, split);
        final var matchAll = new JsNativeFunction("[Symbol.matchAll]", (thisArg, args) -> symbolMatchAll(thisArg,
                argStr(args), intrinsics.regexpStringIteratorProto, proto, intrinsics.ops));
        matchAll.setLength(1);
        proto.setSymbol(JsSymbol.MATCH_ALL, matchAll);
        for (final var symbol : List.of(JsSymbol.MATCH, JsSymbol.SEARCH, JsSymbol.REPLACE, JsSymbol.SPLIT,
                JsSymbol.MATCH_ALL)) {
            proto.setSymbolFlags(symbol, HIDDEN);
        }
    }

    public String argStr(List<JsValue> args) {
        return !args.isEmpty() ? JsCoercion.toStr(args.getFirst(), intrinsics.ops) : "undefined";
    }

    public JsValue asyncGeneratorMethod(JsValue receiver, String name, GeneratorBuiltins.AsyncDriver driver,
            EventLoop eventLoop) {
        try {
            return GeneratorBuiltins.getAsyncMethod(requireAsyncGenerator(receiver, name), name, driver);
        } catch (TypeErrorException e) {
            return rejectedMethod(name, eventLoop, e);
        }
    }

    public JsValue disposeAsyncMethod(JsValue receiver, String name, EventLoop eventLoop) {
        try {
            final var stack = requireStack(receiver, name);
            final var method = DisposableStackBuiltins.getMethod(stack, name, intrinsics.ops, intrinsics.invoker,
                    eventLoop, true);
            if (method != null) {
                return method;
            }
            throw incompatible("AsyncDisposableStack.prototype." + name, receiver);
        } catch (TypeErrorException e) {
            return rejectedMethod(name, eventLoop, e);
        }
    }

    public JsNativeFunction rejectedMethod(String name, EventLoop eventLoop, RuntimeException error) {
        return new JsNativeFunction(name, (_, _) -> {
            final var promise = new JsPromise(eventLoop);
            promise.reject(InterpreterUtils.toErrorValue(error, intrinsics));
            return promise;
        });
    }

    public void installMapSizeAccessor(JsObject proto) {
        final var getter = new JsNativeFunction("get size",
                (thisArg, _) -> new JsNumber(requireMap(thisArg, "size", false).size()));
        getter.setLength(0);
        proto.defineAccessor("size", getter, null);
        proto.setFlags("size", PropertyFlags.HIDDEN);
    }

    public void installSetSizeAccessor(JsObject proto) {
        final var getter = new JsNativeFunction("get size",
                (thisArg, _) -> new JsNumber(requireSet(thisArg, "size", false).size()));
        getter.setLength(0);
        proto.defineAccessor("size", getter, null);
        proto.setFlags("size", PropertyFlags.HIDDEN);
        final var values = proto.get("values");
        if (values != null) {
            installMethod(proto, "keys", values);
        }
    }

    public void installTemporalPlainTimeAccessors(JsObject proto) {
        for (final var name : TemporalPlainTimeBuiltins.FIELD_ACCESSORS) {
            final var getter = new JsNativeFunction("get " + name, (thisArg, _) -> TemporalPlainTimeBuiltins
                    .fieldAccessor(requireTemporalPlainTime(thisArg, name), name));
            proto.defineAccessor(name, getter, null);
            proto.setFlags(name, HIDDEN);
        }
    }

    public void installGeometryAccessors(JsObject proto, List<String> names, Intrinsics.MethodResolver resolver) {
        for (final var name : names) {
            proto.defineAccessor(name,
                    new JsNativeFunction("get " + name, (thisArg, _) -> resolver.resolve(thisArg, name)), null);
            proto.setFlags(name, HIDDEN);
        }
    }

    public void installTypedArrayToStringTag(JsObject proto) {
        final var getter = new JsNativeFunction("get [Symbol.toStringTag]", (thisArg, _) -> {
            final var typed = thisArg instanceof JsTypedArray direct ? direct : null;
            return typed == null ? JsUndefined.getInstance() : new JsString(typed.kind().ctorName());
        });
        getter.setLength(0);
        proto.defineSymbolAccessor(JsSymbol.TO_STRING_TAG, getter, null);
        proto.setSymbolFlags(JsSymbol.TO_STRING_TAG, HIDDEN);
    }

    public void installTypedArrayGeometryAccessors(JsObject proto) {
        proto.defineAccessor("length", new JsNativeFunction("get length",
                (thisArg, _) -> new JsNumber(requireTypedArray(thisArg, "length").length())), null);
        proto.defineAccessor("byteLength", new JsNativeFunction("get byteLength",
                (thisArg, _) -> new JsNumber(requireTypedArray(thisArg, "byteLength").byteLength())), null);
        proto.defineAccessor("byteOffset", new JsNativeFunction("get byteOffset",
                (thisArg, _) -> new JsNumber(requireTypedArray(thisArg, "byteOffset").byteOffset())), null);
        proto.defineAccessor("buffer",
                new JsNativeFunction("get buffer", (thisArg, _) -> requireTypedArray(thisArg, "buffer").getBuffer()),
                null);
        for (final var name : List.of("length", "byteLength", "byteOffset", "buffer")) {
            proto.setFlags(name, JsObject.PropertyFlags.HIDDEN);
        }
    }

}
