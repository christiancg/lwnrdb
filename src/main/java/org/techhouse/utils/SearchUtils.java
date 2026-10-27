package org.techhouse.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.SortedChunks;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ops.req.agg.FieldOperatorType;

public final class SearchUtils {
    private SearchUtils() {
    }

    public static <T> Set<String> findingByOperator(List<FieldIndexEntry<T>> entries, FieldOperatorType operatorType,
            T value) {
        return switch (operatorType) {
            case EQUALS, GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS ->
                findingWindow(entries, operatorType, value);
            case NOT_EQUALS -> findingMembership(entries, List.of(value), false);
            case IN, NOT_IN -> throw new UnsupportedOperationException();
            case CONTAINS -> findingContains(entries, value);
        };
    }

    public static <T> Set<String> findingBetween(List<FieldIndexEntry<T>> entries, RangeBounds bounds) {
        final var slice = sliceBetween(entries, bounds);
        return slice == null ? Set.of() : toIdSet(entries, slice[0], slice[1]);
    }

    public static <T> long countBetween(List<FieldIndexEntry<T>> entries, RangeBounds bounds) {
        return idCountOf(entries, sliceBetween(entries, bounds));
    }

    public static <T> long countEquals(List<FieldIndexEntry<T>> entries, T value) {
        return idCountOf(entries, equalRun(entries, value));
    }

    private static <T> long idCountOf(List<FieldIndexEntry<T>> entries, int[] slice) {
        return slice == null ? 0L : idCountOf(entries, slice[0], slice[1]);
    }

    private static <T> long idCountOf(List<FieldIndexEntry<T>> entries, int from, int to) {
        if (entries instanceof SortedChunks<?> chunked) {
            @SuppressWarnings("unchecked")
            final var chunks = (SortedChunks<FieldIndexEntry<T>>) chunked;
            return chunks.sumOf(from, to, entry -> entry.getIds().size());
        }
        var count = 0L;
        for (var index = from; index < to; index++) {
            count += entries.get(index).getIds().size();
        }
        return count;
    }

    public static <T> int[] sliceBetween(List<FieldIndexEntry<T>> entries, RangeBounds bounds) {
        var from = 0;
        var to = entries.size();
        for (final var bound : bounds.bounds()) {
            final var boundary = boundaryIndex(entries, bound.value(), typeOf(bound.operatorType()));
            if (boundary < 0) {
                return null;
            }
            if (bound.isLower()) {
                from = Math.max(from, boundary);
            } else {
                to = Math.min(to, boundary + 1);
            }
        }
        return from < to ? new int[]{from, to} : null;
    }

    private static GreaterSmallerEqualsType typeOf(FieldOperatorType operatorType) {
        return switch (operatorType) {
            case GREATER_THAN -> GreaterSmallerEqualsType.GREATER_THAN;
            case GREATER_THAN_EQUALS -> GreaterSmallerEqualsType.GREATER_THAN_EQUALS;
            case SMALLER_THAN -> GreaterSmallerEqualsType.SMALLER_THAN;
            case SMALLER_THAN_EQUALS -> GreaterSmallerEqualsType.SMALLER_THAN_EQUALS;
            default -> throw new IllegalArgumentException("Not a range bound: " + operatorType);
        };
    }

    public static <T> int[] windowOf(List<FieldIndexEntry<T>> entries, FieldOperatorType operatorType, T value) {
        return switch (operatorType) {
            case EQUALS -> equalRun(entries, value);
            case GREATER_THAN, GREATER_THAN_EQUALS -> {
                final var index = boundaryIndex(entries, value, typeOf(operatorType));
                yield index < 0 ? null : new int[]{index, entries.size()};
            }
            case SMALLER_THAN, SMALLER_THAN_EQUALS -> {
                final var index = boundaryIndex(entries, value, typeOf(operatorType));
                yield index < 0 ? null : new int[]{0, index + 1};
            }
            default -> throw new IllegalArgumentException("Not a window operator: " + operatorType);
        };
    }

    public static <T> List<int[]> membershipRuns(List<FieldIndexEntry<T>> entries, List<T> operands) {
        final var runs = new ArrayList<int[]>();
        final var starts = new HashSet<Integer>();
        for (final var operand : operands) {
            final var run = equalRun(entries, operand);
            if (run != null && starts.add(run[0])) {
                runs.add(run);
            }
        }
        runs.sort(Comparator.comparingInt(run -> run[0]));
        return runs;
    }

    private static <T> Set<String> findingWindow(List<FieldIndexEntry<T>> entries, FieldOperatorType operatorType,
            T value) {
        final var window = windowOf(entries, operatorType, value);
        return window == null ? Set.of() : toIdSet(entries, window[0], window[1]);
    }

