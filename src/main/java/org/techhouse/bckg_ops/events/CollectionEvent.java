package org.techhouse.bckg_ops.events;

import lombok.EqualsAndHashCode;
import lombok.Getter;

@EqualsAndHashCode(callSuper = true)
@Getter
public class CollectionEvent extends Event {
    private final String dbName;
    private final String collName;
    public CollectionEvent(final EventType type, final String dbName, final String collName) {
        super(type);
        this.dbName = dbName;
        this.collName = collName;
    }
}
