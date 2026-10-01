package org.techhouse.bckg_ops.events;

import java.util.Objects;

public abstract class CollectionScopedEvent extends Event {
    private final String dbName;
    private final String collName;
    private final long incarnation;

    protected CollectionScopedEvent(EventType type, String dbName, String collName) {
        this(type, dbName, collName, 0L);
    }

    protected CollectionScopedEvent(EventType type, String dbName, String collName, long incarnation) {
        super(type);
        this.dbName = dbName;
        this.collName = collName;
        this.incarnation = incarnation;
    }

    public String getDbName() {
        return dbName;
    }

    public String getCollName() {
        return collName;
    }

    public long getIncarnation() {
        return incarnation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof CollectionScopedEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return incarnation == that.incarnation && Objects.equals(dbName, that.dbName)
                && Objects.equals(collName, that.collName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), dbName, collName, incarnation);
    }
}
