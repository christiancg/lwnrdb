package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.Environment;

public final class JsGlobalObject extends JsValue {
    private final Environment env;
    // Own string keys of the global object live in the Environment, never here; the table only
    // carries what the Environment cannot hold (symbol keys and accessor redefinitions).
    private PropertyTable table;

    public JsGlobalObject(Environment env) {
        this.env = env;
    }

    public Environment getEnv() {
        return env;
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}
