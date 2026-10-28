package org.techhouse.fs;

import java.io.IOException;
import java.util.List;

final class PkRowWrites {
    private PkRowWrites() {
    }

    static void deleteRows(FilePaths paths, PkIndexStore store, String dbName, String collName, List<String> ids)
            throws IOException {
        final var plan = new WalPlan(paths, store, dbName, collName);
        ids.forEach(plan::deleteRow);
        plan.commit();
    }
}
