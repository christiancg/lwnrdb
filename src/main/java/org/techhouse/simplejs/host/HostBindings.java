package org.techhouse.simplejs.host;

import java.util.function.Consumer;
import org.techhouse.ejson.elements.JsonObject;

public interface HostBindings {
    JsonObject args();

    DatabaseAccess database();

    Consumer<String> console();

    ResourceLimits limits();

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
