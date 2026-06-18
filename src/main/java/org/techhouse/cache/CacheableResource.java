package org.techhouse.cache;

public record CacheableResource(AccessKind kind, String dbName, String collName, String indexKey,
        long estimatedSizeBytes) {
}
