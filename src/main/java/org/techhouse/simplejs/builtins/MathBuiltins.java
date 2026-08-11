package org.techhouse.simplejs.builtins;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class MathBuiltins {
    private MathBuiltins() {
    }

    private static final double LN2 = Math.log(2);
    private static final double LN10 = Math.log(10);

    public static JsObject create(IterableToList iterableToList) {
        final var math = new JsObject();
        math.set("PI", new JsNumber(Math.PI));
        math.set("E", new JsNumber(Math.E));
        math.set("LN2", new JsNumber(LN2));
        math.set("LN10", new JsNumber(LN10));
        math.set("LOG2E", new JsNumber(1 / LN2));
        math.set("LOG10E", new JsNumber(1 / LN10));
        math.set("SQRT2", new JsNumber(Math.sqrt(2)));
        math.set("SQRT1_2", new JsNumber(Math.sqrt(0.5)));
        unary(math, "abs", Math::abs);
        unary(math, "floor", Math::floor);
        unary(math, "ceil", Math::ceil);
        unary(math, "round", value -> Math.floor(value + 0.5));
        unary(math, "trunc", value -> value < 0 ? Math.ceil(value) : Math.floor(value));
        unary(math, "sqrt", Math::sqrt);
        unary(math, "cbrt", Math::cbrt);
        unary(math, "sign", Math::signum);
        unary(math, "log", Math::log);
        unary(math, "exp", Math::exp);
        unary(math, "sin", Math::sin);
        unary(math, "cos", Math::cos);
        unary(math, "tan", Math::tan);
        unary(math, "f16round", value -> (double) Float.float16ToFloat(Float.floatToFloat16((float) value)));
        unary(math, "log2", value -> Math.log(value) / LN2);
        unary(math, "log10", Math::log10);
        unary(math, "log1p", Math::log1p);
        unary(math, "expm1", Math::expm1);
        unary(math, "asin", Math::asin);
        unary(math, "acos", Math::acos);
        unary(math, "atan", Math::atan);
        unary(math, "sinh", Math::sinh);
        unary(math, "cosh", Math::cosh);
        unary(math, "tanh", Math::tanh);
        unary(math, "asinh", MathBuiltins::asinh);
        unary(math, "acosh", value -> Math.log(value + Math.sqrt(value * value - 1)));
        unary(math, "atanh", value -> 0.5 * Math.log((1 + value) / (1 - value)));
        unary(math, "clz32", MathBuiltins::clz32);
        unary(math, "fround", value -> (double) (float) value);
        math.set("pow", new JsNativeFunction("pow", (_, args) -> new JsNumber(Math.pow(arg(args, 0), arg(args, 1)))));
        math.set("imul", new JsNativeFunction("imul", (_, args) -> new JsNumber(imul(args))));
        math.set("atan2",
                new JsNativeFunction("atan2", (_, args) -> new JsNumber(Math.atan2(arg(args, 0), arg(args, 1)))));
        math.set("hypot", new JsNativeFunction("hypot", (_, args) -> hypot(args)));
        math.set("random", new JsNativeFunction("random", (_, _) -> new JsNumber(Math.random())));
        math.set("sumPrecise",
                new JsNativeFunction("sumPrecise", (_, args) -> new JsNumber(sumPrecise(args, iterableToList))));
        math.set("min", new JsNativeFunction("min", (_, args) -> reduce(args, Double.POSITIVE_INFINITY, true)));
        math.set("max", new JsNativeFunction("max", (_, args) -> reduce(args, Double.NEGATIVE_INFINITY, false)));
        return math;
    }

    private static double asinh(double value) {
        if (Double.isInfinite(value) || value == 0) {
            return value;
        }
        return value > 0
                ? Math.log(value + Math.sqrt(value * value + 1))
                : -Math.log(-value + Math.sqrt(value * value + 1));
    }

    // BigDecimal accumulation is exact for finite doubles, so the sum rounds to a double exactly once.
    // The double constructor is the exact one here; BigDecimal.valueOf would round through toString.
    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static double sumPrecise(List<JsValue> args, IterableToList iterableToList) {
        final var elements = iterableToList.drain(args.isEmpty() ? JsUndefined.getInstance() : args.getFirst());
        if (elements.isEmpty()) {
            return -0.0;
        }
        var total = BigDecimal.ZERO;
        var positiveInfinity = false;
        var negativeInfinity = false;
        var nan = false;
        for (final var element : elements) {
            if (!(element instanceof JsNumber number)) {
                throw new TypeErrorException("Math.sumPrecise argument is not a number");
            }
            final var value = number.getValue();
            if (Double.isNaN(value)) {
                nan = true;
            } else if (Double.isInfinite(value)) {
                positiveInfinity |= value > 0;
                negativeInfinity |= value < 0;
            } else {
                total = total.add(new BigDecimal(value));
            }
        }
        if (nan || (positiveInfinity && negativeInfinity)) {
            return Double.NaN;
        }
        if (positiveInfinity || negativeInfinity) {
            return positiveInfinity ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        }
        return total.doubleValue();
    }

    private static double imul(List<JsValue> args) {
        return NumberFormatter.toInt32(arg(args, 0)) * NumberFormatter.toInt32(arg(args, 1));
    }

    private static double clz32(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 32;
        }
        return Integer.numberOfLeadingZeros((int) NumberFormatter.toUint32(value));
    }

    private static JsValue hypot(List<JsValue> args) {
        var sum = 0d;
        var infinite = false;
        var nan = false;
        for (final var arg : args) {
            final var value = JsCoercion.toNumber(arg);
            if (Double.isInfinite(value)) {
                infinite = true;
            } else if (Double.isNaN(value)) {
                nan = true;
            } else {
                sum += value * value;
            }
        }
        if (infinite) {
            return new JsNumber(Double.POSITIVE_INFINITY);
        }
        return new JsNumber(nan ? Double.NaN : Math.sqrt(sum));
    }

    private static void unary(JsObject math, String name, DoubleUnaryOperator op) {
        math.set(name, new JsNativeFunction(name, (_, args) -> new JsNumber(op.applyAsDouble(arg(args, 0)))));
    }

    private static JsValue reduce(List<JsValue> args, double seed, boolean min) {
        var result = seed;
        for (final var arg : args) {
            final var value = JsCoercion.toNumber(arg);
            if (Double.isNaN(value)) {
                return new JsNumber(Double.NaN);
            }
            result = min ? Math.min(result, value) : Math.max(result, value);
        }
        return new JsNumber(result);
    }

    private static double arg(List<JsValue> args, int index) {
        return index < args.size() ? JsCoercion.toNumber(args.get(index)) : Double.NaN;
    }
}
