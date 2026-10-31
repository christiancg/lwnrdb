package org.techhouse.fs;

import org.techhouse.config.Globals;

public enum PagePlacement {
    APPEND, VACUUM_THEN_APPEND, RELOCATE;

    public static PagePlacement forUpdate(long liveBefore, long fileLength, int lineLength, long maxPageSize) {
        if (fileLength + lineLength <= maxPageSize) {
            return APPEND;
        }
        return liveBytesAdmit(liveBefore, lineLength, maxPageSize) && isVacuumable(liveBefore, fileLength, maxPageSize)
                ? VACUUM_THEN_APPEND
                : RELOCATE;
    }

    public static PagePlacement forStay(long liveBytes, long fileLength, long appendedBytes, long maxPageSize) {
        if (fileLength + appendedBytes <= maxPageSize) {
            return APPEND;
        }
        return isVacuumable(liveBytes, fileLength, maxPageSize) ? VACUUM_THEN_APPEND : APPEND;
    }

    public static PagePlacement forCompactStay(long liveBytes, long fileLength, long appendedBytes, long maxPageSize) {
        if (fileLength - liveBytes >= Math.max(compactFloor(maxPageSize), liveBytes)) {
            return VACUUM_THEN_APPEND;
        }
        return forStay(liveBytes, fileLength, appendedBytes, maxPageSize);
    }

    static long compactFloor(long maxPageSize) {
        return Math.min(quarterOf(maxPageSize), Globals.COMPACT_STAY_DEAD_FLOOR);
    }

    public static boolean acceptsInsert(long liveBytes, long fileLength, long insertedBytes, long maxPageSize) {
        return liveBytesAdmit(liveBytes, insertedBytes, maxPageSize)
                && (fileLength + insertedBytes <= maxPageSize || isVacuumable(liveBytes, fileLength, maxPageSize));
    }

    public static boolean liveBytesAdmit(long liveBytes, long insertedBytes, long maxPageSize) {
        return liveBytes + insertedBytes <= maxPageSize;
    }

    public static boolean vacuumsAfterWrite(long liveAfter, long fileLength, long maxPageSize) {
        return fileLength > 0
                && (liveAfter <= 0 || fileLength - liveAfter >= Math.max(compactFloor(maxPageSize), liveAfter));
    }

    public static boolean vacuumsAfterDelete(long liveAfter, long fileLength, long maxPageSize) {
        return vacuumsAfterWrite(liveAfter, fileLength, maxPageSize)
                || fileLength > 0 && isVacuumable(liveAfter, fileLength, maxPageSize);
    }

    private static boolean isVacuumable(long liveBytes, long fileLength, long maxPageSize) {
        return fileLength - liveBytes >= quarterOf(maxPageSize);
    }

    private static long quarterOf(long maxPageSize) {
        return maxPageSize / Globals.PAGE_VACUUM_DEAD_DIVISOR;
    }
}
