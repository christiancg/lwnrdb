package org.techhouse.simplejs.builtins;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.Iteration;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class MathBuiltins {
    private MathBuiltins() {
    }

    private static final double LN2 = Math.log(2);
    private static final double LN10 = Math.log(10);

    public static JsObject create(InterpreterOps ops) {
        final var math = new JsObject();
        Intrinsics.defineFrozen(math, "PI", new JsNumber(Math.PI));
        Intrinsics.defineFrozen(math, "E", new JsNumber(Math.E));
        Intrinsics.defineFrozen(math, "LN2", new JsNumber(LN2));
        Intrinsics.defineFrozen(math, "LN10", new JsNumber(LN10));
        Intrinsics.defineFrozen(math, "LOG2E", new JsNumber(1 / LN2));
        Intrinsics.defineFrozen(math, "LOG10E", new JsNumber(1 / LN10));
        Intrinsics.defineFrozen(math, "SQRT2", new JsNumber(Math.sqrt(2)));
        Intrinsics.defineFrozen(math, "SQRT1_2", new JsNumber(Math.sqrt(0.5)));
        unary(math, ops, "abs", Math::abs);
        unary(math, ops, "floor", Math::floor);
        unary(math, ops, "ceil", Math::ceil);
        unary(math, ops, "round", MathBuiltins::round);
        unary(math, ops, "trunc", value -> value < 0 ? Math.ceil(value) : Math.floor(value));
        unary(math, ops, "sqrt", Math::sqrt);
        unary(math, ops, "cbrt", Math::cbrt);
        unary(math, ops, "sign", Math::signum);
        unary(math, ops, "log", Math::log);
        unary(math, ops, "exp", Math::exp);
        unary(math, ops, "sin", Math::sin);
        unary(math, ops, "cos", Math::cos);
        unary(math, ops, "tan", Math::tan);
        // Narrowing straight through `(float) value` before `floatToFloat16` rounds twice: a double a
        // hair above the float16 halfway point can round down at the float32 step and then round the
        // "wrong" way again at the float16 step. JsTypedArray.toFloat16 already carries the
        // round-to-odd fix for exactly this (shared with Float16Array element writes / DataView
        // setFloat16), so reuse it here instead of duplicating a naive double-rounding conversion.
        unary(math, ops, "f16round", value -> (double) Float.float16ToFloat(JsTypedArray.toFloat16(value)));
        unary(math, ops, "log2", value -> Math.log(value) / LN2);
        unary(math, ops, "log10", Math::log10);
        unary(math, ops, "log1p", Math::log1p);
        unary(math, ops, "expm1", Math::expm1);
        unary(math, ops, "asin", Math::asin);
        unary(math, ops, "acos", Math::acos);
        unary(math, ops, "atan", Math::atan);
        unary(math, ops, "sinh", Math::sinh);
        unary(math, ops, "cosh", Math::cosh);
        unary(math, ops, "tanh", Math::tanh);
        unary(math, ops, "asinh", MathBuiltins::asinh);
        unary(math, ops, "acosh", value -> Math.log(value + Math.sqrt(value * value - 1)));
        unary(math, ops, "atanh", MathBuiltins::atanh);
        unary(math, ops, "clz32", MathBuiltins::clz32);
        unary(math, ops, "fround", value -> (double) (float) value);
        Intrinsics.defineHidden(math, "pow",
                new JsNativeFunction("pow", (_, args) -> new JsNumber(Math.pow(arg(args, 0, ops), arg(args, 1, ops)))));
        Intrinsics.defineHidden(math, "imul", new JsNativeFunction("imul", (_, args) -> new JsNumber(imul(args, ops))));
        Intrinsics.defineHidden(math, "atan2", new JsNativeFunction("atan2",
                (_, args) -> new JsNumber(Math.atan2(arg(args, 0, ops), arg(args, 1, ops)))));
        Intrinsics.defineHidden(math, "hypot", new JsNativeFunction("hypot", (_, args) -> hypot(args, ops)));
        Intrinsics.defineHidden(math, "random", new JsNativeFunction("random", (_, _) -> new JsNumber(Math.random())));
        Intrinsics.defineHidden(math, "sumPrecise",
                new JsNativeFunction("sumPrecise", (_, args) -> new JsNumber(sumPrecise(args, ops))));
        Intrinsics.defineHidden(math, "min", new JsNativeFunction("min", (_, args) -> reduce(args, ops, true)));
        Intrinsics.defineHidden(math, "max", new JsNativeFunction("max", (_, args) -> reduce(args, ops, false)));
        Intrinsics.defineNamespaceTag(math, "Math");
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

    // The elements are pulled one at a time (the iterable may be endless) and a non-number closes the
    // iterator before its TypeError propagates.
    private static double sumPrecise(List<JsValue> args, InterpreterOps ops) {
        final var sum = new PreciseSum();
        new Iteration(ops, args.isEmpty() ? JsUndefined.getInstance() : args.getFirst()).forEach(sum::add);
        return sum.result();
    }

    // BigDecimal accumulation is exact for finite doubles, so the sum rounds to a double exactly once.
    // The double constructor is the exact one here; BigDecimal.valueOf would round through toString.
    private static final class PreciseSum {
        private BigDecimal total = BigDecimal.ZERO;
        private boolean empty = true;
        private boolean anyPositiveZero;
        private boolean positiveInfinity;
        private boolean negativeInfinity;
        private boolean nan;

        @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
        private void add(JsValue element) {
            if (!(element instanceof JsNumber number)) {
                throw new TypeErrorException("Math.sumPrecise argument is not a number");
            }
            empty = false;
            final var value = number.getValue();
            if (Double.isNaN(value)) {
                nan = true;
            } else if (Double.isInfinite(value)) {
                positiveInfinity |= value > 0;
                negativeInfinity |= value < 0;
            } else {
                anyPositiveZero |= value != 0 || Double.doubleToRawLongBits(value) == 0L;
                total = total.add(new BigDecimal(value));
            }
        }

        private double result() {
            if (empty) {
                return -0.0;
            }
            if (nan || (positiveInfinity && negativeInfinity)) {
                return Double.NaN;
            }
            if (positiveInfinity || negativeInfinity) {
                return positiveInfinity ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            }
            // BigDecimal has no signed zero: a run of -0 sums to -0, anything else to +0.
            final var sum = total.doubleValue();
            return sum == 0 && !anyPositiveZero ? -0.0 : sum;
        }
    }

    private static double imul(List<JsValue> args, InterpreterOps ops) {
        return NumberFormatter.toInt32(arg(args, 0, ops)) * NumberFormatter.toInt32(arg(args, 1, ops));
    }

    // floor(x + 0.5) alone reports +0 for every x in [-0.5, 0) and loses a bit above 2^52.
    private static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value == 0 || Math.abs(value) >= 4.503599627370496e15) {
            return value;
        }
        if (value < 0 && value >= -0.5) {
            return -0.0;
        }
        if (value > 0 && value < 0.5) {
            return 0.0;
        }
        final var floor = Math.floor(value);
        return value - floor >= 0.5 ? floor + 1 : floor;
    }

    private static double atanh(double value) {
        if (Double.isNaN(value) || value == 0) {
            return value;
        }
        if (Math.abs(value) > 1) {
            return Double.NaN;
        }
        return 0.5 * Math.log((1 + value) / (1 - value));
    }

    private static double clz32(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 32;
        }
        return Integer.numberOfLeadingZeros((int) NumberFormatter.toUint32(value));
    }

    private static JsValue hypot(List<JsValue> args, InterpreterOps ops) {
        var sum = 0d;
        var infinite = false;
        var nan = false;
        for (final var arg : args) {
            final var value = JsCoercion.toNumber(arg, ops);
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

    private static void unary(JsObject math, InterpreterOps ops, String name, DoubleUnaryOperator op) {
        Intrinsics.defineHidden(math, name,
                new JsNativeFunction(name, (_, args) -> new JsNumber(op.applyAsDouble(arg(args, 0, ops)))));
    }

    // Every argument is coerced before any of them is compared, so a NaN early in the list still
    // lets a later argument's valueOf run.
    private static JsValue reduce(List<JsValue> args, InterpreterOps ops, boolean min) {
        final var values = new double[args.size()];
        for (var i = 0; i < args.size(); i++) {
            values[i] = JsCoercion.toNumber(args.get(i), ops);
        }
        var result = min ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        var nan = false;
        for (final var value : values) {
            nan |= Double.isNaN(value);
            result = min ? Math.min(result, value) : Math.max(result, value);
        }
        return new JsNumber(nan ? Double.NaN : result);
    }

    private static double arg(List<JsValue> args, int index, InterpreterOps ops) {
        return index < args.size() ? JsCoercion.toNumber(args.get(index), ops) : Double.NaN;
    }
}
