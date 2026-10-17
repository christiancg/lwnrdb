package org.techhouse.ops;

final class MapResultBudget {
    private final PipelineContext context;
    private long bytes;

    MapResultBudget(PipelineContext context) {
        this.context = context;
    }

    void charge(CharSequence text) {
        charge(EntrySizeGuard.utf8Length(text));
    }

    void charge(long addedBytes) {
        bytes += addedBytes;
        if (EntrySizeGuard.exceedsMaxEntrySize(bytes)) {
            throw new AggregateLimitException(ErrorCode.MAP_RESULT_TOO_LARGE);
        }
        if (context != null) {
            context.chargeResult(addedBytes);
        }
    }
}
