package org.techhouse.cache;

import java.util.List;
import org.techhouse.data.FieldIndexEntry;

public final class FieldIndexList extends ChunkedList<FieldIndexEntry<?>> {
    private boolean sharesPkIds;
    private long sharedIdBytes;

    private FieldIndexList() {
    }

    public static FieldIndexList of(List<? extends FieldIndexEntry<?>> sorted) {
        final var list = new FieldIndexList();
        list.fill(sorted);
        return list;
    }

    public static FieldIndexList sharingPkIds(List<?> entries) {
        return entries instanceof FieldIndexList list && list.sharesPkIds ? list : null;
    }

    public boolean sharesPkIds() {
        return sharesPkIds;
    }

    public long sharedIdBytes() {
        return sharedIdBytes;
    }

    void shareWithPk() {
        sharesPkIds = true;
    }

    public void adjustSharedIdBytes(long delta) {
        sharedIdBytes = Math.max(0L, sharedIdBytes + delta);
    }
}
