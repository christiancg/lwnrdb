package org.techhouse.fs;

import org.techhouse.data.PkIndexEntry;

public record UpdateResult(PkIndexEntry indexEntry, PkCompaction compaction) {
}
