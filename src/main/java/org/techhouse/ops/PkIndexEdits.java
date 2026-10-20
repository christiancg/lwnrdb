package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.techhouse.data.PkIndexEntry;

final class PkIndexEdits {
    static final int POINT_EDIT_LIMIT = 64;
    private static final Comparator<PkIndexEntry> BY_ID = Comparator.comparing(PkIndexEntry::getValue);

    private PkIndexEdits() {
    }

    static void replaceAll(List<PkIndexEntry> rows, List<PkIndexEntry> replacements) {
        for (final var row : replacements) {
            final var at = Collections.binarySearch(rows, row.getValue());
            if (at >= 0) {
                rows.set(at, row);
            } else {
                rows.add(-at - 1, row);
            }
        }
    }

    static void insertAll(List<PkIndexEntry> rows, List<PkIndexEntry> inserted) {
        if (inserted.size() <= POINT_EDIT_LIMIT) {
            for (final var row : inserted) {
                final var at = Collections.binarySearch(rows, row.getValue());
                rows.add(at < 0 ? -at - 1 : at, row);
            }
            return;
        }
        final var sorted = new ArrayList<>(inserted);
        sorted.sort(BY_ID);
        final var merged = new ArrayList<PkIndexEntry>(rows.size() + sorted.size());
        var next = 0;
        for (final var row : rows) {
            while (next < sorted.size() && BY_ID.compare(sorted.get(next), row) < 0) {
                merged.add(sorted.get(next++));
            }
            merged.add(row);
        }
        merged.addAll(sorted.subList(next, sorted.size()));
        rows.clear();
        rows.addAll(merged);
    }
}
