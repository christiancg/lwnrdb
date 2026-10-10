package org.techhouse.ops.admin;

import java.io.IOException;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public final class StoredDefinitions {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final EJson eJson = IocContainer.get(EJson.class);

    private StoredDefinitions() {
    }

    public static JsonObject procedure(String dbName, String name) throws IOException {
        return parsed(fs.readProcedure(dbName, name));
    }

    public static JsonObject schedule(String dbName, String name) throws IOException {
        return parsed(fs.readSchedule(dbName, name));
    }

    public static JsonObject triggers(String dbName, String collName) throws IOException {
        return parsed(fs.readTriggers(dbName, collName));
    }

    public static JsonObject schema(String dbName, String collName) throws IOException {
        return parsed(fs.readCollectionSchema(dbName, collName));
    }

    public static void writeProcedure(String dbName, String name, JsonObject definition) throws IOException {
        fs.writeProcedure(dbName, name, eJson.toJson(restamped(definition, procedureVersion(dbName, name))));
    }

    public static void writeSchedule(String dbName, String name, JsonObject definition) throws IOException {
        fs.writeSchedule(dbName, name, eJson.toJson(restamped(definition, scheduleVersion(dbName, name))));
    }

    public static void writeTriggers(String dbName, String collName, JsonObject file) throws IOException {
        fs.writeTriggers(dbName, collName, eJson.toJson(restamped(file, triggersVersion(dbName, collName))));
    }

    public static void writeSchema(String dbName, String collName, JsonObject schema) throws IOException {
        final var version = AdminStamp.over(schemaVersion(dbName, collName));
        fs.writeCollectionSchema(dbName, collName, eJson.toJson(AdminStamp.wrappedSchema(schema, version)));
    }

    private static long triggersVersion(String dbName, String collName) {
        return versionOrZero(() -> triggers(dbName, collName));
    }

    private static long schemaVersion(String dbName, String collName) {
        return versionOrZero(() -> schema(dbName, collName));
    }

    private static long procedureVersion(String dbName, String name) {
        return versionOrZero(() -> procedure(dbName, name));
    }

    private static long scheduleVersion(String dbName, String name) {
        return versionOrZero(() -> schedule(dbName, name));
    }

    private static JsonObject restamped(JsonObject definition, long replacedVersion) {
        return AdminStamp.stamped(definition, AdminStamp.over(replacedVersion));
    }

    private interface StoredReader {
        JsonObject read() throws IOException;
    }

    private static long versionOrZero(StoredReader reader) {
        try {
            return AdminStamp.versionOf(reader.read());
        } catch (IOException | RuntimeException unreadable) {
            return 0L;
        }
    }

    private static JsonObject parsed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return eJson.fromJson(raw, JsonObject.class);
    }
}
