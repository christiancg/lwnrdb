package org.techhouse.bckg_ops.events;

import java.util.List;
import java.util.Objects;
import org.techhouse.data.DbEntry;

public class BulkEntityEvent extends CollectionScopedEvent {
    private final List<EntityRef> inserted;
    private final List<EntityRef> updated;

    public BulkEntityEvent(String dbName, String collName, List<DbEntry> insertedEntries,
            List<DbEntry> updatedEntries) {
        this(dbName, collName, insertedEntries, updatedEntries, 0L);
    }

    public BulkEntityEvent(String dbName, String collName, List<DbEntry> insertedEntries, List<DbEntry> updatedEntries,
            long incarnation) {
        this(dbName, collName, insertedEntries, updatedEntries, incarnation, 0L);
    }

    public BulkEntityEvent(String dbName, String collName, List<DbEntry> insertedEntries, List<DbEntry> updatedEntries,
            long incarnation, long pendingGeneration) {
        super(EventType.CREATED, dbName, collName, incarnation, pendingGeneration);
        this.inserted = EntityRef.allOf(insertedEntries);
        this.updated = EntityRef.allOf(updatedEntries);
    }

    public List<EntityRef> getInserted() {
        return inserted;
    }

    public List<EntityRef> getUpdated() {
        return updated;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof BulkEntityEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(inserted, that.inserted) && Objects.equals(updated, that.updated);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), inserted, updated);
    }

    @Override
    public String toString() {
        return "BulkEntityEvent(super=" + super.toString() + ", dbName=" + getDbName() + ", collName=" + getCollName()
                + ", incarnation=" + getIncarnation() + ", pendingGeneration=" + getPendingGeneration() + ", inserted="
                + inserted + ", updated=" + updated + ")";
    }
}
