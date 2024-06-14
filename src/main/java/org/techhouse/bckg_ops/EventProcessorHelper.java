package org.techhouse.bckg_ops;

import org.techhouse.bckg_ops.events.*;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;

import java.io.IOException;

public class EventProcessorHelper {
    public static void processEvent(Event event)
            throws IOException, InterruptedException {
        switch (event) {
            case DatabaseEvent databaseEvent -> processDatabaseEvent(databaseEvent);
            case CollectionEvent collectionEvent -> processCollectionEvent(collectionEvent);
            case EntityEvent entityEvent -> processEntityEvent(entityEvent);
            case IndexEvent indexEvent -> processIndexEvent(indexEvent);
            default -> throw new IllegalStateException("Unexpected value: " + event);
        }
    }

    private static void processDatabaseEvent(DatabaseEvent event)
            throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        if (event.getType() == EventType.CREATED) {
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
            throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        if (event.getType() == EventType.CREATED) {
            final var existingCollEntry = AdminOperationHelper.getCollectionEntry(dbName, collName);
            if (existingCollEntry == null) {
                final var newAdminCollEntry = new AdminCollEntry(dbName, collName);
                AdminOperationHelper.saveCollectionEntry(newAdminCollEntry);
            }
        } else {
            AdminOperationHelper.deleteCollectionEntry(dbName, collName);
        }
    }

    private static void processEntityEvent(EntityEvent event)
            throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        final var dbEntry = event.getDbEntry();
        final var type = event.getType();
        IndexHelper.updateIndexes(dbName, collName, dbEntry, type);
        AdminOperationHelper.updateEntryCount(dbName, collName, type);
    }

    private static void processIndexEvent(IndexEvent event)
            throws IOException, InterruptedException {
        final var dbName = event.getDbName();
        final var collName = event.getCollName();
        final var fieldName = event.getFieldName();
        if (event.getType() == EventType.CREATED) {
            if (!AdminOperationHelper.hasIndexEntry(dbName, collName, fieldName)) {
                AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
            }
        } else {
            AdminOperationHelper.deleteIndex(dbName, collName, fieldName);
        }
    }
}
