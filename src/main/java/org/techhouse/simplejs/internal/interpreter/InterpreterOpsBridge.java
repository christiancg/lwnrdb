package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import java.util.List;
import org.techhouse.simplejs.builtins.object.ObjectDescriptors;
import org.techhouse.simplejs.builtins.object.ObjectOwnKeys;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsValue;

public final class InterpreterOpsBridge implements org.techhouse.simplejs.builtins.InterpreterOps {
    private final Interpreter interp;

    public InterpreterOpsBridge(Interpreter interp) {
        this.interp = interp;
    }

    @Override
    public JsValue getMember(JsValue target, JsValue key) {
        return interp.getMemberByKey(target, key);
    }

    @Override
    public JsValue getMemberWithReceiver(JsValue target, JsValue key, JsValue receiver) {
        return interp.getMemberByKey(target, key, receiver);
    }

    @Override
    public boolean setMember(JsValue target, JsValue key, JsValue value) {
        return interp.setMemberByKey(target, key, value);
    }

    @Override
    public boolean setMemberWithReceiver(JsValue target, JsValue key, JsValue value, JsValue receiver) {
        return interp.setMemberByKey(target, key, value, receiver);
    }

    @Override
    public boolean has(JsValue target, JsValue key) {
        return interp.hasMembers.hasMember(target, key);
    }

    @Override
    public boolean deleteMember(JsValue target, JsValue key) {
        return interp.memberAccess.deleteMemberValue(target, key);
    }

    @Override
    public List<JsValue> ownKeys(JsValue target) {
        return interp.memberAccess.ownKeysOf(target);
    }

    @Override
    public JsValue call(JsValue fn, JsValue thisArg, List<JsValue> args) {
        return interp.functions.callValue(fn, thisArg, args);
    }

    @Override
    public JsValue construct(JsValue fn, List<JsValue> args, JsValue newTarget) {
        return interp.constructs.constructValue(fn, args, newTarget);
    }

    @Override
    public JsValue getPrototypeOf(JsValue target) {
        if (target instanceof JsProxy proxy) {
            return interp.proxies.getPrototypeOf(proxy);
        }
        if (target instanceof JsNativeFunction nativeFunction && nativeFunction.getOwnProto() != null) {
            return nativeFunction.getOwnProto();
        }
        if (target instanceof JsObject || isNullish(target)) {
            return ObjectDescriptors.getPrototypeOf(List.of(target));
        }
        if ((target instanceof JsClass || target instanceof JsGenerator || target instanceof JsAsyncGenerator
                || target instanceof JsArray) && target.getProto() != null) {
            return target.getProto();
        }
        return interp.intrinsics.protoFor(target);
    }

    @Override
    public boolean setPrototypeOf(JsValue target, JsValue proto) {
        if (target instanceof JsProxy proxy) {
            return interp.proxies.setPrototypeOf(proxy, proto);
        }
        return ObjectDescriptors.trySetPrototypeOf(target, proto, interp.intrinsics);
    }

    @Override
    public boolean isExtensible(JsValue target) {
        return target instanceof JsProxy proxy
                ? interp.proxies.isExtensible(proxy)
                : JsCoercion.toBoolean(ObjectOwnKeys.isExtensible(List.of(target)));
    }

    @Override
    public boolean preventExtensions(JsValue target) {
        if (target instanceof JsProxy proxy) {
            return interp.proxies.preventExtensions(proxy);
        }
        ObjectOwnKeys.preventExtensions(List.of(target));
        return true;
    }

    @Override
    public boolean defineProperty(JsValue target, JsValue key, JsValue descriptor) {
        final var propertyKey = JsCoercion.toPropertyKey(key, this);
        if (target instanceof JsProxy proxy) {
            return interp.proxies.defineProperty(proxy, propertyKey, descriptor);
        }
        ObjectDescriptors.defineProperty(List.of(target, propertyKey, descriptor), this);
        return true;
    }

    @Override
    public JsValue getOwnPropertyDescriptor(JsValue target, JsValue key) {
        final var propertyKey = JsCoercion.toPropertyKey(key, this);
        return target instanceof JsProxy proxy
                ? interp.proxies.getOwnPropertyDescriptor(proxy, propertyKey)
                : ObjectDescriptors.getOwnPropertyDescriptor(List.of(target, propertyKey));
    }

    @Override
    public java.time.ZoneId timeZone() {
        return interp.host.timeZone();
    }

    @Override
    public java.util.Locale locale() {
        return interp.host.locale();
    }

    @Override
    public void charge(long bytes) {
        interp.charge(bytes);
    }

    @Override
    public void release(long bytes) {
        interp.release(bytes);
    }

    @Override
    public void tick() {
        interp.tick();
    }
}
