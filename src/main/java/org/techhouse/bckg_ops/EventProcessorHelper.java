package org.techhouse.bckg_ops;

import org.techhouse.bckg_ops.events.CollectionEvent;
import org.techhouse.bckg_ops.events.DatabaseEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;

public class EventProcessorHelper {
    public static void processEvent(Event event) {
        switch (event) {
            case DatabaseEvent databaseEvent -> processDatabaseEvent(databaseEvent);
            case CollectionEvent collectionEvent -> processCollectionEvent(collectionEvent);
            case EntityEvent entityEvent -> processEntityEvent(entityEvent);
            default -> throw new IllegalStateException("Unexpected value: " + event);
        }
    }

    private static void processDatabaseEvent(DatabaseEvent event) {
        System.out.println("Database " + event.getDbName() + " has been " + event.getType());
    }

    private static void processCollectionEvent(CollectionEvent event) {
        System.out.println("Collection " + event.getCollName() + " has been " + event.getType());
    }

    private static void processEntityEvent(EntityEvent event) {
        System.out.println("Entity " + event.getDbEntry().get_id() + " has been " + event.getType());
    }
}
