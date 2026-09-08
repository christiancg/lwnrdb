package org.techhouse.simplejs.host;

import java.util.List;

public record BulkSaveOutcome(List<String> inserted, List<String> updated) {
    public BulkSaveOutcome {
        inserted = inserted == null ? List.of() : List.copyOf(inserted);
        updated = updated == null ? List.of() : List.copyOf(updated);
    }
}
