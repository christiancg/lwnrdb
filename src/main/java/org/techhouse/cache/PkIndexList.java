package org.techhouse.cache;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.techhouse.data.IdSearchable;
import org.techhouse.data.PkIndexEntry;

public final class PkIndexList extends ChunkedList<PkIndexEntry> implements IdSearchable {
    private static final Comparator<PkIndexEntry> BY_ID = Comparator.comparing(PkIndexEntry::getValue);

    private PkIndexList() {
    }

    public static PkIndexList of(List<PkIndexEntry> sorted) {
        final var list = new PkIndexList();
        list.fill(sorted);
        return list;
    }

    public static int positionOf(List<PkIndexEntry> index, String id) {
        return index instanceof PkIndexList chunked ? chunked.search(id) : Collections.binarySearch(index, id);
    }

    public static PkIndexEntry rowOf(List<PkIndexEntry> index, String id) {
        final var at = positionOf(index, id);
        return at < 0 ? null : index.get(at);
    }

    @Override
    public int search(String id) {
        if (chunkCount() == 0) {
            return -1;
        }
        var low = 0;
        var high = chunkCount() - 1;
        while (low < high) {
            final var middle = (low + high + 1) >>> 1;
            if (chunkAt(middle).getFirst().getValue().compareTo(id) <= 0) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        final var rows = chunkAt(low);
        var from = 0;
        var to = rows.size() - 1;
        while (from <= to) {
            final var middle = (from + to) >>> 1;
            final var order = rows.get(middle).getValue().compareTo(id);
            if (order < 0) {
                from = middle + 1;
            } else if (order > 0) {
                to = middle - 1;
            } else {
                return startOf(low) + middle;
            }
        }
        return -(startOf(low) + from) - 1;
    }

    public void insertSorted(List<PkIndexEntry> sorted) {
        insertSorted(sorted, BY_ID);
    }
}
