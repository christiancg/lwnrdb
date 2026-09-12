package org.techhouse.fs;

import java.util.List;
import org.techhouse.data.IndexedDbEntry;

public record BulkUpdateResult(List<IndexedDbEntry> updated, List<PkCompaction> compactions) {
}
