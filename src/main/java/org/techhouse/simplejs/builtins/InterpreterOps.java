package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.values.JsValue;

public interface InterpreterOps {
    JsValue getMember(JsValue target, JsValue key);

    boolean setMember(JsValue target, JsValue key, JsValue value);

    boolean has(JsValue target, JsValue key);

    boolean deleteMember(JsValue target, JsValue key);

    List<JsValue> ownKeys(JsValue target);

    JsValue call(JsValue fn, JsValue thisArg, List<JsValue> args);

    JsValue construct(JsValue fn, List<JsValue> args);
}
