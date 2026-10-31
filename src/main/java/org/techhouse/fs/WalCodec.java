package org.techhouse.fs;

import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

final class WalCodec {
    private static final byte MAGIC = 'W';
    private static final byte CHECKPOINT = 'C';
    private static final byte WRITE = 'R';
    private static final byte ABORT = 'A';
    private static final byte GROUP_BASE = 'G';
    private static final byte PENDING = 'I';
    private static final byte REWRITE_BEGIN = 'B';
    private static final byte REWRITE_END = 'E';
    static final long NO_GROUP_BASE = -1L;
    static final long NO_REWRITE_END = -1L;
    static final int HEADER_BYTES = 1 + 1 + Long.BYTES + Integer.BYTES + Integer.BYTES;
    private static final int CHANGE_HEADER_BYTES = 1 + Long.BYTES + Long.BYTES + Long.BYTES;
    private static final int RECORD_BYTES_HINT = 256;

    record Decoded(WalCheckpoint checkpoint, List<WalRecord> records, int framesSinceCheckpoint, long lastSeq,
            long cleanLength, long groupBase, List<String> pendingIds, Map<Long, Rewrite> rewrites) {
    }

    record Rewrite(long beginSeq, long lengthBefore, long lengthAfter) {
        boolean skips(WalRecord record, WalChange change) {
            return change.target() != WalTarget.PK_WRITE && record.seq() < beginSeq;
        }

        long floor() {
            return lengthAfter == NO_REWRITE_END ? lengthBefore : lengthAfter;
        }
    }

    static final class RecordBuffer extends OutputStream {
        private byte[] buf;
        private int count = Integer.BYTES;
        private int changes;

        RecordBuffer() {
            this(RECORD_BYTES_HINT);
        }

        RecordBuffer(long expectedPayload) {
            buf = new byte[(int) Math.min(Integer.MAX_VALUE - 8, Integer.BYTES + expectedPayload)];
        }

        static long changeBytes(long dataBytes) {
            return CHANGE_HEADER_BYTES + dataBytes;
        }

        int change(WalTarget target, long page, long offset, long length) {
            ensureCapacity(count + CHANGE_HEADER_BYTES);
            final var headerAt = count;
            ByteBuffer.wrap(buf, count, CHANGE_HEADER_BYTES).put((byte) target.ordinal()).putLong(page).putLong(offset)
                    .putLong(length);
            count += CHANGE_HEADER_BYTES;
            changes++;
            return headerAt;
        }

        void patchLength(int headerAt, long length) {
            ByteBuffer.wrap(buf, headerAt + CHANGE_HEADER_BYTES - Long.BYTES, Long.BYTES).putLong(length);
        }

        @Override
        public void write(int b) {
            ensureCapacity(count + 1);
            buf[count++] = (byte) b;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public void write(byte[] bytes, int offset, int length) {
            ensureCapacity(count + length);
            System.arraycopy(bytes, offset, buf, count, length);
            count += length;
        }

        int size() {
            return count;
        }

        @Override
        public void close() {
            count = Integer.BYTES;
        }

        byte[] bytes() {
            return buf;
        }

        private void ensureCapacity(int capacity) {
            if (capacity > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(capacity, buf.length * 2));
            }
        }

        byte[] header(long seq) {
            ByteBuffer.wrap(buf, 0, Integer.BYTES).putInt(changes);
            return WalCodec.header(WRITE, seq, buf, count);
        }
    }

    private WalCodec() {
    }

    static byte[] encode(WalRecord record) {
        try (var buffer = new RecordBuffer()) {
            for (final var change : record.changes()) {
                buffer.change(change.target(), change.page(), change.offset(), change.length());
                buffer.write(change.bytes(), 0, change.bytes().length);
            }
            final var header = buffer.header(record.seq());
            return ByteBuffer.allocate(header.length + buffer.size()).put(header).put(buffer.bytes(), 0, buffer.size())
                    .array();
        }
    }

    static byte[] encode(WalCheckpoint checkpoint) {
        final var db = checkpoint.dbName().getBytes(StandardCharsets.UTF_8);
        final var coll = checkpoint.collName().getBytes(StandardCharsets.UTF_8);
        final var payload = ByteBuffer.allocate(Integer.BYTES * 3 + db.length + coll.length + Long.BYTES
                + checkpoint.pageLengths().size() * Long.BYTES * 2);
        payload.putInt(db.length).put(db).putInt(coll.length).put(coll).putLong(checkpoint.pkIndexLength())
                .putInt(checkpoint.pageLengths().size());
        checkpoint.pageLengths().forEach((page, length) -> payload.putLong(page).putLong(length));
        return frame(CHECKPOINT, checkpoint.seq(), payload.array());
    }

