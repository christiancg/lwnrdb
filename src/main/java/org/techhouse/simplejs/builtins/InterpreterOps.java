package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.values.JsValue;

public interface InterpreterOps {
    JsValue getMember(JsValue target, JsValue key);

    JsValue getMemberWithReceiver(JsValue target, JsValue key, JsValue receiver);

    boolean setMember(JsValue target, JsValue key, JsValue value);

    boolean setMemberWithReceiver(JsValue target, JsValue key, JsValue value, JsValue receiver);

    boolean has(JsValue target, JsValue key);

    boolean deleteMember(JsValue target, JsValue key);

    List<JsValue> ownKeys(JsValue target);

    JsValue call(JsValue fn, JsValue thisArg, List<JsValue> args);

    JsValue construct(JsValue fn, List<JsValue> args, JsValue newTarget);

    default JsValue construct(JsValue fn, List<JsValue> args) {
        return construct(fn, args, fn);
    }

    JsValue getPrototypeOf(JsValue target);

    boolean setPrototypeOf(JsValue target, JsValue proto);

    boolean isExtensible(JsValue target);

    boolean preventExtensions(JsValue target);

    boolean defineProperty(JsValue target, JsValue key, JsValue descriptor);

    JsValue getOwnPropertyDescriptor(JsValue target, JsValue key);
}
