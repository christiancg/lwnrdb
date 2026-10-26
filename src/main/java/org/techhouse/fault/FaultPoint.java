package org.techhouse.fault;

public enum FaultPoint {
    // @formatter:off
    WRITE_OWNERSHIP_RECHECKED(false),
    WRITE_OWNER_PROBED(false),
    DELETE_TOMBSTONE_RESERVED(false),
    ANTI_ENTROPY_SNAPSHOTTED(false),
    PK_INDEX_REPLACED(false),
    INDEX_BATCH_FLUSHED(false),
    PAGE_COPY_APPENDED(false),
    PK_ROW_COMMITTED(false),
    PAGE_VACUUM_RENAMED(false),
    WAL_REWRITE_BEGUN(false),
    WAL_APPENDED(false),
    WAL_CHECKPOINT_FORCED(false),
    WAL_GROUP_APPENDED(false),
    WAL_GROUP_FORCED(false),
    PEER_REPLY(true);
    // @formatter:on

    private final boolean droppable;

    FaultPoint(boolean droppable) {
        this.droppable = droppable;
    }

    public boolean isDroppable() {
        return droppable;
    }
}
