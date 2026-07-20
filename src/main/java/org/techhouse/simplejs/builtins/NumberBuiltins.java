package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsValue;

public final class NumberBuiltins {
    private NumberBuiltins() {
    }

    public static JsNativeFunction create() {
        final var number = new JsNativeFunction("Number",
                (_, args) -> new JsNumber(args.isEmpty() ? 0 : JsCoercion.toNumber(args.getFirst())));
        number.setProperty("isNaN", new JsNativeFunction("isNaN", (_, args) -> JsBoolean.of(isNaN(args))));
        number.setProperty("isInteger", new JsNativeFunction("isInteger", (_, args) -> JsBoolean.of(isInteger(args))));
        number.setProperty("isFinite",
                new JsNativeFunction("isFinite", (_, args) -> JsBoolean.of(isFiniteNumber(args))));
        number.setProperty("parseFloat", parseFloatFunction());
        number.setProperty("parseInt", parseIntFunction());
        return number;
    }

    public static JsNativeFunction parseFloatFunction() {
        return new JsNativeFunction("parseFloat", (_, args) -> new JsNumber(parseFloat(text(args))));
    }

    public static JsNativeFunction parseIntFunction() {
        return new JsNativeFunction("parseInt", (_, args) -> new JsNumber(parseInt(text(args), radix(args))));
    }

    public static JsNativeFunction isNaNFunction() {
        return new JsNativeFunction("isNaN", (_, args) -> JsBoolean
                .of(Double.isNaN(args.isEmpty() ? Double.NaN : JsCoercion.toNumber(args.getFirst()))));
    }

    public static JsNativeFunction isFiniteFunction() {
        return new JsNativeFunction("isFinite",
                (_, args) -> JsBoolean.of(!args.isEmpty() && Double.isFinite(JsCoercion.toNumber(args.getFirst()))));
    }

    private static boolean isNaN(List<JsValue> args) {
        return !args.isEmpty() && args.getFirst() instanceof JsNumber n && Double.isNaN(n.getValue());
    }

    private static boolean isInteger(List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsNumber n)) {
            return false;
        }
        final var value = n.getValue();
        return Double.isFinite(value) && value == Math.floor(value);
    }

    private static boolean isFiniteNumber(List<JsValue> args) {
        return !args.isEmpty() && args.getFirst() instanceof JsNumber n && Double.isFinite(n.getValue());
    }

    private static String text(List<JsValue> args) {
        return args.isEmpty() ? "" : JsCoercion.toStr(args.getFirst());
    }

    private static int radix(List<JsValue> args) {
        return args.size() < 2 ? 0 : (int) JsCoercion.toNumber(args.get(1));
    }

    private static double parseFloat(String raw) {
        final var s = raw.strip();
        var end = 0;
        var seenDot = false;
        var seenExp = false;
        if (end < s.length() && (s.charAt(end) == '+' || s.charAt(end) == '-')) {
            end++;
        }
        final var start = end;
        while (end < s.length()) {
            final var c = s.charAt(end);
            if (Character.isDigit(c)) {
                end++;
            } else if (c == '.' && !seenDot && !seenExp) {
                seenDot = true;
                end++;
            } else if ((c == 'e' || c == 'E') && !seenExp && end > start) {
                seenExp = true;
                end++;
                if (end < s.length() && (s.charAt(end) == '+' || s.charAt(end) == '-')) {
                    end++;
                }
            } else {
                break;
            }
        }
        try {
            return Double.parseDouble(s.substring(0, end));
        } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {
            return Double.NaN;
        }
    }

    private static double parseInt(String raw, int requestedRadix) {
        var s = raw.strip();
        var index = 0;
        var sign = 1;
        if (index < s.length() && (s.charAt(index) == '+' || s.charAt(index) == '-')) {
            sign = s.charAt(index) == '-' ? -1 : 1;
            index++;
        }
        var radix = requestedRadix;
        if ((radix == 0 || radix == 16) && index + 1 < s.length() && s.charAt(index) == '0'
                && (s.charAt(index + 1) == 'x' || s.charAt(index + 1) == 'X')) {
            index += 2;
            radix = 16;
        } else if (radix == 0) {
            radix = 10;
        }
        if (radix < 2 || radix > 36) {
            return Double.NaN;
        }
        final var digitsStart = index;
        while (index < s.length() && Character.digit(s.charAt(index), radix) >= 0) {
            index++;
        }
        if (index == digitsStart) {
            return Double.NaN;
        }
        try {
            return sign * Long.parseLong(s.substring(digitsStart, index), radix);
        } catch (NumberFormatException ignored) {
            return sign * new java.math.BigInteger(s.substring(digitsStart, index), radix).doubleValue();
        }
    }
}
