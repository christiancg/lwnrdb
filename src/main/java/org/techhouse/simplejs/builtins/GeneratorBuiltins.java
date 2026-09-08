package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.builtins.BuiltinArgs.arg0;

import java.util.List;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.interpreter.MemberEvaluator;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsValue;

public final class GeneratorBuiltins {
    private GeneratorBuiltins() {
    }

    @FunctionalInterface
    public interface AsyncDriver {
        JsValue drive(JsAsyncGenerator generator, MemberEvaluator.AsyncStep step, JsValue argument);
    }

    public static final List<String> PROTO_NAMES = List.of("next", "return", "throw");

    public static JsValue getMethod(JsGenerator generator, String name, JsObject objectProto) {
        final var coroutine = generator.getCoroutine();
        return switch (name) {
            case "next" -> new JsNativeFunction("next",
                    (_, args) -> linkResultProto(InterpreterUtils.stepResult(coroutine.resumeNext(arg0(args))),
                            objectProto));
            case "return" -> new JsNativeFunction("return",
                    (_, args) -> linkResultProto(InterpreterUtils.stepResult(coroutine.resumeReturn(arg0(args))),
                            objectProto));
            case "throw" -> new JsNativeFunction("throw",
                    (_, args) -> linkResultProto(InterpreterUtils.stepResult(coroutine.resumeThrow(arg0(args))),
                            objectProto));
            default -> null;
        };
    }

    private static JsValue linkResultProto(JsValue result, JsObject objectProto) {
        if (result instanceof JsObject object && object.getProto() == null) {
            object.setProto(objectProto);
        }
        return result;
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
}
