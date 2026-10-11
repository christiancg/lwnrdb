package org.techhouse.fault;

public enum FaultPoint {
    // @formatter:off
    WRITE_OWNERSHIP_RECHECKED(false),
    DELETE_TOMBSTONE_RESERVED(false),
    ANTI_ENTROPY_SNAPSHOTTED(false),
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
