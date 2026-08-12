package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.nodes.FieldDefinition;

public final class JsClass extends JsValue {
    private static final JsObject.PropertyFlags HIDDEN = new JsObject.PropertyFlags(true, false, true);

    private final String name;
    private final JsClass superClass;
    private JsFunction constructor;
    private final JsObject prototype = new JsObject();
    private final JsObject staticOwner = new JsObject();
    private final List<FieldDefinition> instanceFields = new ArrayList<>();
    private final Map<String, JsFunction> privateInstanceMethods = new LinkedHashMap<>();
    private final Map<String, JsFunction> privateInstanceGetters = new LinkedHashMap<>();
    private final Map<String, JsFunction> privateInstanceSetters = new LinkedHashMap<>();
    private final Map<String, JsFunction> privateStaticMethods = new LinkedHashMap<>();
    private final Map<String, JsFunction> privateStaticGetters = new LinkedHashMap<>();
    private final Map<String, JsFunction> privateStaticSetters = new LinkedHashMap<>();
    private final Map<String, JsValue> privateStaticFields = new LinkedHashMap<>();
    private final Map<JsSymbol, JsFunction> instanceSymbolMethods = new LinkedHashMap<>();
    private final Map<JsSymbol, JsFunction> instanceSymbolGetters = new LinkedHashMap<>();
    private final Map<JsSymbol, JsFunction> instanceSymbolSetters = new LinkedHashMap<>();
    private final Environment methodScope;
    private JsNativeFunction nativeSuperClass;

    public JsClass(String name, JsClass superClass, Environment methodScope) {
        this.name = name;
        this.superClass = superClass;
        this.methodScope = methodScope;
        if (superClass != null) {
            prototype.setProto(superClass.getPrototype());
        }
        prototype.defineValue("constructor", this);
        prototype.setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
    }

    public JsObject getPrototype() {
        return prototype;
    }

    public JsObject getStaticOwner() {
        return staticOwner;
    }

    public JsNativeFunction getNativeSuperClass() {
        return nativeSuperClass;
    }

    public void setNativeSuperClass(JsNativeFunction nativeSuperClass) {
        this.nativeSuperClass = nativeSuperClass;
        if (nativeSuperClass != null && superClass == null) {
            prototype.setProto(nativeSuperClass.getPrototype());
        }
    }

    public JsNativeFunction findNativeSuperClass() {
        for (var cls = this; cls != null; cls = cls.superClass) {
            if (cls.nativeSuperClass != null) {
                return cls.nativeSuperClass;
            }
        }
        return null;
    }

    public String getName() {
        return name;
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
    }

    public void addInstanceMethod(String key, String kind, JsFunction fn) {
        if ("get".equals(kind)) {
            prototype.defineAccessor(key, fn, null);
        } else if ("set".equals(kind)) {
            prototype.defineAccessor(key, null, fn);
        } else {
            prototype.defineValue(key, fn);
        }
        prototype.setFlags(key, new JsObject.PropertyFlags(true, false, true));
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
    }

    public void addPrivateInstanceMethod(String key, String kind, JsFunction fn) {
        selectAccessor(privateInstanceMethods, privateInstanceGetters, privateInstanceSetters, kind).put(key, fn);
    }

    public void addPrivateStaticMethod(String key, String kind, JsFunction fn) {
        selectAccessor(privateStaticMethods, privateStaticGetters, privateStaticSetters, kind).put(key, fn);
    }

    public JsFunction getPrivateStaticMethod(String key) {
        return privateStaticMethods.get(key);
    }

    public JsFunction getPrivateStaticGetter(String key) {
        return privateStaticGetters.get(key);
    }

    public JsFunction getPrivateStaticSetter(String key) {
        return privateStaticSetters.get(key);
    }

    public void setPrivateStaticField(String key, JsValue value) {
        privateStaticFields.put(key, value);
    }

    public JsValue getPrivateStaticField(String key) {
        return privateStaticFields.get(key);
    }

    public boolean hasPrivateStaticField(String key) {
        return privateStaticFields.containsKey(key);
    }

    public boolean declaresStaticPrivate(String key) {
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

    public void addInstanceField(FieldDefinition field) {
        instanceFields.add(field);
    }

    public List<FieldDefinition> getInstanceFields() {
        return instanceFields;
    }

    public void setStaticProp(String key, JsValue value) {
        staticOwner.set(key, value);
    }

    public JsValue getStaticProp(String key) {
        return staticOwner.get(key);
    }

    public boolean hasStaticProp(String key) {
        return staticOwner.has(key);
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

    public JsFunction getPrivateInstanceMethod(String key) {
        return privateInstanceMethods.get(key);
    }

    public JsFunction getPrivateInstanceGetter(String key) {
        return privateInstanceGetters.get(key);
    }

    public JsFunction getPrivateInstanceSetter(String key) {
        return privateInstanceSetters.get(key);
    }

    public boolean declaresPrivate(String key) {
        return privateInstanceMethods.containsKey(key) || privateInstanceGetters.containsKey(key)
                || privateInstanceSetters.containsKey(key);
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
