package org.techhouse.simplejs.values;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsSet extends JsValue {
    private final Map<Object, JsValue> members = new LinkedHashMap<>();
    private final boolean weak;

    public JsSet() {
        this(false);
    }

    public JsSet(boolean weak) {
        this.weak = weak;
    }

    public boolean isWeak() {
        return weak;
    }

    public void add(JsValue value) {
        members.putIfAbsent(SameValueZero.key(value), value);
    }

    public boolean has(JsValue value) {
        return members.containsKey(SameValueZero.key(value));
    }

    public boolean delete(JsValue value) {
        return members.remove(SameValueZero.key(value)) != null;
    }

    public void clear() {
        members.clear();
    }

    public int size() {
        return members.size();
    }

    public Collection<JsValue> values() {
        return members.values();
    }
}
