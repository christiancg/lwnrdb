package org.techhouse.bckg_ops.events;

import java.util.Objects;

public abstract class CollectionScopedEvent extends Event {
    private final String dbName;
    private final String collName;

    protected CollectionScopedEvent(EventType type, String dbName, String collName) {
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
        if (!(o instanceof CollectionScopedEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(dbName, that.dbName) && Objects.equals(collName, that.collName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), dbName, collName);
    }
}
