package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;

public final class JsGlobalObject extends JsValue {
    private final Environment env;
    // Own string keys of the global object live in the Environment, never here; the table only
    // carries what the Environment cannot hold (symbol keys and accessor redefinitions).
    private PropertyTable table;

    public JsGlobalObject(Environment env) {
        this.env = env;
    }

    public Environment getEnv() {
        return env;
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }

    @Override
    public List<JsValue> ownPropertyKeys() {
        final var keys = new ArrayList<JsValue>();
        for (final var name : env.allGlobalNames()) {
            keys.add(new JsString(name));
        }
        for (final var name : ownProperties().keys()) {
            if (!env.isDeclared(name)) {
                keys.add(new JsString(name));
            }
        }
        keys.addAll(ownProperties().symbolKeys());
        return keys;
    }

    @Override
    public PropertyDescriptor getOwnProperty(JsValue key) {
        if (key instanceof JsSymbol) {
            return super.getOwnProperty(key);
        }
        final var name = OrdinaryProperties.keyName(key);
        if (!env.isDeclared(name)) {
            return super.getOwnProperty(key);
        }
        return PropertyDescriptor.data(OrdinaryProperties.orUndefined(env.tryGet(name)),
                Objects.requireNonNull(env.globalFlags(name)));
    }

    // A declared global is written through to its binding rather than shadowed by a table entry. An
    // accessor cannot live in an Environment binding, so defining one drops the binding and the
    // ordinary table takes over the key.
    @Override
    public boolean defineOwnProperty(JsValue key, PropertyDescriptor descriptor) {
        if (key instanceof JsSymbol) {
            return super.defineOwnProperty(key, descriptor);
        }
        final var name = OrdinaryProperties.keyName(key);
        final var current = env.globalFlags(name);
        if (current == null) {
            if (descriptor.isAccessorDescriptor()) {
                return super.defineOwnProperty(key, descriptor);
            }
            if (!ownProperties().isExtensible()) {
                throw new TypeErrorException("Cannot define property " + name + ", object is not extensible");
            }
            env.defineGlobal(name, OrdinaryProperties.orUndefined(descriptor.value()), new JsObject.PropertyFlags(
                    descriptor.writableOr(false), descriptor.enumerableOr(false), descriptor.configurableOr(false)));
            return true;
        }
        if (!current.configurable()) {
            checkNonConfigurableGlobal(name, current, descriptor);
        }
        if (descriptor.isAccessorDescriptor()) {
            env.deleteGlobal(name);
            return super.defineOwnProperty(key, descriptor);
        }
        env.setGlobalFlags(name, new JsObject.PropertyFlags(descriptor.writableOr(current.writable()),
                descriptor.enumerableOr(current.enumerable()), descriptor.configurableOr(current.configurable())),
                descriptor.value());
        return true;
    }

    private void checkNonConfigurableGlobal(String name, JsObject.PropertyFlags current,
            PropertyDescriptor descriptor) {
        if (Boolean.TRUE.equals(descriptor.configurable()) || descriptor.isAccessorDescriptor()
                || (descriptor.enumerable() != null && descriptor.enumerable() != current.enumerable())) {
            throw OrdinaryProperties.redefineError(name);
        }
        if (!current.writable()
                && (Boolean.TRUE.equals(descriptor.writable()) || (descriptor.value() != null && OrdinaryProperties
                        .isNotSameValue(descriptor.value(), OrdinaryProperties.orUndefined(env.tryGet(name)))))) {
            throw OrdinaryProperties.redefineError(name);
        }
    }

    @Override
    public boolean deleteOwnProperty(JsValue key) {
        return key instanceof JsSymbol
                ? super.deleteOwnProperty(key)
                : env.deleteGlobal(OrdinaryProperties.keyName(key));
    }
}
