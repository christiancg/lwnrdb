package org.techhouse.simplejs.values;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class JsObject extends JsValue {
    private final Map<String, JsValue> properties = new LinkedHashMap<>();

    public JsValue get(String key) {
        final var value = properties.get(key);
        return value == null ? JsUndefined.getInstance() : value;
    }

    public void set(String key, JsValue value) {
        properties.put(key, value);
    }

    public boolean has(String key) {
        return properties.containsKey(key);
    }

    public boolean delete(String key) {
        properties.remove(key);
        return true;
    }

    public Set<String> keys() {
        return properties.keySet();
    }

    public Map<String, JsValue> getProperties() {
        return properties;
    }
}
