package org.techhouse.bckg_ops.events;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.techhouse.data.DbEntry;

@EqualsAndHashCode(callSuper = true)
@Getter
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
}
