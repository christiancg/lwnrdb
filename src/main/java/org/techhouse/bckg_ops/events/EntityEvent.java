package org.techhouse.bckg_ops.events;

import java.util.Objects;
import org.techhouse.data.DbEntry;

public class EntityEvent extends Event {
    private final String dbName;
    private final String collName;
    private final DbEntry dbEntry;

    public EntityEvent(EventType type, String dbName, String collName, DbEntry dbEntry) {
        super(type);
        this.dbName = dbName;
        this.collName = collName;
        this.dbEntry = dbEntry;
    }

    public String getDbName() {
        return dbName;
    }

    public String getCollName() {
        return collName;
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
        return Objects.equals(dbName, that.dbName) && Objects.equals(collName, that.collName)
                && Objects.equals(dbEntry, that.dbEntry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), dbName, collName, dbEntry);
    }

    @Override
    public String toString() {
        return "EntityEvent(super=" + super.toString() + ", dbName=" + dbName + ", collName=" + collName + ", dbEntry="
                + dbEntry + ")";
    }
}
