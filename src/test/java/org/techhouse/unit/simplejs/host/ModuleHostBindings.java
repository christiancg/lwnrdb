package org.techhouse.unit.simplejs.host;

import java.util.function.Consumer;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.host.DatabaseAccess;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ModuleResolver;
import org.techhouse.simplejs.host.ResourceLimits;

// A HostBindings double that exposes a ModuleResolver so module-resolution tests can supply sources.
public record ModuleHostBindings(JsonObject args, DatabaseAccess database, Consumer<String> console,
        ResourceLimits limits, ModuleResolver moduleResolver) implements HostBindings {

    public static ModuleHostBindings of(ModuleResolver resolver, ResourceLimits limits) {
        return new ModuleHostBindings(new JsonObject(), null, null, limits, resolver);
    }

    public static ModuleHostBindings of(ModuleResolver resolver, ResourceLimits limits, DatabaseAccess database) {
        return new ModuleHostBindings(new JsonObject(), database, null, limits, resolver);
    }
}
