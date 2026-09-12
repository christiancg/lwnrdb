package org.techhouse.bckg_ops.events;

import java.util.Objects;
import org.techhouse.cache.AccessKind;

public class CollectionUsageEvent extends CollectionScopedEvent {
    private final AccessKind kind;
    private final String indexKey;
    private final long timestampMillis;

    public CollectionUsageEvent(AccessKind kind, String dbName, String collName, String indexKey,
            long timestampMillis) {
        super(EventType.UPDATED, dbName, collName);
        this.kind = kind;
        this.indexKey = indexKey == null ? "" : indexKey;
        this.timestampMillis = timestampMillis;
    }

    public AccessKind getKind() {
        return kind;
    }

    public String getIndexKey() {
        return indexKey;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof CollectionUsageEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return timestampMillis == that.timestampMillis && kind == that.kind && Objects.equals(indexKey, that.indexKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), kind, indexKey, timestampMillis);
    }

    @Override
    public String toString() {
        return "CollectionUsageEvent(super=" + super.toString() + ", kind=" + kind + ", dbName=" + getDbName()
                + ", collName=" + getCollName() + ", indexKey=" + indexKey + ", timestampMillis=" + timestampMillis
                + ")";
    }
}
