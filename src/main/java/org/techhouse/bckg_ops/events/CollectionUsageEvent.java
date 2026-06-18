package org.techhouse.bckg_ops.events;

import java.util.Objects;
import org.techhouse.cache.AccessKind;

public class CollectionUsageEvent extends Event {
    private final AccessKind kind;
    private final String dbName;
    private final String collName;
    private final String indexKey;
    private final long timestampMillis;

    public CollectionUsageEvent(AccessKind kind, String dbName, String collName, String indexKey,
            long timestampMillis) {
        super(EventType.UPDATED);
        this.kind = kind;
        this.dbName = dbName;
        this.collName = collName;
        this.indexKey = indexKey == null ? "" : indexKey;
        this.timestampMillis = timestampMillis;
    }

    public AccessKind getKind() {
        return kind;
    }

    public String getDbName() {
        return dbName;
    }

    public String getCollName() {
        return collName;
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
        return timestampMillis == that.timestampMillis && kind == that.kind && Objects.equals(dbName, that.dbName)
                && Objects.equals(collName, that.collName) && Objects.equals(indexKey, that.indexKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), kind, dbName, collName, indexKey, timestampMillis);
    }

    @Override
    public String toString() {
        return "CollectionUsageEvent(super=" + super.toString() + ", kind=" + kind + ", dbName=" + dbName
                + ", collName=" + collName + ", indexKey=" + indexKey + ", timestampMillis=" + timestampMillis + ")";
    }
}
