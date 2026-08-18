package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class StringBuiltins {
    public static final List<String> NAMES = List.of("slice", "substring", "split", "replace", "replaceAll", "match",
            "matchAll", "search", "toUpperCase", "toLowerCase", "trim", "includes", "startsWith", "endsWith",
            "padStart", "repeat", "charAt", "indexOf", "lastIndexOf", "charCodeAt", "codePointAt", "at", "padEnd",
            "trimStart", "trimEnd", "normalize", "localeCompare", "concat", "isWellFormed", "toWellFormed", "substr",
            "toLocaleUpperCase", "toLocaleLowerCase", "trimLeft", "trimRight");

    private StringBuiltins() {
    }

    // Spec GetMethod(argument, symbol) + Call, guarded by "If regexp is an Object": the well-known
    // symbol wins for any object argument, a RegExp included, but is never read off a primitive.
    private static JsValue delegateToSymbol(String value, List<JsValue> args, InterpreterOps ops,
                                            List<JsValue> extra) {
        if (ops == null || args.isEmpty()) {
            return null;
        }
        final var pattern = args.getFirst();
        if (!InterpreterUtils.isObjectLike(pattern)) {
            return null;
        }
        final var method = ops.getMember(pattern, JsSymbol.MATCH_ALL);
        if (!(method instanceof JsFunction) && !(method instanceof JsNativeFunction)) {
            return null;
        }
        final var callArgs = new ArrayList<JsValue>();
        callArgs.add(new JsString(value));
        callArgs.addAll(extra);
        return ops.call(method, pattern, callArgs);
    }

    // Spec RegExpCreate(pattern, flags) then Invoke(rx, symbol, S): the fallback of match/matchAll/
    // search is not a private matcher, it is the ordinary RegExp protocol on a fresh RegExp.
    private static JsValue viaRegExp(String value, List<JsValue> args, String flags, JsSymbol symbol,
            InterpreterOps ops) {
        final var pattern = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var source = pattern instanceof JsUndefined ? "" : JsCoercion.toStr(pattern, ops);
        final var regexp = RegexTranslator.compile(source, flags);
        return ops.call(ops.getMember(regexp, symbol), regexp, List.of(new JsString(value)));
    }

    private static List<JsValue> tail(List<JsValue> args) {
        return args.size() > 1 ? args.subList(1, args.size()) : List.of();
    }

    // split/replace/replaceAll/match/search are generic per spec: RequireObjectCoercible(this) runs
    // first, then a well-known-symbol delegation attempt against the raw (un-stringified) receiver
    // and argument, and only once that is ruled out does ToString(this) happen. Intrinsics routes
    // these five names here instead of through the eager-coercing getMethod dispatch.
    public static boolean isGeneric(String name) {
        return switch (name) {
            case "split", "replace", "replaceAll", "match", "search" -> true;
            default -> false;
        };
    }

    public static JsNativeFunction genericMethod(String name, InterpreterOps ops, Invoker invoker) {
        if (!isGeneric(name)) {
            return null;
        }
        return new JsNativeFunction(name, (thisArg, args) -> genericDispatch(name, thisArg, args, ops, invoker));
    }

    private static JsValue genericDispatch(String name, JsValue thisArg, List<JsValue> args, InterpreterOps ops,
            Invoker invoker) {
        requireCoercible(thisArg, name);
        if ("replaceAll".equals(name)) {
            requireGlobalRegExp(args, "replaceAll", ops);
        }
        final var delegated = delegateToSymbolRaw(thisArg, args, genericSymbol(name), ops, genericExtra(name, args));
        if (delegated != null) {
            return delegated;
        }
        final var value = JsCoercion.toStr(thisArg, ops);
        return switch (name) {
            case "split" -> split(value, args, ops);
            case "replace" -> replace(value, args, invoker, false, ops);
            case "replaceAll" -> replace(value, args, invoker, true, ops);
            case "match" -> viaRegExp(value, args, "", JsSymbol.MATCH, ops);
            case "search" -> viaRegExp(value, args, "", JsSymbol.SEARCH, ops);
            default -> throw new IllegalStateException(name);
        };
    }

    private static JsSymbol genericSymbol(String name) {
        return switch (name) {
            case "split" -> JsSymbol.SPLIT;
            case "replace", "replaceAll" -> JsSymbol.REPLACE;
            case "match" -> JsSymbol.MATCH;
            case "search" -> JsSymbol.SEARCH;
            default -> throw new IllegalStateException(name);
        };
    }

    private static List<JsValue> genericExtra(String name, List<JsValue> args) {
        return switch (name) {
            case "split", "replace", "replaceAll" -> tail(args);
            default -> List.of();
        };
    }

    private static void requireCoercible(JsValue receiver, String method) {
        if (receiver instanceof JsNull || receiver instanceof JsUndefined) {
            throw new TypeErrorException("String.prototype." + method + " called on null or undefined");
        }
    }

    // Same GetMethod(argument, symbol) + Call as delegateToSymbol, except the call argument carrying
    // the receiver is the raw RequireObjectCoercible result rather than an already ToString'd copy -
    // the delegate (e.g. RegExp.prototype[@@replace]) performs its own ToString on it per spec, so a
    // poisoned receiver's toString must not run before the delegation check has ruled itself out.
    private static JsValue delegateToSymbolRaw(JsValue receiver, List<JsValue> args, JsSymbol symbol,
            InterpreterOps ops, List<JsValue> extra) {
        if (ops == null || args.isEmpty()) {
            return null;
        }
        final var pattern = args.getFirst();
        if (!InterpreterUtils.isObjectLike(pattern)) {
            return null;
        }
        final var method = ops.getMember(pattern, symbol);
        if (method instanceof JsUndefined || method instanceof JsNull) {
            return null;
        }
        // GetMethod: a defined-but-non-callable well-known-symbol method is a TypeError, not a
        // silent fall-through to the ToString(this)/ToString(searchValue) path.
        if (!(method instanceof JsFunction) && !(method instanceof JsNativeFunction)) {
            throw new TypeErrorException(symbol + " is not a function");
        }
        final var callArgs = new ArrayList<JsValue>();
        callArgs.add(receiver);
        callArgs.addAll(extra);
        return ops.call(method, pattern, callArgs);
    }

    // Spec IsRegExp(argument): Get(argument, @@match) - which can itself throw from a user getter -
    // then ToBoolean it if not undefined, else fall back to "is a RegExp".
    private static boolean isRegExp(JsValue value, InterpreterOps ops) {
        if (!(value instanceof JsObject) && !(value instanceof JsRegExp)) {
            return false;
        }
        if (ops != null) {
            final var matcher = ops.getMember(value, JsSymbol.MATCH);
            if (!(matcher instanceof JsUndefined)) {
                return JsCoercion.toBoolean(matcher);
            }
        }
        return value instanceof JsRegExp;
    }

    // Spec: includes/startsWith/endsWith reject an argument that IsRegExp before touching it at
    // all (RequireObjectCoercible on the receiver already happened via Intrinsics.requireString).
    private static void requireNotRegExp(List<JsValue> args, String method, InterpreterOps ops) {
        if (!args.isEmpty() && isRegExp(args.getFirst(), ops)) {
            throw new TypeErrorException(
                    "First argument to String.prototype." + method + " must not be a regular expression");
        }
    }

    // Spec: replaceAll/matchAll reject an IsRegExp argument whose own "flags" lacks `g`, reading the
    // flags off the object rather than off the compiled pattern.
    private static void requireGlobalRegExp(List<JsValue> args, String method, InterpreterOps ops) {
        if (args.isEmpty() || !RegexBuiltins.isRegExp(args.getFirst(), ops)) {
            return;
        }
        final var flags = ops.getMember(args.getFirst(), new JsString("flags"));
        if (flags instanceof JsUndefined || flags instanceof JsNull) {
            throw new TypeErrorException("String.prototype." + method + " called with a RegExp without flags");
        }
        if (JsCoercion.toStr(flags, ops).indexOf('g') < 0) {
            throw new TypeErrorException("String.prototype." + method + " called with a non-global RegExp argument");
        }
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var string = new JsNativeFunction("String", (_, args) -> new JsString(stringify(args, ops)));
        final var raw = new JsNativeFunction("raw", (_, args) -> new JsString(raw(args, ops)));
        raw.setLength(1);
        string.setProperty("raw", raw);
        final var fromCharCode = new JsNativeFunction("fromCharCode",
                (_, args) -> new JsString(fromCharCode(args, ops)));
        fromCharCode.setLength(1);
        string.setProperty("fromCharCode", fromCharCode);
        final var fromCodePoint = new JsNativeFunction("fromCodePoint",
                (_, args) -> new JsString(fromCodePoint(args, ops)));
        fromCodePoint.setLength(1);
        string.setProperty("fromCodePoint", fromCodePoint);
        return string;
    }

    // The explicit String(sym) conversion describes a symbol; implicit coercion (JsCoercion.toStr)
    // must keep throwing, so this cannot go into the shared coercion.
    private static String stringify(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty()) {
            return "";
        }
        if (args.getFirst() instanceof JsSymbol symbol) {
            return SymbolBuiltins.describe(symbol);
        }
        return JsCoercion.toStr(args.getFirst(), ops);
    }

    private static String fromCharCode(List<JsValue> args, InterpreterOps ops) {
        final var sb = new StringBuilder();
        for (final var arg : args) {
            // ToUint16, so an infinite or out-of-int argument wraps rather than saturating.
            sb.append((char) (NumberFormatter.toUint32(JsCoercion.toNumber(arg, ops)) & 0xFFFFL));
        }
        return sb.toString();
    }

    private static String fromCodePoint(List<JsValue> args, InterpreterOps ops) {
        final var sb = new StringBuilder();
        for (final var arg : args) {
            final var number = JsCoercion.toNumber(arg, ops);
            if (number != Math.rint(number) || Double.isInfinite(number)) {
                throw new org.techhouse.simplejs.exceptions.RangeErrorException(number + " is not a valid code point");
            }
            if (number < 0 || number > 0x10FFFF) {
                throw new org.techhouse.simplejs.exceptions.RangeErrorException(number + " is not a valid code point");
            }
            sb.appendCodePoint((int) number);
        }
        return sb.toString();
    }

    // Fully generic: the template and its `raw` are only required to be array-*like*, so a plain
    // object with a numeric `length` drives the same walk a real tagged-template strings array does.
    private static String raw(List<JsValue> args, InterpreterOps ops) {
        final var template = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        requireObject(template);
        final var literals = ops == null ? rawSegments(template) : ops.getMember(template, new JsString("raw"));
        requireObject(literals);
        final var count = lengthOfArrayLike(literals, ops);
        if (count <= 0) {
            return "";
        }
        final var result = new StringBuilder();
        for (var i = 0; i < count; i++) {
            final var segment = ops == null
                    ? elementOf(literals, i)
                    : ops.getMember(literals, new JsString(Integer.toString(i)));
            result.append(JsCoercion.toStr(segment, ops));
            if (i + 1 == count) {
                break;
            }
            if (i + 1 < args.size()) {
                result.append(JsCoercion.toStr(args.get(i + 1), ops));
            }
        }
        return result.toString();
    }

    private static void requireObject(JsValue value) {
        if (!InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
    }

    private static long lengthOfArrayLike(JsValue target, InterpreterOps ops) {
        final var raw = ops == null ? elementLength(target) : ops.getMember(target, new JsString("length"));
        final var length = JsCoercion.toNumber(raw, ops);
        if (Double.isNaN(length) || length <= 0) {
            return 0;
        }
        return (long) Math.min(length, 9007199254740991d);
    }

    private static JsValue elementLength(JsValue target) {
        return target instanceof JsArray array ? new JsNumber(array.length()) : JsUndefined.getInstance();
    }

    private static JsValue elementOf(JsValue target, int index) {
        return target instanceof JsArray array && index < array.length() ? array.get(index) : JsUndefined.getInstance();
    }

    private static JsValue rawSegments(JsValue strings) {
        if (strings instanceof JsArray array) {
            return array.getProperty("raw") == null ? JsUndefined.getInstance() : array.getProperty("raw");
        }
        if (strings instanceof JsObject object) {
            return object.has("raw") ? object.get("raw") : JsUndefined.getInstance();
        }
        return JsUndefined.getInstance();
    }

    public static JsNativeFunction getMethod(JsString receiver, String name, Invoker invoker, InterpreterOps ops) {
        if (isGeneric(name)) {
            return genericMethod(name, ops, invoker);
        }
        final var value = receiver.getValue();
        return switch (name) {
            case "slice" -> new JsNativeFunction("slice", (_, args) -> new JsString(slice(value, args, ops)));
            case "substring" ->
                new JsNativeFunction("substring", (_, args) -> new JsString(substring(value, args, ops)));
            case "matchAll" -> new JsNativeFunction("matchAll", (_, args) -> {
                requireGlobalRegExp(args, "matchAll", ops);
                final var delegated = delegateToSymbol(value, args, ops, List.of());
                return delegated != null ? delegated : viaRegExp(value, args, "g", JsSymbol.MATCH_ALL, ops);
            });
            case "toUpperCase" ->
                new JsNativeFunction("toUpperCase", (_, _) -> new JsString(value.toUpperCase(Locale.ROOT)));
            case "toLowerCase" ->
                new JsNativeFunction("toLowerCase", (_, _) -> new JsString(value.toLowerCase(Locale.ROOT)));
            case "trim" -> new JsNativeFunction("trim", (_, _) -> new JsString(trim(value, true, true)));
            case "includes" -> new JsNativeFunction("includes", (_, args) -> {
                requireNotRegExp(args, "includes", ops);
                final var search = str(args, 0, ops);
                return JsBoolean.of(value.indexOf(search, startPosition(value, args, ops)) >= 0);
            });
            case "startsWith" -> new JsNativeFunction("startsWith", (_, args) -> {
                requireNotRegExp(args, "startsWith", ops);
                final var search = str(args, 0, ops);
                return JsBoolean.of(value.startsWith(search, startPosition(value, args, ops)));
            });
            case "endsWith" -> new JsNativeFunction("endsWith", (_, args) -> {
                requireNotRegExp(args, "endsWith", ops);
                final var search = str(args, 0, ops);
                return JsBoolean.of(endsWith(value, search, args, ops));
            });
            case "padStart" -> new JsNativeFunction("padStart", (_, args) -> new JsString(padStart(value, args, ops)));
            case "repeat" -> new JsNativeFunction("repeat", (_, args) -> new JsString(repeat(value, args, ops)));
            case "charAt" -> new JsNativeFunction("charAt", (_, args) -> new JsString(charAt(value, args, ops)));
            case "indexOf" -> new JsNativeFunction("indexOf", (_, args) -> new JsNumber(indexOf(value, args, ops)));
            case "lastIndexOf" ->
                new JsNativeFunction("lastIndexOf", (_, args) -> new JsNumber(lastIndexOf(value, args, ops)));
            case "charCodeAt" -> new JsNativeFunction("charCodeAt", (_, args) -> charCodeAt(value, args, ops));
            case "codePointAt" -> new JsNativeFunction("codePointAt", (_, args) -> codePointAt(value, args, ops));
            case "at" -> new JsNativeFunction("at", (_, args) -> at(value, args, ops));
            case "padEnd" -> new JsNativeFunction("padEnd", (_, args) -> new JsString(padEnd(value, args, ops)));
            case "trimStart" -> new JsNativeFunction("trimStart", (_, _) -> new JsString(trim(value, true, false)));
            case "trimEnd" -> new JsNativeFunction("trimEnd", (_, _) -> new JsString(trim(value, false, true)));
            case "normalize" ->
                new JsNativeFunction("normalize", (_, args) -> new JsString(normalize(value, args, ops)));
            case "localeCompare" ->
                new JsNativeFunction("localeCompare", (_, args) -> new JsNumber(localeCompare(value, args, ops)));
            case "concat" -> new JsNativeFunction("concat", (_, args) -> new JsString(concat(value, args, ops)));
            case "isWellFormed" -> new JsNativeFunction("isWellFormed", (_, _) -> JsBoolean.of(isWellFormed(value)));
            case "toWellFormed" -> new JsNativeFunction("toWellFormed", (_, _) -> new JsString(toWellFormed(value)));
            case "substr" -> new JsNativeFunction("substr", (_, args) -> new JsString(substr(value, args, ops)));
            case "toLocaleUpperCase" -> new JsNativeFunction("toLocaleUpperCase",
                    (_, _) -> new JsString(value.toUpperCase(Locale.getDefault())));
            case "toLocaleLowerCase" -> new JsNativeFunction("toLocaleLowerCase",
                    (_, _) -> new JsString(value.toLowerCase(Locale.getDefault())));
            case "trimLeft" -> new JsNativeFunction("trimLeft", (_, _) -> new JsString(trim(value, true, false)));
            case "trimRight" -> new JsNativeFunction("trimRight", (_, _) -> new JsString(trim(value, false, true)));
            default -> null;
        };
    }

    private static boolean isWellFormed(String value) {
        for (var i = 0; i < value.length(); i++) {
            final var ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return false;
            }
        }
        return true;
    }

    private static String toWellFormed(String value) {
        final var result = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            final var ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    result.append(ch).append(value.charAt(i + 1));
                    i++;
                } else {
                    result.append('�');
                }
            } else if (Character.isLowSurrogate(ch)) {
                result.append('�');
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private static JsValue charCodeAt(String value, List<JsValue> args, InterpreterOps ops) {
        final var index = intArg(args, 0, 0, ops);
        if (index < 0 || index >= value.length()) {
            return new JsNumber(Double.NaN);
        }
        return new JsNumber(value.charAt(index));
    }

    private static JsValue codePointAt(String value, List<JsValue> args, InterpreterOps ops) {
        final var index = intArg(args, 0, 0, ops);
        if (index < 0 || index >= value.length()) {
            return JsUndefined.getInstance();
        }
        return new JsNumber(value.codePointAt(index));
    }

    private static JsValue at(String value, List<JsValue> args, InterpreterOps ops) {
        var index = intArg(args, 0, 0, ops);
        if (index < 0) {
            index += value.length();
        }
        if (index < 0 || index >= value.length()) {
            return JsUndefined.getInstance();
        }
        return new JsString(String.valueOf(value.charAt(index)));
    }

    private static String padEnd(String value, List<JsValue> args, InterpreterOps ops) {
        final var target = intArg(args, 0, 0, ops);
        if (value.length() >= target) {
            return value;
        }
        final var pad = args.size() < 2 || args.get(1) instanceof JsUndefined ? " " : str(args, 1, ops);
        if (pad.isEmpty()) {
            return value;
        }
        final var sb = new StringBuilder(value);
        while (sb.length() < target) {
            sb.append(pad);
        }
        return sb.substring(0, target);
    }

    private static String normalize(String value, List<JsValue> args, InterpreterOps ops) {
        final var form = args.isEmpty() || args.getFirst() instanceof JsUndefined ? "NFC" : str(args, 0, ops);
        try {
            return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.valueOf(form));
        } catch (IllegalArgumentException invalidForm) {
            throw new org.techhouse.simplejs.exceptions.RangeErrorException(
                    "The normalization form should be one of NFC, NFD, NFKC, NFKD.");
        }
    }

    private static int localeCompare(String value, List<JsValue> args, InterpreterOps ops) {
        return Integer.signum(java.text.Collator.getInstance(Locale.getDefault()).compare(value, str(args, 0, ops)));
    }

    private static String concat(String value, List<JsValue> args, InterpreterOps ops) {
        final var sb = new StringBuilder(value);
        for (final var arg : args) {
            sb.append(JsCoercion.toStr(arg, ops));
        }
        return sb.toString();
    }

    private static String slice(String value, List<JsValue> args, InterpreterOps ops) {
        final var length = value.length();
        var start = clampIndex(intArg(args, 0, 0, ops), length);
        var end = args.size() < 2 || args.get(1) instanceof JsUndefined
                ? length
                : clampIndex(intArg(args, 1, length, ops), length);
        if (start >= end) {
            return "";
        }
        return value.substring(start, end);
    }

    private static String substr(String value, List<JsValue> args, InterpreterOps ops) {
        final var length = value.length();
        var start = intArg(args, 0, 0, ops);
        if (start < 0) {
            start = Math.max(length + start, 0);
        } else {
            start = Math.min(start, length);
        }
        final var count = args.size() < 2 || args.get(1) instanceof JsUndefined
                ? length - start
                : intArg(args, 1, 0, ops);
        if (count <= 0) {
            return "";
        }
        return value.substring(start, Math.min(start + count, length));
    }

    private static String substring(String value, List<JsValue> args, InterpreterOps ops) {
        final var length = value.length();
        var start = Math.clamp(intArg(args, 0, 0, ops), 0, length);
        var end = args.size() < 2 || args.get(1) instanceof JsUndefined
                ? length
                : Math.clamp(intArg(args, 1, length, ops), 0, length);
        if (start > end) {
            final var tmp = start;
            start = end;
            end = tmp;
        }
        return value.substring(start, end);
    }

    // ToUint32(limit) runs before ToString(separator), and an undefined limit is 2^32-1 rather than
    // "unbounded" - a limit of 0 yields an empty array even for an undefined separator.
    private static JsValue split(String value, List<JsValue> args, InterpreterOps ops) {
        final var limit = args.size() < 2 || args.get(1) instanceof JsUndefined
                ? 0xFFFFFFFFL
                : NumberFormatter.toUint32(JsCoercion.toNumber(args.get(1), ops));
        final var undefinedSeparator = args.isEmpty() || args.getFirst() instanceof JsUndefined;
        final var separator = undefinedSeparator ? "" : str(args, 0, ops);
        if (limit == 0) {
            return new JsArray();
        }
        if (undefinedSeparator) {
            final var single = new JsArray();
            single.push(new JsString(value));
            return single;
        }
        return limited(splitAll(value, separator), limit);
    }

    private static JsArray splitAll(String value, String separator) {
        final var result = new JsArray();
        if (separator.isEmpty()) {
            for (var i = 0; i < value.length(); i++) {
                result.push(new JsString(String.valueOf(value.charAt(i))));
            }
            return result;
        }
        var from = 0;
        var index = value.indexOf(separator);
        while (index >= 0) {
            result.push(new JsString(value.substring(from, index)));
            from = index + separator.length();
            index = value.indexOf(separator, from);
        }
        result.push(new JsString(value.substring(from)));
        return result;
    }

    private static JsValue limited(JsArray array, long limit) {
        if (limit >= array.length()) {
            return array;
        }
        return new JsArray(List.copyOf(array.getElements().subList(0, (int) limit)));
    }

    // ToString(replaceValue) (when it is not callable) runs unconditionally before the string is even
    // searched for a match - a no-match result must still have observed replaceValue's coercion.
    private static JsValue replace(String value, List<JsValue> args, Invoker invoker, boolean all, InterpreterOps ops) {
        final var search = str(args, 0, ops);
        final var callable = args.size() > 1 && isCallable(args.get(1));
        final var replacement = callable ? null : str(args, 1, ops);
        if (all) {
            return new JsString(replaceAllLiteral(value, search, args, invoker, callable, replacement, ops));
        }
        final var index = value.indexOf(search);
        if (index < 0) {
            return new JsString(value);
        }
        final var piece = literalPiece(value, search, index, args, invoker, callable, replacement, ops);
        return new JsString(value.substring(0, index) + piece + value.substring(index + search.length()));
    }

    private static String literalPiece(String value, String search, int index, List<JsValue> args, Invoker invoker,
            boolean callable, String replacement, InterpreterOps ops) {
        if (callable) {
            return JsCoercion.toStr(invoker.call(args.get(1), JsUndefined.getInstance(),
                    List.of(new JsString(search), new JsNumber(index), new JsString(value))), ops);
        }
        return expandLiteral(replacement, search, value, index);
    }

    private static String expandLiteral(String template, String matched, String input, int position) {
        final var sb = new StringBuilder();
        for (var i = 0; i < template.length(); i++) {
            final var ch = template.charAt(i);
            if (ch != '$' || i + 1 >= template.length()) {
                sb.append(ch);
                continue;
            }
            switch (template.charAt(i + 1)) {
                case '$' -> sb.append('$');
                case '&' -> sb.append(matched);
                case '`' -> sb.append(input, 0, position);
                case '\'' -> sb.append(input.substring(position + matched.length()));
                default -> {
                    sb.append(ch);
                    continue;
                }
            }
            i++;
        }
        return sb.toString();
    }

    private static String replaceAllLiteral(String value, String search, List<JsValue> args, Invoker invoker,
            boolean callable, String replacement, InterpreterOps ops) {
        // advanceBy is max(1, searchLength), so an empty search matches at every position including
        // the one past the end.
        final var advanceBy = Math.max(1, search.length());
        final var sb = new StringBuilder();
        var from = 0;
        var index = value.indexOf(search);
        while (index >= 0) {
            sb.append(value, from, index);
            sb.append(literalPiece(value, search, index, args, invoker, callable, replacement, ops));
            from = index + search.length();
            final var next = index + advanceBy;
            if (next > value.length()) {
                break;
            }
            index = value.indexOf(search, next);
        }
        sb.append(value.substring(Math.min(from, value.length())));
        return sb.toString();
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    private static String padStart(String value, List<JsValue> args, InterpreterOps ops) {
        final var target = intArg(args, 0, 0, ops);
        if (value.length() >= target) {
            return value;
        }
        final var pad = args.size() < 2 || args.get(1) instanceof JsUndefined ? " " : str(args, 1, ops);
        if (pad.isEmpty()) {
            return value;
        }
        final var sb = new StringBuilder();
        while (sb.length() < target - value.length()) {
            sb.append(pad);
        }
        return sb.substring(0, target - value.length()) + value;
    }

    private static String repeat(String value, List<JsValue> args, InterpreterOps ops) {
        final var requested = args.isEmpty() || args.getFirst() instanceof JsUndefined
                ? 0
                : JsCoercion.toNumber(args.getFirst(), ops);
        if (requested < 0 || Double.isInfinite(requested)) {
            throw new org.techhouse.simplejs.exceptions.RangeErrorException("Invalid count value: " + requested);
        }
        return value.repeat(Double.isNaN(requested) ? 0 : (int) requested);
    }

    private static String charAt(String value, List<JsValue> args, InterpreterOps ops) {
        final var index = intArg(args, 0, 0, ops);
        if (index < 0 || index >= value.length()) {
            return "";
        }
        return String.valueOf(value.charAt(index));
    }

    // The spec's WhiteSpace + LineTerminator sets, which Java's Character.isWhitespace does not
    // match: it omits U+00A0/U+2007/U+202F/U+FEFF and adds the U+001C-001F separators.
    private static boolean isJsWhitespace(char c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0x00A0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
                || c == 0x3000 || c == 0xFEFF;
    }

    private static String trim(String value, boolean start, boolean end) {
        var from = 0;
        var to = value.length();
        while (start && from < to && isJsWhitespace(value.charAt(from))) {
            from++;
        }
        while (end && to > from && isJsWhitespace(value.charAt(to - 1))) {
            to--;
        }
        return value.substring(from, to);
    }

    private static int startPosition(String value, List<JsValue> args, InterpreterOps ops) {
        final var pos = args.size() > 1 ? JsCoercion.toNumber(args.get(1), ops) : 0;
        return clampPosition(pos, value.length());
    }

    private static boolean endsWith(String value, String search, List<JsValue> args, InterpreterOps ops) {
        final var raw = args.size() > 1 && !(args.get(1) instanceof JsUndefined)
                ? JsCoercion.toNumber(args.get(1), ops)
                : value.length();
        final var end = clampPosition(raw, value.length());
        final var start = end - search.length();
        return start >= 0 && value.startsWith(search, start);
    }

    private static int clampIndex(int index, int length) {
        if (index < 0) {
            return Math.max(length + index, 0);
        }
        return Math.min(index, length);
    }

    private static int indexOf(String value, List<JsValue> args, InterpreterOps ops) {
        final var search = str(args, 0, ops);
        final var pos = args.size() > 1 ? JsCoercion.toNumber(args.get(1), ops) : 0;
        return value.indexOf(search, clampPosition(pos, value.length()));
    }

    private static int lastIndexOf(String value, List<JsValue> args, InterpreterOps ops) {
        final var search = str(args, 0, ops);
        final var raw = args.size() > 1 ? JsCoercion.toNumber(args.get(1), ops) : Double.NaN;
        final var pos = Double.isNaN(raw) ? Double.POSITIVE_INFINITY : raw;
        return value.lastIndexOf(search, clampPosition(pos, value.length()));
    }

    private static int clampPosition(double pos, int length) {
        if (Double.isNaN(pos) || pos <= 0) {
            return 0;
        }
        if (pos >= length) {
            return length;
        }
        return (int) pos;
    }

    private static int intArg(List<JsValue> args, int position, int fallback, InterpreterOps ops) {
        if (position >= args.size() || args.get(position) instanceof JsUndefined) {
            return fallback;
        }
        final var value = JsCoercion.toNumber(args.get(position), ops);
        return Double.isNaN(value) ? 0 : (int) value;
    }

    private static String str(List<JsValue> args, int position, InterpreterOps ops) {
        return position < args.size() ? JsCoercion.toStr(args.get(position), ops) : "undefined";
    }
}
