package org.techhouse.ops.admin;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ioc.IocContainer;

public final class AdminResidentRows {
    private static final Cache cache = IocContainer.get(Cache.class);

    private AdminResidentRows() {
    }

    public static List<PkIndexEntry> of(String collName) {
        return switch (collName) {
            case Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME ->
                new ArrayList<>(cache.getTransactionPkIndexes().values());
            case Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME -> new ArrayList<>(cache.getTriggerRunPkIndexes().values());
            case Globals.ADMIN_COLLECTION_USAGE_NAME -> new ArrayList<>(cache.getCollectionUsagePkIndexes().values());
            case null, default -> null;
        };
    }
}
