package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isNullish;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.OrdinaryProperties;
import org.techhouse.simplejs.values.PropertyDescriptor;

// Proxy trap dispatch: for each intercepted operation, look up the handler's trap and either call
// it or fall back to the raw operation on the target. Every fallback and re-entry routes through
// the interpreter's InterpreterOps seam, so this carries no interpreter state of its own - and so a
// target that is itself a proxy dispatches its own traps. Each trap's result is then checked against
// the target's real descriptor: those checks are the proxy invariants, and violating one is a
// TypeError even though the trap already returned.
public final class ProxyDispatch {
    private static final double MAX_KEYS = Integer.MAX_VALUE;

    private final InterpreterOps ops;

    public ProxyDispatch(InterpreterOps ops) {
        this.ops = ops;
    }

    public JsValue get(JsProxy proxy, JsValue key) {
        return get(proxy, key, proxy);
    }

    public JsValue get(JsProxy proxy, JsValue key, JsValue receiver) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "get");
        if (trap == null) {
            return ops.getMemberWithReceiver(target, key, receiver);
        }
        final var result = ops.call(trap, proxy.getHandler(), List.of(target, key, receiver));
        final var current = targetProperty(target, key);
        if (current == null || current.configurableOr(false)) {
            return result;
        }
        if (current.isAccessorDescriptor()) {
            if (isUndefined(current.getter()) && !(result instanceof JsUndefined)) {
                throw invariant("get", key);
            }
        } else if (!current.writableOr(false) && isNotSame(result, current.value())) {
            throw invariant("get", key);
        }
        return result;
    }

    public boolean set(JsProxy proxy, JsValue key, JsValue value) {
        return set(proxy, key, value, proxy);
    }

    public boolean set(JsProxy proxy, JsValue key, JsValue value, JsValue receiver) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "set");
        if (trap == null) {
            return ops.setMemberWithReceiver(target, key, value, receiver);
        }
        if (!JsCoercion.toBoolean(ops.call(trap, proxy.getHandler(), List.of(target, key, value, receiver)))) {
            return false;
        }
        final var current = targetProperty(target, key);
        if (current == null || current.configurableOr(false)) {
            return true;
        }
        if (current.isAccessorDescriptor()) {
            if (isUndefined(current.setter())) {
                throw invariant("set", key);
            }
        } else if (!current.writableOr(false) && isNotSame(value, current.value())) {
            throw invariant("set", key);
        }
        return true;
    }

    public boolean has(JsProxy proxy, JsValue key) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "has");
        if (trap == null) {
            return ops.has(target, key);
        }
        final var result = JsCoercion.toBoolean(ops.call(trap, proxy.getHandler(), List.of(target, key)));
        if (result) {
            return true;
        }
        final var current = targetProperty(target, key);
        if (current != null && (!current.configurableOr(false) || !ops.isExtensible(target))) {
            throw invariant("has", key);
        }
        return false;
    }

    public boolean delete(JsProxy proxy, JsValue key) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "deleteProperty");
        if (trap == null) {
            return ops.deleteMember(target, key);
        }
        if (!JsCoercion.toBoolean(ops.call(trap, proxy.getHandler(), List.of(target, key)))) {
            return false;
        }
        final var current = targetProperty(target, key);
        if (current != null && (!current.configurableOr(false) || !ops.isExtensible(target))) {
            throw invariant("deleteProperty", key);
        }
        return true;
    }

    public List<JsValue> ownKeys(JsProxy proxy) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "ownKeys");
        if (trap == null) {
            return ops.ownKeys(target);
        }
        final var keys = keyList(ops.call(trap, proxy.getHandler(), List.of(target)));
        final var reported = new HashSet<>();
        for (final var key : keys) {
            if (!reported.add(identity(key))) {
                throw new TypeErrorException("proxy [[OwnPropertyKeys]] returned a duplicate key");
            }
        }
        checkOwnKeysCoverage(target, keys, reported);
        return keys;
    }

    public JsValue apply(JsProxy proxy, JsValue thisArg, List<JsValue> args) {
        final var trap = trapOf(proxy, "apply");
        if (trap == null) {
            return ops.call(proxy.getTarget(), thisArg, args);
        }
        return ops.call(trap, proxy.getHandler(),
                List.of(proxy.getTarget(), thisArg, new JsArray(new ArrayList<>(args))));
    }

    public JsValue construct(JsProxy proxy, List<JsValue> args, JsValue newTarget) {
        final var trap = trapOf(proxy, "construct");
        if (trap == null) {
            return ops.construct(proxy.getTarget(), args, newTarget == proxy ? proxy.getTarget() : newTarget);
        }
        final var result = ops.call(trap, proxy.getHandler(),
                List.of(proxy.getTarget(), new JsArray(new ArrayList<>(args)), newTarget));
        if (!isObjectLike(result)) {
            throw new TypeErrorException("proxy [[Construct]] must return an object");
        }
        return result;
    }

    public JsValue getPrototypeOf(JsProxy proxy) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "getPrototypeOf");
        if (trap == null) {
            return ops.getPrototypeOf(target);
        }
        final var result = ops.call(trap, proxy.getHandler(), List.of(target));
        if (!isObjectLike(result) && !(result instanceof JsNull)) {
            throw new TypeErrorException("proxy [[GetPrototypeOf]] must return an object or null");
        }
        if (!ops.isExtensible(target) && isNotSame(result, ops.getPrototypeOf(target))) {
            throw new TypeErrorException("proxy [[GetPrototypeOf]] disagrees with a non-extensible target");
        }
        return result;
    }

    public boolean setPrototypeOf(JsProxy proxy, JsValue proto) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "setPrototypeOf");
        if (trap == null) {
            return ops.setPrototypeOf(target, proto);
        }
        if (!JsCoercion.toBoolean(ops.call(trap, proxy.getHandler(), List.of(target, proto)))) {
            return false;
        }
        if (!ops.isExtensible(target) && isNotSame(proto, ops.getPrototypeOf(target))) {
            throw new TypeErrorException("proxy [[SetPrototypeOf]] disagrees with a non-extensible target");
        }
        return true;
    }

    public boolean isExtensible(JsProxy proxy) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "isExtensible");
        if (trap == null) {
            return ops.isExtensible(target);
        }
        final var result = JsCoercion.toBoolean(ops.call(trap, proxy.getHandler(), List.of(target)));
        if (result != ops.isExtensible(target)) {
            throw new TypeErrorException("proxy [[IsExtensible]] disagrees with its target");
        }
        return result;
    }

    public boolean preventExtensions(JsProxy proxy) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "preventExtensions");
        if (trap == null) {
            return ops.preventExtensions(target);
        }
        if (!JsCoercion.toBoolean(ops.call(trap, proxy.getHandler(), List.of(target)))) {
            return false;
        }
        if (ops.isExtensible(target)) {
            throw new TypeErrorException("proxy [[PreventExtensions]] left an extensible target");
        }
        return true;
    }

    public boolean defineProperty(JsProxy proxy, JsValue key, JsValue descriptor) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "defineProperty");
        if (trap == null) {
            return ops.defineProperty(target, key, descriptor);
        }
        if (!JsCoercion.toBoolean(ops.call(trap, proxy.getHandler(), List.of(target, key, descriptor)))) {
            return false;
        }
        final var requested = readDescriptor(descriptor);
        final var current = targetProperty(target, key);
        final var extensible = ops.isExtensible(target);
        final var settingConfigFalse = Boolean.FALSE.equals(requested.configurable());
        if (current == null) {
            if (!extensible || settingConfigFalse) {
                throw invariant("defineProperty", key);
            }
            return true;
        }
        if (isNotCompatible(extensible, requested, current) || (settingConfigFalse && current.configurableOr(false))
                || (!current.isAccessorDescriptor() && !current.configurableOr(false) && current.writableOr(false)
                        && Boolean.FALSE.equals(requested.writable()))) {
            throw invariant("defineProperty", key);
        }
        return true;
    }

    public JsValue getOwnPropertyDescriptor(JsProxy proxy, JsValue key) {
        final var target = proxy.getTarget();
        final var trap = trapOf(proxy, "getOwnPropertyDescriptor");
        if (trap == null) {
            return ops.getOwnPropertyDescriptor(target, key);
        }
        final var result = ops.call(trap, proxy.getHandler(), List.of(target, key));
        if (!isObjectLike(result) && !(result instanceof JsUndefined)) {
            throw new TypeErrorException("proxy [[GetOwnProperty]] must return an object or undefined");
        }
        final var current = targetProperty(target, key);
        final var extensible = ops.isExtensible(target);
        if (result instanceof JsUndefined) {
            if (current != null && (!current.configurableOr(false) || !extensible)) {
                throw invariant("getOwnPropertyDescriptor", key);
            }
            return result;
        }
        final var reported = complete(readDescriptor(result));
        if (isNotCompatible(extensible, reported, current)) {
            throw invariant("getOwnPropertyDescriptor", key);
        }
        if (!reported.configurableOr(false) && (current == null || current.configurableOr(false)
                || (Boolean.FALSE.equals(reported.writable()) && current.writableOr(false)))) {
            throw invariant("getOwnPropertyDescriptor", key);
        }
        return result;
    }

    private void checkOwnKeysCoverage(JsValue target, List<JsValue> keys, Set<Object> reported) {
        final var extensible = ops.isExtensible(target);
        final var targetKeys = ops.ownKeys(target);
        final var targetIdentities = new HashSet<>();
        for (final var targetKey : targetKeys) {
            targetIdentities.add(identity(targetKey));
            final var current = targetProperty(target, targetKey);
            final var required = !extensible || (current != null && !current.configurableOr(false));
            if (required && !reported.contains(identity(targetKey))) {
                throw invariant("ownKeys", targetKey);
            }
        }
        if (extensible) {
            return;
        }
        for (final var key : keys) {
            if (!targetIdentities.contains(identity(key))) {
                throw invariant("ownKeys", key);
            }
        }
    }

    // CreateListFromArrayLike(result, « String, Symbol »).
    private List<JsValue> keyList(JsValue result) {
        if (!isObjectLike(result)) {
            throw new TypeErrorException("proxy [[OwnPropertyKeys]] must return an array-like object");
        }
        final var keys = new ArrayList<JsValue>();
        if (result instanceof JsArray array) {
            keys.addAll(array.getElements());
        } else {
            final var length = JsCoercion.toNumber(ops.getMember(result, new JsString("length")), ops);
            if (length > MAX_KEYS) {
                throw new TypeErrorException("proxy [[OwnPropertyKeys]] result length exceeds the supported maximum");
            }
            for (var i = 0; i < (int) Math.max(0, Double.isNaN(length) ? 0 : length); i++) {
                keys.add(ops.getMember(result, new JsString(Integer.toString(i))));
            }
        }
        for (final var key : keys) {
            if (!(key instanceof JsString) && !(key instanceof JsSymbol)) {
                throw new TypeErrorException("proxy [[OwnPropertyKeys]] must return only string and symbol keys");
            }
        }
        return keys;
    }

    private static Object identity(JsValue key) {
        return key instanceof JsString string ? string.getValue() : key;
    }

    private PropertyDescriptor targetProperty(JsValue target, JsValue key) {
        final var descriptor = ops.getOwnPropertyDescriptor(target, key);
        return isObjectLike(descriptor) ? readDescriptor(descriptor) : null;
    }

    // ToPropertyDescriptor: a field is absent unless HasProperty says so, which is what tells a
    // {writable: false} redefine apart from one that never mentioned writability at all.
    private PropertyDescriptor readDescriptor(JsValue descriptor) {
        if (!isObjectLike(descriptor)) {
            return new PropertyDescriptor(null, null, null, null, null, null);
        }
        final var writable = field(descriptor, "writable");
        final var enumerable = field(descriptor, "enumerable");
        final var configurable = field(descriptor, "configurable");
        return new PropertyDescriptor(field(descriptor, "value"), field(descriptor, "get"), field(descriptor, "set"),
                writable == null ? null : JsCoercion.toBoolean(writable),
                enumerable == null ? null : JsCoercion.toBoolean(enumerable),
                configurable == null ? null : JsCoercion.toBoolean(configurable));
    }

    private JsValue field(JsValue descriptor, String name) {
        return ops.has(descriptor, new JsString(name)) ? ops.getMember(descriptor, new JsString(name)) : null;
    }

    private static PropertyDescriptor complete(PropertyDescriptor descriptor) {
        if (descriptor.isAccessorDescriptor()) {
            return new PropertyDescriptor(null, orUndefined(descriptor.getter()), orUndefined(descriptor.setter()),
                    null, descriptor.enumerableOr(false), descriptor.configurableOr(false));
        }
        return new PropertyDescriptor(orUndefined(descriptor.value()), null, null, descriptor.writableOr(false),
                descriptor.enumerableOr(false), descriptor.configurableOr(false));
    }

    // IsCompatiblePropertyDescriptor, i.e. ValidateAndApplyPropertyDescriptor reduced to its verdict.
    private static boolean isNotCompatible(boolean extensible, PropertyDescriptor descriptor,
            PropertyDescriptor current) {
        if (current == null) {
            return !extensible;
        }
        if (current.configurableOr(false)) {
            return false;
        }
        if (Boolean.TRUE.equals(descriptor.configurable())
                || (descriptor.enumerable() != null && descriptor.enumerable() != current.enumerableOr(false))) {
            return true;
        }
        final var accessor = descriptor.isAccessorDescriptor();
        final var data = descriptor.value() != null || descriptor.writable() != null;
        if ((accessor || data) && accessor != current.isAccessorDescriptor()) {
            return true;
        }
        if (current.isAccessorDescriptor()) {
            return descriptor.getter() != null && isNotSame(descriptor.getter(), current.getter())
                    || descriptor.setter() != null && isNotSame(descriptor.setter(), current.setter());
        }
        if (current.writableOr(false)) {
            return false;
        }
        return Boolean.TRUE.equals(descriptor.writable())
                || (descriptor.value() != null && isNotSame(descriptor.value(), current.value()));
    }

    private static boolean isNotSame(JsValue left, JsValue right) {
        return OrdinaryProperties.isNotSameValue(orUndefined(left), orUndefined(right));
    }

    private static boolean isUndefined(JsValue value) {
        return value == null || value instanceof JsUndefined;
    }

    private static JsValue orUndefined(JsValue value) {
        return value == null ? JsUndefined.getInstance() : value;
    }

    private static TypeErrorException invariant(String trap, JsValue key) {
        final var name = key instanceof JsSymbol symbol
                ? "Symbol(" + symbol.getDescription() + ")"
                : JsCoercion.toStr(key);
        return new TypeErrorException("proxy '" + trap + "' trap violated an invariant for property " + name);
    }

    private JsValue trapOf(JsProxy proxy, String name) {
        if (proxy.isRevoked()) {
            throw new TypeErrorException("Cannot perform '" + name + "' on a proxy that has been revoked");
        }
        final var trap = ops.getMember(proxy.getHandler(), new JsString(name));
        if (isNullish(trap)) {
            return null;
        }
        if (!(trap instanceof JsFunction) && !(trap instanceof JsNativeFunction)) {
            throw new TypeErrorException("Proxy handler's '" + name + "' trap is not a function");
        }
        return trap;
    }
}
