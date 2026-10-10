package org.techhouse.cluster.admin;

import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.admin.StoredDefinitions;

public final class UnversionedRecords {
    private static final Logger logger = Logger.logFor(UnversionedRecords.class);

    private UnversionedRecords() {
    }

    public static void stampAll() {
        try {
            for (final var record : AdminRecords.all()) {
                if (!record.isTombstone() && record.version() == 0) {
                    stamp(record);
                }
            }
        } catch (Exception failure) {
            logger.error(
                    "Could not version the admin records written before admin records were versioned; a peer's"
                            + " newer-looking copy, such as a fresh node's bootstrap admin, would replace them",
                    failure);
        }
    }

    private static void stamp(AdminRecord record) throws Exception {
        final var key = record.key();
        final var body = record.body();
        switch (key.kind()) {
            case DATABASE -> AdminOperationHelper.saveDatabaseEntry(AdminDbEntry.fromJsonObject(body));
            case COLLECTION -> AdminOperationHelper.saveCollectionEntry(AdminCollEntry.fromJsonObject(body));
            case USER -> AdminOperationHelper.saveUserEntry(AdminUserEntry.fromJsonObject(body));
            case SCHEMA -> StoredDefinitions.writeSchema(key.dbName(), key.name(), AdminStamp.unwrappedSchema(body));
            case TRIGGERS -> StoredDefinitions.writeTriggers(key.dbName(), key.name(), body);
            case PROCEDURE -> StoredDefinitions.writeProcedure(key.dbName(), key.name(), body);
            default -> StoredDefinitions.writeSchedule(key.dbName(), key.name(), body);
        }
    }
}
