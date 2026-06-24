package org.techhouse.fs;

import org.techhouse.data.PkIndexEntry;

/**
 * Result of {@link FileSystem#updateFromCollection}: the new {@link PkIndexEntry} for the updated row
 * and the {@link PkCompaction} the caller must apply to the in-memory PK positions ({@code null} when
 * no survivor moved).
 */
public record UpdateResult(PkIndexEntry indexEntry, PkCompaction compaction) {
}
