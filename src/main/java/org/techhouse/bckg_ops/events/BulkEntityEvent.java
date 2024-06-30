package org.techhouse.bckg_ops.events;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.techhouse.data.DbEntry;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Getter
public class BulkEntityEvent extends Event {
    private final String dbName;
    private final String collName;
    private final List<DbEntry> insertedEntries;
    private final List<DbEntry> updatedEntries;
    public BulkEntityEvent(String dbName, String collName, List<DbEntry> insertedEntries, List<DbEntry> updatedEntries) {
        super(EventType.CREATED);
        this.dbName = dbName;
        this.collName = collName;
        this.insertedEntries = insertedEntries;
        this.updatedEntries = updatedEntries;
    }
}
