package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsValue;

/**
 * OrdinarySet with a Receiver that is not the target: the write lands on the receiver, and the target is
 * consulted only for the descriptor that authorises it.
 *
 * <p>
 * Written against the {@link InterpreterOps} seam rather than against the value model directly, so the one
 * algorithm serves both places a foreign receiver can arrive: {@code Reflect.set(target, key, value, receiver)}
 * and a {@code super.x = v} reference, whose base is the home object's prototype while its receiver is
 * {@code this}. Those two used to have separate implementations, and the member-write one skipped the
 * own-descriptor step - so a super write to a non-writable property silently created an own property on the
 * instance instead of refusing, and a static super write landed on the base class instead of the receiver.
 */
public final class OrdinarySet {
    private OrdinarySet() {
    }

    public static boolean set(InterpreterOps ops, JsValue target, JsValue key, JsValue value, JsValue receiver) {
        if (target instanceof JsProxy) {
            return ops.setMemberWithReceiver(target, key, value, receiver);
        }
        // Integer-Indexed [[Set]] short-circuits before OrdinarySet: a canonical numeric key that is
        // not a valid index on this view is a silent no-op (no coercion, no prototype walk), and a
        // valid one handled directly here never reaches a setter the receiver would otherwise inherit
        // from a per-kind prototype further up the chain.
        if (target instanceof JsTypedArray typed && typed.setExoticIndex(key, value, receiver)) {
            return true;
        }
        if (!(ops.getOwnPropertyDescriptor(target, key) instanceof JsObject own)) {
            final var parent = ops.getPrototypeOf(target);
            return InterpreterUtils.isObjectLike(parent)
                    ? set(ops, parent, key, value, receiver)
                    : defineOnReceiver(ops, key, value, receiver);
        }
        if (own.has("get") || own.has("set")) {
            final var setter = own.get("set");
            if (!InterpreterUtils.isCallable(setter)) {
                return false;
            }
            ops.call(setter, receiver, List.of(value));
            return true;
        }
        return JsCoercion.toBoolean(own.get("writable")) && defineOnReceiver(ops, key, value, receiver);
    }

    private static boolean defineOnReceiver(InterpreterOps ops, JsValue key, JsValue value, JsValue receiver) {
        if (!InterpreterUtils.isObjectLike(receiver)) {
            return false;
        }
        final var descriptor = new JsObject();
        descriptor.set("value", value);
        if (ops.getOwnPropertyDescriptor(receiver, key) instanceof JsObject existing) {
            if (existing.has("get") || existing.has("set") || !JsCoercion.toBoolean(existing.get("writable"))) {
                return false;
            }
        } else {
            descriptor.set("writable", JsBoolean.TRUE);
            descriptor.set("enumerable", JsBoolean.TRUE);
            descriptor.set("configurable", JsBoolean.TRUE);
        }
        try {
            return ops.defineProperty(receiver, key, descriptor);
        } catch (TypeErrorException ignored) {
            return false;
        }
    }
}
