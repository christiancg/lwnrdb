package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ioc.IocContainer;

public final class TriggerRunRecords {
    private static final String COLL = Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME;
    @SuppressWarnings("FieldMayBeFinal")
    private static Cache cache = IocContainer.get(Cache.class);
    private static final AdminBatchWrite.PkRows PK_ROWS = new AdminBatchWrite.PkRows() {
        @Override
        public PkIndexEntry get(String id) {
            return cache.getPkIndexTriggerRun(id);
        }

        @Override
        public void publish(PkIndexEntry row) {
            cache.putPkIndexTriggerRun(row);
        }

        @Override
        public void unpublish(Set<String> ids) {
            for (final var id : ids) {
                cache.removePkIndexTriggerRun(id);
            }
        }
    };

    private TriggerRunRecords() {
    }

    public static void insert(List<AdminTriggerRunEntry> entries) throws IOException, InterruptedException {
        if (entries.isEmpty()) {
            return;
        }
        final var lines = new IdentityHashMap<AdminTriggerRunEntry, byte[]>();
        for (final var entry : entries) {
            lines.put(entry, entry.encodeLine());
        }
        AdminBatchWrite.writeInChunks(COLL, PK_ROWS, entries, lines::get, List.of());
    }
}
