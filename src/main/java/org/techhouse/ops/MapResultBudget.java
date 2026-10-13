package org.techhouse.ops;

final class MapResultBudget {
    private long bytes;

    void charge(CharSequence text) {
        bytes += EntrySizeGuard.utf8Length(text);
        if (EntrySizeGuard.exceedsMaxEntrySize(bytes)) {
            throw new AggregateLimitException(ErrorCode.MAP_RESULT_TOO_LARGE);
        }
    }
}
