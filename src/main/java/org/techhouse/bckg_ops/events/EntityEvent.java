package org.techhouse.bckg_ops.events;

import java.util.Objects;
import org.techhouse.data.DbEntry;

public class EntityEvent extends CollectionScopedEvent {
    private final DbEntry dbEntry;

    public EntityEvent(EventType type, String dbName, String collName, DbEntry dbEntry) {
        this(type, dbName, collName, dbEntry, 0L);
    }

    public EntityEvent(EventType type, String dbName, String collName, DbEntry dbEntry, long incarnation) {
        super(type, dbName, collName, incarnation);
        this.dbEntry = dbEntry;
    }

    public DbEntry getDbEntry() {
        return dbEntry;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof EntityEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(dbEntry, that.dbEntry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), dbEntry);
    }

    @Override
    public String toString() {
        return "EntityEvent(super=" + super.toString() + ", dbName=" + getDbName() + ", collName=" + getCollName()
                + ", incarnation=" + getIncarnation() + ", dbEntry=" + dbEntry + ")";
    }
}
