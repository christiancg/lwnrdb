package org.techhouse.fs;

import java.util.List;
import org.techhouse.data.IndexedDbEntry;

/**
 * Result of {@link FileSystem#bulkUpdateFromCollection}: the updated entries (with their new
 * {@link org.techhouse.data.PkIndexEntry}) and the ordered list of {@link PkCompaction}s produced by
 * the underlying per-entry updates. The caller must apply each compaction (via
 * {@code Cache.shiftPkPositionsAfterCompaction}) to keep the in-memory PK positions of the surviving
 * (non-updated) same-page entries consistent — exactly as the single-update path does.
 */
public record BulkUpdateResult(List<IndexedDbEntry> updated, List<PkCompaction> compactions) {
}
