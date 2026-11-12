package org.techhouse.client;

import java.util.List;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public final class Requests {
    public static final String TYPE = "type";
    public static final String DATABASE = "databaseName";
    public static final String COLLECTION = "collectionName";
    public static final String NAME = "name";
    public static final String PROCEDURE_NAME = "procedureName";
    public static final String CRON = "cron";
    public static final String INTERVAL_MS = "intervalMs";
    public static final String SNAPSHOT_ID = "snapshotId";
    public static final String COLLECTION_NAMES = "collectionNames";
    private static final String AFTER_ID = "afterId";
    private static final String LIMIT = "limit";

    private Requests() {
    }

    public static JsonObject authenticate(String user, String password) {
        final var request = typed("AUTHENTICATE");
        request.add("username", new JsonString(user));
        request.add("password", new JsonString(password));
        return request;
    }

    public static JsonObject close() {
        return typed("CLOSE_CONNECTION");
    }

    public static JsonObject serverInfo() {
        return typed("SERVER_INFO");
    }

    public static JsonObject listDatabases() {
        return typed("LIST_DATABASES");
    }

    public static JsonObject listCollections(String database) {
        return onDatabase("LIST_COLLECTIONS", database);
    }

    public static JsonObject stats() {
        return typed("GET_DATABASE_STATS");
    }

    public static JsonObject startSnapshot(String database, List<String> collections) {
        final var request = onDatabase("START_SNAPSHOT", database);
        final var names = new JsonArray();
        for (final var collection : collections) {
            names.add(new JsonString(collection));
        }
        request.add(COLLECTION_NAMES, names);
        return request;
    }

    public static JsonObject readSnapshot(String database, String collection, String snapshotId, String afterId,
            int limit) {
        final var request = onCollection("READ_SNAPSHOT", database, collection);
        request.add(SNAPSHOT_ID, new JsonString(snapshotId));
        if (afterId != null) {
            request.add(AFTER_ID, new JsonString(afterId));
        }
        request.add(LIMIT, new JsonNumber(limit));
        return request;
    }

    public static JsonObject releaseSnapshot(String database, String snapshotId) {
        final var request = onDatabase("RELEASE_SNAPSHOT", database);
        request.add(SNAPSHOT_ID, new JsonString(snapshotId));
        return request;
    }

    public static JsonObject getSchema(String database, String collection) {
        return onCollection("GET_SCHEMA", database, collection);
    }

    public static JsonObject listProcedures(String database, boolean includeSource) {
        final var request = onDatabase("LIST_PROCEDURES", database);
        request.add("includeSource", JsonBoolean.of(includeSource));
        return request;
    }

    public static JsonObject listTriggers(String database) {
        return onDatabase("LIST_TRIGGERS", database);
    }

    public static JsonObject listSchedules(String database, boolean includeArgs) {
        final var request = onDatabase("LIST_SCHEDULES", database);
        request.add("includeArgs", JsonBoolean.of(includeArgs));
        return request;
    }

    public static JsonObject createDatabase(String database) {
        return onDatabase("CREATE_DATABASE", database);
    }

    public static JsonObject createCollection(String database, String collection) {
        return onCollection("CREATE_COLLECTION", database, collection);
    }

    public static JsonObject bulkSave(String database, String collection, JsonArray documents) {
        final var request = onCollection("BULK_SAVE", database, collection);
        request.add("objects", documents);
        return request;
    }

    public static JsonObject saveSchema(String database, String collection, JsonObject schema) {
        final var request = onCollection("SAVE_SCHEMA", database, collection);
        request.add("schema", schema);
        return request;
    }

    public static JsonObject createIndexes(String database, String collection, List<String> fields) {
        final var request = onCollection("CREATE_INDEX", database, collection);
        final var names = new JsonArray();
        for (final var field : fields) {
            names.add(new JsonString(field));
        }
        request.add("fieldNames", names);
        return request;
    }

    public static JsonObject saveProcedureFrom(String database, JsonObject listed) {
        final var request = onDatabase("SAVE_PROCEDURE", database);
        copy(listed, request, NAME);
        if (listed.has("source")) {
            request.add("script", listed.get("source"));
        }
        copy(listed, request, "description", "enabled");
        return request;
    }

    public static JsonObject saveTriggerFrom(String database, JsonObject listed) {
        final var request = onDatabase("SAVE_TRIGGER", database);
        copy(listed, request, COLLECTION, NAME, "events", PROCEDURE_NAME, "mode", "timing", "allowCascade", "enabled");
        return request;
    }

    public static JsonObject saveScheduleFrom(String database, JsonObject listed) {
        final var request = onDatabase("SAVE_SCHEDULE", database);
        copy(listed, request, NAME, PROCEDURE_NAME);
        final var cron = listed.get(CRON);
        if (cron != null && cron.isJsonString() && !cron.asJsonString().getValue().isBlank()) {
            request.add(CRON, cron);
        } else {
            copy(listed, request, INTERVAL_MS);
        }
        copy(listed, request, "args", "timeoutMs", "description", "enabled");
        return request;
    }

    private static void copy(JsonObject from, JsonObject to, String... fields) {
        for (final var field : fields) {
            final var value = from.get(field);
            if (value != null && !value.isJsonNull()) {
                to.add(field, value);
            }
        }
    }

    private static JsonObject typed(String type) {
        final var request = new JsonObject();
        request.add(TYPE, new JsonString(type));
        return request;
    }

    private static JsonObject onDatabase(String type, String database) {
        final var request = typed(type);
        request.add(DATABASE, new JsonString(database));
        return request;
    }

    private static JsonObject onCollection(String type, String database, String collection) {
        final var request = onDatabase(type, database);
        request.add(COLLECTION, new JsonString(collection));
        return request;
    }
}
