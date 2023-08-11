package org.techhouse.ops;

import org.techhouse.cache.Cache;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.JsonUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class IndexHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    public static void createIndex(String dbName, String collName, String fieldName) {
        final var coll = cache.getWholeCollection(dbName, collName);
        final var entriesToBeIndexed = coll.values().stream().map(DbEntry::getData)
                .filter(jsonObject -> JsonUtils.hasInPath(jsonObject, fieldName))
                .collect(Collectors.groupingBy(jsonObject -> JsonUtils.getFromPath(jsonObject, fieldName)));
        final Map<Class<?>, List<FieldIndexEntry<?>>> indexes = entriesToBeIndexed.entrySet().stream().map(jsonElementListEntry -> {
            final var key = jsonElementListEntry.getKey();
            final var primitive = key.getAsJsonPrimitive();
            final var ids = jsonElementListEntry.getValue().stream()
                    .map(jsonObject -> jsonObject.get("_id").getAsString()).collect(Collectors.toSet());
            if (primitive.isNumber()) {
                return new FieldIndexEntry<>(dbName, collName, primitive.getAsDouble(), ids);
            } else if (primitive.isBoolean()) {
                return new FieldIndexEntry<>(dbName, collName, primitive.getAsBoolean(), ids);
            } else {
                return new FieldIndexEntry<>(dbName, collName, primitive.getAsString(), ids);
            }
        }).collect(Collectors.groupingBy(fieldIndexEntry -> fieldIndexEntry.getValue().getClass()));
        fs.writeIndexFile(dbName, collName, fieldName, indexes);
    }

    public static List<FieldIndexEntry<?>> getIndexFile(String dbName, String collName, String fieldName, String indexType)
            throws ExecutionException, InterruptedException {
        return cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, indexType);
    }

    public static boolean dropIndex(String dbName, String collName, String fieldName)
            throws ExecutionException, InterruptedException {
        return fs.dropIndex(dbName, collName, fieldName);
    }
}
