package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.internal.Environment;

public final class JsArguments extends JsValue {
    private final List<JsValue> values;
    private final List<String> mappedNames;
    private final Environment env;

    public JsArguments(List<JsValue> args, List<String> mappedNames, Environment env) {
        this.values = new ArrayList<>(args);
        this.mappedNames = mappedNames;
        this.env = env;
    }

    public int length() {
        return values.size();
    }

    public JsValue get(int index) {
        if (index < 0 || index >= values.size()) {
            return JsUndefined.getInstance();
        }
        if (isMapped(index)) {
            return env.get(mappedNames.get(index));
        }
        return values.get(index);
    }

    public void set(int index, JsValue value) {
        if (index < 0) {
            return;
        }
        if (index < values.size() && isMapped(index)) {
            env.assign(mappedNames.get(index), value);
            return;
        }
        while (values.size() <= index) {
            values.add(JsUndefined.getInstance());
        }
        values.set(index, value);
    }

    public List<JsValue> snapshot() {
        final var result = new ArrayList<JsValue>();
        for (var i = 0; i < values.size(); i++) {
            result.add(get(i));
        }
        return result;
    }

    private boolean isMapped(int index) {
        return env != null && mappedNames != null && index < mappedNames.size() && mappedNames.get(index) != null;
    }
}
