package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.techhouse.data.PkIndexEntry;

final class IntentFixture {
    static final String SUBJECT = "b";
    static final long FIRST_PAGE = 0L;
    final StorageFixture fx;

    IntentFixture(File root, String dbName, String collName) throws IOException {
        fx = new StorageFixture(root, dbName, collName);
    }

    WriteIntentJournal.Intent replaceSubject(long targetPage) throws IOException {
        final var row = fx.indexed(SUBJECT);
        final var target = fx.page(targetPage);
        return fx.journal.begin(fx.dbName, fx.collName, List.of(
                WriteIntent.Replace.of(row, row.getPosition(), targetPage, target.exists() ? target.length() : 0L)));
    }

    WriteIntentJournal.Intent deleteSubject() throws IOException {
        return fx.journal.begin(fx.dbName, fx.collName, List.of(WriteIntent.Delete.of(fx.indexed(SUBJECT))));
    }

    void vacuumFirstPage() throws IOException {
        fx.journal.begin(fx.dbName, fx.collName, List.of(new WriteIntent.Vacuum(FIRST_PAGE)));
    }

    PkIndexEntry appendCopy(String id, long page) throws IOException {
        final var position = fx.appendUpdatedCopy(id, page);
        return new PkIndexEntry(fx.dbName, fx.collName, id, position,
                StorageFixture.record(id, "new-" + id).getBytes(StandardCharsets.UTF_8).length, page,
                StorageFixture.NEW_VERSION);
    }

    void commit(PkIndexEntry newRow) throws IOException {
        fx.store.appendReplacements(fx.dbName, fx.collName, List.of(newRow));
    }

    void commitSubjectDelete() throws IOException {
        fx.store.appendDeletes(fx.dbName, fx.collName, List.of(SUBJECT));
    }

    void blank(PkIndexEntry row) throws IOException {
        try (var channel = FileChannel.open(fx.page(row.getPage()).toPath(), StandardOpenOption.WRITE)) {
            PageHoles.blank(channel, row.getPosition(), row.getLength());
        }
    }

    List<PkIndexEntry> recover() throws IOException {
        return fx.recovery.recoverAll();
    }

    Map<String, String> valuesById(long page) throws IOException {
        final var values = new TreeMap<String, String>();
        new DocumentPageStore(fx.paths).readWholeCollectionPage(fx.dbName, fx.collName, page)
                .forEach((id, entry) -> values.put(id, entry.getData().get("v").asJsonString().getValue()));
        return values;
    }

    String page(long page) throws IOException {
        return Files.readString(fx.page(page).toPath(), StandardCharsets.UTF_8);
    }

    int intents() throws IOException {
        return fx.openIntents();
    }
}
