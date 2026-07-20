package org.techhouse.simplejs.internal;

import java.math.BigInteger;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class JsCoercion {
    private JsCoercion() {
    }

    public static boolean toBoolean(JsValue value) {
        return switch (value) {
            case JsBoolean b -> b.getValue();
            case JsNumber n -> n.getValue() != 0 && !Double.isNaN(n.getValue());
            case JsBigInt b -> b.getValue().signum() != 0;
            case JsString s -> !s.getValue().isEmpty();
            case JsUndefined ignored -> false;
            case JsNull ignored -> false;
            default -> true;
        };
    }

    public static double toNumber(JsValue value) {
        return switch (value) {
            case JsNumber n -> n.getValue();
            case JsBoolean b -> b.getValue() ? 1 : 0;
            case JsNull ignored -> 0;
            case JsUndefined ignored -> Double.NaN;
            case JsString s -> stringToNumber(s.getValue());
            case JsBigInt ignored -> throw new TypeErrorException("Cannot convert a BigInt to a number");
            default -> stringToNumber(toStr(value));
        };
    }

    public static String toStr(JsValue value) {
        return switch (value) {
            case JsString s -> s.getValue();
            case JsNumber n -> numberToString(n.getValue());
            case JsBoolean b -> Boolean.toString(b.getValue());
            case JsBigInt b -> b.getValue().toString();
            case JsNull ignored -> "null";
            case JsUndefined ignored -> "undefined";
            case JsArray a -> arrayToString(a);
            case JsObject ignored -> "[object Object]";
            case JsFunction f -> functionToString(f.getName());
            case JsNativeFunction f -> functionToString(f.getName());
            case JsClass c -> "class " + (c.getName() == null ? "" : c.getName());
            default -> throw new TypeErrorException("Cannot convert value to string");
        };
    }

    public static JsValue toPrimitive(JsValue value) {
        if (value instanceof JsObject || value instanceof JsArray) {
            return new JsString(toStr(value));
        }
        return value;
    }

    public static String typeOf(JsValue value) {
        return switch (value) {
            case JsNumber ignored -> "number";
            case JsString ignored -> "string";
            case JsBoolean ignored -> "boolean";
            case JsBigInt ignored -> "bigint";
            case JsUndefined ignored -> "undefined";
            case JsFunction ignored -> "function";
            case JsNativeFunction ignored -> "function";
            case JsClass ignored -> "function";
            default -> "object";
        };
    }

    private static String functionToString(String name) {
        return "function " + (name == null ? "" : name) + "() { }";
    }

    private static String arrayToString(JsArray array) {
        final var sb = new StringBuilder();
        final var elements = array.getElements();
        for (var i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            final var element = elements.get(i);
            if (!(element instanceof JsNull) && !(element instanceof JsUndefined)) {
                sb.append(toStr(element));
            }
        }
        return sb.toString();
    }

    private static double stringToNumber(String raw) {
        final var s = raw.strip();
        switch (s) {
            case "" -> {
                return 0;
            }
            case "Infinity", "+Infinity" -> {
                return Double.POSITIVE_INFINITY;
            }
            case "-Infinity" -> {
                return Double.NEGATIVE_INFINITY;
            }
            default -> {
            }
        }
        final var radixValue = radixLiteralToNumber(s);
        if (!Double.isNaN(radixValue)) {
            return radixValue;
        }
        final var last = s.charAt(s.length() - 1);
        if (last == 'd' || last == 'D' || last == 'f' || last == 'F' || s.indexOf('x') >= 0 || s.indexOf('X') >= 0) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static double radixLiteralToNumber(String s) {
        if (s.length() < 3 || s.charAt(0) != '0') {
            return Double.NaN;
        }
        final var marker = Character.toLowerCase(s.charAt(1));
        final var radix = switch (marker) {
            case 'x' -> 16;
            case 'o' -> 8;
            case 'b' -> 2;
            default -> 0;
        };
        if (radix == 0) {
            return Double.NaN;
        }
        try {
            return new BigInteger(s.substring(2), radix).doubleValue();
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static String numberToString(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0) {
            return "0";
        }
        if (d == Math.floor(d) && Math.abs(d) < 1e21) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }
}
