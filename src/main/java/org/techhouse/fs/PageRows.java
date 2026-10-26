package org.techhouse.fs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.techhouse.data.IdSearchable;
import org.techhouse.data.PkIndexEntry;

final class PageRows {
    private PageRows() {
    }

    static List<PkIndexEntry> of(PkIndexStore store, List<PkIndexEntry> residentRows, String dbName, String collName,
            long page) throws IOException {
        return of(store, residentRows, dbName, collName, page, null);
    }

    static List<PkIndexEntry> of(PkIndexStore store, List<PkIndexEntry> residentRows, String dbName, String collName,
            long page, String excludedId) throws IOException {
        final var rows = residentRows != null ? residentRows : store.readWholePkIndexFile(dbName, collName);
        final var onPage = new ArrayList<PkIndexEntry>();
        for (final var row : rows) {
            if (row.getPage() == page && !row.getValue().equals(excludedId)) {
                onPage.add(row);
            }
        }
        return onPage;
    }

    static PkIndexEntry rowOf(List<PkIndexEntry> rows, String id) {
        final var at = rows instanceof IdSearchable searchable
                ? searchable.search(id)
                : Collections.binarySearch(rows, id);
        return at < 0 ? null : rows.get(at);
    }

    static long liveBytes(List<PkIndexEntry> pageRows) {
        var live = 0L;
        for (final var row : pageRows) {
            live += row.getLength();
        }
        return live;
    }
}
