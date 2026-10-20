package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.techhouse.data.PkIndexEntry;

final class IntentFixture {
    static final String SUBJECT = "b";
    static final long FIRST_PAGE = 0L;
    final StorageFixture fx;

    IntentFixture(File root, String dbName, String collName) throws IOException {
        fx = new StorageFixture(root, dbName, collName);
    }

    WriteIntentJournal.Intent vacuumFirstPage() throws IOException {
        return fx.journal.begin(fx.dbName, fx.collName, List.of(new WriteIntent.Vacuum(FIRST_PAGE)));
    }

    void commitSubjectDelete() throws IOException {
        PkRowWrites.deleteRows(fx.paths, fx.store, fx.dbName, fx.collName, List.of(SUBJECT));
    }

    void blank(PkIndexEntry row) throws IOException {
        try (var channel = FileChannel.open(fx.page(row.getPage()).toPath(), StandardOpenOption.WRITE)) {
            PageHoles.blank(channel, row.getPosition(), row.getLength());
        }
    }

    void recover() throws IOException {
        fx.recovery.recoverAll();
    }

    String firstPage() throws IOException {
        return Files.readString(fx.page(FIRST_PAGE).toPath(), StandardCharsets.UTF_8);
    }

    int intents() throws IOException {
        return fx.openIntents();
    }
}
