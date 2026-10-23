package org.techhouse.cache;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import org.techhouse.data.PkIndexEntry;

public final class PkIndexList extends AbstractList<PkIndexEntry> implements RandomAccess {
    static final int CHUNK_ROWS = 512;
    private static final int SPLIT_ROWS = 2 * CHUNK_ROWS;

    private List<List<PkIndexEntry>> chunks = new ArrayList<>();
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

    public static int positionOf(List<PkIndexEntry> index, String id) {
        return index instanceof PkIndexList chunked ? chunked.search(id) : Collections.binarySearch(index, id);
    }

    public int search(String id) {
        if (chunks.isEmpty()) {
            return -1;
        }
        var low = 0;
        var high = chunks.size() - 1;
        while (low < high) {
            final var middle = (low + high + 1) >>> 1;
            if (chunks.get(middle).getFirst().getValue().compareTo(id) <= 0) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        final var rows = chunks.get(low);
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
                return starts[low] + middle;
            }
        }
        return -(starts[low] + from) - 1;
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

    public void insertSorted(List<PkIndexEntry> sorted) {
        if (sorted.isEmpty()) {
            return;
        }
        try {
            chunks = mergedChunks(sorted);
        } finally {
            modCount++;
            restart();
            size = rowCount();
        }
    }

    private List<List<PkIndexEntry>> mergedChunks(List<PkIndexEntry> sorted) {
        final var source = chunks.isEmpty() ? List.of(new ArrayList<PkIndexEntry>()) : chunks;
        final var rebuilt = new ArrayList<List<PkIndexEntry>>(source.size() + sorted.size() / CHUNK_ROWS + 1);
        var next = 0;
        for (var chunk = 0; chunk < source.size(); chunk++) {
            final var rows = source.get(chunk);
            final var end = chunk + 1 == source.size()
                    ? sorted.size()
                    : endOfRun(sorted, next, source.get(chunk + 1).getFirst().getValue());
            if (end > next) {
                mergeInto(rows, sorted.subList(next, end));
                next = end;
            }
            addCut(rebuilt, rows);
        }
        return rebuilt;
    }

    private int rowCount() {
        var total = 0;
        for (final var rows : chunks) {
            total += rows.size();
        }
        return total;
    }

    private static int endOfRun(List<PkIndexEntry> sorted, int from, String limit) {
        var end = from;
        while (end < sorted.size() && sorted.get(end).getValue().compareTo(limit) < 0) {
            end++;
        }
        return end;
    }

    private static void mergeInto(List<PkIndexEntry> rows, List<PkIndexEntry> run) {
        var existing = rows.size() - 1;
        var incoming = run.size() - 1;
        rows.addAll(run);
        var write = rows.size() - 1;
        while (incoming >= 0) {
            if (existing >= 0 && rows.get(existing).getValue().compareTo(run.get(incoming).getValue()) > 0) {
                rows.set(write--, rows.get(existing--));
            } else {
                rows.set(write--, run.get(incoming--));
            }
        }
    }

    private static void addCut(List<List<PkIndexEntry>> target, List<PkIndexEntry> rows) {
        if (rows.size() <= SPLIT_ROWS) {
            target.add(rows);
            return;
        }
        for (var from = 0; from < rows.size(); from += CHUNK_ROWS) {
            target.add(new ArrayList<>(rows.subList(from, Math.min(rows.size(), from + CHUNK_ROWS))));
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
