package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public final class AdminRecordStore<T> {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private final String collName;
    private final String recordLabel;
    private final Function<String, PkIndexEntry> pkLookup;
    private final Function<JsonObject, T> factory;

    public AdminRecordStore(String collName, String recordLabel, Function<String, PkIndexEntry> pkLookup,
            Function<JsonObject, T> factory) {
        this.collName = collName;
        this.recordLabel = recordLabel;
        this.pkLookup = pkLookup;
        this.factory = factory;
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

    private DbEntry readEntry(PkIndexEntry pk, String recordId) throws IOException {
        try {
            return fs.getById(pk);
        } catch (Exception ex) {
            throw new IOException("Failed to read " + recordLabel + " " + recordId, ex);
        }
    }
}
