package org.techhouse.ops.admin;

import org.techhouse.cluster.HybridClock;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public final class AdminStamp {
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private static final String WRITE_VERSION_FIELD = "writeVersion";
    private static final String SCHEMA_FIELD = "schema";
    public static final long BOOTSTRAP_VERSION = 1L;

    private AdminStamp() {
    }

    public static long over(long replacedVersion) {
        hybridClock.observe(replacedVersion);
        return hybridClock.next();
    }

    public static long versionOf(JsonObject stored) {
        if (stored == null || !stored.has(WRITE_VERSION_FIELD) || !stored.get(WRITE_VERSION_FIELD).isJsonString()) {
            return 0L;
        }
        return Long.parseLong(stored.get(WRITE_VERSION_FIELD).asJsonString().getValue());
    }

    public static JsonObject stamped(JsonObject definition, long version) {
        final var copy = definition.deepCopy();
        copy.addProperty(WRITE_VERSION_FIELD, Long.toString(version));
        return copy;
    }

    public static JsonObject wrappedSchema(JsonObject schema, long version) {
        final var file = new JsonObject();
        file.add(SCHEMA_FIELD, schema);
        return stamped(file, version);
    }

    public static JsonObject unwrappedSchema(JsonObject stored) {
        if (stored != null && stored.has(WRITE_VERSION_FIELD) && stored.has(SCHEMA_FIELD)
                && stored.get(SCHEMA_FIELD).isJsonObject()) {
            return stored.get(SCHEMA_FIELD).asJsonObject();
        }
        return stored;
    }
}
