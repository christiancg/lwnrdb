package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import org.techhouse.config.Globals;

public final class StaleCompactionMarker {
    private StaleCompactionMarker() {
    }

    public static File writeUpdateMarker(String dbPath, String dbName, String collName, String id) throws IOException {
        final var paths = new FilePaths();
        paths.useDbPath(dbPath);
        final var entry = new PkIndexStore(paths).readWholePkIndexFile(dbName, collName).stream()
                .filter(row -> row.getValue().equals(id)).findFirst().orElseThrow();
        final var page = paths.collectionPage(dbName, collName, entry.getPage());
        final var journal = new CompactionJournal(paths);
        try (var raf = new RandomAccessFile(page, Globals.RW_PERMISSIONS)) {
            final var tail = PageCompactor.readRegion(raf, entry.getPosition(), page.length());
            return journal.markerFile(journal.begin(CompactionJournal.Kind.UPDATE, entry, page.length(), tail));
        }
    }
}
