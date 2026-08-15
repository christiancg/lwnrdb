package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;
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

    private static JsValue delegateToSymbol(String value, List<JsValue> args, JsSymbol symbol, InterpreterOps ops,
            List<JsValue> extra) {
        if (ops == null || args.isEmpty() || !(args.getFirst() instanceof JsObject pattern)) {
            return null;
        }
        final var method = ops.getMember(pattern, symbol);
        if (!(method instanceof JsFunction) && !(method instanceof JsNativeFunction)) {
            return null;
        }
        final var callArgs = new ArrayList<JsValue>();
        callArgs.add(new JsString(value));
        callArgs.addAll(extra);
        return ops.call(method, pattern, callArgs);
    }

    private static List<JsValue> tail(List<JsValue> args) {
        return args.size() > 1 ? args.subList(1, args.size()) : List.of();
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

    private static void requireGlobalRegExp(List<JsValue> args) {
        if (!args.isEmpty() && args.getFirst() instanceof JsRegExp regexp && !regexp.isGlobal()) {
            throw new TypeErrorException(
                    "String.prototype." + "replaceAll" + " called with a non-global RegExp argument");
        }
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var string = new JsNativeFunction("String", (_, args) -> new JsString(stringify(args, ops)));
        final var raw = new JsNativeFunction("raw", (_, args) -> new JsString(raw(args)));
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
            final var code = (int) JsCoercion.toNumber(arg, ops);
            sb.append((char) (code & 0xFFFF));
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

    private static String raw(List<JsValue> args) {
        if (args.isEmpty()) {
            return "";
        }
        final var raw = rawSegments(args.getFirst());
        if (raw == null || raw.length() == 0) {
            return "";
        }
        final var result = new StringBuilder();
        for (var i = 0; i < raw.length(); i++) {
            result.append(JsCoercion.toStr(raw.get(i)));
            if (i + 1 < raw.length() && i + 1 < args.size()) {
                result.append(JsCoercion.toStr(args.get(i + 1)));
            }
        }
        return result.toString();
    }

    private static JsArray rawSegments(JsValue strings) {
        final JsValue raw;
        if (strings instanceof JsArray array) {
            raw = array.getProperty("raw");
        } else if (strings instanceof JsObject object) {
            raw = object.get("raw");
        } else {
            raw = null;
        }
        return raw instanceof JsArray array ? array : null;
    }

    public static JsNativeFunction getMethod(JsString receiver, String name, Invoker invoker, InterpreterOps ops) {
        final var value = receiver.getValue();
        return switch (name) {
            case "slice" -> new JsNativeFunction("slice", (_, args) -> new JsString(slice(value, args, ops)));
            case "substring" ->
                new JsNativeFunction("substring", (_, args) -> new JsString(substring(value, args, ops)));
            case "split" -> new JsNativeFunction("split", (_, args) -> {
                final var delegated = delegateToSymbol(value, args, JsSymbol.SPLIT, ops, tail(args));
                return delegated != null ? delegated : split(value, args, ops);
            });
            case "replace" -> new JsNativeFunction("replace", (_, args) -> {
                final var delegated = delegateToSymbol(value, args, JsSymbol.REPLACE, ops, tail(args));
                return delegated != null ? delegated : replace(value, args, invoker, false, ops);
            });
            case "replaceAll" -> new JsNativeFunction("replaceAll", (_, args) -> {
                requireGlobalRegExp(args);
                final var delegated = delegateToSymbol(value, args, JsSymbol.REPLACE, ops, tail(args));
                return delegated != null ? delegated : replace(value, args, invoker, true, ops);
            });
            case "match" -> new JsNativeFunction("match", (_, args) -> {
                final var delegated = delegateToSymbol(value, args, JsSymbol.MATCH, ops, List.of());
                return delegated != null ? delegated : match(value, args, ops);
            });
            case "matchAll" -> new JsNativeFunction("matchAll", (_, args) -> {
                final var delegated = delegateToSymbol(value, args, JsSymbol.MATCH_ALL, ops, List.of());
                return delegated != null ? delegated : matchAll(value, args, ops);
            });
            case "search" -> new JsNativeFunction("search", (_, args) -> {
                final var delegated = delegateToSymbol(value, args, JsSymbol.SEARCH, ops, List.of());
                return delegated != null ? delegated : new JsNumber(search(value, args, ops));
            });
            case "toUpperCase" ->
                new JsNativeFunction("toUpperCase", (_, _) -> new JsString(value.toUpperCase(Locale.ROOT)));
            case "toLowerCase" ->
                new JsNativeFunction("toLowerCase", (_, _) -> new JsString(value.toLowerCase(Locale.ROOT)));
            case "trim" -> new JsNativeFunction("trim", (_, _) -> new JsString(value.strip()));
            case "includes" -> new JsNativeFunction("includes", (_, args) -> {
                requireNotRegExp(args, "includes", ops);
                return JsBoolean.of(value.contains(str(args, 0, ops)));
            });
            case "startsWith" -> new JsNativeFunction("startsWith", (_, args) -> {
                requireNotRegExp(args, "startsWith", ops);
                return JsBoolean.of(value.startsWith(str(args, 0, ops)));
            });
            case "endsWith" -> new JsNativeFunction("endsWith", (_, args) -> {
                requireNotRegExp(args, "endsWith", ops);
                return JsBoolean.of(value.endsWith(str(args, 0, ops)));
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
            case "trimStart" -> new JsNativeFunction("trimStart", (_, _) -> new JsString(value.stripLeading()));
            case "trimEnd" -> new JsNativeFunction("trimEnd", (_, _) -> new JsString(value.stripTrailing()));
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
            case "trimLeft" -> new JsNativeFunction("trimLeft", (_, _) -> new JsString(value.stripLeading()));
            case "trimRight" -> new JsNativeFunction("trimRight", (_, _) -> new JsString(value.stripTrailing()));
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
        final var pad = args.size() < 2 ? " " : str(args, 1, ops);
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

    private static JsValue split(String value, List<JsValue> args, InterpreterOps ops) {
        return limited(splitAll(value, args, ops), intArg(args, 1, -1, ops));
    }

    private static JsArray splitAll(String value, List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            result.push(new JsString(value));
            return result;
        }
        if (args.getFirst() instanceof JsRegExp regexp) {
            return splitByRegex(value, regexp);
        }
        final var separator = str(args, 0, ops);
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

    private static JsValue limited(JsArray array, int limit) {
        if (limit < 0 || limit >= array.length()) {
            return array;
        }
        return new JsArray(List.copyOf(array.getElements().subList(0, limit)));
    }

    private static JsValue replace(String value, List<JsValue> args, Invoker invoker, boolean all, InterpreterOps ops) {
        if (!args.isEmpty() && args.getFirst() instanceof JsRegExp regexp) {
            return new JsString(replaceRegex(value, regexp, args, invoker, all, ops));
        }
        final var search = str(args, 0, ops);
        if (all) {
            return new JsString(replaceAllLiteral(value, search, args, invoker, ops));
        }
        final var index = value.indexOf(search);
        if (index < 0) {
            return new JsString(value);
        }
        final var piece = literalPiece(value, search, index, args, invoker, ops);
        return new JsString(value.substring(0, index) + piece + value.substring(index + search.length()));
    }

    private static String literalPiece(String value, String search, int index, List<JsValue> args, Invoker invoker,
            InterpreterOps ops) {
        if (args.size() > 1 && isCallable(args.get(1))) {
            return JsCoercion.toStr(invoker.call(args.get(1), JsUndefined.getInstance(),
                    List.of(new JsString(search), new JsNumber(index), new JsString(value))));
        }
        return expandLiteral(str(args, 1, ops), search, value, index);
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
            InterpreterOps ops) {
        if (search.isEmpty()) {
            return value;
        }
        final var sb = new StringBuilder();
        var from = 0;
        var index = value.indexOf(search);
        while (index >= 0) {
            sb.append(value, from, index);
            sb.append(literalPiece(value, search, index, args, invoker, ops));
            from = index + search.length();
            index = value.indexOf(search, from);
        }
        sb.append(value.substring(from));
        return sb.toString();
    }

    private static String replaceRegex(String value, JsRegExp regexp, List<JsValue> args, Invoker invoker, boolean all,
            InterpreterOps ops) {
        final var matcher = regexp.getPattern().matcher(value);
        final var global = all || regexp.isGlobal();
        final var sb = new StringBuilder();
        var last = 0;
        while (matcher.find()) {
            sb.append(value, last, matcher.start());
            sb.append(replacementPiece(matcher, value, args, invoker, regexp, ops));
            last = matcher.end();
            if (!global) {
                break;
            }
            if (matcher.end() == matcher.start()) {
                if (matcher.end() >= value.length()) {
                    break;
                }
                sb.append(value.charAt(matcher.end()));
                last = matcher.end() + 1;
                matcher.region(last, value.length());
            }
        }
        sb.append(value.substring(last));
        return sb.toString();
    }

    private static String replacementPiece(Matcher matcher, String input, List<JsValue> args, Invoker invoker,
            JsRegExp regexp, InterpreterOps ops) {
        if (args.size() > 1 && isCallable(args.get(1))) {
            final var callArgs = new ArrayList<JsValue>();
            callArgs.add(new JsString(matcher.group()));
            for (var i = 1; i <= matcher.groupCount(); i++) {
                callArgs.add(matcher.group(i) == null ? JsUndefined.getInstance() : new JsString(matcher.group(i)));
            }
            callArgs.add(new JsNumber(matcher.start()));
            callArgs.add(new JsString(input));
            return JsCoercion.toStr(invoker.call(args.get(1), JsUndefined.getInstance(), callArgs));
        }
        return expand(str(args, 1, ops), matcher, input, regexp);
    }

    private static String expand(String template, Matcher matcher, String input, JsRegExp regexp) {
        final var sb = new StringBuilder();
        for (var i = 0; i < template.length(); i++) {
            final var ch = template.charAt(i);
            if (ch != '$' || i + 1 >= template.length()) {
                sb.append(ch);
                continue;
            }
            final var next = template.charAt(i + 1);
            switch (next) {
                case '$' -> sb.append('$');
                case '&' -> sb.append(matcher.group());
                case '`' -> sb.append(input, 0, matcher.start());
                case '\'' -> sb.append(input.substring(matcher.end()));
                case '<' -> i = appendNamedGroup(sb, template, i + 2, matcher, regexp) - 1;
                default -> {
                    if (Character.isDigit(next)) {
                        i = appendNumberedGroup(sb, template, i + 1, matcher) - 1;
                    } else {
                        sb.append('$');
                        continue;
                    }
                }
            }
            i++;
        }
        return sb.toString();
    }

    private static int appendNamedGroup(StringBuilder sb, String template, int start, Matcher matcher,
            JsRegExp regexp) {
        final var close = template.indexOf('>', start);
        if (close < 0) {
            sb.append("$<");
            return start;
        }
        final var alias = RegexBuiltins.participatingGroup(regexp, template.substring(start, close), matcher);
        final var group = alias == null ? null : matcher.group(alias);
        if (group != null) {
            sb.append(group);
        }
        return close;
    }

    private static int appendNumberedGroup(StringBuilder sb, String template, int start, Matcher matcher) {
        var end = start + 1;
        if (end < template.length() && Character.isDigit(template.charAt(end))
                && Integer.parseInt(template.substring(start, end + 1)) <= matcher.groupCount()) {
            end++;
        }
        final var group = Integer.parseInt(template.substring(start, end));
        if (group >= 1 && group <= matcher.groupCount() && matcher.group(group) != null) {
            sb.append(matcher.group(group));
        }
        return end - 1;
    }

    private static JsValue match(String value, List<JsValue> args, InterpreterOps ops) {
        final var regexp = toRegExp(args, ops);
        if (!regexp.isGlobal()) {
            final var matcher = regexp.getPattern().matcher(value);
            return matcher.find() ? matchResult(matcher, value, regexp) : JsNull.getInstance();
        }
        final var matcher = regexp.getPattern().matcher(value);
        final var result = new JsArray();
        while (matcher.find()) {
            result.push(new JsString(matcher.group()));
            if (matcher.end() == matcher.start()) {
                if (matcher.end() >= value.length()) {
                    break;
                }
                matcher.region(matcher.end() + 1, value.length());
            }
        }
        return result.length() == 0 ? JsNull.getInstance() : result;
    }

    private static JsValue matchAll(String value, List<JsValue> args, InterpreterOps ops) {
        if (!args.isEmpty() && args.getFirst() instanceof JsRegExp given && !given.isGlobal()) {
            throw new TypeErrorException("String.prototype.matchAll called with a non-global RegExp argument");
        }
        final var regexp = toRegExp(args, ops);
        final var matcher = regexp.getPattern().matcher(value);
        final var result = new JsArray();
        while (matcher.find()) {
            result.push(matchResult(matcher, value, regexp));
            if (matcher.end() == matcher.start()) {
                if (matcher.end() >= value.length()) {
                    break;
                }
                matcher.region(matcher.end() + 1, value.length());
            }
        }
        return result;
    }

    private static JsValue matchResult(Matcher matcher, String input, JsRegExp regexp) {
        final var result = RegexBuiltins.buildMatchResult(matcher, input, regexp);
        if (regexp.hasIndices()) {
            RegexBuiltins.addIndices(result, matcher, regexp);
        }
        return result;
    }

    private static int search(String value, List<JsValue> args, InterpreterOps ops) {
        final var regexp = toRegExp(args, ops);
        final var matcher = regexp.getPattern().matcher(value);
        return matcher.find() ? matcher.start() : -1;
    }

    private static JsArray splitByRegex(String value, JsRegExp regexp) {
        final var parts = regexp.getPattern().split(value, -1);
        final var result = new JsArray();
        for (final var part : parts) {
            result.push(new JsString(part));
        }
        return result;
    }

    private static JsRegExp toRegExp(List<JsValue> args, InterpreterOps ops) {
        if (!args.isEmpty() && args.getFirst() instanceof JsRegExp regexp) {
            return regexp;
        }
        return RegexTranslator.compile(args.isEmpty() ? "" : str(args, 0, ops), "");
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    private static String padStart(String value, List<JsValue> args, InterpreterOps ops) {
        final var target = intArg(args, 0, 0, ops);
        if (value.length() >= target) {
            return value;
        }
        final var pad = args.size() < 2 ? " " : str(args, 1, ops);
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
        final var count = intArg(args, 0, 0, ops);
        if (count < 0) {
            throw new org.techhouse.simplejs.exceptions.RangeErrorException("Invalid count value: " + count);
        }
        return value.repeat(count);
    }

    private static String charAt(String value, List<JsValue> args, InterpreterOps ops) {
        final var index = intArg(args, 0, 0, ops);
        if (index < 0 || index >= value.length()) {
            return "";
        }
        return String.valueOf(value.charAt(index));
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
