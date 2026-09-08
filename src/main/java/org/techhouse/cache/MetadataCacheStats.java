package org.techhouse.cache;

public record MetadataCacheStats(long procedureBytes, int procedureEntries, long triggerBytes, int triggerEntries,
        long schemaBytes, int schemaEntries, long scheduleBytes, int scheduleEntries, int missEntries) {
}
