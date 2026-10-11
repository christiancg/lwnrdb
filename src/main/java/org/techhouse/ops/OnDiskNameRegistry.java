package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ioc.IocContainer;

public final class OnDiskNameRegistry {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final List<String> RESERVED_DATABASE_NAMES = List.of(Globals.ADMIN_DB_NAME,
            Globals.ADMIN_PAGES_DB_NAME, Globals.CLUSTER_FOLDER);

    private OnDiskNameRegistry() {
    }

    public static String onDiskKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static String collidingDatabase(String dbName) {
        return firstColliding(allDatabaseNames(), dbName);
    }

    public static String collidingCollection(String dbName, String collName) {
        return firstColliding(collectionNamesOf(dbName), collName);
    }

    public static String collidingIndexField(String dbName, String collName, String fieldName) {
        return firstColliding(cache.getIndexesForCollection(dbName, collName), fieldName);
    }

    public static String collidingDefinition(Collection<String> existing, String name) {
        return firstColliding(existing, name);
    }

    public static List<List<String>> groupedByOnDiskKey() {
        final var groups = new ArrayList<List<String>>();
        addGroupsOf(groups, allDatabaseNames());
        for (final var dbName : allDatabaseNames()) {
            addGroupsOf(groups, collectionNamesOf(dbName));
        }
        return groups;
    }

    public static List<String> registeredDatabasesInTheClusterFolder() {
        final var names = new ArrayList<String>();
        for (final var entry : cache.getAllAdminDbEntries()) {
            final var name = entry.get_id();
            if (name != null && onDiskKey(name).equals(Globals.CLUSTER_FOLDER)) {
                names.add(name);
            }
        }
        return names;
    }

    private static void addGroupsOf(List<List<String>> groups, Collection<String> names) {
        final var byKey = new LinkedHashMap<String, List<String>>();
        for (final var name : names) {
            byKey.computeIfAbsent(onDiskKey(name), _ -> new ArrayList<>()).add(name);
        }
        for (final var group : byKey.values()) {
            if (group.size() > 1) {
                groups.add(group);
            }
        }
    }

    private static String firstColliding(Collection<String> existingNames, String candidate) {
        if (candidate == null) {
            return null;
        }
        final var candidateKey = onDiskKey(candidate);
        for (final var existing : existingNames) {
            if (!existing.equals(candidate) && onDiskKey(existing).equals(candidateKey)) {
                return existing;
            }
        }
        return null;
    }

    private static List<String> allDatabaseNames() {
        final var names = new ArrayList<>(RESERVED_DATABASE_NAMES);
        for (final var entry : cache.getAllAdminDbEntries()) {
            final var name = entry.get_id();
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static List<String> collectionNamesOf(String dbName) {
        final var entry = cache.getAdminDbEntry(dbName);
        return entry == null ? List.of() : collectionsOf(entry);
    }

    private static List<String> collectionsOf(AdminDbEntry entry) {
        final var collections = entry.getCollections();
        return collections == null ? List.of() : collections;
    }
}