    static byte[] encodeAbort(long seq, long abortedSeq) {
        return frame(ABORT, seq, ByteBuffer.allocate(Long.BYTES).putLong(abortedSeq).array());
    }

    static byte[] encodePending(long seq, Collection<String> ids) {
        final var encoded = new ArrayList<byte[]>(ids.size());
        var size = Integer.BYTES;
        for (final var id : ids) {
            final var bytes = id.getBytes(StandardCharsets.UTF_8);
            encoded.add(bytes);
            size += Integer.BYTES + bytes.length;
        }
        final var payload = ByteBuffer.allocate(size).putInt(encoded.size());
        encoded.forEach(bytes -> payload.putInt(bytes.length).put(bytes));
        return frame(PENDING, seq, payload.array());
    }

    static byte[] withPending(byte[] checkpointFrame, long pendingSeq, Collection<String> pending) {
        if (pending.isEmpty()) {
            return checkpointFrame;
        }
        final var pendingFrame = encodePending(pendingSeq, pending);
        final var joined = Arrays.copyOf(checkpointFrame, checkpointFrame.length + pendingFrame.length);
        System.arraycopy(pendingFrame, 0, joined, checkpointFrame.length, pendingFrame.length);
        return joined;
    }

    static byte[] encodeRewriteBegin(long seq, long page, long lengthBefore) {
        return frame(REWRITE_BEGIN, seq,
                ByteBuffer.allocate(Long.BYTES * 2).putLong(page).putLong(lengthBefore).array());
    }

    static byte[] encodeRewriteEnd(long seq, long beginSeq, long page, long lengthAfter) {
        return frame(REWRITE_END, seq,
                ByteBuffer.allocate(Long.BYTES * 3).putLong(beginSeq).putLong(page).putLong(lengthAfter).array());
    }

    static byte[] encodeGroupBase(long seq, long pkIndexLength) {
        return frame(GROUP_BASE, seq, ByteBuffer.allocate(Long.BYTES).putLong(pkIndexLength).array());
    }

    private static byte[] frame(byte kind, long seq, byte[] payload) {
        return ByteBuffer.allocate(HEADER_BYTES + payload.length).put(header(kind, seq, payload, payload.length))
                .put(payload).array();
    }

    private static byte[] header(byte kind, long seq, byte[] payload, int length) {
        return ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(kind).putLong(seq).putInt(length)
                .putInt(crcOf(kind, seq, payload, length)).array();
    }

    private static int crcOf(byte kind, long seq, byte[] payload, int length) {
        final var crc = new CRC32();
        crc.update(kind);
        crc.update(ByteBuffer.allocate(Long.BYTES + Integer.BYTES).putLong(seq).putInt(length).array());
        crc.update(payload, 0, length);
        return (int) crc.getValue();
    }

