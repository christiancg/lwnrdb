package org.techhouse.bckg_ops.events;

import java.util.Objects;

public class CollectionEvent extends Event {
    private final String dbName;
    private final String collName;

    public CollectionEvent(final EventType type, final String dbName, final String collName) {
        super(type);
        this.dbName = dbName;
        this.collName = collName;
    }

    public String getDbName() {
        return dbName;
    }

    public String getCollName() {
        return collName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof CollectionEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(dbName, that.dbName) && Objects.equals(collName, that.collName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), dbName, collName);
    }

    @Override
    public String toString() {
        return "CollectionEvent(super=" + super.toString() + ", dbName=" + dbName + ", collName=" + collName + ")";
    }
}
