package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

// An append-and-remove admin collection addressed purely by primary key: transaction slice ops and
// pending trigger runs. Both only ever insert and delete whole records, so they share one shape.
public final class AdminRecordStore<T> {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private final String collName;
    private final String recordLabel;
    private final Function<String, PkIndexEntry> pkLookup;
    private final Consumer<String> pkRemove;
    private final Function<JsonObject, T> factory;
    private final EntryCountUpdater entryCountUpdater;

    public AdminRecordStore(String collName, String recordLabel, Function<String, PkIndexEntry> pkLookup,
            Consumer<String> pkRemove, Function<JsonObject, T> factory, EntryCountUpdater entryCountUpdater) {
        this.collName = collName;
        this.recordLabel = recordLabel;
        this.pkLookup = pkLookup;
        this.pkRemove = pkRemove;
        this.factory = factory;
        this.entryCountUpdater = entryCountUpdater;
    }

    public interface EntryCountUpdater {
        void apply(String dbName, String collName, EventType type, List<DbEntry> entries)
                throws IOException, InterruptedException;
    }

    public List<T> read(List<String> recordIds) throws IOException, InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, collName);
        try {
            final var result = new ArrayList<T>();
            for (var recordId : recordIds) {
                final var pk = pkLookup.apply(recordId);
                final var entry = pk == null ? null : readEntry(pk, recordId);
                if (entry == null) {
                    continue;
                }
                final var data = entry.getData();
                data.addProperty(Globals.PK_FIELD, entry.get_id());
                result.add(factory.apply(data));
            }
            return result;
        } finally {
            locks.release(Globals.ADMIN_DB_NAME, collName);
        }
    }

    public void delete(List<String> recordIds) throws IOException, InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, collName);
        try {
            for (var recordId : recordIds) {
                final var pk = pkLookup.apply(recordId);
                final var entry = pk == null ? null : readEntry(pk, recordId);
                if (entry == null) {
                    continue;
                }
                entry.setPage(pk.getPage());
                entry.setPreviousByteSize(pk.getLength());
                cache.shiftPkPositionsAfterCompaction(fs.deleteFromCollection(pk));
                pkRemove.accept(recordId);
                entryCountUpdater.apply(Globals.ADMIN_DB_NAME, collName, EventType.DELETED, List.of(entry));
            }
        } finally {
            locks.release(Globals.ADMIN_DB_NAME, collName);
        }
    }

    private DbEntry readEntry(PkIndexEntry pk, String recordId) throws IOException {
        try {
            return fs.getById(pk);
        } catch (Exception ex) {
            throw new IOException("Failed to read " + recordLabel + " " + recordId, ex);
        }
    }
}
