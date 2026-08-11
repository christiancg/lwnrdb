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
    private boolean sealed;
    private boolean extensible = true;

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

    public boolean set(int index, JsValue value) {
        if (frozen || (!extensible && index >= elements.size())) {
            return false;
        }
        while (elements.size() <= index) {
            elements.add(HOLE);
        }
        elements.set(index, value);
        return true;
    }

    public boolean push(JsValue value) {
        if (frozen || !extensible) {
            return false;
        }
        elements.add(value);
        return true;
    }

    public JsValue getProperty(String key) {
        return ownProperties == null ? null : ownProperties.get(key);
    }

    public boolean setProperty(String key, JsValue value) {
        if (frozen || (!extensible && !hasProperty(key))) {
            return false;
        }
        if (ownProperties == null) {
            ownProperties = new LinkedHashMap<>();
        }
        ownProperties.put(key, value);
        return true;
    }

    public boolean hasProperty(String key) {
        return ownProperties != null && ownProperties.containsKey(key);
    }

    public void freeze() {
        frozen = true;
        sealed = true;
        extensible = false;
    }

    public boolean isFrozen() {
        return frozen || (!extensible && elements.isEmpty());
    }

    public void seal() {
        sealed = true;
        extensible = false;
    }

    public boolean isSealed() {
        return sealed || isFrozen();
    }

    public void preventExtensions() {
        extensible = false;
    }

    public boolean isExtensible() {
        return extensible;
    }

    public int length() {
        return elements.size();
    }

    public boolean setLength(int length) {
        if (frozen || (sealed && length != elements.size())) {
            return false;
        }
        if (!extensible && length > elements.size()) {
            return false;
        }
        while (elements.size() > length) {
            elements.removeLast();
        }
        while (elements.size() < length) {
            elements.add(HOLE);
        }
        return true;
    }

    public List<JsValue> getElements() {
        return elements;
    }
}
