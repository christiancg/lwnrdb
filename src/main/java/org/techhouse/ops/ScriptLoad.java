package org.techhouse.ops;

import org.techhouse.ioc.IocContainer;

public class ScriptLoad {
    private static final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);

    public int current() {
        return registry.size();
    }
}
