package org.techhouse.simplejs.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.BindingTable.Binding;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class Environment implements GlobalSink {
    public record DisposalEntry(JsValue resource, JsValue method, boolean async) {
    }

    private final Environment parent;
    private final boolean functionScope;
    private BindingTable bindings;
    private BindingTable globalLexicalBindings;
    private JsValue thisValue;
    private boolean hasThis;
    private boolean thisInitialized = true;
    private JsValue homeClass;
    private boolean hasHomeClass;
    private JsClass privateOwner;
    private JsValue newTarget;
    private boolean hasNewTarget;
    private Coroutine coroutine;
    private List<DisposalEntry> disposables;

    private BindingTable bindings() {
        var existing = bindings;
        if (existing == null) {
            existing = new BindingTable(parent == null);
            bindings = existing;
        }
        return existing;
    }

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

    public void replaceThis(JsValue value) {
        final var env = thisEnvironment();
        if (env != null) {
            env.thisValue = value;
        }
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

    public void definePrivateEnvironment(JsClass owner) {
        this.privateOwner = owner;
    }

    public JsClass resolvePrivateClass(String name) {
        var env = this;
        while (env != null) {
            if (env.privateOwner != null && env.privateOwner.privateNameFor(name) != null) {
                return env.privateOwner;
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

    public void defineCoroutine(Coroutine value) {
        this.coroutine = value;
    }

    public Coroutine resolveCoroutine() {
        return functionScope().coroutine;
    }

    public void declareFunction(String name, JsValue value) {
        bindings().put(name, Binding.var(value, true, true, false));
    }

    @Override
    public void declareBuiltin(String name, JsValue value) {
        bindings().put(name, Binding.var(value, false, true, true));
    }

    public void declareLazyBuiltin(String name, LazyBuiltin holder) {
        bindings().put(name, Binding.lazyVar(holder));
    }

    public void declareNonWritableBuiltin(String name, JsValue value) {
        bindings().put(name, Binding.var(value, false, false, false));
    }

    public boolean hasLocal(String name) {
        return bindings != null && bindings.containsKey(name);
    }

    public void declareVar(String name) {
        final var target = functionScope();
        target.bindings().putIfAbsent(name, Binding.var(JsUndefined.getInstance(), true, true, false));
    }

    public void declareParam(String name) {
        final var target = functionScope();
        target.bindings().putIfAbsent(name,
                new Binding(JsUndefined.getInstance(), true, false, false, true, true, false));
    }

    public void declareParamValue(String name, JsValue value) {
        functionScope().bindings().put(name, new Binding(value, true, false, true, true, true, false));
    }

    public void declareLexical(String name, String kind) {
        if (parent == null) {
            if (globalLexicalBindings == null) {
                globalLexicalBindings = new BindingTable(true);
            }
            globalLexicalBindings.put(name, new Binding(JsUndefined.getInstance(), kind, false, false));
            return;
        }
        bindings().put(name, new Binding(JsUndefined.getInstance(), kind, false, false));
    }

    public void initialize(String name, JsValue value) {
        final var binding = Objects.requireNonNull(ownBinding(name), () -> "no binding declared for " + name);
        binding.assign(value);
        binding.initialized = true;
    }

    private Binding ownBinding(String name) {
        if (parent == null && globalLexicalBindings != null) {
            final var lexical = globalLexicalBindings.get(name);
            if (lexical != null) {
                return lexical;
            }
        }
        return bindings == null ? null : bindings.get(name);
    }

    public JsValue get(String name) {
        final var binding = resolve(name);
        if (binding == null) {
            throw new ReferenceErrorException(name + " is not defined");
        }
        if (!binding.initialized) {
            throw new ReferenceErrorException("Cannot access '" + name + "' before initialization");
        }
        return binding.value();
    }

    public void assign(String name, JsValue value) {
        final var binding = resolve(name);
        if (binding == null) {
            throw new ReferenceErrorException(name + " is not defined");
        }
        if (!binding.initialized && !binding.isVar) {
            throw new ReferenceErrorException("Cannot access '" + name + "' before initialization");
        }
        if (binding.isConst) {
            throw new TypeErrorException("Assignment to constant variable.");
        }
        if (!binding.writable) {
            throw new TypeErrorException("Cannot assign to read only property '" + name + "' of object");
        }
        binding.assign(value);
        binding.initialized = true;
    }

    public JsValue tryGet(String name) {
        final var binding = resolve(name);
        return binding == null || !binding.initialized ? null : binding.value();
    }

    public JsValue tryGetGlobalProperty(String name) {
        final var binding = bindings == null ? null : bindings.get(name);
        return binding == null || !binding.initialized ? null : binding.value();
    }

    public boolean isDeclared(String name) {
        return resolve(name) != null;
    }

    public List<String> enumerableGlobalNames() {
        final var names = new ArrayList<String>();
        if (bindings != null) {
            bindings.forEach((name, binding) -> {
                if (binding.initialized && binding.enumerable) {
                    names.add(name);
                }
            });
        }
        return names;
    }

    public List<String> allGlobalNames() {
        final var names = new ArrayList<String>();
        if (bindings != null) {
            bindings.forEach((name, binding) -> {
                if (binding.initialized && binding.isVar) {
                    names.add(name);
                }
            });
        }
        return names;
    }

    public void setGlobal(String name, JsValue value) {
        if (resolve(name) == null) {
            bindings().put(name, Binding.var(JsUndefined.getInstance(), true, true, true));
        }
        assign(name, value);
    }

    public void defineGlobal(String name, JsValue value, JsObject.PropertyFlags flags) {
        bindings().put(name, Binding.var(value, flags.enumerable(), flags.writable(), flags.configurable()));
    }

    public void setGlobalFlags(String name, JsObject.PropertyFlags flags, JsValue value) {
        final var binding = bindings == null ? null : bindings.get(name);
        if (binding == null) {
            return;
        }
        bindings().put(name, binding.withFlags(value, flags.enumerable(), flags.writable(), flags.configurable()));
    }

    public boolean hasGlobalProperty(String name) {
        return bindings != null && bindings.containsKey(name);
    }

    public JsObject.PropertyFlags globalPropertyFlags(String name) {
        final var binding = bindings == null ? null : bindings.get(name);
        return binding == null
                ? null
                : new JsObject.PropertyFlags(binding.writable, binding.enumerable, binding.configurable);
    }

    public boolean deleteGlobal(String name) {
        final var binding = bindings == null ? null : bindings.get(name);
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
            if (env.parent == null && env.globalLexicalBindings != null) {
                final var lexical = env.globalLexicalBindings.get(name);
                if (lexical != null) {
                    return lexical;
                }
            }
            final var binding = env.bindings == null ? null : env.bindings.get(name);
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
