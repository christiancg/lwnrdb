package org.techhouse.bckg_ops.events;

import lombok.EqualsAndHashCode;
import lombok.Getter;

@EqualsAndHashCode(callSuper = true)
@Getter
public class DatabaseEvent extends Event {
    private final String dbName;
    public DatabaseEvent(final EventType type, final String dbName) {
        super(type);
        this.dbName = dbName;
    }
}
