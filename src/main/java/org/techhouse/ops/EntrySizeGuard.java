package org.techhouse.ops;

import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.resp.OperationResponse;

// maxEntrySize is enforced on every write path, transactional or not, and a rejection has to name the
// same numbers wherever it comes from - the client sees one error for one condition.
public final class EntrySizeGuard {
    private static final Configuration configuration = Configuration.getInstance();

    private EntrySizeGuard() {
    }

    // Null when the entry fits; otherwise the rejection to return to the client.
    public static OperationResponse check(DbEntry entry, OperationType type) {
        return checkSize(entry.byteSize(), type);
    }

    public static OperationResponse check(String dbName, String collName, JsonObject object, OperationType type) {
        return checkSize(DbEntry.fromJsonObject(dbName, collName, object).byteSize(), type);
    }

    private static OperationResponse checkSize(int size, OperationType type) {
        final var maxEntrySize = configuration.getMaxEntrySize();
        if (size <= maxEntrySize) {
            return null;
        }
        return new OperationResponse(type,
                "Entry size of " + size + " bytes exceeds the maximum allowed size of " + maxEntrySize + " bytes",
                ErrorCode.ENTRY_TOO_LARGE);
    }
}
