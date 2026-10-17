package org.techhouse.cluster.admin;

import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.req.validations.NameValidations;

public final class AdminRecordNames {
    private AdminRecordNames() {
    }

    public static AdminRecordKey parse(String id) {
        if (id == null) {
            return null;
        }
        final AdminRecordKey key;
        try {
            key = AdminRecordKey.parse(id);
        } catch (RuntimeException malformed) {
            return null;
        }
        return key.id().equals(id) && accepts(key) ? key : null;
    }

    public static boolean accepts(AdminRecordKey key) {
        return switch (key.kind()) {
            case DATABASE -> isDatabase(key.dbName()) && key.dbName().equals(key.name());
            case USER -> key.dbName().isEmpty() && NameValidations.isUsername(key.name());
            case COLLECTION, SCHEMA, TRIGGERS, PROCEDURE, SCHEDULE ->
                isDatabase(key.dbName()) && NameValidations.isName(key.name());
        };
    }

    private static boolean isDatabase(String dbName) {
        return NameValidations.isName(dbName) && !NameValidations.isReservedDbName(dbName);
    }
}
