package org.techhouse.ops;

import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.resp.OperationResponse;

// maxEntrySize is enforced on every write path, transactional or not - one error for one condition.
public final class EntrySizeGuard {
    private static final Configuration configuration = Configuration.getInstance();
    private static final long MAX_UTF8_BYTES_PER_CHAR = 3L;

    private EntrySizeGuard() {
    }

    public static OperationResponse check(DbEntry entry, OperationType type) {
        return checkSize(entry.byteSize(), type);
    }

    public static OperationResponse check(String dbName, String collName, JsonObject object, OperationType type) {
        return checkSize(DbEntry.fromJsonObject(dbName, collName, object).byteSize(), type);
    }

    public static boolean exceedsMaxEntrySize(CharSequence text) {
        final long maxEntrySize = configuration.getMaxEntrySize();
        return (long) text.length() * MAX_UTF8_BYTES_PER_CHAR > maxEntrySize && utf8Length(text) > maxEntrySize;
    }

    private static long utf8Length(CharSequence text) {
        var bytes = 0L;
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
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
