package org.techhouse.fs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.data.PkIndexEntry;

final class PageRows {
    private PageRows() {
    }

    static List<PkIndexEntry> of(PkIndexStore store, List<PkIndexEntry> residentRows, String dbName, String collName,
            long page) throws IOException {
        final var rows = residentRows != null ? residentRows : store.readWholePkIndexFile(dbName, collName);
        final var onPage = new ArrayList<PkIndexEntry>();
        for (final var row : rows) {
            if (row.getPage() == page) {
                onPage.add(row);
            }
        }
        return onPage;
    }

    static long liveBytes(List<PkIndexEntry> pageRows) {
        var live = 0L;
        for (final var row : pageRows) {
            live += row.getLength();
        }
        return live;
    }
}
