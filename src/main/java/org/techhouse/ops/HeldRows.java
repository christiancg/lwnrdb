package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.cache.heap.DocumentHeapSizer;
import org.techhouse.cache.heap.HeapBound;
import org.techhouse.cache.heap.HeapLayout;
import org.techhouse.ejson.elements.JsonBaseElement;

public final class HeldRows {
    public static final int EXACT_ROWS = 64;
    public static final int MAX_UNDERCHARGE = 4;
    private static final HeapLayout layout = HeapLayout.current();
    private static final HeapBound bound = new HeapBound(layout);

    private final PipelineContext context;
    private final List<JsonBaseElement> unmeasured = new ArrayList<>();
    private long unmeasuredAtMost;
    private long rows;
    private long sampledRows;
    private long sampledBytes;

    public HeldRows(PipelineContext context) {
        this.context = context;
    }

    public void hold(JsonBaseElement row) {
        rows++;
        if (rows <= EXACT_ROWS) {
            final var atMost = bytesAtMost(row);
            if (context.defersHeld(this, atMost)) {
                unmeasured.add(row);
                unmeasuredAtMost += atMost;
                return;
            }
        }
        settle();
        if (rows <= EXACT_ROWS || rows % EXACT_ROWS == 0) {
            chargeMeasured(row);
        } else {
            chargeBounded(row);
        }
    }

    private void chargeBounded(JsonBaseElement row) {
        final var atMost = bytesAtMost(row);
        final var average = Math.ceilDiv(sampledBytes, sampledRows);
        if (atMost > MAX_UNDERCHARGE * average) {
            chargeMeasured(row);
        } else {
            context.chargeHeld(Math.min(average, atMost));
        }
    }

    public boolean keptWhen(boolean kept, JsonBaseElement row) {
        if (kept) {
            hold(row);
        }
        return kept;
    }

    void settle() {
        if (unmeasured.isEmpty()) {
            return;
        }
        final var pending = List.copyOf(unmeasured);
        unmeasured.clear();
        context.settledHeld(unmeasuredAtMost);
        unmeasuredAtMost = 0L;
        for (final var row : pending) {
            chargeMeasured(row);
        }
    }

    private void chargeMeasured(JsonBaseElement row) {
        final var bytes = bytesOf(row);
        sampledRows++;
        sampledBytes += bytes;
        context.chargeHeld(bytes);
    }

    public static long bytesOf(JsonBaseElement row) {
        return DocumentHeapSizer.sizeOf(row, layout) + layout.referenceBytes();
    }

    static long bytesAtMost(JsonBaseElement row) {
        return bound.atMost(row) + layout.referenceBytes();
    }
}
