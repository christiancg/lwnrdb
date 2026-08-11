package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsArray extends JsValue {
    private static final JsValue HOLE = JsUndefined.getHole();

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

    public boolean isHole(int index) {
        return index >= 0 && index < elements.size() && elements.get(index) == HOLE;
    }

    public void pushHole() {
        if (!frozen) {
            elements.add(HOLE);
        }
    }

    public int removeHoles() {
        var removed = 0;
        final var iterator = elements.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() == HOLE) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public void set(int index, JsValue value) {
        if (frozen) {
            return;
        }
        while (elements.size() <= index) {
            elements.add(HOLE);
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

    public void setLength(int length) {
        if (frozen) {
            return;
        }
        while (elements.size() > length) {
            elements.removeLast();
        }
        while (elements.size() < length) {
            elements.add(HOLE);
        }
    }

    public List<JsValue> getElements() {
        return elements;
    }
}
