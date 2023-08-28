package org.techhouse.bckg_ops;

import org.techhouse.bckg_ops.events.*;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ops.AdminOperationHelper;

import java.util.concurrent.ExecutionException;

public class EventProcessorHelper {
    public static void processEvent(Event event)
            throws ExecutionException, InterruptedException {
        switch (event) {
            case DatabaseEvent databaseEvent -> processDatabaseEvent(databaseEvent);
            case CollectionEvent collectionEvent -> processCollectionEvent(collectionEvent);
            case EntityEvent entityEvent -> processEntityEvent(entityEvent);
            case IndexEvent indexEvent -> processIndexEvent(indexEvent);
            default -> throw new IllegalStateException("Unexpected value: " + event);
        }
    }

    private static void processDatabaseEvent(DatabaseEvent event)
            throws ExecutionException, InterruptedException {
        final var dbName = event.getDbName();
        if (event.getType() == EventType.CREATED_UPDATED) {
            final var existingDbEntry = AdminOperationHelper.getDatabaseEntry(dbName);
            if (existingDbEntry == null) {
                final var newAdminDbEntry = new AdminDbEntry(dbName);
                AdminOperationHelper.saveDatabaseEntry(newAdminDbEntry);
            }
        } else {
            AdminOperationHelper.deleteDatabaseEntry(dbName);
        }
    }

    private static void processCollectionEvent(CollectionEvent event)
            throws ExecutionException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        if (event.getType() == EventType.CREATED_UPDATED) {
            final var existingCollEntry = AdminOperationHelper.getCollectionEntry(dbName, collName);
            if (existingCollEntry == null) {
                final var newAdminCollEntry = new AdminCollEntry(dbName, collName);
                AdminOperationHelper.saveCollectionEntry(newAdminCollEntry);
            }
        } else {
            AdminOperationHelper.deleteCollectionEntry(dbName, collName);
        }
    }

    private static void processEntityEvent(EntityEvent event) {
        System.out.println("Entity " + event.getDbEntry().get_id() + " has been " + event.getType());
    }

    private static void processIndexEvent(IndexEvent event)
            throws ExecutionException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        final var fieldName = event.getFieldName();
        if (event.getType() == EventType.CREATED_UPDATED) {
            if (!AdminOperationHelper.hasIndexEntry(dbName, collName, fieldName)) {
                AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
            }
        } else {
            AdminOperationHelper.deleteIndex(dbName, collName, fieldName);
        }
    }
}
