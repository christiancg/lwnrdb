package org.techhouse.simplejs.host;

import org.techhouse.simplejs.CompiledScript;

public record ResolvedModule(String moduleId, String source, CompiledScript compiled, String displayName,
        boolean sourceHidden) {
    public ResolvedModule {
        displayName = displayName == null || displayName.isEmpty() ? moduleId : displayName;
    }

    public ResolvedModule(String moduleId, String source, CompiledScript compiled, String displayName) {
        this(moduleId, source, compiled, displayName, false);
    }

    public ResolvedModule(String moduleId, String source) {
        this(moduleId, source, null, null);
    }

    public static ResolvedModule withHiddenSource(String moduleId, String source, String displayName) {
        return new ResolvedModule(moduleId, source, null, displayName, true);
    }
}
