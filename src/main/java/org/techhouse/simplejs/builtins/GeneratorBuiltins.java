package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.interpreter.MemberEvaluator;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class GeneratorBuiltins {
    private GeneratorBuiltins() {
    }

    @FunctionalInterface
    public interface AsyncDriver {
        JsValue drive(JsAsyncGenerator generator, MemberEvaluator.AsyncStep step, JsValue argument);
    }

    public static final List<String> PROTO_NAMES = List.of("next", "return", "throw");

    public static JsValue getMethod(JsGenerator generator, String name) {
        final var coroutine = generator.getCoroutine();
        return switch (name) {
            case "next" -> new JsNativeFunction("next",
                    (_, args) -> InterpreterUtils.stepResult(coroutine.resumeNext(arg0(args))));
            case "return" -> new JsNativeFunction("return",
                    (_, args) -> InterpreterUtils.stepResult(coroutine.resumeReturn(arg0(args))));
            case "throw" -> new JsNativeFunction("throw",
                    (_, args) -> InterpreterUtils.stepResult(coroutine.resumeThrow(arg0(args))));
            default -> null;
        };
    }

    public static JsValue getAsyncMethod(JsAsyncGenerator generator, String name, AsyncDriver driver) {
        return switch (name) {
            case "next" -> new JsNativeFunction("next",
                    (_, args) -> driver.drive(generator, MemberEvaluator.AsyncStep.NEXT, arg0(args)));
            case "return" -> new JsNativeFunction("return",
                    (_, args) -> driver.drive(generator, MemberEvaluator.AsyncStep.RETURN, arg0(args)));
            case "throw" -> new JsNativeFunction("throw",
                    (_, args) -> driver.drive(generator, MemberEvaluator.AsyncStep.THROW, arg0(args)));
            default -> null;
        };
    }

    private static JsValue arg0(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }
}
