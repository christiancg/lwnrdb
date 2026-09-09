package org.techhouse.ops.req.validations;

import java.util.Locale;
import java.util.UUID;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.req.CancelScriptRequest;
import org.techhouse.ops.req.ListTriggerRunsRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.ResolveTransactionRequest;
import org.techhouse.ops.req.ResolveTriggerRunRequest;
import org.techhouse.ops.req.StopListenRequest;

public final class ControlRequestValidator {
    private ControlRequestValidator() {
    }

    // The run id is a UUID the server itself minted, so anything else names no run that could ever exist.
    static ValidationResult validateCancelScript(CancelScriptRequest request) {
        final var runId = request.getRunId();
        if (runId == null || runId.isBlank()) {
            return ValidationResult.fail("runId is required");
        }
        try {
            UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail("runId must be a UUID");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateListTriggerRuns(ListTriggerRunsRequest request) {
        final var status = request.getStatus();
        if (status == null || status.isBlank()) {
            return ValidationResult.ok();
        }
        try {
            TriggerRunStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail("status must be 'PENDING' or 'DEAD'");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateResolveTriggerRun(ResolveTriggerRunRequest request) {
        final var runId = request.getRunId();
        if (runId == null || runId.isBlank()) {
            return ValidationResult.fail("runId is required");
        }
        if (!ResolveTriggerRunRequest.DECISION_REPLAY.equals(request.getDecision())
                && !ResolveTriggerRunRequest.DECISION_DISCARD.equals(request.getDecision())) {
            return ValidationResult.fail("decision must be 'replay' or 'discard'");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateResolveTransaction(ResolveTransactionRequest request) {
        if (request.getDtxId() == null || request.getDtxId().isBlank()) {
            return ValidationResult.fail("dtxId is required");
        }
        if (!ResolveTransactionRequest.DECISION_COMMIT.equals(request.getDecision())
                && !ResolveTransactionRequest.DECISION_ABORT.equals(request.getDecision())) {
            return ValidationResult.fail("decision must be 'commit' or 'abort'");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateListen(ListenRequest request) {
        final var base = NameValidations.validateDbAndColl(request, false);
        if (!base.isValid()) {
            return base;
        }
        if (request.getAggregationSteps() == null) {
            return ValidationResult.fail("LISTEN request requires an aggregationSteps array");
        }
        // A LISTEN pipeline re-runs on every matching change, so a script in it would execute per write
        // with no client-visible budget to bound it.
        if (AggregationStepValidator.containsScript(request.getAggregationSteps())) {
            return ValidationResult.fail(ErrorCode.SCRIPT_NOT_ALLOWED_IN_LISTEN,
                    ErrorCode.SCRIPT_NOT_ALLOWED_IN_LISTEN.getDefaultMessage());
        }
        return DataRequestValidator.validateAggregationSteps(request.getAggregationSteps());
    }

    static ValidationResult validateStopListen(StopListenRequest request) {
        if (request.getListenId() == null || request.getListenId().isBlank()) {
            return ValidationResult.fail("STOP_LISTEN request requires a listenId");
        }
        try {
            java.util.UUID.fromString(request.getListenId());
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail("STOP_LISTEN listenId must be a valid UUID");
        }
        return ValidationResult.ok();
    }
}
