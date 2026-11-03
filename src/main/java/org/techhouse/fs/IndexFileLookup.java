package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.techhouse.data.FieldIndexEntry;

public final class IndexFileLookup {
    public static final long DECLINED = -1L;

    public record Bound(Object value, boolean lower, boolean inclusive) {
    }

    private IndexFileLookup() {
    }

    public static boolean supports(Class<?> type) {
        return IndexKeyOrder.supports(type);
    }

    public static Set<String> equalsIds(File index, Class<?> type, Object value) throws IOException {
        return anyOfIds(index, type, List.of(value));
    }

    public static Set<String> anyOfIds(File index, Class<?> type, List<?> values) throws IOException {
        return idsOf(index, anyOf(type, values));
    }

    public static long anyOfCount(File index, Class<?> type, List<?> values) throws IOException {
        return countOf(index, anyOf(type, values));
    }

    public static Set<String> rangeIds(File index, Class<?> type, List<Bound> bounds) throws IOException {
        return idsOf(index, range(type, bounds));
    }

    public static long rangeCount(File index, Class<?> type, List<Bound> bounds) throws IOException {
        return countOf(index, range(type, bounds));
    }

    public static Set<String> complementIds(File index, Class<?> type, List<?> values) throws IOException {
        final var order = operandOrder(type, values);
        if (order == null) {
            return null;
        }
        final var operands = distinctSorted(order, values);
        final Predicate<Object> outside = value -> !matchesAny(order, operands, value);
        return idsOf(index,
                new Query(order, outside, (cursor, collector) -> wholeFile(cursor, order, outside, collector)));
    }

    public static long idCount(File index, Class<?> type) throws IOException {
        if (!IndexKeyOrder.supports(type)) {
            return DECLINED;
        }
        final var order = IndexKeyOrder.of(type);
        final Predicate<Object> every = _ -> true;
        return countOf(index,
                new Query(order, every, (cursor, collector) -> wholeFile(cursor, order, every, collector)));
    }

    private interface Walk {
        void run(IndexFileCursor cursor, Collector collector) throws IOException;
    }

    private record Query(IndexKeyOrder order, Predicate<Object> selects, Walk walk) {
    }

    private static Query anyOf(Class<?> type, List<?> values) {
        final var order = operandOrder(type, values);
        if (order == null) {
            return null;
        }
        final var operands = distinctSorted(order, values);
        return new Query(order, value -> matchesAny(order, operands, value), (cursor, collector) -> {
            for (final var operand : operands) {
                windowOf(cursor, order, value -> order.compare(value, operand) >= 0,
                        value -> order.compare(value, operand) <= 0, collector);
            }
        });
    }

    private static Query range(Class<?> type, List<Bound> bounds) {
        if (!IndexKeyOrder.supportsRanges(type) || bounds.isEmpty()) {
            return null;
        }
        final var order = IndexKeyOrder.of(type);
        for (final var bound : bounds) {
            if (bound.value() == null || order.rejects(bound.value())) {
                return null;
            }
        }
        final Predicate<Object> lowerOk = value -> satisfiesEvery(order, bounds, true, value);
        final Predicate<Object> upperOk = value -> satisfiesEvery(order, bounds, false, value);
        return new Query(order, lowerOk.and(upperOk),
                (cursor, collector) -> windowOf(cursor, order, lowerOk, upperOk, collector));
    }

    private static Set<String> idsOf(File index, Query query) throws IOException {
        final var collector = query == null ? null : collect(index, query, false);
        return collector == null ? null : collector.ids();
    }

    private static long countOf(File index, Query query) throws IOException {
        final var collector = query == null ? null : collect(index, query, true);
        return collector == null ? DECLINED : collector.count();
    }

    private static Collector collect(File index, Query query, boolean countOnly) throws IOException {
        final var lock = FileLocks.lockRead(index);
        var absent = false;
        try {
            final var collector = new Collector(
                    IndexLogOverlay.read(FilePaths.logFileOf(index), query.order(), query.selects()), countOnly);
            try (var cursor = IndexFileCursor.open(index)) {
                absent = cursor == null && !collector.log.present();
                if (absent) {
                    return null;
                }
                if (cursor != null) {
                    query.walk().run(cursor, collector);
                }
            }
            return collector;
        } catch (IndexFileDeclined declined) {
            return null;
        } finally {
            lock.unlock();
            if (absent) {
                FileLocks.evictIfUnused(index);
            }
        }
    }

