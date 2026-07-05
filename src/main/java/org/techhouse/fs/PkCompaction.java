package org.techhouse.fs;

/**
 * Describes a single-entry page compaction so the caller can keep the in-memory PK index consistent
 * with the rewritten page file: every cached entry on {@code page} of {@code dbName}/{@code collName}
 * whose position is greater than {@code removedPosition} has shifted toward the start of the file by
 * {@code removedLength}. Returned by {@link FileSystem#deleteFromCollection} and
 * {@link FileSystem#updateFromCollection}; a {@code null} value means no survivor moved.
 */
public record PkCompaction(String dbName, String collName, long page, long removedPosition, long removedLength) {
}
