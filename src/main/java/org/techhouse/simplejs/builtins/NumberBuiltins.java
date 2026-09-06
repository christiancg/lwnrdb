package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
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

    // Number(value) is ToNumeric, not ToNumber: a BigInt converts to its numeric value here even
    // though every other ToNumber path rejects it.
    private static double numberValueOf(JsValue value, InterpreterOps ops) {
        final var primitive = JsCoercion.toPrimitive(value, "number", ops);
        if (primitive instanceof JsBigInt big) {
            return big.getValue().doubleValue();
        }
        return JsCoercion.toNumber(primitive, ops);
    }

    public static JsNativeFunction create() {
        return create(null);
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var number = new JsNativeFunction("Number",
                (_, args) -> new JsNumber(args.isEmpty() ? 0 : numberValueOf(args.getFirst(), ops)));
        number.setProperty("isNaN", new JsNativeFunction("isNaN", (_, args) -> JsBoolean.of(isNaN(args))));
        number.setProperty("isInteger", new JsNativeFunction("isInteger", (_, args) -> JsBoolean.of(isInteger(args))));
        number.setProperty("isFinite",
                new JsNativeFunction("isFinite", (_, args) -> JsBoolean.of(isFiniteNumber(args))));
        number.setProperty("isSafeInteger",
                new JsNativeFunction("isSafeInteger", (_, args) -> JsBoolean.of(isSafeInteger(args))));
        number.setProperty("parseFloat", parseFloatFunction(ops));
        number.setProperty("parseInt", parseIntFunction(ops));
        constant(number, "MAX_SAFE_INTEGER", MAX_SAFE_INTEGER);
        constant(number, "MIN_SAFE_INTEGER", -MAX_SAFE_INTEGER);
        constant(number, "MAX_VALUE", Double.MAX_VALUE);
        constant(number, "MIN_VALUE", Double.MIN_VALUE);
        constant(number, "EPSILON", Math.ulp(1.0));
        constant(number, "POSITIVE_INFINITY", Double.POSITIVE_INFINITY);
        constant(number, "NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY);
        constant(number, "NaN", Double.NaN);
        return number;
    }

    private static void constant(JsNativeFunction owner, String key, double value) {
        owner.ownProperties().defineValue(key, new JsNumber(value));
        owner.ownProperties().setFlags(key, new PropertyFlags(false, false, false));
    }

    public static JsNativeFunction bigIntFunction(InterpreterOps ops) {
        return new JsNativeFunction("BigInt",
                (_, args) -> toBigInt(args.isEmpty() ? JsUndefined.getInstance() : args.getFirst(), ops));
    }

    private static JsBigInt toBigInt(JsValue value, InterpreterOps ops) {
        final var primitive = ops == null ? value : JsCoercion.toPrimitive(value, "number", ops);
        if (primitive instanceof JsNumber number) {
            return fromNumber(number.getValue());
        }
        return toBigIntValue(primitive);
    }

    // Spec ToBigInt, used wherever a value is *stored* as a BigInt (a BigInt typed array element,
    // DataView.setBigInt64, ...). Unlike the BigInt() function it rejects a Number outright: only
    // the explicit constructor call applies NumberToBigInt.
    public static JsBigInt toBigIntValue(JsValue value, InterpreterOps ops) {
        return toBigIntValue(ops == null ? value : JsCoercion.toPrimitive(value, "number", ops));
    }

    public static JsBigInt toBigIntValue(JsValue value) {
        return switch (value) {
            case JsBigInt bigInt -> bigInt;
            case JsBoolean bool -> new JsBigInt(bool.getValue() ? BigInteger.ONE : BigInteger.ZERO);
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
        final var parsed = JsCoercion.stringToBigInt(value);
        if (parsed == null) {
            throw new SyntaxErrorException("Cannot convert " + value + " to a BigInt");
        }
        return new JsBigInt(parsed);
    }

    public static JsNativeFunction getMethod(JsNumber receiver, String name) {
        return getMethod(receiver, name, null);
    }

    public static JsNativeFunction getMethod(JsNumber receiver, String name, InterpreterOps ops) {
        final var value = receiver.getValue();
        return switch (name) {
            case "toFixed" -> new JsNativeFunction("toFixed", (_, args) -> new JsString(toFixed(value, args, ops)));
            case "toPrecision" ->
                new JsNativeFunction("toPrecision", (_, args) -> new JsString(toPrecision(value, args, ops)));
            case "toExponential" ->
                new JsNativeFunction("toExponential", (_, args) -> new JsString(toExponential(value, args, ops)));
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> new JsString(toStringRadix(value, args, ops)));
            case "toLocaleString" ->
                new JsNativeFunction("toLocaleString", (_, args) -> new JsString(toLocaleString(value, args, ops)));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> new JsNumber(value));
            default -> null;
        };
    }

    private static String toLocaleString(double value, List<JsValue> args, InterpreterOps ops) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "∞" : "-∞";
        }
        return java.text.NumberFormat.getInstance(LocaleResolver.resolve(args, 0, ops)).format(value);
    }

    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static String toFixed(double value, List<JsValue> args, InterpreterOps ops) {
        final var requested = integerOrInfinity(args, 0, ops);
        if (!Double.isFinite(requested) || requested < 0 || requested > MAX_FRACTION_DIGITS) {
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
        return new java.math.BigDecimal(value).setScale((int) requested, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String toPrecision(double value, List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return NumberFormatter.toJsString(value);
        }
        final var requested = integerOrInfinity(args, 0, ops);
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (!Double.isFinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (!Double.isFinite(requested) || requested < 1 || requested > MAX_FRACTION_DIGITS) {
            throw new RangeErrorException("toPrecision() argument must be between 1 and 100");
        }
        return formatPrecision(value, (int) requested);
    }

    // Number::toString-style significant-digit rendering: the mantissa digits come from the exact
    // binary expansion of the double, so a request for more digits than the shortest form has still
    // reports the real bits rather than a run of zeroes.
    private static String formatPrecision(double value, int precision) {
        final var negative = value < 0;
        final var magnitude = Math.abs(value);
        final var digits = significantDigits(magnitude, precision);
        final var exponent = digits.exponent();
        final var m = digits.mantissa();
        final String body;
        if (exponent < -6 || exponent >= precision) {
            body = exponentialForm(m, exponent);
        } else if (exponent == precision - 1) {
            body = m;
        } else if (exponent >= 0) {
            body = m.substring(0, exponent + 1) + "." + m.substring(exponent + 1);
        } else {
            body = "0." + "0".repeat(-(exponent + 1)) + m;
        }
        return negative ? "-" + body : body;
    }

    private static String toExponential(double value, List<JsValue> args, InterpreterOps ops) {
        final var provided = !args.isEmpty() && !(args.getFirst() instanceof JsUndefined);
        final var requested = integerOrInfinity(args, 0, ops);
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (!Double.isFinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (!Double.isFinite(requested) || requested < 0 || requested > MAX_FRACTION_DIGITS) {
            throw new RangeErrorException("toExponential() digits argument must be between 0 and 100");
        }
        final var negative = value < 0;
        final var magnitude = Math.abs(value);
        final var digits = provided ? significantDigits(magnitude, (int) requested + 1) : shortestDigits(magnitude);
        final var body = exponentialForm(digits.mantissa(), digits.exponent());
        return negative ? "-" + body : body;
    }

    private record Digits(String mantissa, int exponent) {
    }

    private static String exponentialForm(String mantissa, int exponent) {
        final var head = mantissa.length() > 1 ? mantissa.charAt(0) + "." + mantissa.substring(1) : mantissa;
        return head + "e" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
    }

    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static Digits significantDigits(double magnitude, int count) {
        if (magnitude == 0) {
            return new Digits("0".repeat(count), 0);
        }
        final var rounded = new java.math.BigDecimal(magnitude)
                .round(new java.math.MathContext(count, java.math.RoundingMode.HALF_UP));
        var mantissa = rounded.unscaledValue().toString();
        final var exponent = mantissa.length() - rounded.scale() - 1;
        if (mantissa.length() < count) {
            mantissa = mantissa + "0".repeat(count - mantissa.length());
        } else if (mantissa.length() > count) {
            mantissa = mantissa.substring(0, count);
        }
        return new Digits(mantissa, exponent);
    }

    // The no-argument toExponential form asks for the *shortest* digit string that round-trips, which
    // is exactly what Number::toString already produces.
    private static Digits shortestDigits(double magnitude) {
        if (magnitude == 0) {
            return new Digits("0", 0);
        }
        final var stripped = new java.math.BigDecimal(NumberFormatter.toJsString(magnitude)).stripTrailingZeros();
        final var mantissa = stripped.unscaledValue().toString();
        return new Digits(mantissa, mantissa.length() - stripped.scale() - 1);
    }

    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static String toStringRadix(double value, List<JsValue> args, InterpreterOps ops) {
        final var requested = integerOrInfinity(args, 10, ops);
        if (!Double.isFinite(requested) || requested < 2 || requested > 36) {
            throw new RangeErrorException("toString() radix must be between 2 and 36");
        }
        final var radix = (int) requested;
        if (radix == 10) {
            return NumberFormatter.toJsString(value);
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

    // The spec requires Number.parseFloat/parseInt to be the *same* function object as the global
    // parseFloat/parseInt (18.2.4/18.2.5 vs 21.1.2.15/21.1.2.16), but GlobalScope wires the global
    // and the Number namespace through two independent calls into this class. A per-realm cache
    // (keyed by the realm's InterpreterOps, weakly so it never outlives the realm) makes the second
    // call return the exact instance the first one built instead of a behaviourally-identical twin.
    private static final Map<InterpreterOps, JsNativeFunction> PARSE_FLOAT_CACHE = Collections
            .synchronizedMap(new WeakHashMap<>());
    private static final Map<InterpreterOps, JsNativeFunction> PARSE_INT_CACHE = Collections
            .synchronizedMap(new WeakHashMap<>());

    public static JsNativeFunction parseFloatFunction(InterpreterOps ops) {
        return ops == null
                ? newParseFloatFunction(null)
                : PARSE_FLOAT_CACHE.computeIfAbsent(ops, NumberBuiltins::newParseFloatFunction);
    }

    private static JsNativeFunction newParseFloatFunction(InterpreterOps ops) {
        return new JsNativeFunction("parseFloat", (_, args) -> new JsNumber(parseFloat(text(args, ops))));
    }

    public static JsNativeFunction parseIntFunction(InterpreterOps ops) {
        return ops == null
                ? newParseIntFunction(null)
                : PARSE_INT_CACHE.computeIfAbsent(ops, NumberBuiltins::newParseIntFunction);
    }

    private static JsNativeFunction newParseIntFunction(InterpreterOps ops) {
        return new JsNativeFunction("parseInt", (_, args) -> new JsNumber(parseInt(text(args, ops), radix(args, ops))));
    }

    public static JsNativeFunction isNaNFunction(InterpreterOps ops) {
        return new JsNativeFunction("isNaN", (_, args) -> JsBoolean
                .of(Double.isNaN(args.isEmpty() ? Double.NaN : JsCoercion.toNumber(args.getFirst(), ops))));
    }

    public static JsNativeFunction isFiniteFunction(InterpreterOps ops) {
        return new JsNativeFunction("isFinite", (_, args) -> JsBoolean
                .of(!args.isEmpty() && Double.isFinite(JsCoercion.toNumber(args.getFirst(), ops))));
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

    private static String text(List<JsValue> args, InterpreterOps ops) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst(), ops);
    }

    // ToInt32, not a plain cast: parseInt("11", 4294967298) has to see radix 2.
    private static int radix(List<JsValue> args, InterpreterOps ops) {
        return args.size() < 2 ? 0 : NumberFormatter.toInt32(JsCoercion.toNumber(args.get(1), ops));
    }

    // ToIntegerOrInfinity, kept as a double so the callers can tell an out-of-range infinity from a
    // truncated integer before they range-check it.
    private static double integerOrInfinity(List<JsValue> args, double fallback, InterpreterOps ops) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return fallback;
        }
        final var value = JsCoercion.toNumber(args.getFirst(), ops);
        if (Double.isNaN(value)) {
            return 0;
        }
        return Double.isInfinite(value) ? value : (double) (long) value;
    }

    private static double parseFloat(String raw) {
        final var s = JsCoercion.stripJs(raw);
        if (s.startsWith("Infinity") || s.startsWith("+Infinity")) {
            return Double.POSITIVE_INFINITY;
        }
        if (s.startsWith("-Infinity")) {
            return Double.NEGATIVE_INFINITY;
        }
        final var end = decimalLiteralEnd(s);
        try {
            return Double.parseDouble(s.substring(0, end));
        } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {
            return Double.NaN;
        }
    }

    // The longest prefix that is a StrDecimalLiteral: a trailing `e`/`e+` with no digit after it is
    // not part of the literal, so parseFloat("1ex") is 1 rather than NaN.
    private static int decimalLiteralEnd(String s) {
        var index = 0;
        if (index < s.length() && (s.charAt(index) == '+' || s.charAt(index) == '-')) {
            index++;
        }
        final var digitsStart = index;
        index = skipDigits(s, index);
        if (index < s.length() && s.charAt(index) == '.') {
            index = skipDigits(s, index + 1);
        }
        if (index == digitsStart || (index == digitsStart + 1 && s.charAt(digitsStart) == '.')) {
            return 0;
        }
        final var mantissaEnd = index;
        if (index < s.length() && (s.charAt(index) == 'e' || s.charAt(index) == 'E')) {
            var exponent = index + 1;
            if (exponent < s.length() && (s.charAt(exponent) == '+' || s.charAt(exponent) == '-')) {
                exponent++;
            }
            final var afterSign = exponent;
            exponent = skipDigits(s, exponent);
            if (exponent > afterSign) {
                return exponent;
            }
        }
        return mantissaEnd;
    }

    private static int skipDigits(String s, int from) {
        var index = from;
        while (index < s.length() && s.charAt(index) >= '0' && s.charAt(index) <= '9') {
            index++;
        }
        return index;
    }

    private static double parseInt(String raw, int requestedRadix) {
        var s = JsCoercion.stripJs(raw);
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
        while (index < s.length() && isRadixDigit(s.charAt(index), radix)) {
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

    // Character.digit accepts every Unicode decimal digit; a RadixDigit is ASCII only.
    private static boolean isRadixDigit(char c, int radix) {
        final var isAscii = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        return isAscii && Character.digit(c, radix) >= 0;
    }
}
