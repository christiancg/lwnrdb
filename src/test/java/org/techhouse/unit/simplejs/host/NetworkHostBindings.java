package org.techhouse.unit.simplejs.host;

import java.util.function.Consumer;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.NetworkAccess;
import org.techhouse.simplejs.host.ResourceLimits;

// A HostBindings double that exposes a NetworkAccess so fetch tests can opt into (and mock) the network.
public record NetworkHostBindings(JsonObject args, DatabaseAccess database, Consumer<String> console,
        ResourceLimits limits, NetworkAccess network) implements HostBindings {

    public static NetworkHostBindings of(NetworkAccess network, ResourceLimits limits) {
        return new NetworkHostBindings(new JsonObject(), null, null, limits, network);
    }
}
