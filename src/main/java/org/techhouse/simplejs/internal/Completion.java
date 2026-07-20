package org.techhouse.simplejs.internal;

import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public record Completion(Kind kind, JsValue value, String label) {
    public enum Kind {
        NORMAL, BREAK, CONTINUE, RETURN
    }

    private static final Completion EMPTY = new Completion(Kind.NORMAL, JsUndefined.getInstance(), null);

    public static Completion empty() {
        return EMPTY;
    }

    public static Completion normal(JsValue value) {
        return new Completion(Kind.NORMAL, value, null);
    }

    public static Completion breakOut(String label) {
        return new Completion(Kind.BREAK, JsUndefined.getInstance(), label);
    }

    public static Completion continueOut(String label) {
        return new Completion(Kind.CONTINUE, JsUndefined.getInstance(), label);
    }

    public static Completion returnValue(JsValue value) {
        return new Completion(Kind.RETURN, value, null);
    }

    public boolean isNormal() {
        return kind == Kind.NORMAL;
    }
}
