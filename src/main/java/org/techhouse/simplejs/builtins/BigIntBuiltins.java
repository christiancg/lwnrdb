package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.InterpreterOps.locale;

import java.math.BigInteger;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class BigIntBuiltins {
    public static final List<String> NAMES = List.of("toString", "valueOf", "toLocaleString");

    private static final int MIN_RADIX = 2;
    private static final int MAX_RADIX = 36;
    private static final double MAX_SAFE_INTEGER = 9007199254740991d;
    // BigInteger cannot represent a value wider than Integer.MAX_VALUE bits, so a bit count the spec
    // would accept but this implementation cannot allocate is reported the way engines report their
    // own width limit rather than crashing out of the sandbox.
    private static final int MAX_BITS = 1 << 24;

    private BigIntBuiltins() {
    }

    public static void installStatics(JsNativeFunction bigInt, InterpreterOps ops) {
        bigInt.setProperty("asIntN", new JsNativeFunction("asIntN", (_, args) -> asIntN(args, true, ops)));
        bigInt.setProperty("asUintN", new JsNativeFunction("asUintN", (_, args) -> asIntN(args, false, ops)));
    }

    public static JsValue getMethod(JsBigInt receiver, String name) {
        return getMethod(receiver, name, null);
    }

    public static JsValue getMethod(JsBigInt receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "toString" ->
                new JsNativeFunction("toString", (_, args) -> new JsString(toString(receiver, args, ops)));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> receiver);
            case "toLocaleString" -> new JsNativeFunction("toLocaleString", (_,
                    _) -> new JsString(java.text.NumberFormat.getInstance(locale(ops)).format(receiver.getValue())));
            default -> null;
        };
    }

    // ToBigInt: unlike the BigInt() function a Number is rejected outright, and an unparseable
    // string is a SyntaxError rather than a TypeError.
    public static JsBigInt toBigInt(JsValue value, InterpreterOps ops) {
        final var primitive = JsCoercion.toPrimitive(value, "number", ops);
        return switch (primitive) {
            case JsBigInt big -> big;
            case JsBoolean bool -> new JsBigInt(bool.getValue() ? BigInteger.ONE : BigInteger.ZERO);
            case JsString string -> fromString(string.getValue());
            default -> throw new TypeErrorException("Cannot convert the argument to a BigInt");
        };
    }

    public static int toIndex(JsValue value, InterpreterOps ops) {
        if (value instanceof JsUndefined) {
            return 0;
        }
        final var integer = toIntegerOrInfinity(JsCoercion.toNumber(value, ops));
        if (integer < 0 || integer > MAX_SAFE_INTEGER) {
            throw new RangeErrorException("Invalid value: not (convertible to) a safe integer");
        }
        return (int) integer;
    }

    private static double toIntegerOrInfinity(double number) {
        if (Double.isNaN(number)) {
            return 0;
        }
        if (Double.isInfinite(number)) {
            return number;
        }
        return number < 0 ? Math.ceil(number) : Math.floor(number);
    }

    private static JsBigInt fromString(String raw) {
        final var parsed = JsCoercion.stringToBigInt(raw);
        if (parsed == null) {
            throw new SyntaxErrorException("Cannot convert " + raw + " to a BigInt");
        }
        return new JsBigInt(parsed);
    }

    private static String toString(JsBigInt receiver, List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return receiver.getValue().toString();
        }
        final var radix = (int) toIntegerOrInfinity(JsCoercion.toNumber(args.getFirst(), ops));
        if (radix < MIN_RADIX || radix > MAX_RADIX) {
            throw new RangeErrorException("toString() radix must be between 2 and 36");
        }
        return receiver.getValue().toString(radix);
    }

    private static JsValue asIntN(List<JsValue> args, boolean signed, InterpreterOps ops) {
        final var bits = toIndex(args.isEmpty() ? JsUndefined.getInstance() : args.getFirst(), ops);
        final var value = toBigInt(args.size() < 2 ? JsUndefined.getInstance() : args.get(1), ops);
        if (bits > MAX_BITS) {
            throw new RangeErrorException("BigInt is too big");
        }
        final var modulus = BigInteger.ONE.shiftLeft(bits);
        final var remainder = value.getValue().mod(modulus);
        if (signed && bits > 0 && remainder.compareTo(BigInteger.ONE.shiftLeft(bits - 1)) >= 0) {
            return new JsBigInt(remainder.subtract(modulus));
        }
        return new JsBigInt(remainder);
    }
}
