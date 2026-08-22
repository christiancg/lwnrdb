package org.techhouse.simplejs.host;

import java.util.function.Consumer;
import org.techhouse.ejson.elements.JsonObject;

public interface HostBindings {
    JsonObject args();

    DatabaseAccess database();

    Consumer<String> console();

    ResourceLimits limits();

    // Null-safe view of the parse goal, shared by the top-level entrypoint and every imported module
    // so a script and its imports are parsed under the same rules.
    default boolean strictScriptGoal() {
        final var limits = limits();
        return limits != null && limits.strictScriptGoal();
    }

    // Module resolution beyond the "args"/"db"/"script" built-ins is opt-in: a null resolver means a
    // bare specifier is unresolvable, which is the standalone behaviour.
    default ModuleResolver moduleResolver() {
        return null;
    }

    // Network access is opt-in and off by default: a null binding means `fetch` is unavailable.
    default NetworkAccess network() {
        return null;
    }

    // The JVM defaults keep every existing embedding (and the test262 worker) on today's behaviour;
    // only a binding that overrides these makes a script answer the same on every cluster node.
    default java.time.ZoneId timeZone() {
        return java.time.ZoneId.systemDefault();
    }

    default java.util.Locale locale() {
        return java.util.Locale.getDefault();
    }
}
