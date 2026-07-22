package org.techhouse.simplejs.values;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class JsObject extends JsValue {
    private final Map<String, JsValue> properties = new LinkedHashMap<>();
    private boolean frozen;
    private JsClass klass;
    private Map<String, JsValue> privateFields;
    private Map<JsSymbol, JsValue> symbolProperties;
    private JsObject proto;
    private Map<String, JsValue> accessorGetters;
    private Map<String, JsValue> accessorSetters;

    public JsValue get(String key) {
        final var value = properties.get(key);
        return value == null ? JsUndefined.getInstance() : value;
    }

    public void set(String key, JsValue value) {
        if (frozen) {
            return;
        }
        properties.put(key, value);
    }

    public boolean has(String key) {
        return properties.containsKey(key);
    }

    public boolean delete(String key) {
        if (frozen) {
            return false;
        }
        properties.remove(key);
        return true;
    }

    public void freeze() {
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public Set<String> keys() {
        return properties.keySet();
    }

    public Map<String, JsValue> getProperties() {
        return properties;
    }

    public JsClass getKlass() {
        return klass;
    }

    public void setKlass(JsClass klass) {
        this.klass = klass;
    }

    public JsValue getPrivate(String key) {
        return privateFields == null ? null : privateFields.get(key);
    }

    public void setPrivate(String key, JsValue value) {
        if (privateFields == null) {
            privateFields = new LinkedHashMap<>();
        }
        privateFields.put(key, value);
    }

    public boolean hasPrivate(String key) {
        return privateFields != null && privateFields.containsKey(key);
    }

    public JsValue getSymbol(JsSymbol key) {
        final var value = symbolProperties == null ? null : symbolProperties.get(key);
        return value == null ? JsUndefined.getInstance() : value;
    }

    public void setSymbol(JsSymbol key, JsValue value) {
        if (frozen) {
            return;
        }
        if (symbolProperties == null) {
            symbolProperties = new LinkedHashMap<>();
        }
        symbolProperties.put(key, value);
    }

    public boolean hasSymbol(JsSymbol key) {
        return symbolProperties != null && symbolProperties.containsKey(key);
    }

    public JsObject getProto() {
        return proto;
    }

    public void setProto(JsObject proto) {
        this.proto = proto;
    }

    public void defineAccessor(String key, JsValue getter, JsValue setter) {
        if (getter != null) {
            if (accessorGetters == null) {
                accessorGetters = new LinkedHashMap<>();
            }
            accessorGetters.put(key, getter);
        }
        if (setter != null) {
            if (accessorSetters == null) {
                accessorSetters = new LinkedHashMap<>();
            }
            accessorSetters.put(key, setter);
        }
    }

    public JsValue getAccessorGetter(String key) {
        return accessorGetters == null ? null : accessorGetters.get(key);
    }

    public JsValue getAccessorSetter(String key) {
        return accessorSetters == null ? null : accessorSetters.get(key);
    }

    public boolean hasAccessor(String key) {
        return (accessorGetters != null && accessorGetters.containsKey(key))
                || (accessorSetters != null && accessorSetters.containsKey(key));
    }
}
