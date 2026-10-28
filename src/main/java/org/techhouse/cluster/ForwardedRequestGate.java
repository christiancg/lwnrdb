package org.techhouse.cluster;

import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.ops.req.validations.ValidationResult;
import org.techhouse.ops.resp.OperationResponse;

public final class ForwardedRequestGate {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Set<OperationType> SYSTEM_HISTORY_WRITES = Set.of(OperationType.SAVE, OperationType.BULK_SAVE,
            OperationType.DELETE, OperationType.CREATE_COLLECTION);

    private ForwardedRequestGate() {
    }

    public static OperationResponse check(OperationRequest request, String actingUser) {
        if (isSystemHistoryWrite(request)) {
            return refusal(request, RequestValidator.validateSystemHistory(request));
        }
        final var invalid = refusal(request, RequestValidator.validate(request));
        if (invalid != null) {
            return invalid;
        }
        if (actingUser == null || actingUser.isBlank()) {
            return new OperationResponse(request.getType(), ErrorCode.MUST_AUTHENTICATE_FIRST);
        }
        final var user = cache.getAdminUserEntry(actingUser);
        if (user == null) {
            return new OperationResponse(request.getType(), ErrorCode.USER_NO_LONGER_EXISTS);
        }
        return AuthorizationChecker.check(request, user).isAllowed()
                ? null
                : new OperationResponse(request.getType(), ErrorCode.NO_PERMISSIONS);
    }

    public static OperationResponse checkTransactional(OperationRequest request, String actingUser) {
        return checkTransactional(request, actingUser, false);
    }

    public static OperationResponse checkTransactional(OperationRequest request, String actingUser,
            boolean sessionHoldsTransaction) {
        if (request.getType() == OperationType.ROLLBACK_TRANSACTION) {
            return null;
        }
        if (sessionHoldsTransaction && request.getType() == OperationType.COMMIT_TRANSACTION) {
            return null;
        }
        return check(request, actingUser);
    }

    static boolean isSystemHistoryWrite(OperationRequest request) {
        return SYSTEM_HISTORY_WRITES.contains(request.getType())
                && Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(request.getCollectionName());
    }

    private static OperationResponse refusal(OperationRequest request, ValidationResult validation) {
        return validation.isValid()
                ? null
                : new OperationResponse(request.getType(), validation.getErrorMessage(), validation.getErrorCode());
    }
}
