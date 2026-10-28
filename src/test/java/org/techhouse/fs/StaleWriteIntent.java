package org.techhouse.fs;

import java.io.IOException;
import java.util.List;

public final class StaleWriteIntent {
    private StaleWriteIntent() {
    }

    public static String writeVacuumIntent(String dbPath, String dbName, String collName, long page)
            throws IOException {
        final var paths = new FilePaths();
        paths.useDbPath(dbPath);
        return new WriteIntentJournal(paths).begin(dbName, collName, List.of(new WriteIntent.Vacuum(page))).label();
    }
}
