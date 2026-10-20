package org.techhouse.fs;

import java.io.IOException;

public final class IntentLogs {
    private IntentLogs() {
    }

    public static int openIntents(String dbPath, String dbName, String collName) throws IOException {
        return journal(dbPath).openIntents(dbName, collName).size();
    }

    public static int openIntentsUnder(String dbPath) throws IOException {
        final var journal = journal(dbPath);
        var open = 0;
        for (final var log : journal.findLogs()) {
            open += journal.openIntents(log).size();
        }
        return open;
    }

    private static WriteIntentJournal journal(String dbPath) {
        final var paths = new FilePaths();
        paths.useDbPath(dbPath);
        return new WriteIntentJournal(paths);
    }
}
