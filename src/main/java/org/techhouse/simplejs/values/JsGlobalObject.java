package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        return PropertyDescriptor.data(OrdinaryProperties.orUndefined(env.tryGet(name)), Objects.requireNonNull(env.globalFlags(name)));
    }

    // A declared global is written through to its binding rather than shadowed by a table entry.
    @Override
    public boolean defineOwnProperty(JsValue key, PropertyDescriptor descriptor) {
        if (key instanceof JsSymbol) {
            return super.defineOwnProperty(key, descriptor);
        }
        final var name = OrdinaryProperties.keyName(key);
        if (!env.isDeclared(name)) {
            return super.defineOwnProperty(key, descriptor);
        }
        if (!Objects.requireNonNull(env.globalFlags(name)).configurable()) {
            throw OrdinaryProperties.redefineError(name);
        }
        if (descriptor.value() != null) {
            env.setGlobal(name, descriptor.value());
        }
        return true;
    }

    @Override
    public boolean deleteOwnProperty(JsValue key) {
        return key instanceof JsSymbol
                ? super.deleteOwnProperty(key)
                : env.deleteGlobal(OrdinaryProperties.keyName(key));
    }
}
