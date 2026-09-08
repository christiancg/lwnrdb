package org.techhouse.simplejs.builtins;

import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class NewTargetSupport {
    public static void requireNewTarget(String constructorName, JsValue thisArg) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if ((newTarget == null || newTarget instanceof JsUndefined) && thisArg instanceof JsUndefined) {
            throw new TypeErrorException("Constructor " + constructorName + " requires 'new'");
        }
    }

    public static void requireNewTargetOrSubclassInstance(String constructorName, JsValue thisArg) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (newTarget != null && !(newTarget instanceof JsUndefined)) {
            return;
        }
        if (thisArg instanceof JsObject object && object.getKlass() != null) {
            return;
        }
        throw new TypeErrorException("Constructor " + constructorName + " requires 'new'");
    }

    public static JsValue withNewTargetPrototype(JsValue constructed, InterpreterOps ops) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if (ops == null || newTarget == null || newTarget instanceof JsUndefined) {
            return constructed;
        }
        final var proto = ops.getMember(newTarget, new JsString("prototype"));
        if (!(proto instanceof JsObject requested) || proto == ops.getPrototypeOf(constructed)) {
            return constructed;
        }
        final var wrapper = new JsObject();
        wrapper.setPrimitive(constructed);
        wrapper.setProto(requested);
        return wrapper;
    }

    private NewTargetSupport() {
    }
}
