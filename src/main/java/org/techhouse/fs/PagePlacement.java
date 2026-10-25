package org.techhouse.fs;

import org.techhouse.config.Globals;

public enum PagePlacement {
    APPEND, VACUUM_THEN_APPEND, RELOCATE;

    public static PagePlacement forUpdate(long liveAfter, long fileLength, int lineLength, long maxPageSize) {
        if (fileLength + lineLength <= maxPageSize) {
            return APPEND;
        }
        return maxPageSize - liveAfter >= quarterOf(maxPageSize) ? VACUUM_THEN_APPEND : RELOCATE;
    }

    public static PagePlacement forStay(long liveBytes, long fileLength, long appendedBytes, long maxPageSize) {
        if (fileLength + appendedBytes <= maxPageSize) {
            return APPEND;
        }
        return isVacuumable(liveBytes, fileLength, maxPageSize) ? VACUUM_THEN_APPEND : APPEND;
    }

    public static boolean acceptsInsert(long liveBytes, long fileLength, long insertedBytes, long maxPageSize) {
        return liveBytesAdmit(liveBytes, insertedBytes, maxPageSize)
                && (fileLength + insertedBytes <= maxPageSize || isVacuumable(liveBytes, fileLength, maxPageSize));
    }

    public static boolean liveBytesAdmit(long liveBytes, long insertedBytes, long maxPageSize) {
        return liveBytes + insertedBytes <= maxPageSize;
    }

    public static boolean vacuumsAfterDelete(long liveAfter, long fileLength, long maxPageSize) {
        return fileLength > 0 && (liveAfter <= 0 || isVacuumable(liveAfter, fileLength, maxPageSize));
    }

    private static boolean isVacuumable(long liveBytes, long fileLength, long maxPageSize) {
        return fileLength - liveBytes >= quarterOf(maxPageSize);
    }

    private static long quarterOf(long maxPageSize) {
        return maxPageSize / Globals.PAGE_VACUUM_DEAD_DIVISOR;
    }
}