    private static void windowOf(IndexFileCursor cursor, IndexKeyOrder order, Predicate<Object> lowerOk,
            Predicate<Object> upperOk, Collector collector) throws IOException {
        var low = 0L;
        var high = cursor.length();
        while (low < high) {
            final var middle = (low + high) >>> 1;
            final var lineStart = cursor.lineStartAtOrAfter(middle);
            if (lineStart < cursor.length() && !lowerOk.test(keyAt(cursor, order, lineStart))) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        final var start = cursor.lineStartAtOrAfter(low);
        final var previousStart = cursor.previousLineStart(start);
        Object previous = null;
        if (previousStart >= 0) {
            previous = keyAt(cursor, order, previousStart);
            if (lowerOk.test(previous)) {
                throw new IndexFileDeclined();
            }
        }
        cursor.seek(start);
        while (cursor.nextLine()) {
            final var separator = cursor.separatorIn(cursor.lineFrom(), cursor.lineTo());
            final var value = order.parse(cursor.lineBytes(), cursor.lineFrom(), separator);
            if (previous != null && order.fileOrder(previous, value) >= 0) {
                throw new IndexFileDeclined();
            }
            previous = value;
            if (!upperOk.test(value)) {
                return;
            }
            if (!lowerOk.test(value)) {
                throw new IndexFileDeclined();
            }
            collector.add(value, cursor, separator);
        }
    }

    private static void wholeFile(IndexFileCursor cursor, IndexKeyOrder order, Predicate<Object> selects,
            Collector collector) throws IOException {
        Object previous = null;
        cursor.seek(0);
        while (cursor.nextLine()) {
            final var separator = cursor.separatorIn(cursor.lineFrom(), cursor.lineTo());
            final var value = order.parse(cursor.lineBytes(), cursor.lineFrom(), separator);
            if (previous != null && order.fileOrder(previous, value) >= 0) {
                throw new IndexFileDeclined();
            }
            previous = value;
            if (selects.test(value)) {
                collector.add(value, cursor, separator);
            }
        }
    }

    private static Object keyAt(IndexFileCursor cursor, IndexKeyOrder order, long lineStart) throws IOException {
        final var key = cursor.keyAt(lineStart);
        return order.parse(key, 0, key.length);
    }

    private static IndexKeyOrder operandOrder(Class<?> type, List<?> values) {
        if (!IndexKeyOrder.supports(type) || values.isEmpty()) {
            return null;
        }
        final var order = IndexKeyOrder.of(type);
        for (final var value : values) {
            if (value == null || order.rejects(value)) {
                return null;
            }
        }
        return order;
    }

    private static List<Object> distinctSorted(IndexKeyOrder order, List<?> values) {
        final var sorted = new ArrayList<Object>(values);
        sorted.sort(order::compare);
        final var distinct = new ArrayList<>(sorted.size());
        for (final var value : sorted) {
            if (distinct.isEmpty() || order.compare(distinct.getLast(), value) != 0) {
                distinct.add(value);
            }
        }
        return distinct;
    }

    private static boolean matchesAny(IndexKeyOrder order, List<Object> operands, Object value) {
        var low = 0;
        var high = operands.size() - 1;
        while (low <= high) {
            final var middle = (low + high) >>> 1;
            final var compared = order.compare(value, operands.get(middle));
            if (compared == 0) {
                return true;
            }
            if (compared < 0) {
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return false;
    }

    private static boolean satisfiesEvery(IndexKeyOrder order, List<Bound> bounds, boolean lowerSide, Object value) {
        for (final var bound : bounds) {
            if (bound.lower() == lowerSide) {
                final var compared = order.compare(value, bound.value());
                final var inside = lowerSide ? compared > 0 : compared < 0;
                if (!inside && !(bound.inclusive() && compared == 0)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final class Collector {
        private final IndexLogOverlay log;
        private final boolean countOnly;
        private final Set<String> ids = new HashSet<>();
        private final Map<String, Set<String>> touched = new HashMap<>();
        private long counted;

        private Collector(IndexLogOverlay log, boolean countOnly) {
            this.log = log;
            this.countOnly = countOnly;
        }

        private void add(Object value, IndexFileCursor cursor, int separator) {
            final var key = FieldIndexEntry.fileKeyOf(value);
            if (log.touches(key)) {
                IndexLines.addIds(touched.computeIfAbsent(key, _ -> new HashSet<>()), cursor.lineBytes(), separator,
                        cursor.lineTo());
            } else if (countOnly) {
                counted += IndexLines.idCount(cursor.lineBytes(), separator, cursor.lineTo());
            } else {
                IndexLines.addIds(ids, cursor.lineBytes(), separator, cursor.lineTo());
            }
        }

        private Set<String> ids() {
            for (final var key : log.keys()) {
                ids.addAll(log.applied(key, touched.get(key)));
            }
            return ids;
        }

        private long count() {
            var total = counted;
            for (final var key : log.keys()) {
                total += log.applied(key, touched.get(key)).size();
            }
            return total;
        }
    }
}
