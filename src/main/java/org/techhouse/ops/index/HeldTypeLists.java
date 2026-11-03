package org.techhouse.ops.index;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.fs.IndexTypeReader;
import org.techhouse.fs.PendingFold;

final class HeldTypeLists {
    private static final class Held {
        private final List<? extends FieldIndexEntry<?>> list;
        private final LongSupplier stampSource;
        private long stamp;

        private Held(List<? extends FieldIndexEntry<?>> list, LongSupplier stampSource, long stamp) {
            this.list = list;
            this.stampSource = stampSource;
            this.stamp = stamp;
        }
    }

    private final Map<String, Held> held = new HashMap<>();
    private final Map<String, Held> served = new HashMap<>();

    static IndexTypeReader reading(HeldTypeLists lists, String identifier, LongSupplier stampSource,
            IndexTypeReader fromDisk) {
        if (lists == null) {
            return fromDisk;
        }
        return () -> lists.read(identifier, stampSource, fromDisk);
    }

    private List<? extends FieldIndexEntry<?>> read(String identifier, LongSupplier stampSource,
            IndexTypeReader fromDisk) throws IOException {
        final var known = held.get(identifier);
        final var stamp = stampSource.getAsLong();
        if (known != null && known.stamp == stamp) {
            served.put(identifier, known);
            return known.list;
        }
        held.remove(identifier);
        final var loaded = fromDisk.read();
        if (loaded != null) {
            served.put(identifier, new Held(loaded, stampSource, stamp));
        }
        return loaded;
    }

    void chunkFlushed() {
        held.clear();
        for (final var entry : served.entrySet()) {
            final var list = entry.getValue();
            list.stamp = list.stampSource.getAsLong();
            held.put(entry.getKey(), list);
        }
        served.clear();
    }

    void folded(DeferredFold fold, PendingFold.Outcome outcome) {
        final var list = held.get(fold.identifier());
        if (list == null) {
            return;
        }
        if (outcome == PendingFold.Outcome.FOLDED && list.stamp == fold.pending().stamp()) {
            list.stamp = list.stampSource.getAsLong();
        } else if (outcome != PendingFold.Outcome.SKIPPED) {
            held.remove(fold.identifier());
        }
    }

    void clear() {
        held.clear();
        served.clear();
    }
}