    static Decoded decodeAll(byte[] bytes) {
        final var buffer = ByteBuffer.wrap(bytes);
        WalCheckpoint checkpoint = null;
        final var records = new ArrayList<WalRecord>();
        var framesSinceCheckpoint = 0;
        var lastSeq = 0L;
        var cleanLength = 0L;
        var groupBase = NO_GROUP_BASE;
        List<String> pendingIds = List.of();
        final var rewrites = new HashMap<Long, Rewrite>();
        while (buffer.remaining() >= HEADER_BYTES) {
            final var start = buffer.position();
            final var magic = buffer.get();
            final var kind = buffer.get();
            final var seq = buffer.getLong();
            final var length = buffer.getInt();
            final var crc = buffer.getInt();
            if (magic != MAGIC || length < 0 || length > buffer.remaining()) {
                break;
            }
            final var payload = new byte[length];
            buffer.get(payload);
            if (crcOf(kind, seq, payload, length) != crc) {
                break;
            }
            final boolean recognised;
            try {
                recognised = switch (kind) {
                    case CHECKPOINT -> {
                        checkpoint = decodeCheckpoint(seq, payload);
                        records.clear();
                        framesSinceCheckpoint = -1;
                        groupBase = NO_GROUP_BASE;
                        pendingIds = List.of();
                        rewrites.clear();
                        yield true;
                    }
                    case WRITE -> records.add(decodeRecord(seq, payload));
                    case ABORT -> {
                        final var aborted = ByteBuffer.wrap(payload).getLong();
                        records.removeIf(record -> record.seq() == aborted);
                        yield true;
                    }
                    case PENDING -> {
                        pendingIds = decodePending(payload);
                        yield true;
                    }
                    case GROUP_BASE -> {
                        groupBase = ByteBuffer.wrap(payload).getLong();
                        yield true;
                    }
                    case REWRITE_BEGIN -> {
                        final var frame = ByteBuffer.wrap(payload);
                        rewrites.put(frame.getLong(), new Rewrite(seq, frame.getLong(), NO_REWRITE_END));
                        yield true;
                    }
                    case REWRITE_END -> {
                        endRewrite(rewrites, ByteBuffer.wrap(payload));
                        yield true;
                    }
                    default -> false;
                };
            } catch (BufferUnderflowException | IllegalArgumentException | ArithmeticException e) {
                buffer.position(start);
                break;
            }
            if (!recognised) {
                break;
            }
            if (kind != GROUP_BASE && kind != PENDING) {
                framesSinceCheckpoint++;
            }
            lastSeq = Math.max(lastSeq, seq);
            cleanLength = buffer.position();
        }
        return new Decoded(checkpoint, List.copyOf(records), framesSinceCheckpoint, lastSeq, cleanLength, groupBase,
                pendingIds, Map.copyOf(rewrites));
    }

    private static void endRewrite(Map<Long, Rewrite> rewrites, ByteBuffer frame) {
        final var beginSeq = frame.getLong();
        final var page = frame.getLong();
        final var lengthAfter = frame.getLong();
        final var begun = rewrites.get(page);
        if (begun != null && begun.beginSeq() == beginSeq) {
            rewrites.put(page, new Rewrite(beginSeq, begun.lengthBefore(), lengthAfter));
        }
    }

    private static WalCheckpoint decodeCheckpoint(long seq, byte[] payload) {
        final var buffer = ByteBuffer.wrap(payload);
        final var db = readString(buffer);
        final var coll = readString(buffer);
        final var pkLength = buffer.getLong();
        final var pages = buffer.getInt();
        if (pages < 0) {
            throw new IllegalArgumentException("negative page count");
        }
        final var lengths = new TreeMap<Long, Long>();
        for (var index = 0; index < pages; index++) {
            lengths.put(buffer.getLong(), buffer.getLong());
        }
        return new WalCheckpoint(seq, db, coll, pkLength, lengths);
    }

    private static List<String> decodePending(byte[] payload) {
        final var buffer = ByteBuffer.wrap(payload);
        final var count = buffer.getInt();
        if (count < 0) {
            throw new IllegalArgumentException("negative pending id count");
        }
        final var ids = new ArrayList<String>(Math.min(count, payload.length));
        for (var index = 0; index < count; index++) {
            ids.add(readString(buffer));
        }
        return List.copyOf(ids);
    }

    private static String readString(ByteBuffer buffer) {
        final var length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            throw new IllegalArgumentException("bad string length");
        }
        final var bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static WalRecord decodeRecord(long seq, byte[] payload) {
        final var buffer = ByteBuffer.wrap(payload);
        final var count = buffer.getInt();
        if (count < 0) {
            throw new IllegalArgumentException("negative change count");
        }
        final var changes = new ArrayList<WalChange>(Math.min(count, payload.length));
        final var targets = WalTarget.values();
        for (var index = 0; index < count; index++) {
            final var ordinal = buffer.get();
            if (ordinal < 0 || ordinal >= targets.length) {
                throw new IllegalArgumentException("unknown change target");
            }
            final var target = targets[ordinal];
            final var page = buffer.getLong();
            final var offset = buffer.getLong();
            final var length = buffer.getLong();
            if (offset < 0 || length < 0) {
                throw new IllegalArgumentException("negative change position");
            }
            final var bytes = new byte[target == WalTarget.PAGE_BLANK ? 0 : Math.toIntExact(length)];
            buffer.get(bytes);
            changes.add(new WalChange(target, page, offset, length, bytes));
        }
        return new WalRecord(seq, List.copyOf(changes));
    }
}