    private static <T> int[] equalRun(List<FieldIndexEntry<T>> entries, T value) {
        final var from = partitionPoint(entries, entry -> entry.compareTo(value) < 0);
        final var to = partitionPoint(entries, entry -> entry.compareTo(value) <= 0);
        return from < to ? new int[]{from, to} : null;
    }

    private static <T> int boundaryIndex(List<FieldIndexEntry<T>> entries, Object value,
            GreaterSmallerEqualsType type) {
        if (value instanceof Number n) {
            return binarySearchBoundary(entries, n.doubleValue(), type, SearchUtils::compareAsDouble);
        }
        if (value instanceof JsonCustom<?> as) {
            @SuppressWarnings("unchecked")
            final var target = (JsonCustom<Object>) as;
            return binarySearchBoundary(entries, target, type, SearchUtils::compareAsCustom);
        }
        return -1;
    }

    private static <T> int compareAsDouble(T entryValue, Double target) {
        return FieldIndexEntry.compareIndexedNumbers((Number) entryValue, target);
    }

    private static <T> int compareAsCustom(T entryValue, JsonCustom<Object> target) {
        @SuppressWarnings("unchecked")
        final var entryCustom = (JsonCustom<Object>) entryValue;
        return entryCustom.compare(target.getCustomValue());
    }

    private static <T, V> int binarySearchBoundary(List<FieldIndexEntry<T>> entries, V value,
            GreaterSmallerEqualsType type, ToIntBiFunction<T, V> compare) {
        return switch (type) {
            case GREATER_THAN -> lowerBoundary(entries,
                    partitionPoint(entries, entry -> compare.applyAsInt(entry.getValue(), value) <= 0));
            case GREATER_THAN_EQUALS -> lowerBoundary(entries,
                    partitionPoint(entries, entry -> compare.applyAsInt(entry.getValue(), value) < 0));
            case SMALLER_THAN -> partitionPoint(entries, entry -> compare.applyAsInt(entry.getValue(), value) < 0) - 1;
            case SMALLER_THAN_EQUALS ->
                partitionPoint(entries, entry -> compare.applyAsInt(entry.getValue(), value) <= 0) - 1;
        };
    }

    private static int lowerBoundary(List<?> entries, int point) {
        return point == entries.size() ? -1 : point;
    }

    private static <E> int partitionPoint(List<E> entries, Predicate<? super E> before) {
        if (entries instanceof SortedChunks<?> chunked) {
            @SuppressWarnings("unchecked")
            final var chunks = (SortedChunks<E>) chunked;
            return chunks.partitionPoint(before);
        }
        var low = 0;
        var high = entries.size();
        while (low < high) {
            final var middle = (low + high) >>> 1;
            if (before.test(entries.get(middle))) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static <T> Set<String> toIdSet(List<FieldIndexEntry<T>> entries, int start, int foundIndex) {
        final var ids = HashSet.<String>newHashSet((int) idCountOf(entries, start, foundIndex));
        for (final var entry : entries.subList(start, foundIndex)) {
            ids.addAll(entry.getIds());
        }
        return ids;
    }

    public static <T> Set<String> findingInNotIn(List<FieldIndexEntry<T>> entries, FieldOperatorType operatorType,
            List<T> value) {
        return switch (operatorType) {
            case EQUALS, GREATER_THAN, GREATER_THAN_EQUALS, NOT_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS, CONTAINS ->
                throw new UnsupportedOperationException();
            case IN -> findingMembership(entries, value, true);
            case NOT_IN -> findingMembership(entries, value, false);
        };
    }

    private static <T> Set<String> findingMembership(List<FieldIndexEntry<T>> entries, List<T> value, boolean present) {
        final var matched = new HashSet<String>();
        for (final var run : membershipRuns(entries, value)) {
            matched.addAll(toIdSet(entries, run[0], run[1]));
        }
        if (present) {
            return matched;
        }
        final var ids = new HashSet<String>();
        for (final var entry : entries) {
            for (final var id : entry.getIds()) {
                if (!matched.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static <T> Set<String> findingContains(List<FieldIndexEntry<T>> entries, T value) {
        if (!(value instanceof String s)) {
            return null;
        }
        final var ids = new HashSet<String>();
        for (final var entry : entries) {
            if (((String) entry.getValue()).contains(s)) {
                ids.addAll(entry.getIds());
            }
        }
        return ids;
    }

    private enum GreaterSmallerEqualsType {
        GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS
    }
}
