package org.techhouse.simplejs.values;

import org.techhouse.simplejs.internal.Environment;

public final class JsGlobalObject extends JsValue {
    private final Environment env;

    public JsGlobalObject(Environment env) {
        this.env = env;
    }

    public Environment getEnv() {
        return env;
    }
}
