package org.techhouse.cache;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import org.techhouse.data.SortedChunks;

public class ChunkedList<E> extends AbstractList<E> implements RandomAccess, SortedChunks<E> {
    static final int CHUNK_ROWS = 512;
    private static final int SPLIT_ROWS = 2 * CHUNK_ROWS;

    private List<List<E>> chunks = new ArrayList<>();
    private int[] starts = new int[0];
    private int size;
    private int lastChunk;

    protected ChunkedList() {
    }

    public static <E> ChunkedList<E> copyOf(List<? extends E> sorted) {
        final var list = new ChunkedList<E>();
        list.fill(sorted);
        return list;
    }

    protected final void fill(List<? extends E> rows) {
        for (var from = 0; from < rows.size(); from += CHUNK_ROWS) {
            chunks.add(new ArrayList<>(rows.subList(from, Math.min(rows.size(), from + CHUNK_ROWS))));
        }
        size = rows.size();
        restart();
    }

    @Override
    public int size() {
        return size;
    }

    protected final int chunkCount() {
        return chunks.size();
    }

    protected final List<E> chunkAt(int chunk) {
        return chunks.get(chunk);
    }

    protected final int startOf(int chunk) {
        return starts[chunk];
    }

    @Override
    public E get(int index) {
        Objects.checkIndex(index, size);
        final var chunk = chunkOf(index);
        return chunks.get(chunk).get(index - starts[chunk]);
    }

    @Override
    public E set(int index, E row) {
        Objects.checkIndex(index, size);
        final var chunk = chunkOf(index);
        return chunks.get(chunk).set(index - starts[chunk], row);
    }

    @Override
    public void add(int index, E row) {
        Objects.checkIndex(index, size + 1);
        if (chunks.isEmpty()) {
            chunks.add(new ArrayList<>());
            restart();
        }
        final var chunk = index == size ? chunks.size() - 1 : chunkOf(index);
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
    @SuppressWarnings("NullableProblems")
    public boolean addAll(Collection<? extends E> rows) {
        if (!isEmpty() || rows.isEmpty()) {
            return super.addAll(rows);
        }
        modCount++;
        fill(rows instanceof List<? extends E> list ? list : new ArrayList<>(rows));
        return true;
    }

    public void insertSorted(List<E> sorted, Comparator<? super E> order) {
        if (sorted.isEmpty()) {
            return;
        }
        try {
            chunks = mergedChunks(sorted, order);
        } finally {
            modCount++;
            restart();
            size = rowCount();
        }
    }

    private List<List<E>> mergedChunks(List<E> sorted, Comparator<? super E> order) {
        final List<List<E>> source = chunks.isEmpty() ? List.of(new ArrayList<>()) : chunks;
        final var rebuilt = new ArrayList<List<E>>(source.size() + sorted.size() / CHUNK_ROWS + 1);
        var next = 0;
        for (var chunk = 0; chunk < source.size(); chunk++) {
            final var rows = source.get(chunk);
            final var end = chunk + 1 == source.size()
                    ? sorted.size()
                    : endOfRun(sorted, next, source.get(chunk + 1).getFirst(), order);
            if (end > next) {
                mergeInto(rows, sorted.subList(next, end), order);
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

    private static <E> int endOfRun(List<E> sorted, int from, E limit, Comparator<? super E> order) {
        var end = from;
        while (end < sorted.size() && order.compare(sorted.get(end), limit) < 0) {
            end++;
        }
        return end;
    }

    private static <E> void mergeInto(List<E> rows, List<E> run, Comparator<? super E> order) {
        var existing = rows.size() - 1;
        var incoming = run.size() - 1;
        rows.addAll(run);
        var write = rows.size() - 1;
        while (incoming >= 0) {
            if (existing >= 0 && order.compare(rows.get(existing), run.get(incoming)) > 0) {
                rows.set(write--, rows.get(existing--));
            } else {
                rows.set(write--, run.get(incoming--));
            }
        }
    }

    private static <E> void addCut(List<List<E>> target, List<E> rows) {
        if (rows.size() <= SPLIT_ROWS) {
            target.add(rows);
            return;
        }
        for (var from = 0; from < rows.size(); from += CHUNK_ROWS) {
            target.add(new ArrayList<>(rows.subList(from, Math.min(rows.size(), from + CHUNK_ROWS))));
        }
    }

    @Override
    public E remove(int index) {
        Objects.checkIndex(index, size);
        final var chunk = chunkOf(index);
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

    @Override
    public int partitionPoint(Predicate<? super E> before) {
        var low = 0;
        var high = chunks.size();
        while (low < high) {
            final var middle = (low + high) >>> 1;
            if (before.test(chunks.get(middle).getLast())) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        if (low == chunks.size()) {
            return size;
        }
        final var rows = chunks.get(low);
        var first = 0;
        var last = rows.size();
        while (first < last) {
            final var middle = (first + last) >>> 1;
            if (before.test(rows.get(middle))) {
                first = middle + 1;
            } else {
                last = middle;
            }
        }
        return starts[low] + first;
    }

    @Override
    public long sumOf(int fromIndex, int toIndex, ToLongFunction<? super E> weight) {
        Objects.checkFromToIndex(fromIndex, toIndex, size);
        var total = 0L;
        if (fromIndex == toIndex) {
            return total;
        }
        var chunk = chunkContaining(fromIndex);
        var offset = fromIndex - starts[chunk];
        var remaining = toIndex - fromIndex;
        while (remaining > 0) {
            final var rows = chunks.get(chunk);
            final var end = Math.min(rows.size(), offset + remaining);
            for (var row = offset; row < end; row++) {
                total += weight.applyAsLong(rows.get(row));
            }
            remaining -= end - offset;
            offset = 0;
            chunk++;
        }
        return total;
    }

    private int chunkContaining(int index) {
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
        return low;
    }

    private int chunkOf(int index) {
        final var hinted = lastChunk;
        if (hinted < starts.length && starts[hinted] <= index
                && (hinted + 1 == starts.length || index < starts[hinted + 1])) {
            return hinted;
        }
        final var found = chunkContaining(index);
        lastChunk = found;
        return found;
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

    @Override
    @SuppressWarnings("NullableProblems")
    public List<E> subList(int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, size);
        return new Window(fromIndex, toIndex);
    }

    private final class Window extends AbstractList<E> implements RandomAccess {
        private final int from;
        private final int to;

        private Window(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public E get(int index) {
            Objects.checkIndex(index, to - from);
            return ChunkedList.this.get(from + index);
        }

        @Override
        public int size() {
            return to - from;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Iterator<E> iterator() {
            return new Iterator<>() {
                private int remaining = to - from;
                private int chunk = remaining == 0 ? 0 : chunkOf(from);
                private int offset = remaining == 0 ? 0 : from - starts[chunk];
                private List<E> rows = remaining == 0 ? List.of() : chunks.get(chunk);
                private int rowCount = rows.size();

                @Override
                public boolean hasNext() {
                    return remaining > 0;
                }

                @Override
                public E next() {
                    if (remaining == 0) {
                        throw new NoSuchElementException();
                    }
                    if (offset == rowCount) {
                        chunk++;
                        offset = 0;
                        rows = chunks.get(chunk);
                        rowCount = rows.size();
                    }
                    remaining--;
                    return rows.get(offset++);
                }
            };
        }
    }
}
