package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;

final class ReplacerFixture {
    final StorageFixture fx;
    final PageVacuum vacuum;
    final RecordReplacer replacer;
    final PageAppender appender;
    final List<VacuumedPage> vacuumed = new ArrayList<>();

    ReplacerFixture(File root, String dbName, String collName) throws IOException {
        fx = new StorageFixture(root, dbName, collName);
        vacuum = new PageVacuum(fx.paths, fx.store, fx.journal);
        vacuum.onVacuum(vacuumed::add);
        replacer = new RecordReplacer(fx.paths, fx.store, fx.journal, vacuum);
        appender = new PageAppender(fx.paths, fx.store, vacuum);
    }

    DbEntry entry(String id, String value, long page) {
        final var data = new JsonObject();
        data.addProperty("_id", id);
        data.addProperty("v", value);
        final var entry = DbEntry.fromJsonObject(fx.dbName, fx.collName, data);
        entry.setPage(page);
        entry.setVersion(StorageFixture.NEW_VERSION);
        return entry;
    }

    static byte[] line(String id, String value) {
        return StorageFixture.record(id, value).getBytes(StandardCharsets.UTF_8);
    }

    PkIndexEntry replace(String id, String value, long targetPage, PagePlacement placement) throws IOException {
        final var entry = entry(id, value, targetPage);
        return replacer.replace(entry, line(id, value), fx.indexed(id), targetPage, placement, null);
    }

    IndexedDbEntry indexed(String id, String value) throws IOException {
        final var row = fx.indexed(id);
        final var indexed = new IndexedDbEntry();
        indexed.setIndex(row);
        indexed.setDatabaseName(fx.dbName);
        indexed.setCollectionName(fx.collName);
        indexed.set_id(id);
        indexed.setData(entry(id, value, row.getPage()).getData());
        indexed.setVersion(StorageFixture.NEW_VERSION);
        return indexed;
    }

    List<IndexedDbEntry> bulk(List<IndexedDbEntry> entries) throws IOException {
        return replacer.bulkReplace(fx.dbName, fx.collName, entries, null,
                updated -> line(updated.get_id(), updated.getData().get("v").asJsonString().getValue()));
    }

    String valueOf(PkIndexEntry row) throws IOException {
        return fx.readAt(row);
    }

    int intents() throws IOException {
        return fx.openIntents();
    }
}
