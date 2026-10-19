package org.techhouse.unit.client.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.client.Connection;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;

public final class FakeSource {
    private static final EJson eJson = IocContainer.get(EJson.class);

    private final Map<String, List<String>> collections = new LinkedHashMap<>();
    private final Map<String, List<JsonObject>> documents = new LinkedHashMap<>();
    private final Map<String, List<String>> indexes = new LinkedHashMap<>();
    private final Map<String, String> schemas = new LinkedHashMap<>();
    private final Map<String, List<String>> procedures = new LinkedHashMap<>();
    private final Map<String, List<String>> triggers = new LinkedHashMap<>();
    private final Map<String, List<String>> schedules = new LinkedHashMap<>();

    public FakeSource database(String database, String... collectionNames) {
        collections.put(database, List.of(collectionNames));
        return this;
    }

    public FakeSource documents(String database, String collection, int count, int padding) {
        final var list = new ArrayList<JsonObject>();
        for (var i = 0; i < count; i++) {
            final var document = new JsonObject();
            document.add("_id", new JsonString(collection + "-" + i));
            document.add("pad", new JsonString("x".repeat(padding)));
            list.add(document);
        }
        documents.put(database + "." + collection, list);
        return this;
    }

    public FakeSource index(String database, String collection, String... fields) {
        indexes.put(database + "." + collection, List.of(fields));
        return this;
    }

    public FakeSource schema(String database, String collection, String schema) {
        schemas.put(database + "." + collection, schema);
        return this;
    }

    public FakeSource procedure(String database, String json) {
        procedures.computeIfAbsent(database, ignored -> new ArrayList<>()).add(json);
        return this;
    }

    public FakeSource trigger(String database, String json) {
        triggers.computeIfAbsent(database, ignored -> new ArrayList<>()).add(json);
        return this;
    }

    public FakeSource schedule(String database, String json) {
        schedules.computeIfAbsent(database, ignored -> new ArrayList<>()).add(json);
        return this;
    }

    public void serveOn(FakeLineServer server) {
        server.respond("LIST_DATABASES", ignored -> FakeLineServer.ok("LIST_DATABASES",
                "\"databases\":" + strings(List.copyOf(collections.keySet()))));
        server.respond("LIST_COLLECTIONS", request -> {
            final var names = collections.get(database(request));
            return names == null
                    ? FakeLineServer.error("LIST_COLLECTIONS", "NOT_FOUND", "404-4")
                    : FakeLineServer.ok("LIST_COLLECTIONS", "\"collections\":" + strings(names));
        });
        server.respond("GET_DATABASE_STATS",
                ignored -> FakeLineServer.ok("GET_DATABASE_STATS", "\"stats\":" + stats()));
        server.respond("AGGREGATE", this::page);
        server.respond("GET_SCHEMA", request -> {
            final var schema = schemas.get(database(request) + "." + collection(request));
            return schema == null
                    ? FakeLineServer.error("GET_SCHEMA", "NOT_FOUND", "404-12")
                    : FakeLineServer.ok("GET_SCHEMA", "\"schema\":" + schema);
        });
        server.respond("LIST_PROCEDURES", request -> FakeLineServer.ok("LIST_PROCEDURES",
                "\"procedures\":[" + String.join(",", procedures.getOrDefault(database(request), List.of())) + "]"));
        server.respond("LIST_TRIGGERS", request -> FakeLineServer.ok("LIST_TRIGGERS",
                "\"triggers\":[" + String.join(",", triggers.getOrDefault(database(request), List.of())) + "]"));
        server.respond("LIST_SCHEDULES", request -> FakeLineServer.ok("LIST_SCHEDULES",
                "\"schedules\":[" + String.join(",", schedules.getOrDefault(database(request), List.of())) + "]"));
    }

    private String page(JsonObject request) {
        final var steps = request.get("aggregationSteps").asJsonArray();
        final var skip = steps.get(0).asJsonObject().get("skip").asJsonNumber().getValue().intValue();
        final var limit = steps.get(1).asJsonObject().get("limit").asJsonNumber().getValue().intValue();
        final var all = documents.getOrDefault(database(request) + "." + collection(request), List.of());
        if (skip >= all.size()) {
            return FakeLineServer.error("AGGREGATE", "NOT_FOUND", "404-3");
        }
        final var results = new JsonArray();
        for (final var document : all.subList(skip, Math.min(all.size(), skip + limit))) {
            results.add(document);
        }
        return FakeLineServer.ok("AGGREGATE", "\"results\":" + eJson.toJson(results));
    }

    private String stats() {
        final var databases = new JsonArray();
        for (final var entry : collections.entrySet()) {
            final var database = new JsonObject();
            database.add("name", new JsonString(entry.getKey()));
            final var collectionStats = new JsonArray();
            for (final var name : entry.getValue()) {
                final var collection = new JsonObject();
                collection.add("name", new JsonString(name));
                final var fields = new JsonArray();
                indexes.getOrDefault(entry.getKey() + "." + name, List.of()).forEach(fields::add);
                collection.add("indexes", fields);
                collectionStats.add(collection);
            }
            database.add("collections", collectionStats);
            databases.add(database);
        }
        final var stats = new JsonObject();
        stats.add("databases", databases);
        return eJson.toJson(stats);
    }

    private static String strings(List<String> values) {
        final var array = new JsonArray();
        values.forEach(array::add);
        return eJson.toJson(array);
    }

    private static String database(JsonObject request) {
        return Connection.stringOf(request, "databaseName");
    }

    private static String collection(JsonObject request) {
        return Connection.stringOf(request, "collectionName");
    }
}
