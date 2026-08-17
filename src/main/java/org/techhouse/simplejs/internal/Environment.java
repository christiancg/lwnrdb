package org.techhouse.simplejs.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class Environment {
    public record DisposalEntry(JsValue resource, JsValue method, boolean async) {
    }

    private static final class Binding {
        private JsValue value;
        private final String kind;
        private boolean initialized;
        private final boolean enumerable;
        private final boolean writable;
        private final boolean configurable;

        private Binding(JsValue value, String kind, boolean initialized, boolean enumerable) {
            this(value, kind, initialized, enumerable, true, false);
        }

        private Binding(JsValue value, String kind, boolean initialized, boolean enumerable, boolean writable,
                boolean configurable) {
            this.value = value;
            this.kind = kind;
            this.initialized = initialized;
            this.enumerable = enumerable;
            this.writable = writable;
            this.configurable = configurable;
        }
    }

    private final Environment parent;
    private final boolean functionScope;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private JsValue thisValue;
    private boolean hasThis;
    private boolean thisInitialized = true;
    private JsValue homeClass;
    private boolean hasHomeClass;
    private JsValue newTarget;
    private boolean hasNewTarget;
    private List<DisposalEntry> disposables;

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
        this.thisInitialized = true;
    }

    // A derived constructor's `this` is in TDZ until super() returns, even though the instance itself
    // already exists here (it is created before the constructor chain runs).
    public void defineThisUninitialized(JsValue value) {
        this.thisValue = value;
        this.hasThis = true;
        this.thisInitialized = false;
    }

    public JsValue resolveThis() {
        final var env = thisEnvironment();
        if (env == null) {
            return JsUndefined.getInstance();
        }
        if (!env.thisInitialized) {
            throw new ReferenceErrorException(
                    "Must call super constructor before accessing 'this' in a derived class constructor");
        }
        return env.thisValue;
    }

    public JsValue resolveThisBeforeSuper() {
        final var env = thisEnvironment();
        return env == null ? JsUndefined.getInstance() : env.thisValue;
    }

    public boolean isThisInitialized() {
        final var env = thisEnvironment();
        return env == null || env.thisInitialized;
    }

    public void markThisInitialized() {
        final var env = thisEnvironment();
        if (env != null) {
            env.thisInitialized = true;
        }
    }

    private Environment thisEnvironment() {
        var env = this;
        while (env != null) {
            if (env.hasThis) {
                return env;
            }
            env = env.parent;
        }
        return null;
    }

    public void defineHomeClass(JsValue value) {
        this.homeClass = value;
        this.hasHomeClass = true;
    }

    public JsValue resolveHomeClass() {
        var env = this;
        while (env != null) {
            if (env.hasHomeClass) {
                return env.homeClass;
            }
            env = env.parent;
        }
        return null;
    }

    public void defineNewTarget(JsValue value) {
        this.newTarget = value;
        this.hasNewTarget = true;
    }

    public JsValue resolveNewTarget() {
        var env = this;
        while (env != null) {
            if (env.hasNewTarget) {
                return env.newTarget;
            }
            env = env.parent;
        }
        return JsUndefined.getInstance();
    }

    public void declareFunction(String name, JsValue value) {
        bindings.put(name, new Binding(value, "var", true, true));
    }

    // Installs a host builtin as a non-enumerable global binding, so it is not reported by
    // Object.keys(globalThis)/for-in (spec: global-object builtins are non-enumerable).
    public void declareBuiltin(String name, JsValue value) {
        bindings.put(name, new Binding(value, "var", true, false, true, true));
    }

    // NaN/Infinity/undefined are the global object's non-writable, non-configurable data
    // properties (unlike every other global builtin, which stays plain-writable).
    public void declareNonWritableBuiltin(String name, JsValue value) {
        bindings.put(name, new Binding(value, "var", true, false, false, false));
    }

    public boolean hasLocal(String name) {
        return bindings.containsKey(name);
    }

    public void declareVar(String name) {
        final var target = functionScope();
        target.bindings.computeIfAbsent(name, ignored -> new Binding(JsUndefined.getInstance(), "var", true, true));
    }

    // FunctionDeclarationInstantiation creates every parameter binding up front but only initializes
    // it when its own element is bound, so a default that reads a later (or its own) parameter sees
    // an uninitialized binding.
    public void declareParam(String name) {
        final var target = functionScope();
        target.bindings.computeIfAbsent(name, ignored -> new Binding(JsUndefined.getInstance(), "var", false, true));
    }

    public void declareLexical(String name, String kind) {
        bindings.put(name, new Binding(JsUndefined.getInstance(), kind, false, false));
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
        if (!binding.writable) {
            throw new TypeErrorException("Cannot assign to read only property '" + name + "' of object");
        }
        binding.value = value;
        binding.initialized = true;
    }

    public JsValue tryGet(String name) {
        final var binding = resolve(name);
        return binding == null || !binding.initialized ? null : binding.value;
    }

    public boolean isDeclared(String name) {
        return resolve(name) != null;
    }

    public List<String> enumerableGlobalNames() {
        final var names = new ArrayList<String>();
        for (final var entry : bindings.entrySet()) {
            final var binding = entry.getValue();
            if (binding.initialized && binding.enumerable) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    // Own property names of the global object: var/function/builtin bindings (not lexical
    // let/const, which are not properties of the global object).
    public List<String> allGlobalNames() {
        final var names = new ArrayList<String>();
        for (final var entry : bindings.entrySet()) {
            final var binding = entry.getValue();
            if (binding.initialized && "var".equals(binding.kind)) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    // CreateGlobalVarBinding vs an implicit property creation: a `var` at the top level is a
    // non-configurable global property, while `globalThis.x = 1` on a fresh name is an ordinary
    // configurable one.
    public void setGlobal(String name, JsValue value) {
        if (resolve(name) == null) {
            bindings.put(name, new Binding(JsUndefined.getInstance(), "var", true, true, true, true));
        }
        assign(name, value);
    }

    // Object.defineProperty(globalThis, …) on a name the global scope does not hold yet: the
    // property becomes a real binding with the descriptor's own attributes, so a delete-then-restore
    // round-trip ends up where it started instead of in a shadow table nothing else reads.
    public void defineGlobal(String name, JsValue value, JsObject.PropertyFlags flags) {
        bindings.put(name, new Binding(value, "var", true, flags.enumerable(), flags.writable(), flags.configurable()));
    }

    public void setGlobalFlags(String name, JsObject.PropertyFlags flags, JsValue value) {
        final var binding = bindings.get(name);
        if (binding == null) {
            return;
        }
        bindings.put(name, new Binding(value == null ? binding.value : value, binding.kind, true, flags.enumerable(),
                flags.writable(), flags.configurable()));
    }

    public JsObject.PropertyFlags globalFlags(String name) {
        final var binding = resolve(name);
        return binding == null
                ? null
                : new JsObject.PropertyFlags(binding.writable, binding.enumerable, binding.configurable);
    }

    public boolean deleteGlobal(String name) {
        final var binding = bindings.get(name);
        if (binding == null) {
            return true;
        }
        if (!binding.configurable) {
            return false;
        }
        bindings.remove(name);
        return true;
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

    public void registerDisposable(JsValue resource, JsValue method, boolean async) {
        if (disposables == null) {
            disposables = new ArrayList<>();
        }
        disposables.add(new DisposalEntry(resource, method, async));
    }

    public boolean hasDisposables() {
        return disposables != null && !disposables.isEmpty();
    }

    public List<DisposalEntry> disposables() {
        return disposables == null ? List.of() : disposables;
    }

    private Environment functionScope() {
        var env = this;
        while (!env.functionScope && env.parent != null) {
            env = env.parent;
        }
        return env;
    }
}
