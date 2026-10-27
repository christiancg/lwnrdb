package org.techhouse.ops.admin;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ioc.IocContainer;

public final class AdminResidentRows {
    private static final Cache cache = IocContainer.get(Cache.class);

    private AdminResidentRows() {
    }

    public static List<PkIndexEntry> of(String collName) {
        final Map<String, PkIndexEntry> rows = switch (collName) {
            case Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME -> cache.getTransactionPkIndexes();
            case Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME -> cache.getTriggerRunPkIndexes();
            case Globals.ADMIN_COLLECTION_USAGE_NAME -> cache.getCollectionUsagePkIndexes();
            case null, default -> null;
        };
        return rows == null ? null : new CopiedOnFirstRead(rows);
    }

    private static final class CopiedOnFirstRead extends AbstractList<PkIndexEntry> {
        private final Map<String, PkIndexEntry> source;
        private List<PkIndexEntry> copy;

        private CopiedOnFirstRead(Map<String, PkIndexEntry> source) {
            this.source = source;
        }

        private List<PkIndexEntry> rows() {
            if (copy == null) {
                copy = new ArrayList<>(source.values());
            }
            return copy;
        }

        @Override
        public PkIndexEntry get(int index) {
            return rows().get(index);
        }

        @Override
        public int size() {
            return rows().size();
        }
    }
}
