package org.techhouse.fs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class TombstoneChunks {
    static final int CHUNK_ENTRIES = 1024;
    static final long ABSENT = Long.MIN_VALUE;
    static final TombstoneChunks EMPTY = new TombstoneChunks(new Chunk[0]);
    private static final long OBJECT_BYTES = 16L;
    private static final long ARRAY_BYTES = 16L;
    private static final long REFERENCE_BYTES = 8L;

    @FunctionalInterface
    interface EntryConsumer {
        void accept(byte[] keys, int from, int to, long version) throws java.io.IOException;
    }

    @FunctionalInterface
    interface IdConsumer {
        void accept(String id, long version);
    }

    private record Chunk(byte[] keys, int[] ends, long[] versions) {
        int size() {
            return versions.length;
        }

        int start(int index) {
            return index == 0 ? 0 : ends[index - 1];
        }

        int compareFirst(String id) {
            return TombstoneKeys.compare(id, keys, 0, ends[0]);
        }

        long versionOf(String id) {
            var low = 0;
            var high = versions.length - 1;
            while (low <= high) {
                final var middle = (low + high) >>> 1;
                final var order = TombstoneKeys.compare(id, keys, start(middle), ends[middle]);
                if (order == 0) {
                    return versions[middle];
                }
                if (order < 0) {
                    high = middle - 1;
                } else {
                    low = middle + 1;
                }
            }
            return ABSENT;
        }

        long bytes() {
            return OBJECT_BYTES + 3 * (REFERENCE_BYTES + ARRAY_BYTES) + keys.length + (long) Integer.BYTES * ends.length
                    + (long) Long.BYTES * versions.length;
        }
    }

    private final Chunk[] chunks;
    private final int size;
    private final long bytes;

    private TombstoneChunks(Chunk[] chunks) {
        this.chunks = chunks;
        var entries = 0;
        var total = OBJECT_BYTES + ARRAY_BYTES + REFERENCE_BYTES * chunks.length;
        for (final var chunk : chunks) {
            entries += chunk.size();
            total += chunk.bytes();
        }
        this.size = entries;
        this.bytes = total;
    }

    int size() {
        return size;
    }

    long bytes() {
        return bytes;
    }

    long versionOf(String id) {
        if (chunks.length == 0) {
            return ABSENT;
        }
        var low = 0;
        var high = chunks.length - 1;
        while (low < high) {
            final var middle = (low + high + 1) >>> 1;
            if (chunks[middle].compareFirst(id) >= 0) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return chunks[low].versionOf(id);
    }

    void forEach(IdConsumer action) {
        for (final var chunk : chunks) {
            for (var index = 0; index < chunk.size(); index++) {
                action.accept(TombstoneKeys.decode(chunk.keys, chunk.start(index), chunk.ends[index]),
                        chunk.versions[index]);
            }
        }
    }

    void forEachEntry(EntryConsumer action) throws java.io.IOException {
        for (final var chunk : chunks) {
            for (var index = 0; index < chunk.size(); index++) {
                action.accept(chunk.keys, chunk.start(index), chunk.ends[index], chunk.versions[index]);
            }
        }
    }

    long[] versionRange() {
        var oldest = Long.MAX_VALUE;
        var highest = 0L;
        for (final var chunk : chunks) {
            for (final var version : chunk.versions) {
                oldest = Math.min(oldest, version);
                highest = Math.max(highest, version);
            }
        }
        return new long[]{size == 0 ? 0L : oldest, highest};
    }

    TombstoneChunks retainedFrom(long minVersion) {
        final var emitter = new Emitter(CHUNK_ENTRIES);
        for (final var chunk : chunks) {
            for (var index = 0; index < chunk.size(); index++) {
                if (chunk.versions[index] >= minVersion) {
                    emitter.add(chunk.keys, chunk.start(index), chunk.ends[index], chunk.versions[index]);
                }
            }
        }
        return new TombstoneChunks(emitter.finish().toArray(Chunk[]::new));
    }

    TombstoneChunks merged(Map<String, Long> buffered) {
        if (buffered.isEmpty()) {
            return this;
        }
        final var incoming = new Builder(buffered.size());
        buffered.forEach(incoming::add);
        final var sortedIncoming = incoming.build();
        if (chunks.length == 0) {
            return sortedIncoming;
        }
        final var cursor = new Cursor(sortedIncoming);
        final var merged = new ArrayList<Chunk>(chunks.length + 1);
        for (var index = 0; index < chunks.length; index++) {
            final var chunk = chunks[index];
            final var next = index + 1 < chunks.length ? chunks[index + 1] : null;
            final var taken = cursor.countBelow(next);
            if (taken == 0) {
                merged.add(chunk);
            } else {
                merged.addAll(mergeInto(chunk, cursor, taken));
            }
        }
        return new TombstoneChunks(merged.toArray(Chunk[]::new));
    }

    private static List<Chunk> mergeInto(Chunk chunk, Cursor cursor, int taken) {
        final var total = chunk.size() + taken;
        final var parts = total <= 2 * CHUNK_ENTRIES ? 1 : Math.ceilDiv(total, CHUNK_ENTRIES);
        final var emitter = new Emitter(Math.ceilDiv(total, parts));
        var index = 0;
        var remaining = taken;
        while (index < chunk.size() || remaining > 0) {
            final var order = remaining == 0
                    ? -1
                    : index == chunk.size()
                            ? 1
                            : TombstoneKeys.compare(chunk.keys, chunk.start(index), chunk.ends[index], cursor.keys(),
                                    cursor.from(), cursor.to());
            if (order <= 0) {
                emitter.add(chunk.keys, chunk.start(index), chunk.ends[index], chunk.versions[index]);
                index++;
            }
            if (order >= 0) {
                emitter.addOrRaise(cursor.keys(), cursor.from(), cursor.to(), cursor.version(), order == 0);
                cursor.advance();
                remaining--;
            }
        }
        return emitter.finish();
    }

    private static final class Cursor {
        private final Chunk[] chunks;
        private int chunk;
        private int index;

        Cursor(TombstoneChunks source) {
            this.chunks = source.chunks;
        }

        byte[] keys() {
            return chunks[chunk].keys;
        }

        int from() {
            return chunks[chunk].start(index);
        }

        int to() {
            return chunks[chunk].ends[index];
        }

        long version() {
            return chunks[chunk].versions[index];
        }

        void advance() {
            if (++index == chunks[chunk].size()) {
                chunk++;
                index = 0;
            }
        }

        int countBelow(Chunk bound) {
            if (bound == null) {
                var remaining = 0;
                for (var at = chunk; at < chunks.length; at++) {
                    remaining += chunks[at].size() - (at == chunk ? index : 0);
                }
                return remaining;
            }
            var count = 0;
            var atChunk = chunk;
            var atIndex = index;
            while (atChunk < chunks.length) {
                final var current = chunks[atChunk];
                if (TombstoneKeys.compare(current.keys, current.start(atIndex), current.ends[atIndex], bound.keys, 0,
                        bound.ends[0]) >= 0) {
                    break;
                }
                count++;
                if (++atIndex == current.size()) {
                    atChunk++;
                    atIndex = 0;
                }
            }
            return count;
        }
    }

    private static final class Emitter {
        private final int cutAt;
        private final List<Chunk> done = new ArrayList<>();
        private byte[] keys = new byte[256];
        private int keysLength;
        private final int[] ends;
        private final long[] versions;
        private int count;

        Emitter(int cutAt) {
            this.cutAt = Math.max(1, cutAt);
            this.ends = new int[this.cutAt];
            this.versions = new long[this.cutAt];
        }

        void add(byte[] source, int from, int to, long version) {
            if (count == cutAt) {
                cut();
            }
            final var length = to - from;
            if (keysLength + length > keys.length) {
                keys = Arrays.copyOf(keys, Math.max(keys.length * 2, keysLength + length));
            }
            System.arraycopy(source, from, keys, keysLength, length);
            keysLength += length;
            ends[count] = keysLength;
            versions[count++] = version;
        }

        void addOrRaise(byte[] source, int from, int to, long version, boolean sameAsLast) {
            if (sameAsLast) {
                versions[count - 1] = Math.max(versions[count - 1], version);
            } else {
                add(source, from, to, version);
            }
        }

        private void cut() {
            done.add(new Chunk(Arrays.copyOf(keys, keysLength), Arrays.copyOf(ends, count),
                    Arrays.copyOf(versions, count)));
            keysLength = 0;
            count = 0;
        }

        List<Chunk> finish() {
            if (count > 0) {
                cut();
            }
            return done;
        }
    }

    static final class Builder {
        private byte[] data;
        private int dataLength;
        private int[] ends;
        private long[] versions;
        private int count;

        Builder(int expectedEntries) {
            this(expectedEntries, Math.max(16, expectedEntries) * 16L);
        }

        Builder(int expectedEntries, long expectedBytes) {
            final var capacity = Math.max(16, expectedEntries);
            data = new byte[(int) Math.clamp(expectedBytes, 256L, Integer.MAX_VALUE - 16L)];
            ends = new int[capacity];
            versions = new long[capacity];
        }

        void add(String id, long version) {
            reserve(TombstoneKeys.encodedLength(id));
            dataLength = TombstoneKeys.encode(id, data, dataLength);
            push(version);
        }

        void add(byte[] source, int from, int to, long version) {
            reserve(to - from);
            System.arraycopy(source, from, data, dataLength, to - from);
            dataLength += to - from;
            push(version);
        }

        private void reserve(int length) {
            if (dataLength + length > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, dataLength + length));
            }
            if (count == versions.length) {
                ends = Arrays.copyOf(ends, count * 2);
                versions = Arrays.copyOf(versions, count * 2);
            }
        }

        private void push(long version) {
            ends[count] = dataLength;
            versions[count++] = version;
        }

        private int start(int entry) {
            return entry == 0 ? 0 : ends[entry - 1];
        }

        private int compare(int left, int right) {
            return TombstoneKeys.compare(data, start(left), ends[left], data, start(right), ends[right]);
        }

        TombstoneChunks build() {
            final var order = sortedOrder();
            final var emitter = new Emitter(CHUNK_ENTRIES);
            var previous = -1;
            for (final var entry : order) {
                emitter.addOrRaise(data, start(entry), ends[entry], versions[entry],
                        previous >= 0 && compare(previous, entry) == 0);
                previous = entry;
            }
            return new TombstoneChunks(emitter.finish().toArray(Chunk[]::new));
        }

        private int[] sortedOrder() {
            var order = new int[count];
            for (var i = 0; i < count; i++) {
                order[i] = i;
            }
            var scratch = new int[count];
            for (var width = 1; width < count; width *= 2) {
                for (var low = 0; low < count; low += 2 * width) {
                    mergeRuns(order, scratch, low, Math.min(low + width, count), Math.min(low + 2 * width, count));
                }
                final var swap = order;
                order = scratch;
                scratch = swap;
            }
            return order;
        }

        private void mergeRuns(int[] source, int[] target, int low, int middle, int high) {
            var left = low;
            var right = middle;
            for (var out = low; out < high; out++) {
                if (left < middle && (right >= high || compare(source[left], source[right]) <= 0)) {
                    target[out] = source[left++];
                } else {
                    target[out] = source[right++];
                }
            }
        }
    }
}
