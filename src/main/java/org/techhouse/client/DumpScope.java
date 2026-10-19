package org.techhouse.client;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;

public final class DumpScope {
    public record DatabasePlan(String name, List<String> collections, boolean whole) {
        public boolean keepsTrigger(JsonObject listed) {
            return collections.contains(Connection.stringOf(listed, Requests.COLLECTION));
        }
    }

    private static final String FORBIDDEN = "FORBIDDEN";
    private static final String COLLECTIONS = "collections";

    private final List<DatabasePlan> databases;
    private final Map<String, List<String>> indexes;

    private DumpScope(List<DatabasePlan> databases, Map<String, List<String>> indexes) {
        this.databases = databases;
        this.indexes = indexes;
    }

    public static DumpScope plan(Connection connection, ClientOptions options, PrintStream err) {
        final var databases = options.collections().isEmpty()
                ? wholeDatabases(connection, options.databases(), err)
                : namedCollections(connection, options.collections());
        return new DumpScope(databases, indexesOf(connection.sendOk(Requests.stats())));
    }

    public List<DatabasePlan> databases() {
        return databases;
    }

    public List<String> indexesOf(String database, String collection) {
        return indexes.getOrDefault(database + "." + collection, List.of());
    }

    private static List<DatabasePlan> wholeDatabases(Connection connection, List<String> filter, PrintStream err) {
        final var existing = stringsOf(connection.sendOk(Requests.listDatabases()), "databases");
        for (final var name : filter) {
            if (!existing.contains(name)) {
                throw new ClientException("database '" + name + "' not found", ClientException.FAILED);
            }
        }
        final var plans = new ArrayList<DatabasePlan>();
        for (final var name : filter.isEmpty() ? existing : filter) {
            final var request = Requests.listCollections(name);
            final var reply = connection.send(request);
            if (filter.isEmpty() && FORBIDDEN.equals(Connection.stringOf(reply, Connection.STATUS))) {
                err.println("note: " + name + " was not dumped: " + Connection.stringOf(reply, Connection.MESSAGE));
                continue;
            }
            if (!Connection.isOk(reply)) {
                throw Connection.refused(request, reply);
            }
            final var collections = new ArrayList<>(stringsOf(reply, COLLECTIONS));
            collections.remove(Globals.SCRIPT_RUNS_COLLECTION_NAME);
            plans.add(new DatabasePlan(name, List.copyOf(collections), true));
        }
        return plans;
    }

    private static List<DatabasePlan> namedCollections(Connection connection, List<ClientOptions.CollectionRef> refs) {
        final var grouped = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (final var ref : refs) {
            grouped.computeIfAbsent(ref.database(), ignored -> new LinkedHashSet<>()).add(ref.collection());
        }
        final var plans = new ArrayList<DatabasePlan>();
        for (final var entry : grouped.entrySet()) {
            final var existing = collectionsOf(connection, entry.getKey());
            for (final var collection : entry.getValue()) {
                if (!existing.contains(collection) || Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(collection)) {
                    throw new ClientException("collection '" + entry.getKey() + "." + collection + "' not found",
                            ClientException.FAILED);
                }
            }
            plans.add(new DatabasePlan(entry.getKey(), List.copyOf(entry.getValue()), false));
        }
        return plans;
    }

    private static List<String> collectionsOf(Connection connection, String database) {
        return stringsOf(connection.sendOk(Requests.listCollections(database)), COLLECTIONS);
    }

    private static Map<String, List<String>> indexesOf(JsonObject statsReply) {
        final var found = new HashMap<String, List<String>>();
        final var stats = statsReply.get("stats").asJsonObject();
        for (final var database : objectsOf(stats, "databases")) {
            final var databaseName = Connection.stringOf(database, Requests.NAME);
            for (final var collection : objectsOf(database, COLLECTIONS)) {
                found.put(databaseName + "." + Connection.stringOf(collection, Requests.NAME),
                        stringsOf(collection, "indexes"));
            }
        }
        return found;
    }

    public static List<String> stringsOf(JsonObject object, String field) {
        final var strings = new ArrayList<String>();
        final var array = object.get(field);
        if (array != null && array.isJsonArray()) {
            for (final var element : array.asJsonArray()) {
                strings.add(element.asJsonString().getValue());
            }
        }
        return strings;
    }

    public static List<JsonObject> objectsOf(JsonObject object, String field) {
        final var objects = new ArrayList<JsonObject>();
        final var array = object.get(field);
        if (array != null && array.isJsonArray()) {
            for (final var element : array.asJsonArray()) {
                objects.add(element.asJsonObject());
            }
        }
        return objects;
    }
}
