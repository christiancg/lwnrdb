package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;

final class IndexLogMemo {
    static final IndexLogMemo SHARED = new IndexLogMemo(Globals.INDEX_LOG_MEMO_MAX_BYTES);
    private static final byte ADD = '+';
    private static final byte REMOVE = '-';
    private static final long KEY_BYTES = 32;
    private static final long LINE_SLOT_BYTES = 16;
    private static final int INITIAL_LINES = 16;
    private static final int INITIAL_LINE_BYTES = 512;
    private static final int TAIL_BYTES = 32;
    private static final byte[] NO_TAIL = new byte[0];

    private final long maxBytes;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, ParsedLog> logs = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;

    IndexLogMemo(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    IndexLogOverlay overlayOf(File log, IndexKeyOrder order, Predicate<Object> selects) throws IOException {
        final var path = log.getAbsolutePath();
        final var generation = FieldIndexLog.generationOf(log);
        try (var cursor = IndexFileCursor.open(log)) {
            if (cursor == null) {
                invalidate(log);
                return IndexLogOverlay.ABSENT;
            }
            final var parsed = parsedLogOf(path, generation, order.type());
            var answered = false;
            try {
                final IndexLogOverlay overlay;
                final long parsedBytes;
                parsed.lock.lock();
                try {
                    parsedBytes = parsed.catchUp(cursor, order);
                    overlay = parsed.overlay(selects);
                } finally {
                    parsed.lock.unlock();
                }
                answered = true;
                account(path, parsed, parsedBytes);
                return overlay;
            } finally {
                if (!answered) {
                    drop(path, parsed);
                }
            }
        }
    }

    void invalidate(File log) {
        final var path = log.getAbsolutePath();
        lock.lock();
        try {
            final var parsed = logs.remove(path);
            if (parsed != null) {
                bytes -= parsed.accounted;
            }
        } finally {
            lock.unlock();
        }
    }

    void forgetUnder(File folder) {
        final var prefix = folder.getAbsolutePath() + File.separator;
        lock.lock();
        try {
            final var entries = logs.entrySet().iterator();
            while (entries.hasNext()) {
                final var entry = entries.next();
                if (entry.getKey().startsWith(prefix)) {
                    bytes -= entry.getValue().accounted;
                    entries.remove();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    long bytes() {
        lock.lock();
        try {
            return bytes;
        } finally {
            lock.unlock();
        }
    }

    boolean holds(File log) {
        lock.lock();
        try {
            return logs.containsKey(log.getAbsolutePath());
        } finally {
            lock.unlock();
        }
    }

    private ParsedLog parsedLogOf(String path, long generation, Class<?> type) {
        lock.lock();
        try {
            var parsed = logs.get(path);
            if (parsed == null || parsed.generation != generation || parsed.type != type) {
                if (parsed != null) {
                    bytes -= parsed.accounted;
                }
                parsed = new ParsedLog(generation, type);
                logs.put(path, parsed);
            }
            return parsed;
        } finally {
            lock.unlock();
        }
    }

    private void account(String path, ParsedLog parsed, long parsedBytes) {
        lock.lock();
        try {
            if (logs.get(path) != parsed) {
                return;
            }
            bytes += parsedBytes - parsed.accounted;
            parsed.accounted = parsedBytes;
            final var eldest = logs.values().iterator();
            while (bytes > maxBytes && eldest.hasNext()) {
                bytes -= eldest.next().accounted;
                eldest.remove();
            }
        } finally {
            lock.unlock();
        }
    }

    private void drop(String path, ParsedLog parsed) {
        lock.lock();
        try {
            if (logs.get(path) == parsed) {
                logs.remove(path);
                bytes -= parsed.accounted;
            }
        } finally {
            lock.unlock();
        }
    }

    private static final class ParsedLog {
        private final ReentrantLock lock = new ReentrantLock();
        private final long generation;
        private final Class<?> type;
        private byte[] lines = new byte[INITIAL_LINE_BYTES];
        private int usedBytes;
        private int[] lineFrom = new int[INITIAL_LINES];
        private int[] keyEnd = new int[INITIAL_LINES];
        private int[] lineTo = new int[INITIAL_LINES];
        private Object[] keys = new Object[INITIAL_LINES];
        private int lineCount;
        private long keyBytes;
        private long length;
        private byte[] tail = NO_TAIL;
        private long accounted;

        private ParsedLog(long generation, Class<?> type) {
            this.generation = generation;
            this.type = type;
        }

        private long catchUp(IndexFileCursor cursor, IndexKeyOrder order) throws IOException {
            if (length > cursor.length() || !cursor.holdsAt(length - tail.length, tail)) {
                usedBytes = 0;
                lineCount = 0;
                Arrays.fill(keys, null);
                keyBytes = 0;
                length = 0;
                tail = NO_TAIL;
            }
            if (length < cursor.length()) {
                cursor.seek(length);
                while (cursor.nextLine()) {
                    parseLine(cursor, order);
                }
                length = cursor.length();
                final var tailLength = (int) Math.min(length, TAIL_BYTES);
                tail = cursor.bytesAt(length - tailLength, tailLength);
            }
            return lines.length + (long) keys.length * LINE_SLOT_BYTES + keyBytes;
        }

        private void parseLine(IndexFileCursor cursor, IndexKeyOrder order) {
            final var bytes = cursor.lineBytes();
            final var from = cursor.lineFrom();
            final var to = cursor.lineTo();
            if (!cursor.lineTerminated() || to - from < 2 || bytes[from] != ADD && bytes[from] != REMOVE
                    || bytes[from + 1] != IndexFileCursor.SEPARATOR) {
                throw new IndexFileDeclined();
            }
            final var keyTo = cursor.separatorIn(from + 2, to);
            final var key = order.parse(bytes, from + 2, keyTo);
            if (lineCount == keys.length) {
                final var capacity = lineCount * 2;
                lineFrom = Arrays.copyOf(lineFrom, capacity);
                keyEnd = Arrays.copyOf(keyEnd, capacity);
                lineTo = Arrays.copyOf(lineTo, capacity);
                keys = Arrays.copyOf(keys, capacity);
            }
            final var lineLength = to - from;
            if (usedBytes + lineLength > lines.length) {
                lines = Arrays.copyOf(lines, Math.max(lines.length * 2, usedBytes + lineLength));
            }
            System.arraycopy(bytes, from, lines, usedBytes, lineLength);
            lineFrom[lineCount] = usedBytes;
            keyEnd[lineCount] = usedBytes + keyTo - from;
            lineTo[lineCount] = usedBytes + lineLength;
            keys[lineCount] = key;
            lineCount++;
            usedBytes += lineLength;
            keyBytes += KEY_BYTES + 2L * (keyTo - from);
        }

        private IndexLogOverlay overlay(Predicate<Object> selects) {
            final var selected = new LinkedHashMap<String, List<IndexLogOverlay.Change>>();
            for (var line = 0; line < lineCount; line++) {
                final var key = keys[line];
                if (selects.test(key)) {
                    selected.computeIfAbsent(FieldIndexEntry.fileKeyOf(key), _ -> new ArrayList<>())
                            .add(new IndexLogOverlay.Change(lines[lineFrom[line]] == ADD,
                                    IndexLines.ids(lines, keyEnd[line], lineTo[line])));
                }
            }
            return IndexLogOverlay.of(selected);
        }
    }
}
