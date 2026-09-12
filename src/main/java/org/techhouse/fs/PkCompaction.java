package org.techhouse.fs;

/**
 * The caller must apply this to the cached PK positions, and only to entries on {@code page}: a
 * file-order shift would corrupt the positions of every other page.
 */
public record PkCompaction(String dbName, String collName, long page, long removedPosition, long removedLength) {
}
