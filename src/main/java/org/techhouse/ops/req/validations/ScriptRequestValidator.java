package org.techhouse.ops.req.validations;

import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.req.TestTriggerRequest;

public final class ScriptRequestValidator {
    private ScriptRequestValidator() {
    }

    static ValidationResult validateRunScript(RunScriptRequest request) {
        final var dbResult = NameValidations.validateDbName(request.getDatabaseName(), true);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        final var script = request.getScript();
        if (script == null || script.isBlank()) {
            return ValidationResult.fail("RUN_SCRIPT request requires a script");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateSaveProcedure(SaveProcedureRequest request) {
        final var dbResult = NameValidations.validateDbName(request.getDatabaseName(), true);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        final var nameResult = NameValidations.validateProcedureName(request.getName());
        if (!nameResult.isValid()) {
            return nameResult;
        }
        final var script = request.getScript();
        if (script == null || script.isBlank()) {
            return ValidationResult.fail("SAVE_PROCEDURE request requires a script");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateDeleteProcedure(DeleteProcedureRequest request) {
        final var dbResult = NameValidations.validateDbName(request.getDatabaseName(), true);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        return NameValidations.validateProcedureName(request.getName());
    }

    static ValidationResult validateCallProcedure(CallProcedureRequest request) {
        final var dbResult = NameValidations.validateDbName(request.getDatabaseName(), true);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        return NameValidations.validateProcedureName(request.getProcedureName());
    }

    static ValidationResult validateSaveTrigger(SaveTriggerRequest request) {
        final var namesResult = NameValidations.validateDbAndColl(request, true, true);
        if (!namesResult.isValid()) {
            return namesResult;
        }
        final var nameResult = NameValidations.validateProcedureName(request.getName());
        if (!nameResult.isValid()) {
            return nameResult;
        }
        return NameValidations.validateProcedureName(request.getProcedureName());
    }

    static ValidationResult validateDeleteTrigger(DeleteTriggerRequest request) {
        final var namesResult = NameValidations.validateDbAndColl(request, true);
        if (!namesResult.isValid()) {
            return namesResult;
        }
        return NameValidations.validateProcedureName(request.getName());
    }

    static ValidationResult validateTestTrigger(TestTriggerRequest request) {
        final var namesResult = NameValidations.validateDbAndColl(request, true);
        if (!namesResult.isValid()) {
            return namesResult;
        }
        final var nameResult = NameValidations.validateProcedureName(request.getName());
        if (!nameResult.isValid()) {
            return nameResult;
        }
        if (request.getDocument() == null) {
            return ValidationResult.fail("A document is required");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateListTriggers(ListTriggersRequest request) {
        final var dbResult = NameValidations.validateDbName(request.getDatabaseName(), true);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        final var collName = request.getCollectionName();
        if (collName == null || collName.isBlank()) {
            return ValidationResult.ok();
        }
        return NameValidations.validateCollectionName(collName);
    }

    static ValidationResult validateSaveSchedule(SaveScheduleRequest request) {
        final var dbResult = NameValidations.validateDbName(request.getDatabaseName(), true);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        final var nameResult = NameValidations.validateProcedureName(request.getName());
        if (!nameResult.isValid()) {
            return nameResult;
        }
        return NameValidations.validateProcedureName(request.getProcedureName());
    }

    static ValidationResult validateDeleteSchedule(DeleteScheduleRequest request) {
        final var dbResult = NameValidations.validateDbName(request.getDatabaseName(), true);
        if (!dbResult.isValid()) {
            return dbResult;
        }
        return NameValidations.validateProcedureName(request.getName());
    }
}
