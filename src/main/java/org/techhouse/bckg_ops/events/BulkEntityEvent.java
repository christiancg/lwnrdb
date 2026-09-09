package org.techhouse.bckg_ops.events;

import java.util.List;
import java.util.Objects;
import org.techhouse.data.DbEntry;

public class BulkEntityEvent extends CollectionScopedEvent {
    private final List<DbEntry> insertedEntries;
    private final List<DbEntry> updatedEntries;

    public BulkEntityEvent(String dbName, String collName, List<DbEntry> insertedEntries,
            List<DbEntry> updatedEntries) {
        super(EventType.CREATED, dbName, collName);
        this.insertedEntries = insertedEntries;
        this.updatedEntries = updatedEntries;
    }

    public List<DbEntry> getInsertedEntries() {
        return insertedEntries;
    }

    public List<DbEntry> getUpdatedEntries() {
        return updatedEntries;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof BulkEntityEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(insertedEntries, that.insertedEntries)
                && Objects.equals(updatedEntries, that.updatedEntries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), insertedEntries, updatedEntries);
    }

    @Override
    public String toString() {
        return "BulkEntityEvent(super=" + super.toString() + ", dbName=" + getDbName() + ", collName=" + getCollName()
                + ", insertedEntries=" + insertedEntries + ", updatedEntries=" + updatedEntries + ")";
    }
}
