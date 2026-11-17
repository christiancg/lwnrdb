package org.techhouse.fault;

public enum FaultPoint {
    // @formatter:off
    WRITE_OWNERSHIP_RECHECKED(false),
    WRITE_OWNER_PROBED(false),
    DELETE_TOMBSTONE_RESERVED(false),
    ANTI_ENTROPY_SNAPSHOTTED(false),
    REPLICATED_APPLY_LOCKED(false),
    REPLICATED_TOMBSTONES_APPENDED(false),
    PK_INDEX_REPLACED(false),
    INDEX_BATCH_FLUSHED(false),
    INDEX_BUILD_FIELD_WRITTEN(false),
    PAGE_COPY_APPENDED(false),
    PK_ROW_COMMITTED(false),
    PAGE_VACUUM_RENAMED(false),
    WAL_REWRITE_BEGUN(false),
    WAL_APPENDED(false),
    WAL_CHECKPOINT_FORCED(false),
    WAL_GROUP_APPENDED(false),
    WAL_GROUP_FORCED(false),
    GROUP_RUNS_STAGED(false),
    PEER_REPLY(true),
    FILE_FORCED(false, true);
    // @formatter:on

    private final boolean droppable;
    private final boolean failable;

    FaultPoint(boolean droppable) {
        this(droppable, false);
    }

    FaultPoint(boolean droppable, boolean failable) {
        this.droppable = droppable;
        this.failable = failable;
    }

    public boolean isDroppable() {
        return droppable;
    }

    public boolean isFailable() {
        return failable;
    }
}
