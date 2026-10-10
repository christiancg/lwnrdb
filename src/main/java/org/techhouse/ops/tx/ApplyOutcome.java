package org.techhouse.ops.tx;

public enum ApplyOutcome {
    APPLIED, ALREADY_APPLIED, SUPERSEDED;

    private static final long UNVERSIONED = 0L;

    public static ApplyOutcome forSave(long storedVersion, long tombstoneVersion, long opVersion) {
        if (opVersion <= UNVERSIONED) {
            return APPLIED;
        }
        if (storedVersion == opVersion) {
            return ALREADY_APPLIED;
        }
        if (storedVersion > opVersion || tombstoneVersion >= opVersion) {
            return SUPERSEDED;
        }
        return APPLIED;
    }

    public static ApplyOutcome forDelete(long storedVersion, boolean present, long opVersion) {
        if (!present) {
            return ALREADY_APPLIED;
        }
        if (opVersion <= UNVERSIONED) {
            return APPLIED;
        }
        return storedVersion > opVersion ? SUPERSEDED : APPLIED;
    }

    public boolean firesTriggers() {
        return this != SUPERSEDED;
    }
}
