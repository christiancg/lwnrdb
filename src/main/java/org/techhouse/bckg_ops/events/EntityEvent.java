package org.techhouse.bckg_ops.events;

import java.util.Objects;
import org.techhouse.data.DbEntry;

public class EntityEvent extends CollectionScopedEvent {
    private final EntityRef entity;

    public EntityEvent(EventType type, String dbName, String collName, DbEntry dbEntry) {
        this(type, dbName, collName, dbEntry, 0L);
    }

    public EntityEvent(EventType type, String dbName, String collName, DbEntry dbEntry, long incarnation) {
        this(type, dbName, collName, dbEntry, incarnation, 0L);
    }

    public EntityEvent(EventType type, String dbName, String collName, DbEntry dbEntry, long incarnation,
            long pendingGeneration) {
        this(type, dbName, collName, EntityRef.of(dbEntry), incarnation, pendingGeneration);
    }

    public EntityEvent(EventType type, String dbName, String collName, EntityRef entity, long incarnation,
            long pendingGeneration) {
        super(type, dbName, collName, incarnation, pendingGeneration);
        this.entity = entity;
    }

    public EntityRef getEntity() {
        return entity;
    }

    public String getId() {
        return entity.id();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof EntityEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(entity, that.entity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), entity);
    }

    @Override
    public String toString() {
        return "EntityEvent(super=" + super.toString() + ", dbName=" + getDbName() + ", collName=" + getCollName()
                + ", incarnation=" + getIncarnation() + ", pendingGeneration=" + getPendingGeneration() + ", entity="
                + entity + ")";
    }
}
