package org.techhouse.simplejs.internal;

import java.util.HashMap;
import java.util.Map;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class Environment {
    private static final class Binding {
        private JsValue value;
        private final String kind;
        private boolean initialized;

        private Binding(JsValue value, String kind, boolean initialized) {
            this.value = value;
            this.kind = kind;
            this.initialized = initialized;
        }
    }

    private final Environment parent;
    private final boolean functionScope;
    private final Map<String, Binding> bindings = new HashMap<>();
    private JsValue thisValue;
    private boolean hasThis;

    private Environment(Environment parent, boolean functionScope) {
        this.parent = parent;
        this.functionScope = functionScope;
    }

    public static Environment global() {
        return new Environment(null, true);
    }

    public Environment child() {
        return new Environment(this, false);
    }

    public Environment functionChild() {
        return new Environment(this, true);
    }

    public void defineThis(JsValue value) {
        this.thisValue = value;
        this.hasThis = true;
    }

    public JsValue resolveThis() {
        var env = this;
        while (env != null) {
            if (env.hasThis) {
                return env.thisValue;
            }
            env = env.parent;
        }
        return JsUndefined.getInstance();
    }

    public void declareFunction(String name, JsValue value) {
        bindings.put(name, new Binding(value, "var", true));
    }

    public boolean hasLocal(String name) {
        return bindings.containsKey(name);
    }

    public void declareVar(String name) {
        final var target = functionScope();
        target.bindings.computeIfAbsent(name, ignored -> new Binding(JsUndefined.getInstance(), "var", true));
    }

    public void declareLexical(String name, String kind) {
        bindings.put(name, new Binding(JsUndefined.getInstance(), kind, false));
    }

    public void initialize(String name, JsValue value) {
        final var binding = bindings.get(name);
        binding.value = value;
        binding.initialized = true;
    }

    public JsValue get(String name) {
        final var binding = resolve(name);
        if (binding == null) {
            throw new ReferenceErrorException(name + " is not defined");
        }
        if (!binding.initialized) {
            throw new ReferenceErrorException("Cannot access '" + name + "' before initialization");
        }
        return binding.value;
    }

    public void assign(String name, JsValue value) {
        final var binding = resolve(name);
        if (binding == null) {
            throw new ReferenceErrorException(name + " is not defined");
        }
        if ("const".equals(binding.kind) && binding.initialized) {
            throw new TypeErrorException("Assignment to constant variable.");
        }
        binding.value = value;
        binding.initialized = true;
    }

    private Binding resolve(String name) {
        var env = this;
        while (env != null) {
            final var binding = env.bindings.get(name);
            if (binding != null) {
                return binding;
            }
            env = env.parent;
        }
        return null;
    }

    private Environment functionScope() {
        var env = this;
        while (!env.functionScope && env.parent != null) {
            env = env.parent;
        }
        return env;
    }
}
