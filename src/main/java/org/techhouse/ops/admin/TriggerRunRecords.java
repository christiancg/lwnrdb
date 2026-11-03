package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.TriggerRunIndex;

public final class TriggerRunRecords {
    private static final String COLL = Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME;
    @SuppressWarnings("FieldMayBeFinal")
    private static Cache cache = IocContainer.get(Cache.class);

    private record Rows(Map<String, AdminTriggerRunEntry> written) implements AdminBatchWrite.PkRows {
        @Override
        public PkIndexEntry get(String id) {
            return cache.getPkIndexTriggerRun(id);
        }

        @Override
        public void publish(PkIndexEntry row) {
            cache.putPkIndexTriggerRun(row);
            final var entry = written.get(row.getValue());
            if (entry == null) {
                TriggerRunIndex.forget(row.getValue());
            } else {
                TriggerRunIndex.learn(entry, row);
            }
        }

        @Override
        public void unpublish(Set<String> ids) {
            for (final var id : ids) {
                cache.removePkIndexTriggerRun(id);
                TriggerRunIndex.forget(id);
            }
        }
    }

    private TriggerRunRecords() {
    }

    public static void insert(List<AdminTriggerRunEntry> entries) throws IOException, InterruptedException {
        write(entries, List.of());
    }

    public static void write(List<AdminTriggerRunEntry> entries, List<String> replacedRecordIds)
            throws IOException, InterruptedException {
        if (entries.isEmpty()) {
            return;
        }
        final var lines = new IdentityHashMap<AdminTriggerRunEntry, byte[]>();
        final var byId = new HashMap<String, AdminTriggerRunEntry>(entries.size() * 2);
        for (final var entry : entries) {
            lines.put(entry, entry.encodeLine());
            byId.put(entry.get_id(), entry);
        }
        AdminBatchWrite.writeInChunks(COLL, new Rows(byId), entries, lines::get, replacedRecordIds);
    }

    public static void delete(List<String> recordIds) throws IOException, InterruptedException {
        if (recordIds.isEmpty()) {
            return;
        }
        AdminBatchWrite.write(COLL, new Rows(Map.of()), List.<AdminTriggerRunEntry>of(), _ -> new byte[0], recordIds);
    }
}
