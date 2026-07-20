package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class StringBuiltins {
    private StringBuiltins() {
    }

    public static JsNativeFunction create() {
        return new JsNativeFunction("String",
                (_, args) -> new JsString(args.isEmpty() ? "" : JsCoercion.toStr(args.getFirst())));
    }

    public static JsNativeFunction getMethod(JsString receiver, String name) {
        final var value = receiver.getValue();
        return switch (name) {
            case "slice" -> new JsNativeFunction("slice", (_, args) -> new JsString(slice(value, args)));
            case "substring" ->
                new JsNativeFunction("substring", (_, args) -> new JsString(substring(value, args)));
            case "split" -> new JsNativeFunction("split", (_, args) -> split(value, args));
            case "replace" -> new JsNativeFunction("replace", (_, args) -> new JsString(replace(value, args)));
            case "toUpperCase" ->
                new JsNativeFunction("toUpperCase", (_, _) -> new JsString(value.toUpperCase()));
            case "toLowerCase" ->
                new JsNativeFunction("toLowerCase", (_, _) -> new JsString(value.toLowerCase()));
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
            case "indexOf" ->
                new JsNativeFunction("indexOf", (_, args) -> new JsNumber(value.indexOf(str(args, 0))));
            default -> null;
        };
    }

    private static String slice(String value, List<JsValue> args) {
        final var length = value.length();
        var start = clampIndex(intArg(args, 0, 0), length);
        var end = args.size() < 2 || args.get(1) instanceof JsUndefined ? length
                : clampIndex(intArg(args, 1, length), length);
        if (start >= end) {
            return "";
        }
        return value.substring(start, end);
    }

    private static String substring(String value, List<JsValue> args) {
        final var length = value.length();
        var start = Math.clamp(intArg(args, 0, 0), 0, length);
        var end = args.size() < 2 || args.get(1) instanceof JsUndefined ? length
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

    private static String replace(String value, List<JsValue> args) {
        final var search = str(args, 0);
        final var replacement = str(args, 1);
        final var index = value.indexOf(search);
        if (index < 0) {
            return value;
        }
        return value.substring(0, index) + replacement + value.substring(index + search.length());
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
