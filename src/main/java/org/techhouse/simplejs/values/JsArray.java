package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.List;

public final class JsArray extends JsValue {
    private final List<JsValue> elements = new ArrayList<>();

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
        while (elements.size() <= index) {
            elements.add(JsUndefined.getInstance());
        }
        elements.set(index, value);
    }

    public void push(JsValue value) {
        elements.add(value);
    }

    public int length() {
        return elements.size();
    }

    public List<JsValue> getElements() {
        return elements;
    }
}
