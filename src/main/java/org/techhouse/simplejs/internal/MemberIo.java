package org.techhouse.simplejs.internal;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import java.util.List;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsValue;

final class MemberIo {
    private final Interpreter interpreter;

    MemberIo(Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    JsValue getMemberByKey(JsValue target, JsValue keyValue) {
        if (target instanceof JsProxy proxy) {
            return interpreter.proxies.get(proxy, JsCoercion.toPropertyKey(keyValue, interpreter.ops));
        }
        interpreter.memberAccess.requireObjectCoercible(target);
        final var key = JsCoercion.toPropertyKey(keyValue, interpreter.ops);
        if (key instanceof JsSymbol symbol) {
            return interpreter.members.getSymbolMember(target, symbol);
        }
        return interpreter.members.getMember(target, ((JsString) key).getValue());
    }

    JsValue getMemberByKey(JsValue target, JsValue keyValue, JsValue receiver) {
        if (target instanceof JsProxy proxy) {
            return interpreter.proxies.get(proxy, JsCoercion.toPropertyKey(keyValue, interpreter.ops), receiver);
        }
        if (keyValue instanceof JsSymbol symbol) {
            return interpreter.members.getSymbolMember(target, symbol);
        }
        return interpreter.members.getMember(target, JsCoercion.toStr(keyValue), receiver);
    }

    JsValue getMember(JsValue target, String key) {
        return interpreter.members.getMember(target, key);
    }

    boolean setMemberByKey(JsValue target, JsValue rawKey, JsValue value) {
        if (target instanceof JsProxy proxy) {
            return interpreter.proxies.set(proxy, JsCoercion.toPropertyKey(rawKey, interpreter.ops), value);
        }
        interpreter.memberAccess.requireObjectCoercible(target);
        final var keyValue = JsCoercion.toPropertyKey(rawKey, interpreter.ops);
        if (keyValue instanceof JsSymbol symbol) {
            if (target instanceof JsObject object) {
                if (object.hasSymbolAccessor(symbol)) {
                    final var setter = object.getSymbolAccessorSetter(symbol);
                    if (setter != null) {
                        interpreter.functions.callValue(setter, object, List.of(value));
                    }
                    return true;
                }
                final var cls = object.getKlass();
                if (cls != null && !object.hasSymbol(symbol)) {
                    final var setter = cls.findInstanceSymbolSetter(symbol);
                    if (setter != null) {
                        interpreter.functions.callFunction(setter, object, List.of(value));
                        return true;
                    }
                }
                return object.setSymbol(symbol, value);
            }
            if (target instanceof JsClass cls) {
                cls.setStaticSymbolProp(symbol, value);
                return true;
            }
            final var table = target.ownProperties();
            return table == null || table.setSymbol(symbol, value);
        }
        if (!isObjectLike(target)) {
            return interpreter.memberAccess.setPrimitiveMember(target, ((JsString) keyValue).getValue(), value, target);
        }
        return interpreter.members.setMember(target, ((JsString) keyValue).getValue(), value);
    }

    boolean setMemberByKey(JsValue target, JsValue rawKey, JsValue value, JsValue receiver) {
        if (target instanceof JsProxy proxy) {
            return interpreter.proxies.set(proxy, JsCoercion.toPropertyKey(rawKey, interpreter.ops), value, receiver);
        }
        interpreter.memberAccess.requireObjectCoercible(target);
        final var keyValue = JsCoercion.toPropertyKey(rawKey, interpreter.ops);
        if (keyValue instanceof JsSymbol) {
            return setMemberByKey(target, keyValue, value);
        }
        if (!isObjectLike(target)) {
            return interpreter.memberAccess.setPrimitiveMember(target, ((JsString) keyValue).getValue(), value,
                    receiver);
        }
        return interpreter.members.setMember(target, ((JsString) keyValue).getValue(), value, receiver);
    }
}
