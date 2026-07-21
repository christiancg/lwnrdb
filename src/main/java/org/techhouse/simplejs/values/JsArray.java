package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsArray extends JsValue {
    private final List<JsValue> elements = new ArrayList<>();
    private Map<String, JsValue> ownProperties;
    private boolean frozen;

    public JsArray() {
    }

    public JsArray(List<JsValue> initial) {
        elements.addAll(initial);
    }

    public JsValue get(int index) {
        if (index < 0 || index >= elements.size()) {
            return JsUndefined.getInstance();
        }
        return elements.get(index);
    }

    public void set(int index, JsValue value) {
        if (frozen) {
            return;
        }
        while (elements.size() <= index) {
            elements.add(JsUndefined.getInstance());
        }
        elements.set(index, value);
    }

    public void push(JsValue value) {
        if (frozen) {
            return;
        }
        elements.add(value);
    }

    public JsValue getProperty(String key) {
        return ownProperties == null ? null : ownProperties.get(key);
    }

    public void setProperty(String key, JsValue value) {
        if (frozen) {
            return;
        }
        if (ownProperties == null) {
            ownProperties = new LinkedHashMap<>();
        }
        ownProperties.put(key, value);
    }

    public boolean hasProperty(String key) {
        return ownProperties != null && ownProperties.containsKey(key);
    }

    public void freeze() {
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public int length() {
        return elements.size();
    }

    public List<JsValue> getElements() {
        return elements;
    }
}
