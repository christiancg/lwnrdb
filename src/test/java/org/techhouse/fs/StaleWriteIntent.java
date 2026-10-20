package org.techhouse.fs;

import java.io.IOException;
import java.util.List;

public final class StaleWriteIntent {
    private StaleWriteIntent() {
    }

    public static String writeReplaceIntent(String dbPath, String dbName, String collName, String id)
            throws IOException {
        final var paths = new FilePaths();
        paths.useDbPath(dbPath);
        final var entry = new PkIndexStore(paths).readWholePkIndexFile(dbName, collName).stream()
                .filter(row -> row.getValue().equals(id)).findFirst().orElseThrow();
        final var page = paths.collectionPage(dbName, collName, entry.getPage());
        return new WriteIntentJournal(paths)
                .begin(dbName, collName,
                        List.of(WriteIntent.Replace.of(entry, entry.getPosition(), entry.getPage(), page.length())))
                .label();
    }
}
