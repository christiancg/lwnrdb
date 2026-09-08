package org.techhouse.simplejs.host;

import java.util.function.Consumer;
import org.techhouse.ejson.elements.JsonObject;

public interface HostBindings {
    JsonObject args();

    DatabaseAccess database();

    Consumer<String> console();

    ResourceLimits limits();

    default boolean strictScriptGoal() {
        final var limits = limits();
        return limits != null && limits.strictScriptGoal();
    }

    default ModuleResolver moduleResolver() {
        return null;
    }

    default NetworkAccess network() {
        return null;
    }

    default CancellationToken cancellation() {
        return null;
    }

    default java.time.ZoneId timeZone() {
        return java.time.ZoneId.systemDefault();
    }

    default java.util.Locale locale() {
        return java.util.Locale.getDefault();
    }
}
