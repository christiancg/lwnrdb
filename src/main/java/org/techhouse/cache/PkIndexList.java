package org.techhouse.cache;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import org.techhouse.data.PkIndexEntry;

public final class PkIndexList extends AbstractList<PkIndexEntry> implements RandomAccess {
    static final int CHUNK_ROWS = 512;
    private static final int SPLIT_ROWS = 2 * CHUNK_ROWS;

    private final List<List<PkIndexEntry>> chunks = new ArrayList<>();
    private int[] starts = new int[0];
    private int size;
    private int lastChunk;

    private PkIndexList() {
    }

    public static PkIndexList of(List<PkIndexEntry> sorted) {
        final var list = new PkIndexList();
        for (var from = 0; from < sorted.size(); from += CHUNK_ROWS) {
            list.chunks.add(new ArrayList<>(sorted.subList(from, Math.min(sorted.size(), from + CHUNK_ROWS))));
        }
        list.size = sorted.size();
        list.restart();
        return list;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public PkIndexEntry get(int index) {
        Objects.checkIndex(index, size);
        final var chunk = chunkAt(index);
        return chunks.get(chunk).get(index - starts[chunk]);
    }

    @Override
    public PkIndexEntry set(int index, PkIndexEntry row) {
        Objects.checkIndex(index, size);
        final var chunk = chunkAt(index);
        return chunks.get(chunk).set(index - starts[chunk], row);
    }

    @Override
    public void add(int index, PkIndexEntry row) {
        Objects.checkIndex(index, size + 1);
        if (chunks.isEmpty()) {
            chunks.add(new ArrayList<>());
            restart();
        }
        final var chunk = index == size ? chunks.size() - 1 : chunkAt(index);
        final var rows = chunks.get(chunk);
        rows.add(index - starts[chunk], row);
        size++;
        modCount++;
        if (rows.size() > SPLIT_ROWS) {
            split(chunk);
        } else {
            shiftStartsAfter(chunk, 1);
        }
    }

    @Override
    public PkIndexEntry remove(int index) {
        Objects.checkIndex(index, size);
        final var chunk = chunkAt(index);
        final var rows = chunks.get(chunk);
        final var removed = rows.remove(index - starts[chunk]);
        size--;
        modCount++;
        if (rows.isEmpty()) {
            chunks.remove(chunk);
            restart();
        } else {
            shiftStartsAfter(chunk, -1);
        }
        return removed;
    }

    @Override
    public void clear() {
        chunks.clear();
        size = 0;
        modCount++;
        restart();
    }

    private int chunkAt(int index) {
        final var hinted = lastChunk;
        if (hinted < starts.length && starts[hinted] <= index
                && (hinted + 1 == starts.length || index < starts[hinted + 1])) {
            return hinted;
        }
        var low = 0;
        var high = chunks.size() - 1;
        while (low < high) {
            final var middle = (low + high + 1) >>> 1;
            if (starts[middle] <= index) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        lastChunk = low;
        return low;
    }

    private void shiftStartsAfter(int chunk, int delta) {
        for (var next = chunk + 1; next < starts.length; next++) {
            starts[next] += delta;
        }
    }

    private void split(int chunk) {
        final var rows = chunks.get(chunk);
        final var tail = rows.subList(rows.size() / 2, rows.size());
        chunks.add(chunk + 1, new ArrayList<>(tail));
        tail.clear();
        restart();
    }

    private void restart() {
        starts = new int[chunks.size()];
        var start = 0;
        for (var chunk = 0; chunk < starts.length; chunk++) {
            starts[chunk] = start;
            start += chunks.get(chunk).size();
        }
    }
}
