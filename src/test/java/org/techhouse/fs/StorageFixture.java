package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.techhouse.data.PkIndexEntry;

final class StorageFixture {
    static final long OLD_VERSION = 1_700_000_000_000L;
    static final long NEW_VERSION = 1_800_000_000_000L;

    final String dbName;
    final String collName;
    final FilePaths paths;
    final PkIndexStore store;
    final WriteIntentJournal journal;
    final WriteIntentRecovery recovery;

    StorageFixture(File root, String dbName, String collName) throws IOException {
        this.dbName = dbName;
        this.collName = collName;
        paths = new FilePaths();
        paths.useDbPath(root.getAbsolutePath());
        Files.createDirectories(paths.collectionFolder(dbName, collName).toPath());
        store = new PkIndexStore(paths);
        journal = new WriteIntentJournal(paths);
        recovery = new WriteIntentRecovery(paths, store, journal);
    }

    static String record(String id, String value) {
        return "{\"_id\":\"" + id + "\",\"v\":\"" + value + "\"}\n";
    }

    File page(long page) {
        return paths.collectionPage(dbName, collName, page);
    }

    void seed(long page, String... ids) throws IOException {
        for (final var id : ids) {
            final var position = page(page).exists() ? page(page).length() : 0L;
            final var text = record(id, "old-" + id);
            Files.writeString(page(page).toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            store.indexNewPKValue(dbName, collName, id, position, text.getBytes(StandardCharsets.UTF_8).length, page,
                    OLD_VERSION);
        }
    }

    PkIndexEntry indexed(String id) throws IOException {
        return store.readWholePkIndexFile(dbName, collName).stream().filter(entry -> entry.getValue().equals(id))
                .findFirst().orElse(null);
    }

    List<PkIndexEntry> indexedRows() throws IOException {
        return store.readWholePkIndexFile(dbName, collName);
    }

    String readAt(PkIndexEntry entry) throws IOException {
        final var bytes = Files.readAllBytes(page(entry.getPage()).toPath());
        return new String(bytes, (int) entry.getPosition(), (int) entry.getLength(), StandardCharsets.UTF_8);
    }

    String firstPageText() throws IOException {
        return Files.readString(page(0).toPath(), StandardCharsets.UTF_8);
    }

    long appendUpdatedCopy(String id, long page) throws IOException {
        final var text = record(id, "new-" + id);
        final var position = page(page).exists() ? page(page).length() : 0L;
        Files.writeString(page(page).toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        return position;
    }

    boolean everyRowReadsItsOwnRecord() throws IOException {
        for (final var row : indexedRows()) {
            if (!readAt(row).startsWith("{\"_id\":\"" + row.getValue() + "\"")) {
                return false;
            }
        }
        return true;
    }

    int openIntents() throws IOException {
        return journal.openIntents(dbName, collName).size();
    }
}
