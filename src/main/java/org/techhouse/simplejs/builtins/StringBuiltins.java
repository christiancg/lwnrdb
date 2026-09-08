package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.str;
import static org.techhouse.simplejs.builtins.string.StringRegExpDelegation.delegateToSymbol;
import static org.techhouse.simplejs.builtins.string.StringRegExpDelegation.genericMethod;
import static org.techhouse.simplejs.builtins.string.StringRegExpDelegation.isGeneric;
import static org.techhouse.simplejs.builtins.string.StringRegExpDelegation.requireGlobalRegExp;
import static org.techhouse.simplejs.builtins.string.StringRegExpDelegation.requireNotRegExp;
import static org.techhouse.simplejs.builtins.string.StringRegExpDelegation.viaRegExp;
import static org.techhouse.simplejs.builtins.string.StringSlicing.at;
import static org.techhouse.simplejs.builtins.string.StringSlicing.charAt;
import static org.techhouse.simplejs.builtins.string.StringSlicing.charCodeAt;
import static org.techhouse.simplejs.builtins.string.StringSlicing.codePointAt;
import static org.techhouse.simplejs.builtins.string.StringSlicing.concat;
import static org.techhouse.simplejs.builtins.string.StringSlicing.endsWith;
import static org.techhouse.simplejs.builtins.string.StringSlicing.indexOf;
import static org.techhouse.simplejs.builtins.string.StringSlicing.lastIndexOf;
import static org.techhouse.simplejs.builtins.string.StringSlicing.padEnd;
import static org.techhouse.simplejs.builtins.string.StringSlicing.padStart;
import static org.techhouse.simplejs.builtins.string.StringSlicing.repeat;
import static org.techhouse.simplejs.builtins.string.StringSlicing.slice;
import static org.techhouse.simplejs.builtins.string.StringSlicing.startPosition;
import static org.techhouse.simplejs.builtins.string.StringSlicing.substr;
import static org.techhouse.simplejs.builtins.string.StringSlicing.substring;
import static org.techhouse.simplejs.builtins.string.StringSlicing.trim;
import static org.techhouse.simplejs.builtins.string.StringUnicode.isWellFormed;
import static org.techhouse.simplejs.builtins.string.StringUnicode.localeCompare;
import static org.techhouse.simplejs.builtins.string.StringUnicode.normalize;
import static org.techhouse.simplejs.builtins.string.StringUnicode.toLowerCaseWithFinalSigma;
import static org.techhouse.simplejs.builtins.string.StringUnicode.toWellFormed;
import static org.techhouse.simplejs.values.JsLimits.MAX_SAFE_INTEGER;

import java.util.List;
import java.util.Locale;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class StringBuiltins {
    public static final double MAX_STRING_LENGTH = (1 << 29) - 24;
    public static final List<String> NAMES = List.of("slice", "substring", "split", "replace", "replaceAll", "match",
            "matchAll", "search", "toUpperCase", "toLowerCase", "trim", "includes", "startsWith", "endsWith",
            "padStart", "repeat", "charAt", "indexOf", "lastIndexOf", "charCodeAt", "codePointAt", "at", "padEnd",
            "trimStart", "trimEnd", "normalize", "localeCompare", "concat", "isWellFormed", "toWellFormed", "substr",
            "toLocaleUpperCase", "toLocaleLowerCase", "trimLeft", "trimRight");

    private StringBuiltins() {
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

    public static String stringify(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty()) {
            return "";
        }
        if (args.getFirst() instanceof JsSymbol symbol) {
            return SymbolBuiltins.describe(symbol);
        }
        return JsCoercion.toStr(args.getFirst(), ops);
    }

    public static String fromCharCode(List<JsValue> args, InterpreterOps ops) {
        final var sb = new StringBuilder();
        for (final var arg : args) {
            sb.append((char) (NumberFormatter.toUint32(JsCoercion.toNumber(arg, ops)) & 0xFFFFL));
        }
        return sb.toString();
    }

    public static String fromCodePoint(List<JsValue> args, InterpreterOps ops) {
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

    public static String raw(List<JsValue> args, InterpreterOps ops) {
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
            InterpreterOps.tick(ops);
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

    public static void requireObject(JsValue value) {
        if (!InterpreterUtils.isObjectLike(value)) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
    }

    public static long lengthOfArrayLike(JsValue target, InterpreterOps ops) {
        final var raw = ops == null ? elementLength(target) : ops.getMember(target, new JsString("length"));
        final var length = JsCoercion.toNumber(raw, ops);
        if (Double.isNaN(length) || length <= 0) {
            return 0;
        }
        return (long) Math.min(length, MAX_SAFE_INTEGER);
    }

    public static JsValue elementLength(JsValue target) {
        return target instanceof JsArray array ? new JsNumber(array.length()) : JsUndefined.getInstance();
    }

    public static JsValue elementOf(JsValue target, int index) {
        return target instanceof JsArray array && index < array.length() ? array.get(index) : JsUndefined.getInstance();
    }

    public static JsValue rawSegments(JsValue strings) {
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
            case "toLowerCase" -> new JsNativeFunction("toLowerCase",
                    (_, _) -> new JsString(toLowerCaseWithFinalSigma(value, Locale.ROOT)));
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
                    (_, _) -> new JsString(value.toUpperCase(InterpreterOps.locale(ops))));
            case "toLocaleLowerCase" -> new JsNativeFunction("toLocaleLowerCase",
                    (_, _) -> new JsString(toLowerCaseWithFinalSigma(value, InterpreterOps.locale(ops))));
            case "trimLeft" -> new JsNativeFunction("trimLeft", (_, _) -> new JsString(trim(value, true, false)));
            case "trimRight" -> new JsNativeFunction("trimRight", (_, _) -> new JsString(trim(value, false, true)));
            default -> null;
        };
    }

    public static double rawLengthArg(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return 0;
        }
        final var value = JsCoercion.toNumber(args.getFirst(), ops);
        return Double.isNaN(value) ? 0 : value;
    }

    public static long requireStringLength(double length, InterpreterOps ops) {
        if (length > MAX_STRING_LENGTH) {
            throw new org.techhouse.simplejs.exceptions.RangeErrorException("Invalid string length");
        }
        final var safe = (long) Math.max(length, 0);
        InterpreterOps.chargeChars(ops, safe);
        return safe;
    }

    public static int intArg(List<JsValue> args, int position, int fallback, InterpreterOps ops) {
        if (position >= args.size() || args.get(position) instanceof JsUndefined) {
            return fallback;
        }
        final var value = JsCoercion.toNumber(args.get(position), ops);
        return Double.isNaN(value) ? 0 : (int) value;
    }

}
