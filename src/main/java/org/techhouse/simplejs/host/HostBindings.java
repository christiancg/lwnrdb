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
}
