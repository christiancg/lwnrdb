package org.techhouse.cache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Globals;

final class AbsentIndexTypes {
    private final Map<String, Set<String>> byCollection = new ConcurrentHashMap<>();

    boolean isAbsent(String collectionIdentifier, String indexIdentifier) {
        final var absent = byCollection.get(collectionIdentifier);
        return absent != null && absent.contains(indexIdentifier);
    }

    void remember(String collectionIdentifier, String indexIdentifier) {
        byCollection.computeIfAbsent(collectionIdentifier, _ -> ConcurrentHashMap.newKeySet()).add(indexIdentifier);
    }

    void forget(String collectionIdentifier, String indexIdentifier) {
        final var absent = byCollection.get(collectionIdentifier);
        if (absent != null) {
            absent.remove(indexIdentifier);
        }
    }

    void forgetField(String collectionIdentifier, String fieldName) {
        final var absent = byCollection.get(collectionIdentifier);
        if (absent != null) {
            final var prefix = fieldName + Globals.COLL_IDENTIFIER_SEPARATOR;
            absent.removeIf(identifier -> identifier.startsWith(prefix));
        }
    }

    void forgetCollection(String collectionIdentifier) {
        byCollection.remove(collectionIdentifier);
    }

    void forgetDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        byCollection.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
