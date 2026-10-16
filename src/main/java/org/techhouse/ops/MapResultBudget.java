package org.techhouse.ops;

import org.techhouse.analyze.AggregateResultBudget;

final class MapResultBudget {
    private long bytes;

    void charge(CharSequence text) {
        final var textBytes = EntrySizeGuard.utf8Length(text);
        bytes += textBytes;
        if (EntrySizeGuard.exceedsMaxEntrySize(bytes)) {
            throw new AggregateLimitException(ErrorCode.MAP_RESULT_TOO_LARGE);
        }
        AggregateResultBudget.charge(textBytes);
    }
}
