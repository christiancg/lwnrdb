package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class NumberBuiltins {
    private NumberBuiltins() {
    }

    public static final List<String> NAMES = List.of("toFixed", "toPrecision", "toExponential", "toString",
            "toLocaleString", "valueOf");

    private static final double MAX_SAFE_INTEGER = 9007199254740991d;
    private static final int MAX_FRACTION_DIGITS = 100;
    private static final double TO_FIXED_LIMIT = 1e21;

    public static JsNativeFunction create() {
        final var number = new JsNativeFunction("Number",
                (_, args) -> new JsNumber(args.isEmpty() ? 0 : JsCoercion.toNumber(args.getFirst())));
        number.setProperty("isNaN", new JsNativeFunction("isNaN", (_, args) -> JsBoolean.of(isNaN(args))));
        number.setProperty("isInteger", new JsNativeFunction("isInteger", (_, args) -> JsBoolean.of(isInteger(args))));
        number.setProperty("isFinite",
                new JsNativeFunction("isFinite", (_, args) -> JsBoolean.of(isFiniteNumber(args))));
        number.setProperty("isSafeInteger",
                new JsNativeFunction("isSafeInteger", (_, args) -> JsBoolean.of(isSafeInteger(args))));
        number.setProperty("parseFloat", parseFloatFunction());
        number.setProperty("parseInt", parseIntFunction());
        number.setProperty("MAX_SAFE_INTEGER", new JsNumber(MAX_SAFE_INTEGER));
        number.setProperty("MIN_SAFE_INTEGER", new JsNumber(-MAX_SAFE_INTEGER));
        number.setProperty("MAX_VALUE", new JsNumber(Double.MAX_VALUE));
        number.setProperty("MIN_VALUE", new JsNumber(Double.MIN_VALUE));
        number.setProperty("EPSILON", new JsNumber(Math.ulp(1.0)));
        number.setProperty("POSITIVE_INFINITY", new JsNumber(Double.POSITIVE_INFINITY));
        number.setProperty("NEGATIVE_INFINITY", new JsNumber(Double.NEGATIVE_INFINITY));
        number.setProperty("NaN", new JsNumber(Double.NaN));
        return number;
    }

    public static JsNativeFunction bigIntFunction() {
        return new JsNativeFunction("BigInt",
                (_, args) -> toBigInt(args.isEmpty() ? JsUndefined.getInstance() : args.getFirst()));
    }

    private static JsBigInt toBigInt(JsValue value) {
        return switch (value) {
            case JsBigInt bigInt -> bigInt;
            case JsBoolean bool -> new JsBigInt(bool.getValue() ? BigInteger.ONE : BigInteger.ZERO);
            case JsNumber number -> fromNumber(number.getValue());
            case JsString string -> fromString(string.getValue());
            default -> throw new TypeErrorException("Cannot convert " + JsCoercion.toStr(value) + " to a BigInt");
        };
    }

    private static JsBigInt fromNumber(double value) {
        if (!Double.isFinite(value) || value != Math.rint(value)) {
            throw new RangeErrorException("The number " + JsCoercion.toStr(new JsNumber(value))
                    + " cannot be converted to a BigInt because it is not an integer");
        }
        return new JsBigInt(java.math.BigDecimal.valueOf(value).toBigIntegerExact());
    }

    private static JsBigInt fromString(String value) {
        final var trimmed = value.trim();
        try {
            return new JsBigInt(trimmed.isEmpty() ? BigInteger.ZERO : new BigInteger(trimmed));
        } catch (NumberFormatException ex) {
            throw new SyntaxErrorException("Cannot convert " + value + " to a BigInt");
        }
    }

    public static JsNativeFunction getMethod(JsNumber receiver, String name) {
        final var value = receiver.getValue();
        return switch (name) {
            case "toFixed" -> new JsNativeFunction("toFixed", (_, args) -> new JsString(toFixed(value, args)));
            case "toPrecision" ->
                new JsNativeFunction("toPrecision", (_, args) -> new JsString(toPrecision(value, args)));
            case "toExponential" ->
                new JsNativeFunction("toExponential", (_, args) -> new JsString(toExponential(value, args)));
            case "toString" -> new JsNativeFunction("toString", (_, args) -> new JsString(toStringRadix(value, args)));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, _) -> new JsString(toLocaleString(value)));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> new JsNumber(value));
            default -> null;
        };
    }

    private static String toLocaleString(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "∞" : "-∞";
        }
        return java.text.NumberFormat.getInstance(java.util.Locale.getDefault()).format(value);
    }

    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static String toFixed(double value, List<JsValue> args) {
        final var digits = intArg(args, 0);
        if (digits < 0 || digits > MAX_FRACTION_DIGITS) {
            throw new RangeErrorException("toFixed() digits argument must be between 0 and 100");
        }
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (!Double.isFinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (Math.abs(value) >= TO_FIXED_LIMIT) {
            return NumberFormatter.toJsString(value);
        }
        // The exact-binary BigDecimal ctor (not valueOf) is what makes (1.005).toFixed(2) round to "1.00"
        return new java.math.BigDecimal(value).setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String toPrecision(double value, List<JsValue> args) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return JsCoercion.toStr(new JsNumber(value));
        }
        final var precision = intArg(args, 0);
        if (precision < 1 || precision > 100) {
            throw new org.techhouse.simplejs.exceptions.RangeErrorException(
                    "toPrecision() argument must be between 1 and 100");
        }
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (!Double.isFinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        return java.math.BigDecimal.valueOf(value).round(new java.math.MathContext(precision)).toString();
    }

    private static String toExponential(double value, List<JsValue> args) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (!Double.isFinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        final var fixed = !args.isEmpty() && !(args.getFirst() instanceof JsUndefined);
        final var pattern = "%." + (fixed ? intArg(args, 0) : 20) + "e";
        return normalizeExponent(String.format(Locale.ROOT, pattern, value), fixed);
    }

    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static String toStringRadix(double value, List<JsValue> args) {
        final var radix = intArg(args, 10);
        if (radix == 10) {
            return JsCoercion.toStr(new JsNumber(value));
        }
        if (radix < 2 || radix > 36) {
            throw new org.techhouse.simplejs.exceptions.RangeErrorException(
                    "toString() radix must be between 2 and 36");
        }
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (!Double.isFinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (value == Math.floor(value)) {
            return Math.abs(value) <= MAX_SAFE_INTEGER
                    ? Long.toString((long) value, radix)
                    : new java.math.BigDecimal(value).toBigInteger().toString(radix);
        }
        return doubleToRadix(value, radix);
    }

    private static String doubleToRadix(double value, int radix) {
        final var negative = value < 0;
        var v = Math.abs(value);
        final var intPart = (long) v;
        var frac = v - intPart;
        final var sb = new StringBuilder(Long.toString(intPart, radix));
        if (frac > 0) {
            sb.append('.');
            for (var i = 0; i < 20 && frac > 0; i++) {
                frac *= radix;
                final var digit = (int) frac;
                sb.append(Character.forDigit(digit, radix));
                frac -= digit;
            }
        }
        return negative ? "-" + sb : sb.toString();
    }

    private static String normalizeExponent(String formatted, boolean fixed) {
        final var idx = formatted.indexOf('e');
        if (idx < 0) {
            return formatted;
        }
        var mantissa = formatted.substring(0, idx);
        if (!fixed && mantissa.contains(".")) {
            mantissa = mantissa.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        var exp = formatted.substring(idx + 1);
        final var sign = exp.startsWith("-") ? "-" : "+";
        exp = exp.replaceAll("^[+-]", "").replaceAll("^0+(?=\\d)", "");
        return mantissa + "e" + sign + exp;
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

    private static boolean isSafeInteger(List<JsValue> args) {
        return isInteger(args) && Math.abs(((JsNumber) args.getFirst()).getValue()) <= MAX_SAFE_INTEGER;
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

    private static int intArg(List<JsValue> args, int fallback) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return fallback;
        }
        final var value = JsCoercion.toNumber(args.getFirst());
        return Double.isNaN(value) ? 0 : (int) value;
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
