package org.techhouse.simplejs.values;

import static org.techhouse.simplejs.values.JsObject.PropertyFlags.HIDDEN;
import static org.techhouse.simplejs.values.JsObject.PropertyFlags.TAG;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.nodes.FieldDefinition;

public final class JsClass extends JsValue {

    private String name;
    private final JsClass superClass;
    private JsFunction constructor;
    private final JsObject prototype = new JsObject();
    private final JsObject staticOwner = new JsObject();
    private final List<InstanceField> instanceFields = new ArrayList<>();
    private final Map<String, PrivateName> privateNames = new LinkedHashMap<>();
    private final Map<PrivateName, JsFunction> privateInstanceMethods = new IdentityHashMap<>();
    private final Map<PrivateName, JsFunction> privateInstanceGetters = new IdentityHashMap<>();
    private final Map<PrivateName, JsFunction> privateInstanceSetters = new IdentityHashMap<>();
    private final Map<PrivateName, JsFunction> privateStaticMethods = new IdentityHashMap<>();
    private final Map<PrivateName, JsFunction> privateStaticGetters = new IdentityHashMap<>();
    private final Map<PrivateName, JsFunction> privateStaticSetters = new IdentityHashMap<>();
    private final Map<PrivateName, JsValue> privateStaticFields = new IdentityHashMap<>();
    private final Map<JsSymbol, JsFunction> instanceSymbolMethods = new LinkedHashMap<>();
    private final Map<JsSymbol, JsFunction> instanceSymbolGetters = new LinkedHashMap<>();
    private final Map<JsSymbol, JsFunction> instanceSymbolSetters = new LinkedHashMap<>();
    private final Environment methodScope;
    private JsValue superConstructor;
    private boolean nullHeritage;
    private JsValue proto;
    private boolean explicitNameProperty;
    private String sourceText;

    public JsClass(String name, JsClass superClass, Environment methodScope) {
        this.name = name;
        this.superClass = superClass;
        this.methodScope = methodScope;
        if (superClass != null) {
            prototype.setProto(superClass.getPrototype());
            this.proto = superClass;
        }
        prototype.defineValue("constructor", this);
        prototype.setFlags("constructor", JsObject.PropertyFlags.HIDDEN);
        staticOwner.defineValue("length", new JsNumber(0));
        staticOwner.setFlags("length", TAG);
        staticOwner.defineValue("name", new JsString(name == null ? "" : name));
        staticOwner.setFlags("name", TAG);
        staticOwner.defineValue("prototype", prototype);
        staticOwner.setFlags("prototype", new JsObject.PropertyFlags(false, false, false));
    }

    public JsObject getPrototype() {
        return prototype;
    }

    @Override
    public JsValue getProto() {
        return proto;
    }

    @Override
    public void setProto(JsValue proto) {
        this.proto = proto;
    }

    public JsObject getStaticOwner() {
        return staticOwner;
    }

    @Override
    public PropertyTable ownProperties() {
        return staticOwner.ownProperties();
    }

    public JsNativeFunction getNativeSuperClass() {
        return superConstructor instanceof JsNativeFunction nativeSuper ? nativeSuper : null;
    }

    public JsValue getSuperConstructor() {
        return superConstructor;
    }

    public void setSuperConstructor(JsValue superConstructor, JsValue parentPrototype) {
        this.superConstructor = superConstructor;
        this.proto = superConstructor;
        if (superConstructor != null && superClass == null && parentPrototype != null) {
            prototype.setProto(parentPrototype);
        }
    }

    public void markNullHeritage() {
        this.nullHeritage = true;
    }

    public boolean hasNullHeritage() {
        return nullHeritage;
    }

    public boolean isDerived() {
        return superClass != null || superConstructor != null || nullHeritage;
    }

    public JsNativeFunction findNativeSuperClass() {
        for (var cls = this; cls != null; cls = cls.superClass) {
            if (cls.getNativeSuperClass() != null) {
                return cls.getNativeSuperClass();
            }
        }
        return null;
    }

    public String getName() {
        return name;
    }

