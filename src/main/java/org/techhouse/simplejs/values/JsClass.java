package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.nodes.FieldDefinition;

public final class JsClass extends JsValue {
    private final String name;
    private final JsClass superClass;
    private JsFunction constructor;
    private final Map<String, JsFunction> instanceMethods = new LinkedHashMap<>();
    private final Map<String, JsFunction> instanceGetters = new LinkedHashMap<>();
    private final Map<String, JsFunction> instanceSetters = new LinkedHashMap<>();
    private final Map<String, JsFunction> staticMethods = new LinkedHashMap<>();
    private final Map<String, JsFunction> staticGetters = new LinkedHashMap<>();
    private final Map<String, JsFunction> staticSetters = new LinkedHashMap<>();
    private final Map<String, JsValue> staticProps = new LinkedHashMap<>();
    private final List<FieldDefinition> instanceFields = new ArrayList<>();
    private final Map<String, JsFunction> privateInstanceMethods = new LinkedHashMap<>();
    private final Map<String, JsFunction> privateInstanceGetters = new LinkedHashMap<>();
    private final Map<String, JsFunction> privateInstanceSetters = new LinkedHashMap<>();
    private final Environment methodScope;

    public JsClass(String name, JsClass superClass, Environment methodScope) {
        this.name = name;
        this.superClass = superClass;
        this.methodScope = methodScope;
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
        selectAccessor(instanceMethods, instanceGetters, instanceSetters, kind).put(key, fn);
    }

    public void addStaticMethod(String key, String kind, JsFunction fn) {
        selectAccessor(staticMethods, staticGetters, staticSetters, kind).put(key, fn);
    }

    public void addPrivateInstanceMethod(String key, String kind, JsFunction fn) {
        selectAccessor(privateInstanceMethods, privateInstanceGetters, privateInstanceSetters, kind).put(key, fn);
    }

    private static Map<String, JsFunction> selectAccessor(Map<String, JsFunction> methods,
            Map<String, JsFunction> getters, Map<String, JsFunction> setters, String kind) {
        return switch (kind) {
            case "get" -> getters;
            case "set" -> setters;
            default -> methods;
        };
    }

    public void addInstanceField(FieldDefinition field) {
        instanceFields.add(field);
    }

    public List<FieldDefinition> getInstanceFields() {
        return instanceFields;
    }

    public void setStaticProp(String key, JsValue value) {
        staticProps.put(key, value);
    }

    public JsValue getStaticProp(String key) {
        return staticProps.get(key);
    }

    public boolean hasStaticProp(String key) {
        return staticProps.containsKey(key);
    }

    public JsFunction findInstanceMethod(String key) {
        return findInChain(cls -> cls.instanceMethods, key);
    }

    public JsFunction findInstanceGetter(String key) {
        return findInChain(cls -> cls.instanceGetters, key);
    }

    public JsFunction findInstanceSetter(String key) {
        return findInChain(cls -> cls.instanceSetters, key);
    }

    public JsFunction findStaticMethod(String key) {
        return findInChain(cls -> cls.staticMethods, key);
    }

    public JsFunction findStaticGetter(String key) {
        return findInChain(cls -> cls.staticGetters, key);
    }

    public JsFunction findStaticSetter(String key) {
        return findInChain(cls -> cls.staticSetters, key);
    }

    private JsFunction findInChain(Function<JsClass, Map<String, JsFunction>> table, String key) {
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
