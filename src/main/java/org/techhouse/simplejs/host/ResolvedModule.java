package org.techhouse.simplejs.host;

import org.techhouse.simplejs.CompiledScript;

public record ResolvedModule(String moduleId, String source, CompiledScript compiled, String displayName) {
    public ResolvedModule {
        displayName = displayName == null || displayName.isEmpty() ? moduleId : displayName;
    }

    public ResolvedModule(String moduleId, String source) {
        this(moduleId, source, null, null);
    }
}
