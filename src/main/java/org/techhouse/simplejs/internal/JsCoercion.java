package org.techhouse.simplejs.internal;

import java.math.BigInteger;
import java.util.List;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
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
            case JsDate d -> d.getTime();
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
            case JsObject wrapper when wrapper.getPrimitive() != null -> toStr(wrapper.getPrimitive());
            case JsObject ignored -> "[object Object]";
            case JsFunction f -> FunctionProtoBuiltins.sourceText(f);
            case JsNativeFunction f -> FunctionProtoBuiltins.sourceText(f);
            case JsClass c -> FunctionProtoBuiltins.sourceText(c);
            case JsPromise ignored -> "[object Promise]";
            case JsGenerator ignored -> "[object Generator]";
            case JsAsyncGenerator ignored -> "[object AsyncGenerator]";
            case JsRegExp r -> "/" + r.getSource() + "/" + r.getFlags();
            case JsMap ignored -> "[object Map]";
            case JsSet ignored -> "[object Set]";
            case JsDate d -> d.toDateString();
            case JsProxy proxy -> toStr(proxy.getTarget());
            case JsArguments ignored -> "[object Arguments]";
            case JsGlobalObject ignored -> "[object global]";
            case JsTypedArray typed -> typedArrayToString(typed);
            case JsArrayBuffer ignored -> "[object ArrayBuffer]";
            case JsDataView ignored -> "[object DataView]";
            default -> throw new TypeErrorException("Cannot convert value to string");
        };
    }

    public static double toNumber(JsValue value, InterpreterOps ops) {
        if (ops != null && value instanceof JsObject) {
            return toNumber(toPrimitive(value, "number", ops));
        }
        return toNumber(value);
    }

    public static String toStr(JsValue value, InterpreterOps ops) {
        if (ops != null && value instanceof JsObject) {
            return toStr(toPrimitive(value, "string", ops));
        }
        return toStr(value);
    }

    // ToPropertyKey: a symbol stays a symbol, everything else goes through ToPrimitive(string) then
    // ToString - so an object key invokes the user's toString/valueOf rather than stringifying flatly.
    public static JsValue toPropertyKey(JsValue value, InterpreterOps ops) {
        final var primitive = value instanceof JsObject ? toPrimitive(value, "string", ops) : value;
        return primitive instanceof JsSymbol ? primitive : new JsString(toStr(primitive, ops));
    }

    public static JsValue toPrimitive(JsValue value) {
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() != null) {
            return wrapper.getPrimitive();
        }
        if (value instanceof JsObject || value instanceof JsArray) {
            return new JsString(toStr(value));
        }
        return value;
    }

    public static JsValue toPrimitive(JsValue value, String hint, InterpreterOps ops) {
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() != null) {
            return wrapper.getPrimitive();
        }
        if (ops == null || !(value instanceof JsObject)) {
            return toPrimitive(value);
        }
        final var exotic = ops.getMember(value, JsSymbol.TO_PRIMITIVE);
        if (isCallable(exotic)) {
            final var result = ops.call(exotic, value, List.of(new JsString(hint)));
            if (isPrimitive(result)) {
                return result;
            }
            throw new TypeErrorException("Cannot convert object to primitive value");
        }
        final var methods = "string".equals(hint)
                ? new String[]{"toString", "valueOf"}
                : new String[]{"valueOf", "toString"};
        for (final var name : methods) {
            final var method = ops.getMember(value, new JsString(name));
            if (isCallable(method)) {
                final var result = ops.call(method, value, List.of());
                if (isPrimitive(result)) {
                    return result;
                }
            }
        }
        throw new TypeErrorException("Cannot convert object to primitive value");
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    private static boolean isPrimitive(JsValue value) {
        return value instanceof JsNumber || value instanceof JsString || value instanceof JsBoolean
                || value instanceof JsBigInt || value instanceof JsNull || value instanceof JsUndefined
                || value instanceof JsSymbol;
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
            case JsSymbol ignored -> "symbol";
            case JsProxy proxy -> typeOf(proxy.getTarget());
            default -> "object";
        };
    }

    private static String typedArrayToString(JsTypedArray typed) {
        final var sb = new StringBuilder();
        for (var i = 0; i < typed.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(toStr(typed.getElement(i)));
        }
        return sb.toString();
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
        return NumberFormatter.toJsString(d);
    }
}
