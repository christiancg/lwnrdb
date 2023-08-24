package org.techhouse.utils;

import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ops.req.agg.FieldOperatorType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SearchUtils {
    public static <T> Set<String> findingByOperator(List<FieldIndexEntry<T>> entries, FieldOperatorType operatorType, T value) {
        return switch (operatorType) {
            case EQUALS -> findingEquals(entries, value);
            case NOT_EQUALS -> findingNotEquals(entries, value);
            case GREATER_THAN -> findingGreaterThan(entries, value);
            case GREATER_THAN_EQUALS -> findingGreaterThanEquals(entries, value);
            case SMALLER_THAN -> findingLessThan(entries, value);
            case SMALLER_THAN_EQUALS -> findingLessThanEquals(entries, value);
            case IN, NOT_IN -> throw new UnsupportedOperationException();
            case CONTAINS -> findingContains(entries, value);
        };
    }

    private static <T> Set<String> findingEquals(List<FieldIndexEntry<T>> entries, T value) {
        final var indexIndex = Collections.binarySearch(entries, value);
        return indexIndex >= 0 ? entries.get(indexIndex).getIds() : Set.of();
    }

    private static <T> Set<String> findingNotEquals(List<FieldIndexEntry<T>> entries, T value) {
        final var indexIndex = Collections.binarySearch(entries, value);
        Stream<FieldIndexEntry<T>> resultStream;
        if (indexIndex >= 0) {
            final var indexCopy = new ArrayList<FieldIndexEntry<T>>();
            Collections.copy(indexCopy, entries);
            indexCopy.remove(indexIndex);
            resultStream = indexCopy.stream();
        } else {
            resultStream = entries.stream();
        }
        return resultStream.flatMap(tFieldIndexEntry -> tFieldIndexEntry.getIds().stream())
                .collect(Collectors.toSet());
    }

    private static <T> List<FieldIndexEntry<Double>> castToDoubleList(List<FieldIndexEntry<T>> entries) {
        return entries.stream().map(doubleFieldIndexEntry ->
                new FieldIndexEntry<>(doubleFieldIndexEntry.getDatabaseName(), doubleFieldIndexEntry.getCollectionName(),
                        (Double) doubleFieldIndexEntry.getValue(), doubleFieldIndexEntry.getIds())).toList();
    }

    private static <T> Set<String> findingGreaterThan(List<FieldIndexEntry<T>> entries, T value) {
        if (value instanceof Double) {
            int index = internalGreaterSmallerEquals(castToDoubleList(entries), (Double) value,
                    GreaterSmallerEqualsType.GREATER_THAN);
            if (index >= 0) {
                return toIdSet(entries, index, entries.size());
            }
        }
        return Set.of();
    }

    private static <T> Set<String> findingGreaterThanEquals(List<FieldIndexEntry<T>> entries, T value) {
        if (value instanceof Double) {
            int index = internalGreaterSmallerEquals(castToDoubleList(entries), (Double) value,
                    GreaterSmallerEqualsType.GREATER_THAN_EQUALS);
            if (index >= 0) {
                return toIdSet(entries, index, entries.size());
            }
        }
        return Set.of();
    }

    private static <T> Set<String> findingLessThan(List<FieldIndexEntry<T>> entries, T value) {
        if (value instanceof Double) {
            int index = internalGreaterSmallerEquals(castToDoubleList(entries), (Double) value,
                    GreaterSmallerEqualsType.SMALLER_THAN);
            if (index >= 0) {
                return toIdSet(entries, 0, ++index);
            }
        }
        return Set.of();
    }

    private static <T> Set<String> findingLessThanEquals(List<FieldIndexEntry<T>> entries, T value) {
        if (value instanceof Double) {
            int index = internalGreaterSmallerEquals(castToDoubleList(entries), (Double) value,
                    GreaterSmallerEqualsType.SMALLER_THAN_EQUALS);
            if (index >= 0) {
                return toIdSet(entries, 0, ++index);
            }
        }
        return Set.of();
    }

    private static <T> Set<String> toIdSet(List<FieldIndexEntry<T>> entries, int start, int foundIndex) {
        return entries.subList(start, foundIndex).stream()
                .flatMap(tFieldIndexEntry -> tFieldIndexEntry.getIds().stream())
                .collect(Collectors.toSet());
    }

    public static <T> Set<String> findingInNotIn(List<FieldIndexEntry<T>> entries, FieldOperatorType operatorType, List<T> value) {
        return switch (operatorType) {
            case EQUALS, GREATER_THAN, GREATER_THAN_EQUALS, NOT_EQUALS, SMALLER_THAN,
                    SMALLER_THAN_EQUALS, CONTAINS -> throw new UnsupportedOperationException();
            case IN -> findingIn(entries, value);
            case NOT_IN -> findingNotIn(entries, value);
        };
    }

    private static <T> Set<String> findingIn(List<FieldIndexEntry<T>> entries, List<T> value) {
        return entries.stream()
                .filter(tFieldIndexEntry -> value.contains(tFieldIndexEntry.getValue()))
                .flatMap(tFieldIndexEntry -> tFieldIndexEntry.getIds().stream())
                .collect(Collectors.toSet());
    }

    private static <T> Set<String> findingNotIn(List<FieldIndexEntry<T>> entries, List<T> value) {
        return entries.stream()
                .filter(tFieldIndexEntry -> !value.contains(tFieldIndexEntry.getValue()))
                .flatMap(tFieldIndexEntry -> tFieldIndexEntry.getIds().stream())
                .collect(Collectors.toSet());
    }

    private static <T> Set<String> findingContains(List<FieldIndexEntry<T>> entries, T value) {
        if (value instanceof String) {
            return entries.stream().filter(tFieldIndexEntry -> ((String) tFieldIndexEntry.getValue()).contains((String) value))
                    .flatMap(tFieldIndexEntry -> tFieldIndexEntry.getIds().stream())
                    .collect(Collectors.toSet());
        }
        return Set.of();
    }

    private static int internalGreaterSmallerEquals(List<FieldIndexEntry<Double>> entries, Double value, GreaterSmallerEqualsType type) {
        int start = 0, end = entries.size() - 1;
        // Minimum size of the array should be 1
        if (end == 0) {
            return -1;
        }
        // If target lies beyond the max element, then the index of strictly smaller
        // value than target should be (end - 1)
        switch (type) {
            case SMALLER_THAN -> {
                var entry = entries.get(end);
                if (value > entry.getValue()) {
                    return end;
                }
            }
            case SMALLER_THAN_EQUALS -> {
                var entry = entries.get(end);
                if (value >= entry.getValue()) {
                    return end;
                }
            }
            case GREATER_THAN -> {
                var entry = entries.get(start);
                if (value < entry.getValue()) {
                    return start;
                }
            }
            case GREATER_THAN_EQUALS -> {
                var entry = entries.get(start);
                if (value <= entry.getValue()) {
                    return start;
                }
            }
        }
        int ans = -1;
        if (type == GreaterSmallerEqualsType.SMALLER_THAN || type == GreaterSmallerEqualsType.SMALLER_THAN_EQUALS) {
            while (start <= end) {
                int mid = (start + end) / 2; // Move to the left side if the target is smaller
                final var midValue = entries.get(mid).getValue();
                if (type == GreaterSmallerEqualsType.SMALLER_THAN && midValue >= value || midValue > value) {
                    end = mid - 1;
                } else { // Move right side
                    ans = mid;
                    start = mid + 1;
                }
            }
        } else {
            while (start <= end) {
                int mid = (start + end) / 2;
                final var midValue = entries.get(mid).getValue();
                // Move to right side if target is greater.
                if (type == GreaterSmallerEqualsType.GREATER_THAN && midValue <= value || midValue < value) {
                    start = mid + 1;
                } else { // Move left side.
                    ans = mid;
                    end = mid - 1;
                }
            }
        }
        return ans;
    }

    private enum GreaterSmallerEqualsType {
        GREATER_THAN,
        GREATER_THAN_EQUALS,
        SMALLER_THAN,
        SMALLER_THAN_EQUALS,
    }
}
