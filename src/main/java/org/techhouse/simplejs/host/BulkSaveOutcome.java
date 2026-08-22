package org.techhouse.simplejs.host;

import java.util.List;

// What a bulk save reports back, mirroring BulkSaveResponse: re-reading N documents the way a single
// save does would defeat the point of the batch, so the ids are the whole result.
public record BulkSaveOutcome(List<String> inserted, List<String> updated) {

    public BulkSaveOutcome {
        inserted = inserted == null ? List.of() : List.copyOf(inserted);
        updated = updated == null ? List.of() : List.copyOf(updated);
    }
}
