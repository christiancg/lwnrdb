package org.techhouse.cache;

public record AdmissionRequest(AccessKind tier, String dbName, String collName, String fieldName, long bytes) {
    static AdmissionRequest forFieldIndex(String dbName, String collName, String indexIdentifier, long bytes) {
        return new AdmissionRequest(AccessKind.FIELD_INDEX, dbName, collName,
                CacheableResource.indexedFieldOf(indexIdentifier), bytes);
    }

    boolean isFieldOf(String otherDb, String otherColl, String otherField) {
        return otherField != null && otherField.equals(fieldName) && otherColl.equals(collName)
                && otherDb.equals(dbName);
    }
}
