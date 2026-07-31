package org.techhouse.simplejs.builtins;

import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsValue;

public final class MathBuiltins {
    private MathBuiltins() {
    }

    public static JsObject create() {
        final var math = new JsObject();
        math.set("PI", new JsNumber(Math.PI));
        math.set("E", new JsNumber(Math.E));
        unary(math, "abs", Math::abs);
        unary(math, "floor", Math::floor);
        unary(math, "ceil", Math::ceil);
        unary(math, "round", value -> Math.floor(value + 0.5));
        unary(math, "trunc", value -> (double) (long) value);
        unary(math, "sqrt", Math::sqrt);
        unary(math, "cbrt", Math::cbrt);
        unary(math, "sign", Math::signum);
        unary(math, "log", Math::log);
        unary(math, "exp", Math::exp);
        unary(math, "sin", Math::sin);
        unary(math, "cos", Math::cos);
        unary(math, "tan", Math::tan);
        unary(math, "f16round", value -> (double) Float.float16ToFloat(Float.floatToFloat16((float) value)));
        math.set("pow", new JsNativeFunction("pow", (_, args) -> new JsNumber(Math.pow(arg(args, 0), arg(args, 1)))));
        math.set("random", new JsNativeFunction("random", (_, _) -> new JsNumber(Math.random())));
        math.set("min", new JsNativeFunction("min", (_, args) -> reduce(args, Double.POSITIVE_INFINITY, true)));
        math.set("max", new JsNativeFunction("max", (_, args) -> reduce(args, Double.NEGATIVE_INFINITY, false)));
        return math;
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
