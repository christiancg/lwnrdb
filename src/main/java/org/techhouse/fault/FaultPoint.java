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

    public static FaultPoint parse(String name) {
        for (final var point : values()) {
            if (point.name().equalsIgnoreCase(name)) {
                return point;
            }
        }
        return null;
    }
}
