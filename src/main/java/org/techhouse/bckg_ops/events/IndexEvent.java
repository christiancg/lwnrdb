package org.techhouse.bckg_ops.events;

import java.util.Objects;

public class IndexEvent extends Event {
    private final String dbName;
    private final String collName;
    private final String fieldName;

    public IndexEvent(EventType type, String dbName, String collName, String fieldName) {
        super(type);
        this.dbName = dbName;
        this.collName = collName;
        this.fieldName = fieldName;
    }

    public String getDbName() {
        return dbName;
    }

    public String getCollName() {
        return collName;
    }

    public String getFieldName() {
        return fieldName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof IndexEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(dbName, that.dbName) && Objects.equals(collName, that.collName)
                && Objects.equals(fieldName, that.fieldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), dbName, collName, fieldName);
    }

    @Override
    public String toString() {
        return "IndexEvent(super=" + super.toString() + ", dbName=" + dbName + ", collName=" + collName + ", fieldName="
                + fieldName + ")";
    }
}
