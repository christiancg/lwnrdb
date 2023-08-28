package org.techhouse.bckg_ops.events;

import lombok.EqualsAndHashCode;
import lombok.Getter;

@EqualsAndHashCode(callSuper = true)
@Getter
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
}
