package org.techhouse.simplejs.values;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsMap extends JsValue {
    public record Entry(JsValue key, JsValue value) {
    }

    private final Map<Object, Entry> entries = new LinkedHashMap<>();
    private final boolean weak;

    public JsMap() {
        this(false);
    }

    public JsMap(boolean weak) {
        this.weak = weak;
    }

    public boolean isWeak() {
        return weak;
    }

    public JsValue get(JsValue key) {
        final var entry = entries.get(SameValueZero.key(key));
        return entry == null ? JsUndefined.getInstance() : entry.value();
    }

    public void set(JsValue key, JsValue value) {
        entries.put(SameValueZero.key(key), new Entry(key, value));
    }

    public boolean has(JsValue key) {
        return entries.containsKey(SameValueZero.key(key));
    }

    public boolean delete(JsValue key) {
        return entries.remove(SameValueZero.key(key)) != null;
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    public Collection<Entry> entries() {
        return entries.values();
    }
}
