package org.techhouse.fs;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.techhouse.data.PkIndexEntry;

public final class PkRowsSession {
    private final PkIndexStore store;
    private final String dbName;
    private final String collName;
    private boolean loaded;
    private List<PkIndexEntry> recognisedRows;

    PkRowsSession(PkIndexStore store, String dbName, String collName) {
        this.store = store;
        this.dbName = dbName;
        this.collName = collName;
    }

    PkIndexStore store() {
        return store;
    }

    String dbName() {
        return dbName;
    }

    String collName() {
        return collName;
    }

    public Optional<List<PkIndexEntry>> recognised() throws IOException {
        if (!loaded) {
            recognisedRows = store.readRecognisedPkIndex(dbName, collName).orElse(null);
            loaded = true;
        }
        return Optional.ofNullable(recognisedRows);
    }

    public List<PkIndexEntry> rows() throws IOException {
        final var rows = recognised();
        return rows.isPresent() ? rows.get() : store.readWholePkIndexFile(dbName, collName);
    }

    public void invalidate() {
        loaded = false;
        recognisedRows = null;
    }
}
