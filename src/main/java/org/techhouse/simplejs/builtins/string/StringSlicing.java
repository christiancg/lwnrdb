package org.techhouse.simplejs.builtins.string;

import static org.techhouse.simplejs.builtins.BuiltinArgs.str;
import static org.techhouse.simplejs.builtins.StringBuiltins.intArg;
import static org.techhouse.simplejs.builtins.StringBuiltins.rawLengthArg;
import static org.techhouse.simplejs.builtins.StringBuiltins.requireStringLength;

import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class StringSlicing {
    public static JsValue charCodeAt(String value, List<JsValue> args, InterpreterOps ops) {
        final var index = intArg(args, 0, 0, ops);
        if (index < 0 || index >= value.length()) {
            return new JsNumber(Double.NaN);
        }
        return new JsNumber(value.charAt(index));
    }

    public static JsValue codePointAt(String value, List<JsValue> args, InterpreterOps ops) {
        final var index = intArg(args, 0, 0, ops);
        if (index < 0 || index >= value.length()) {
            return JsUndefined.getInstance();
        }
        return new JsNumber(value.codePointAt(index));
    }

    public static JsValue at(String value, List<JsValue> args, InterpreterOps ops) {
        var index = intArg(args, 0, 0, ops);
        if (index < 0) {
            index += value.length();
        }
        if (index < 0 || index >= value.length()) {
            return JsUndefined.getInstance();
        }
        return new JsString(String.valueOf(value.charAt(index)));
    }

    public static String padEnd(String value, List<JsValue> args, InterpreterOps ops) {
        final var requested = rawLengthArg(args, ops);
        if (value.length() >= requested) {
            return value;
        }
        final var pad = args.size() < 2 || args.get(1) instanceof JsUndefined ? " " : str(args, 1, ops);
        if (pad.isEmpty()) {
            return value;
        }
        final var target = (int) requireStringLength(requested, ops);
        final var sb = new StringBuilder(value);
        while (sb.length() < target) {
            sb.append(pad);
        }
        return sb.substring(0, target);
    }

    public static String concat(String value, List<JsValue> args, InterpreterOps ops) {
        final var sb = new StringBuilder(value);
        for (final var arg : args) {
            final var text = JsCoercion.toStr(arg, ops);
            InterpreterOps.chargeChars(ops, text.length());
            sb.append(text);
        }
        return sb.toString();
    }

    public static String slice(String value, List<JsValue> args, InterpreterOps ops) {
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

    public static String substr(String value, List<JsValue> args, InterpreterOps ops) {
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

    public static String substring(String value, List<JsValue> args, InterpreterOps ops) {
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

    public static String padStart(String value, List<JsValue> args, InterpreterOps ops) {
        final var requested = rawLengthArg(args, ops);
        if (value.length() >= requested) {
            return value;
        }
        final var pad = args.size() < 2 || args.get(1) instanceof JsUndefined ? " " : str(args, 1, ops);
        if (pad.isEmpty()) {
            return value;
        }
        final var target = (int) requireStringLength(requested, ops);
        final var sb = new StringBuilder();
        while (sb.length() < target - value.length()) {
            sb.append(pad);
        }
        return sb.substring(0, target - value.length()) + value;
    }

    public static String repeat(String value, List<JsValue> args, InterpreterOps ops) {
        final var requested = args.isEmpty() || args.getFirst() instanceof JsUndefined
                ? 0
                : JsCoercion.toNumber(args.getFirst(), ops);
        if (requested < 0 || Double.isInfinite(requested)) {
            throw new org.techhouse.simplejs.exceptions.RangeErrorException("Invalid count value: " + requested);
        }
        final var count = Double.isNaN(requested) ? 0 : requested;
        requireStringLength(count * value.length(), ops);
        return value.isEmpty() ? value : value.repeat((int) count);
    }

    public static String charAt(String value, List<JsValue> args, InterpreterOps ops) {
        final var index = intArg(args, 0, 0, ops);
        if (index < 0 || index >= value.length()) {
            return "";
        }
        return String.valueOf(value.charAt(index));
    }

    public static boolean isJsWhitespace(char c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0x00A0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
                || c == 0x3000 || c == 0xFEFF;
    }

    public static String trim(String value, boolean start, boolean end) {
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

    public static int startPosition(String value, List<JsValue> args, InterpreterOps ops) {
        final var pos = args.size() > 1 ? JsCoercion.toNumber(args.get(1), ops) : 0;
        return clampPosition(pos, value.length());
    }

    public static boolean endsWith(String value, String search, List<JsValue> args, InterpreterOps ops) {
        final var raw = args.size() > 1 && !(args.get(1) instanceof JsUndefined)
                ? JsCoercion.toNumber(args.get(1), ops)
                : value.length();
        final var end = clampPosition(raw, value.length());
        final var start = end - search.length();
        return start >= 0 && value.startsWith(search, start);
    }

    public static int clampIndex(int index, int length) {
        if (index < 0) {
            return Math.max(length + index, 0);
        }
        return Math.min(index, length);
    }

    public static int indexOf(String value, List<JsValue> args, InterpreterOps ops) {
        final var search = str(args, 0, ops);
        final var pos = args.size() > 1 ? JsCoercion.toNumber(args.get(1), ops) : 0;
        return value.indexOf(search, clampPosition(pos, value.length()));
    }

    public static int lastIndexOf(String value, List<JsValue> args, InterpreterOps ops) {
        final var search = str(args, 0, ops);
        final var raw = args.size() > 1 ? JsCoercion.toNumber(args.get(1), ops) : Double.NaN;
        final var pos = Double.isNaN(raw) ? Double.POSITIVE_INFINITY : raw;
        return value.lastIndexOf(search, clampPosition(pos, value.length()));
    }

    public static int clampPosition(double pos, int length) {
        if (Double.isNaN(pos) || pos <= 0) {
            return 0;
        }
        if (pos >= length) {
            return length;
        }
        return (int) pos;
    }

    private StringSlicing() {
    }
}
