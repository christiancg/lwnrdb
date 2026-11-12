package org.techhouse.conn;

import org.techhouse.config.ConfigKey;
import org.techhouse.config.Globals;
import org.techhouse.ejson.internal.EncodedJson;
import org.techhouse.ejson.internal.Utf8Encoder;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.LogRateLimiter;
import org.techhouse.log.Logger;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;

final class ResponseFrames {
    private static final ResponseBudget budget = IocContainer.get(ResponseBudget.class);
    private static final Logger logger = Logger.logFor(ResponseFrames.class);
    private static final LogRateLimiter REFUSAL_LOG = new LogRateLimiter(Globals.LOG_RATE_LIMIT_PER_SECOND);

    record Reserved(EncodedJson frame, long bytes, OperationStatus status, String errorCode) {
    }

    private ResponseFrames() {
    }

    static Reserved encode(OperationType requestType, OperationResponse response) {
        final var estimate = response.estimatedJsonChars();
        if (cannotReserve(requestType, estimate)) {
            return refused(requestType, estimate);
        }
        var encoded = false;
        try {
            final var frame = OutboundQueue.frameOf(response, estimate);
            encoded = true;
            return settled(estimate, frame, response.getStatus(), response.getErrorCode());
        } finally {
            if (!encoded) {
                budget.release(estimate);
            }
        }
    }

    static Reserved encodeText(OperationType requestType, String text) {
        final long estimate = text.length();
        if (cannotReserve(requestType, estimate)) {
            return refused(requestType, estimate);
        }
        var encoded = false;
        try {
            final var frame = EncodedJson.of(Utf8Encoder.encode(text));
            encoded = true;
            return settled(estimate, frame, null, null);
        } finally {
            if (!encoded) {
                budget.release(estimate);
            }
        }
    }

    static Reserved close(ErrorCode code) {
        final var frame = OutboundQueue.closeFrame(code);
        budget.reserve(frame.length());
        return new Reserved(frame, frame.length(), code.getStatus(), code.getCode());
    }

    private static boolean cannotReserve(OperationType requestType, long estimate) {
        if (ResponseBudget.isRefusable(requestType)) {
            return !budget.tryReserve(estimate);
        }
        budget.reserve(estimate);
        return false;
    }

    private static Reserved settled(long estimate, EncodedJson frame, OperationStatus status, String errorCode) {
        budget.settle(estimate, frame.length());
        return new Reserved(frame, frame.length(), status, errorCode);
    }

    private static Reserved refused(OperationType requestType, long estimate) {
        final var entry = REFUSAL_LOG.admit("Answered " + ErrorCode.RESPONSE_BUDGET_EXHAUSTED.getCode() + " to "
                + requestType + ": its response of about " + estimate + " bytes would pass "
                + ConfigKey.MAX_UNSENT_RESPONSE_BYTES.key() + " (" + budget.unsentBytes() + " of " + budget.limit()
                + " bytes wait unsent)");
        if (entry != null) {
            logger.warning(entry);
        }
        final var frame = OutboundQueue
                .frameOf(new OperationResponse(requestType, ErrorCode.RESPONSE_BUDGET_EXHAUSTED));
        budget.reserve(frame.length());
        final var refusal = ErrorCode.RESPONSE_BUDGET_EXHAUSTED;
        return new Reserved(frame, frame.length(), refusal.getStatus(), refusal.getCode());
    }
}
