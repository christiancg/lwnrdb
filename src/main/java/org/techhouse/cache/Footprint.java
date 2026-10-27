package org.techhouse.cache;

import java.util.concurrent.atomic.LongAdder;

final class Footprint {
    private final LongAdder documents = new LongAdder();
    private final LongAdder fieldIndexes = new LongAdder();
    private final LongAdder pkIndexes = new LongAdder();

    SizeCell newCell(AccessKind kind) {
        return new SizeCell(tier(kind));
    }

    long bytes() {
        return documents.sum() + fieldIndexes.sum() + pkIndexes.sum();
    }

    long bytes(AccessKind kind) {
        return tier(kind).sum();
    }

    long bytesBelow(AccessKind kind) {
        return switch (kind) {
            case COLLECTION -> 0L;
            case FIELD_INDEX -> documents.sum();
            case PK_INDEX -> documents.sum() + fieldIndexes.sum();
        };
    }

    private LongAdder tier(AccessKind kind) {
        return switch (kind) {
            case COLLECTION -> documents;
            case FIELD_INDEX -> fieldIndexes;
            case PK_INDEX -> pkIndexes;
        };
    }
}