    public String getSourceText() {
        return sourceText;
    }

    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
    }

    public void setInferredName(String inferred) {
        if (name == null && !explicitNameProperty) {
            name = inferred;
            staticOwner.defineValue("name", new JsString(inferred));
        }
    }

    public JsClass getSuperClass() {
        return superClass;
    }

    public Environment getMethodScope() {
        return methodScope;
    }

    public JsFunction getConstructor() {
        return constructor;
    }

    public void setConstructor(JsFunction constructor) {
        this.constructor = constructor;
        staticOwner.defineValue("length", FunctionProtoBuiltins.metadata(constructor, "length"));
    }

    public void addInstanceMethod(String key, String kind, JsFunction fn) {
        if ("get".equals(kind)) {
            prototype.defineAccessor(key, fn, null);
        } else if ("set".equals(kind)) {
            prototype.defineAccessor(key, null, fn);
        } else {
            prototype.defineValue(key, fn);
        }
        prototype.setFlags(key, JsObject.PropertyFlags.HIDDEN);
    }

    public void addStaticMethod(String key, String kind, JsFunction fn) {
        if ("get".equals(kind)) {
            staticOwner.defineAccessor(key, fn, null);
        } else if ("set".equals(kind)) {
            staticOwner.defineAccessor(key, null, fn);
        } else {
            staticOwner.defineValue(key, fn);
        }
        staticOwner.setFlags(key, HIDDEN);
        if ("name".equals(key)) {
            explicitNameProperty = true;
        }
    }

    public PrivateName declarePrivateName(String name) {
        return privateNames.computeIfAbsent(name, key -> new PrivateName("#" + key));
    }

    public PrivateName privateNameFor(String name) {
        return privateNames.get(name);
    }

    public void addPrivateInstanceMethod(PrivateName key, String kind, JsFunction fn) {
        selectAccessor(privateInstanceMethods, privateInstanceGetters, privateInstanceSetters, kind).put(key, fn);
    }

    public void addPrivateStaticMethod(PrivateName key, String kind, JsFunction fn) {
        selectAccessor(privateStaticMethods, privateStaticGetters, privateStaticSetters, kind).put(key, fn);
    }

    public JsFunction getPrivateStaticMethod(PrivateName key) {
        return privateStaticMethods.get(key);
    }

    public JsFunction getPrivateStaticGetter(PrivateName key) {
        return privateStaticGetters.get(key);
    }

    public JsFunction getPrivateStaticSetter(PrivateName key) {
        return privateStaticSetters.get(key);
    }

    // PrivateFieldAdd: the name must not already be present and the receiver must be extensible.
    public boolean addPrivateStaticField(PrivateName key, JsValue value) {
        if (privateStaticFields.containsKey(key) || !staticOwner.isExtensible()) {
            return false;
        }
        privateStaticFields.put(key, value);
        return true;
    }

    public void setPrivateStaticField(PrivateName key, JsValue value) {
        privateStaticFields.put(key, value);
    }

    public JsValue getPrivateStaticField(PrivateName key) {
        return privateStaticFields.get(key);
    }

    public boolean hasPrivateStaticField(PrivateName key) {
        return privateStaticFields.containsKey(key);
    }

    public boolean declaresStaticPrivate(PrivateName key) {
        return privateStaticMethods.containsKey(key) || privateStaticGetters.containsKey(key)
                || privateStaticSetters.containsKey(key) || privateStaticFields.containsKey(key);
    }

    private static <K> Map<K, JsFunction> selectAccessor(Map<K, JsFunction> methods, Map<K, JsFunction> getters,
            Map<K, JsFunction> setters, String kind) {
        return switch (kind) {
            case "get" -> getters;
            case "set" -> setters;
            default -> methods;
        };
    }

    public void addInstanceSymbolMethod(JsSymbol key, String kind, JsFunction fn) {
        selectAccessor(instanceSymbolMethods, instanceSymbolGetters, instanceSymbolSetters, kind).put(key, fn);
    }

    public void addStaticSymbolMethod(JsSymbol key, String kind, JsFunction fn) {
        if ("get".equals(kind)) {
            staticOwner.defineSymbolAccessor(key, fn, null);
        } else if ("set".equals(kind)) {
            staticOwner.defineSymbolAccessor(key, null, fn);
        } else {
            staticOwner.setSymbol(key, fn);
        }
    }

    public JsFunction findInstanceSymbolMethod(JsSymbol key) {
        return findInChain(cls -> cls.instanceSymbolMethods, key);
    }

    public JsFunction findInstanceSymbolGetter(JsSymbol key) {
        return findInChain(cls -> cls.instanceSymbolGetters, key);
    }

    public JsFunction findInstanceSymbolSetter(JsSymbol key) {
        return findInChain(cls -> cls.instanceSymbolSetters, key);
    }

    private JsClass staticSymbolOwnerFor(JsSymbol key) {
        for (var cls = this; cls != null; cls = cls.superClass) {
            if (cls.staticOwner.hasSymbol(key) || cls.staticOwner.hasSymbolAccessor(key)) {
                return cls;
            }
        }
        return null;
    }

    public JsFunction findStaticSymbolMethod(JsSymbol key) {
        final var owner = staticSymbolOwnerFor(key);
        if (owner == null || owner.staticOwner.hasSymbolAccessor(key)) {
            return null;
        }
        return owner.staticOwner.getSymbol(key) instanceof JsFunction fn ? fn : null;
    }

    public JsFunction findStaticSymbolGetter(JsSymbol key) {
        final var owner = staticSymbolOwnerFor(key);
        return owner == null ? null : (JsFunction) owner.staticOwner.getSymbolAccessorGetter(key);
    }

    public JsFunction findStaticSymbolSetter(JsSymbol key) {
        final var owner = staticSymbolOwnerFor(key);
        return owner == null ? null : (JsFunction) owner.staticOwner.getSymbolAccessorSetter(key);
    }

    public void setStaticSymbolProp(JsSymbol key, JsValue value) {
        staticOwner.setSymbol(key, value);
    }

    public JsValue getStaticSymbolProp(JsSymbol key) {
        return staticOwner.getSymbol(key);
    }

    public boolean hasStaticSymbolProp(JsSymbol key) {
        return staticOwner.hasSymbol(key);
    }

    public record InstanceField(FieldDefinition definition, JsValue key) {
    }

    public void addInstanceField(FieldDefinition field, JsValue key) {
        instanceFields.add(new InstanceField(field, key));
    }

    public List<InstanceField> getInstanceFields() {
        return instanceFields;
    }

    public void setStaticProp(String key, JsValue value) {
        staticOwner.set(key, value);
    }

    public void defineStaticField(String key, JsValue value) {
        staticOwner.defineValue(key, value);
        staticOwner.setFlags(key, JsObject.PropertyFlags.DEFAULT);
        if ("name".equals(key)) {
            explicitNameProperty = true;
        }
    }

    public JsValue getStaticProp(String key) {
        return staticOwner.get(key);
    }

    public boolean hasStaticProp(String key) {
        return staticOwner.has(key) || staticOwner.hasAccessor(key);
    }

    private JsClass staticOwnerFor(String key) {
        for (var cls = this; cls != null; cls = cls.superClass) {
            if (cls.staticOwner.has(key) || cls.staticOwner.hasAccessor(key)) {
                return cls;
            }
        }
        return null;
    }

    public JsFunction findStaticMethod(String key) {
        final var owner = staticOwnerFor(key);
        if (owner == null || owner.staticOwner.hasAccessor(key)) {
            return null;
        }
        return owner.staticOwner.get(key) instanceof JsFunction fn ? fn : null;
    }

    public JsFunction findStaticGetter(String key) {
        final var owner = staticOwnerFor(key);
        return owner == null ? null : (JsFunction) owner.staticOwner.getAccessorGetter(key);
    }

    public JsFunction findStaticSetter(String key) {
        final var owner = staticOwnerFor(key);
        return owner == null ? null : (JsFunction) owner.staticOwner.getAccessorSetter(key);
    }

    private <K> JsFunction findInChain(Function<JsClass, Map<K, JsFunction>> table, K key) {
        for (var cls = this; cls != null; cls = cls.superClass) {
            final var fn = table.apply(cls).get(key);
            if (fn != null) {
                return fn;
            }
        }
        return null;
    }

    public JsFunction getPrivateInstanceMethod(PrivateName key) {
        return privateInstanceMethods.get(key);
    }

    public JsFunction getPrivateInstanceGetter(PrivateName key) {
        return privateInstanceGetters.get(key);
    }

    public JsFunction getPrivateInstanceSetter(PrivateName key) {
        return privateInstanceSetters.get(key);
    }

    public boolean declaresPrivate(PrivateName key) {
        return privateInstanceMethods.containsKey(key) || privateInstanceGetters.containsKey(key)
                || privateInstanceSetters.containsKey(key);
    }

    public boolean hasPrivateInstanceBrand() {
        return !privateInstanceMethods.isEmpty() || !privateInstanceGetters.isEmpty()
                || !privateInstanceSetters.isEmpty();
    }

    public boolean isSubclassOf(JsClass other) {
        for (var cls = this; cls != null; cls = cls.superClass) {
            if (cls == other) {
                return true;
            }
        }
        return false;
    }
}
