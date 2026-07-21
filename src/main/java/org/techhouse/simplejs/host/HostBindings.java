package org.techhouse.simplejs.host;

import java.util.function.Consumer;
import org.techhouse.ejson.elements.JsonObject;

public interface HostBindings {
    JsonObject args();

    DatabaseAccess database();

    Consumer<String> console();

    ResourceLimits limits();
}
