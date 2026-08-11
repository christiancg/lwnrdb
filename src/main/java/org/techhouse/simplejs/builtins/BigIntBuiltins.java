package org.techhouse.simplejs.builtins;

import java.math.BigInteger;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class BigIntBuiltins {
    public static final List<String> NAMES = List.of("toString", "valueOf", "toLocaleString");

    private static final int MIN_RADIX = 2;
    private static final int MAX_RADIX = 36;

    private BigIntBuiltins() {
    }

    public static void installStatics(JsNativeFunction bigInt) {
        bigInt.setProperty("asIntN", new JsNativeFunction("asIntN", (_, args) -> asIntN(args, true)));
        bigInt.setProperty("asUintN", new JsNativeFunction("asUintN", (_, args) -> asIntN(args, false)));
    }

    public static JsValue getMethod(JsBigInt receiver, String name) {
        return switch (name) {
            case "toString" -> new JsNativeFunction("toString", (_, args) -> new JsString(toString(receiver, args)));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> receiver);
            case "toLocaleString" -> new JsNativeFunction("toLocaleString", (_, _) -> new JsString(
                    java.text.NumberFormat.getInstance(java.util.Locale.getDefault()).format(receiver.getValue())));
            default -> null;
        };
    }

    private static String toString(JsBigInt receiver, List<JsValue> args) {
        if (args.isEmpty() || args.getFirst() instanceof JsUndefined) {
            return receiver.getValue().toString();
        }
        final var radix = (int) JsCoercion.toNumber(args.getFirst());
        if (radix < MIN_RADIX || radix > MAX_RADIX) {
            throw new RangeErrorException("toString() radix must be between 2 and 36");
        }
        return receiver.getValue().toString(radix);
    }

    private static JsValue asIntN(List<JsValue> args, boolean signed) {
        final var bits = (int) JsCoercion.toNumber(args.isEmpty() ? JsUndefined.getInstance() : args.getFirst());
        if (bits < 0) {
            throw new RangeErrorException("Invalid value: not (convertible to) a safe integer");
        }
        if (args.size() < 2 || !(args.get(1) instanceof JsBigInt value)) {
            throw new TypeErrorException("Cannot convert the argument to a BigInt");
        }
        final var modulus = BigInteger.ONE.shiftLeft(bits);
        final var remainder = value.getValue().mod(modulus);
        if (signed && bits > 0 && remainder.compareTo(BigInteger.ONE.shiftLeft(bits - 1)) >= 0) {
            return new JsBigInt(remainder.subtract(modulus));
        }
        return new JsBigInt(remainder);
    }
}
