package org.techhouse.ops.snapshot;

final class SnapshotCharges {
    static final long REMEMBERED_ID_BYTES = 96L;

    private SnapshotCharges() {
    }

    static long perId(String id) {
        return REMEMBERED_ID_BYTES + 2L * id.length();
    }
}
