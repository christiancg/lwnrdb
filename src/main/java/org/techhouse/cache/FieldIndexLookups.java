package org.techhouse.cache;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Globals;

final class FieldIndexLookups {
    private record Resident(FieldIndexLookup lookup, SizeCell cell) {
    }

    private final Map<String, Map<String, Resident>> byCollection = new ConcurrentHashMap<>();

    FieldIndexLookup get(String collectionIdentifier, String fieldName) {
        final var lookups = byCollection.get(collectionIdentifier);
        final var resident = lookups == null ? null : lookups.get(fieldName);
        return resident == null ? null : resident.lookup();
    }

    void put(String collectionIdentifier, String fieldName, FieldIndexLookup lookup, Footprint footprint) {
        final var bytes = CacheSizeEstimator.estimateFieldIndexLookupSize(lookup.size());
        byCollection.compute(collectionIdentifier, (_, existing) -> {
            final var lookups = existing != null ? existing : new ConcurrentHashMap<String, Resident>();
            final var resident = lookups.get(fieldName);
            final var cell = resident != null ? resident.cell() : footprint.newCell(AccessKind.FIELD_INDEX);
            cell.set(bytes);
            lookups.put(fieldName, new Resident(lookup, cell));
            return lookups;
        });
    }

    void evict(String collectionIdentifier, String fieldName) {
        byCollection.computeIfPresent(collectionIdentifier, (_, lookups) -> {
            retire(lookups.remove(fieldName));
            return lookups.isEmpty() ? null : lookups;
        });
    }

    void evictCollection(String collectionIdentifier) {
        retireAll(byCollection.remove(collectionIdentifier));
    }

    void evictByPrefix(String prefix) {
        for (final var key : List.copyOf(byCollection.keySet())) {
            if (key.startsWith(prefix)) {
                retireAll(byCollection.remove(key));
            }
        }
    }

    private static void retireAll(Map<String, Resident> lookups) {
        if (lookups != null) {
            lookups.values().forEach(FieldIndexLookups::retire);
        }
    }

    private static void retire(Resident resident) {
        if (resident != null) {
            resident.cell().retire();
        }
    }

    void addResources(List<CacheableResource> resources) {
        for (var entry : byCollection.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2) {
                continue;
            }
            for (var inner : entry.getValue().entrySet()) {
                resources.add(new CacheableResource(AccessKind.FIELD_INDEX, parts[0], parts[1],
                        Cache.getFieldLookupIdentifier(inner.getKey()), inner.getValue().cell().bytes()));
            }
        }
    }
}
