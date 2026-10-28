package org.techhouse.cache;

public record AdmissionRequest(AccessKind tier, String dbName, String collName, String fieldName, long bytes) {
    boolean isFieldOf(String otherDb, String otherColl, String otherField) {
        return otherField != null && otherField.equals(fieldName) && otherColl.equals(collName)
                && otherDb.equals(dbName);
    }
}
