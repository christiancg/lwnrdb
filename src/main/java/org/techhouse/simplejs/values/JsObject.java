package org.techhouse.simplejs.values;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class JsObject extends JsValue {
    private final Map<String, JsValue> properties = new LinkedHashMap<>();
    private boolean frozen;

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
}
