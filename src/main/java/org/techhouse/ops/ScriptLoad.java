package org.techhouse.ops;

import org.techhouse.ioc.IocContainer;

public class ScriptLoad {
    private static final ScriptRunRegistry sharedRegistry = IocContainer.get(ScriptRunRegistry.class);

    private final ScriptRunRegistry registry;

    @SuppressWarnings("unused")
    public ScriptLoad() {
        this(sharedRegistry);
    }

    public ScriptLoad(ScriptRunRegistry registry) {
        this.registry = registry;
    }

    public int current() {
        return registry.size();
    }
}
