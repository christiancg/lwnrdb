package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
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
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class StringBuiltins {
    private StringBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var string = new JsNativeFunction("String",
                (_, args) -> new JsString(args.isEmpty() ? "" : JsCoercion.toStr(args.getFirst(), ops)));
        string.setProperty("raw", new JsNativeFunction("raw", (_, args) -> new JsString(raw(args))));
        string.setProperty("fromCharCode",
                new JsNativeFunction("fromCharCode", (_, args) -> new JsString(fromCharCode(args))));
        string.setProperty("fromCodePoint",
                new JsNativeFunction("fromCodePoint", (_, args) -> new JsString(fromCodePoint(args))));
        return string;
    }

    private static String fromCharCode(List<JsValue> args) {
        final var sb = new StringBuilder();
        for (final var arg : args) {
            sb.append((char) (int) JsCoercion.toNumber(arg));
        }
        return sb.toString();
    }

    private static String fromCodePoint(List<JsValue> args) {
        final var sb = new StringBuilder();
        for (final var arg : args) {
            sb.appendCodePoint((int) JsCoercion.toNumber(arg));
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

    public static JsNativeFunction getMethod(JsString receiver, String name, Invoker invoker) {
        final var value = receiver.getValue();
        return switch (name) {
            case "slice" -> new JsNativeFunction("slice", (_, args) -> new JsString(slice(value, args)));
            case "substring" -> new JsNativeFunction("substring", (_, args) -> new JsString(substring(value, args)));
            case "split" -> new JsNativeFunction("split", (_, args) -> split(value, args));
            case "replace" -> new JsNativeFunction("replace", (_, args) -> replace(value, args, invoker, false));
            case "replaceAll" -> new JsNativeFunction("replaceAll", (_, args) -> replace(value, args, invoker, true));
            case "match" -> new JsNativeFunction("match", (_, args) -> match(value, args));
            case "matchAll" -> new JsNativeFunction("matchAll", (_, args) -> matchAll(value, args));
            case "search" -> new JsNativeFunction("search", (_, args) -> new JsNumber(search(value, args)));
            case "toUpperCase" ->
                new JsNativeFunction("toUpperCase", (_, _) -> new JsString(value.toUpperCase(Locale.ROOT)));
            case "toLowerCase" ->
                new JsNativeFunction("toLowerCase", (_, _) -> new JsString(value.toLowerCase(Locale.ROOT)));
            case "trim" -> new JsNativeFunction("trim", (_, _) -> new JsString(value.strip()));
            case "includes" ->
                new JsNativeFunction("includes", (_, args) -> JsBoolean.of(value.contains(str(args, 0))));
            case "startsWith" ->
                new JsNativeFunction("startsWith", (_, args) -> JsBoolean.of(value.startsWith(str(args, 0))));
            case "endsWith" ->
                new JsNativeFunction("endsWith", (_, args) -> JsBoolean.of(value.endsWith(str(args, 0))));
            case "padStart" -> new JsNativeFunction("padStart", (_, args) -> new JsString(padStart(value, args)));
            case "repeat" -> new JsNativeFunction("repeat", (_, args) -> new JsString(repeat(value, args)));
            case "charAt" -> new JsNativeFunction("charAt", (_, args) -> new JsString(charAt(value, args)));
            case "indexOf" -> new JsNativeFunction("indexOf", (_, args) -> new JsNumber(value.indexOf(str(args, 0))));
            case "charCodeAt" -> new JsNativeFunction("charCodeAt", (_, args) -> charCodeAt(value, args));
            case "codePointAt" -> new JsNativeFunction("codePointAt", (_, args) -> codePointAt(value, args));
            case "at" -> new JsNativeFunction("at", (_, args) -> at(value, args));
            case "padEnd" -> new JsNativeFunction("padEnd", (_, args) -> new JsString(padEnd(value, args)));
            case "trimStart" -> new JsNativeFunction("trimStart", (_, _) -> new JsString(value.stripLeading()));
            case "trimEnd" -> new JsNativeFunction("trimEnd", (_, _) -> new JsString(value.stripTrailing()));
            case "normalize" -> new JsNativeFunction("normalize", (_, args) -> new JsString(normalize(value, args)));
            case "localeCompare" ->
                new JsNativeFunction("localeCompare", (_, args) -> new JsNumber(localeCompare(value, args)));
            case "concat" -> new JsNativeFunction("concat", (_, args) -> new JsString(concat(value, args)));
            case "isWellFormed" -> new JsNativeFunction("isWellFormed", (_, _) -> JsBoolean.of(isWellFormed(value)));
            case "toWellFormed" -> new JsNativeFunction("toWellFormed", (_, _) -> new JsString(toWellFormed(value)));
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

    private static JsValue charCodeAt(String value, List<JsValue> args) {
        final var index = intArg(args, 0, 0);
        if (index < 0 || index >= value.length()) {
            return new JsNumber(Double.NaN);
        }
        return new JsNumber(value.charAt(index));
    }

    private static JsValue codePointAt(String value, List<JsValue> args) {
        final var index = intArg(args, 0, 0);
        if (index < 0 || index >= value.length()) {
            return JsUndefined.getInstance();
        }
        return new JsNumber(value.codePointAt(index));
    }

    private static JsValue at(String value, List<JsValue> args) {
        var index = intArg(args, 0, 0);
        if (index < 0) {
            index += value.length();
        }
        if (index < 0 || index >= value.length()) {
            return JsUndefined.getInstance();
        }
        return new JsString(String.valueOf(value.charAt(index)));
    }

    private static String padEnd(String value, List<JsValue> args) {
        final var target = intArg(args, 0, 0);
        if (value.length() >= target) {
            return value;
        }
        final var pad = args.size() < 2 ? " " : str(args, 1);
        if (pad.isEmpty()) {
            return value;
        }
        final var sb = new StringBuilder(value);
        while (sb.length() < target) {
            sb.append(pad);
        }
        return sb.substring(0, target);
    }

    private static String normalize(String value, List<JsValue> args) {
        final var form = args.isEmpty() || args.getFirst() instanceof JsUndefined ? "NFC" : str(args, 0);
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.valueOf(form));
    }

    private static int localeCompare(String value, List<JsValue> args) {
        return Integer.signum(value.compareTo(str(args, 0)));
    }

    private static String concat(String value, List<JsValue> args) {
        final var sb = new StringBuilder(value);
        for (final var arg : args) {
            sb.append(JsCoercion.toStr(arg));
        }
        return sb.toString();
    }

    private static String slice(String value, List<JsValue> args) {
        final var length = value.length();
        var start = clampIndex(intArg(args, 0, 0), length);
        var end = args.size() < 2 || args.get(1) instanceof JsUndefined
                ? length
                : clampIndex(intArg(args, 1, length), length);
        if (start >= end) {
            return "";
        }
        return value.substring(start, end);
    }

    private static String substring(String value, List<JsValue> args) {
        final var length = value.length();
        var start = Math.clamp(intArg(args, 0, 0), 0, length);
        var end = args.size() < 2 || args.get(1) instanceof JsUndefined
                ? length
                : Math.clamp(intArg(args, 1, length), 0, length);
        if (start > end) {
            final var tmp = start;
            start = end;
            end = tmp;
        }
        return value.substring(start, end);
    }

    private static JsValue split(String value, List<JsValue> args) {
        final var result = new JsArray();
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            result.push(new JsString(value));
            return result;
        }
        if (args.getFirst() instanceof JsRegExp regexp) {
            return splitByRegex(value, regexp);
        }
        final var separator = str(args, 0);
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

    private static JsValue replace(String value, List<JsValue> args, Invoker invoker, boolean all) {
        if (!args.isEmpty() && args.getFirst() instanceof JsRegExp regexp) {
            return new JsString(replaceRegex(value, regexp, args, invoker, all));
        }
        final var search = str(args, 0);
        final var replacement = args.size() > 1 && isCallable(args.get(1)) ? null : str(args, 1);
        if (all) {
            return new JsString(replaceAllLiteral(value, search, args, invoker));
        }
        final var index = value.indexOf(search);
        if (index < 0) {
            return new JsString(value);
        }
        final var piece = replacement != null
                ? replacement
                : JsCoercion.toStr(invoker.call(args.get(1), JsUndefined.getInstance(),
                        List.of(new JsString(search), new JsNumber(index), new JsString(value))));
        return new JsString(value.substring(0, index) + piece + value.substring(index + search.length()));
    }

    private static String replaceAllLiteral(String value, String search, List<JsValue> args, Invoker invoker) {
        if (search.isEmpty()) {
            return value;
        }
        final var sb = new StringBuilder();
        var from = 0;
        var index = value.indexOf(search);
        while (index >= 0) {
            sb.append(value, from, index);
            if (args.size() > 1 && isCallable(args.get(1))) {
                sb.append(JsCoercion.toStr(invoker.call(args.get(1), JsUndefined.getInstance(),
                        List.of(new JsString(search), new JsNumber(index), new JsString(value)))));
            } else {
                sb.append(str(args, 1));
            }
            from = index + search.length();
            index = value.indexOf(search, from);
        }
        sb.append(value.substring(from));
        return sb.toString();
    }

    private static String replaceRegex(String value, JsRegExp regexp, List<JsValue> args, Invoker invoker,
            boolean all) {
        final var matcher = regexp.getPattern().matcher(value);
        final var global = all || regexp.isGlobal();
        final var sb = new StringBuilder();
        var last = 0;
        while (matcher.find()) {
            sb.append(value, last, matcher.start());
            sb.append(replacementPiece(matcher, value, args, invoker));
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

    private static String replacementPiece(Matcher matcher, String input, List<JsValue> args, Invoker invoker) {
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
        return expand(str(args, 1), matcher, input);
    }

    private static String expand(String template, Matcher matcher, String input) {
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
                case '<' -> i = appendNamedGroup(sb, template, i + 2, matcher) - 1;
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

    private static int appendNamedGroup(StringBuilder sb, String template, int start, Matcher matcher) {
        final var close = template.indexOf('>', start);
        if (close < 0) {
            sb.append("$<");
            return start;
        }
        final var name = template.substring(start, close);
        final var group = matcher.group(name);
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

    private static JsValue match(String value, List<JsValue> args) {
        final var regexp = toRegExp(args);
        if (!regexp.isGlobal()) {
            final var matcher = regexp.getPattern().matcher(value);
            return matcher.find()
                    ? RegexBuiltins.buildMatchResult(matcher, value, regexp.getSource())
                    : JsNull.getInstance();
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

    private static JsValue matchAll(String value, List<JsValue> args) {
        final var regexp = toRegExp(args);
        final var matcher = regexp.getPattern().matcher(value);
        final var result = new JsArray();
        while (matcher.find()) {
            result.push(RegexBuiltins.buildMatchResult(matcher, value, regexp.getSource()));
            if (matcher.end() == matcher.start()) {
                if (matcher.end() >= value.length()) {
                    break;
                }
                matcher.region(matcher.end() + 1, value.length());
            }
        }
        return result;
    }

    private static int search(String value, List<JsValue> args) {
        final var regexp = toRegExp(args);
        final var matcher = regexp.getPattern().matcher(value);
        return matcher.find() ? matcher.start() : -1;
    }

    private static JsValue splitByRegex(String value, JsRegExp regexp) {
        final var parts = regexp.getPattern().split(value, -1);
        final var result = new JsArray();
        for (final var part : parts) {
            result.push(new JsString(part));
        }
        return result;
    }

    private static JsRegExp toRegExp(List<JsValue> args) {
        if (!args.isEmpty() && args.getFirst() instanceof JsRegExp regexp) {
            return regexp;
        }
        return RegexTranslator.compile(args.isEmpty() ? "" : str(args, 0), "");
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    private static String padStart(String value, List<JsValue> args) {
        final var target = intArg(args, 0, 0);
        if (value.length() >= target) {
            return value;
        }
        final var pad = args.size() < 2 ? " " : str(args, 1);
        if (pad.isEmpty()) {
            return value;
        }
        final var sb = new StringBuilder();
        while (sb.length() < target - value.length()) {
            sb.append(pad);
        }
        return sb.substring(0, target - value.length()) + value;
    }

    private static String repeat(String value, List<JsValue> args) {
        final var count = intArg(args, 0, 0);
        if (count < 0) {
            throw new org.techhouse.simplejs.exceptions.RangeErrorException("Invalid count value: " + count);
        }
        return value.repeat(count);
    }

    private static String charAt(String value, List<JsValue> args) {
        final var index = intArg(args, 0, 0);
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

    private static int intArg(List<JsValue> args, int position, int fallback) {
        if (position >= args.size() || args.get(position) instanceof JsUndefined) {
            return fallback;
        }
        final var value = JsCoercion.toNumber(args.get(position));
        return Double.isNaN(value) ? 0 : (int) value;
    }

    private static String str(List<JsValue> args, int position) {
        return position < args.size() ? JsCoercion.toStr(args.get(position)) : "undefined";
    }
}
