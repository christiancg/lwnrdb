package org.techhouse.ops.req.validations;

import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public final class DataRequestValidator {
    private DataRequestValidator() {
    }

    static ValidationResult validateSave(SaveRequest request) {
        final var base = NameValidations.validateDbAndColl(request, true, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.getObject() == null) {
            return ValidationResult.fail("SAVE request requires an object");
        }
        if (request.get_id() != null && !request.get_id().matches(NameValidations.ID_PATTERN)) {
            return ValidationResult.fail("_id must be 1-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateBulkSave(BulkSaveRequest request) {
        final var base = NameValidations.validateDbAndColl(request, true, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.getObjects() == null || request.getObjects().isEmpty()) {
            return ValidationResult.fail("BULK_SAVE request requires at least one object");
        }
        for (var obj : request.getObjects()) {
            if (obj.has(Globals.PK_FIELD)) {
                final var id = obj.get(Globals.PK_FIELD).asJsonString().getValue();
                if (!id.matches(NameValidations.ID_PATTERN)) {
                    return ValidationResult.fail("_id must be 1-64 alphanumeric characters, underscores, or hyphens");
                }
            }
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateFindById(FindByIdRequest request) {
        final var base = NameValidations.validateDbAndColl(request, false);
        if (!base.isValid()) {
            return base;
        }
        if (request.get_id() == null) {
            return ValidationResult.fail("FIND_BY_ID request requires an _id");
        }
        if (!request.get_id().matches(NameValidations.ID_PATTERN)) {
            return ValidationResult.fail("_id must be 1-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateDelete(DeleteRequest request) {
        final var base = NameValidations.validateDbAndColl(request, true, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.get_id() == null) {
            return ValidationResult.fail("DELETE request requires an _id");
        }
        if (!request.get_id().matches(NameValidations.ID_PATTERN)) {
            return ValidationResult.fail("_id must be 1-64 alphanumeric characters, underscores, or hyphens");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateAggregate(AggregateRequest request) {
        final var base = NameValidations.validateDbAndColl(request, false);
        if (!base.isValid()) {
            return base;
        }
        if (request.getAggregationSteps() == null) {
            return ValidationResult.fail("AGGREGATE request requires an aggregationSteps array");
        }
        return validateAggregationSteps(request.getAggregationSteps());
    }

    // Validates each step in isolation and enforces the only positional rule: COUNT collapses the
    // stream to a single {count:N} document, so it is only meaningful as the final step. A COUNT
    // anywhere but last (e.g. followed by a FILTER) is rejected.
    static ValidationResult validateAggregationSteps(List<BaseAggregationStep> steps) {
        for (var i = 0; i < steps.size(); i++) {
            final var step = steps.get(i);
            final var stepResult = AggregationStepValidator.validate(step);
            if (!stepResult.isValid()) {
                return stepResult;
            }
            if (step.getType() == AggregationStepType.COUNT && i < steps.size() - 1) {
                return ValidationResult.fail("COUNT must be the last aggregation step");
            }
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateCreateIndex(CreateIndexRequest request) {
        final var base = NameValidations.validateDbAndColl(request, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.getFieldName() == null || request.getFieldName().isBlank()) {
            return ValidationResult.fail("CREATE_INDEX request requires a non-blank fieldName");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateSaveSchema(SaveSchemaRequest request) {
        final var base = NameValidations.validateDbAndColl(request, true, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.getSchema() == null || request.getSchema().isEmpty()) {
            return ValidationResult.fail("SAVE_SCHEMA request requires a non-empty schema object");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateDropIndex(DropIndexRequest request) {
        final var base = NameValidations.validateDbAndColl(request, true);
        if (!base.isValid()) {
            return base;
        }
        if (request.getFieldName() == null || request.getFieldName().isBlank()) {
            return ValidationResult.fail("DROP_INDEX request requires a non-blank fieldName");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateReindex(ReindexRequest request) {
        final var base = NameValidations.validateDbAndColl(request, true);
        if (!base.isValid()) {
            return base;
        }
        for (var fieldName : request.getFieldNames()) {
            if (fieldName == null || fieldName.isBlank()) {
                return ValidationResult.fail("REINDEX fieldNames must not contain blank entries");
            }
        }
        return ValidationResult.ok();
    }
}
