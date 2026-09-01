package org.techhouse.cache;

/**
 * The footprint of the bounded admin metadata caches, reported by GET_DATABASE_STATS. These caches are
 * budgeted separately from {@code maxMemory}, which bounds the user document/index cache only, so an
 * operator sizing a node needs both numbers.
 */
public record MetadataCacheStats(long procedureBytes, int procedureEntries, long triggerBytes, int triggerEntries,
        long schemaBytes, int schemaEntries, int missEntries) {
}
