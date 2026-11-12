package org.techhouse.ops.req.validations;

import java.util.HashSet;
import java.util.UUID;
import org.techhouse.config.Globals;
import org.techhouse.ops.req.ReadSnapshotRequest;
import org.techhouse.ops.req.ReleaseSnapshotRequest;
import org.techhouse.ops.req.StartSnapshotRequest;

final class SnapshotRequestValidator {
    private SnapshotRequestValidator() {
    }

    static ValidationResult validateStart(StartSnapshotRequest request) {
        final var database = NameValidations.validateDbOnly(request, true);
        if (!database.isValid() || request.getCollectionNames() == null) {
            return database;
        }
        if (request.getCollectionNames().isEmpty()) {
            return ValidationResult.fail("START_SNAPSHOT collectionNames must name at least one collection");
        }
        final var seen = new HashSet<String>();
        for (final var collectionName : request.getCollectionNames()) {
            final var collection = NameValidations.validateCollectionName(collectionName);
            if (!collection.isValid()) {
                return collection;
            }
            if (!seen.add(collectionName)) {
                return ValidationResult.fail("START_SNAPSHOT collectionNames names '" + collectionName + "' twice");
            }
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateRead(ReadSnapshotRequest request) {
        final var names = NameValidations.validateDbAndColl(request, true);
        if (!names.isValid()) {
            return names;
        }
        final var snapshotId = validateSnapshotId("READ_SNAPSHOT", request.getSnapshotId());
        if (!snapshotId.isValid()) {
            return snapshotId;
        }
        if (request.getAfterId() != null && !NameValidations.isId(request.getAfterId())) {
            return ValidationResult.fail("READ_SNAPSHOT afterId must be a valid _id");
        }
        if (request.getLimit() < 1 || request.getLimit() > Globals.SNAPSHOT_MAX_PAGE_DOCUMENTS) {
            return ValidationResult
                    .fail("READ_SNAPSHOT limit must be between 1 and " + Globals.SNAPSHOT_MAX_PAGE_DOCUMENTS);
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateRelease(ReleaseSnapshotRequest request) {
        final var database = NameValidations.validateDbOnly(request, true);
        return database.isValid() ? validateSnapshotId("RELEASE_SNAPSHOT", request.getSnapshotId()) : database;
    }

    private static ValidationResult validateSnapshotId(String operation, String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            return ValidationResult.fail(operation + " request requires a snapshotId");
        }
        try {
            UUID.fromString(snapshotId);
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail(operation + " snapshotId must be a valid UUID");
        }
        return ValidationResult.ok();
    }
}
