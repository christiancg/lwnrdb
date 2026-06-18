package org.techhouse.bckg_ops.events;

import java.util.Objects;

public class DatabaseEvent extends Event {
    private final String dbName;

    public DatabaseEvent(final EventType type, final String dbName) {
        super(type);
        this.dbName = dbName;
    }

    public String getDbName() {
        return dbName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof DatabaseEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(dbName, that.dbName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), dbName);
    }

    @Override
    public String toString() {
        return "DatabaseEvent(super=" + super.toString() + ", dbName=" + dbName + ")";
    }
}
