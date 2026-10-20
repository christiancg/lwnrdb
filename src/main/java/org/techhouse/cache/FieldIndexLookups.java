package org.techhouse.cache;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Globals;

final class FieldIndexLookups {
    private final Map<String, Map<String, FieldIndexLookup>> byCollection = new ConcurrentHashMap<>();

    FieldIndexLookup get(String collectionIdentifier, String fieldName) {
        final var lookups = byCollection.get(collectionIdentifier);
        return lookups == null ? null : lookups.get(fieldName);
    }

    void put(String collectionIdentifier, String fieldName, FieldIndexLookup lookup) {
        byCollection.computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>()).put(fieldName, lookup);
    }

    void evict(String collectionIdentifier, String fieldName) {
        final var lookups = byCollection.get(collectionIdentifier);
        if (lookups != null) {
            lookups.remove(fieldName);
            if (lookups.isEmpty()) {
                byCollection.remove(collectionIdentifier, lookups);
            }
        }
    }

    void evictCollection(String collectionIdentifier) {
        byCollection.remove(collectionIdentifier);
    }

    void evictByPrefix(String prefix) {
        byCollection.keySet().removeIf(key -> key.startsWith(prefix));
    }

    long footprintBytes() {
        var total = 0L;
        for (final var lookups : byCollection.values()) {
            for (final var lookup : lookups.values()) {
                total += CacheSizeEstimator.estimateFieldIndexLookupSize(lookup.size());
            }
        }
        return total;
    }

    void addResources(List<CacheableResource> resources) {
        for (var entry : byCollection.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2) {
                continue;
            }
            for (var inner : entry.getValue().entrySet()) {
                resources.add(new CacheableResource(AccessKind.FIELD_INDEX, parts[0], parts[1],
                        Cache.getFieldLookupIdentifier(inner.getKey()),
                        CacheSizeEstimator.estimateFieldIndexLookupSize(inner.getValue().size())));
            }
        }
    }
}
