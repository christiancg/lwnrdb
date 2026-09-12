package org.techhouse.test;

import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public final class JsEval {

    private JsEval() {
    }

    public static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    public static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    public static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }
}
