package org.techhouse.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntBiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ops.req.agg.FieldOperatorType;

public final class SearchUtils {
    private SearchUtils() {
    }

    public static <T> Set<String> findingByOperator(List<FieldIndexEntry<T>> entries, FieldOperatorType operatorType,
            T value) {
        return switch (operatorType) {
            case EQUALS -> findingEquals(entries, value);
            case NOT_EQUALS -> findingNotEquals(entries, value);
            case GREATER_THAN -> findingRange(entries, value, GreaterSmallerEqualsType.GREATER_THAN);
            case GREATER_THAN_EQUALS -> findingRange(entries, value, GreaterSmallerEqualsType.GREATER_THAN_EQUALS);
            case SMALLER_THAN -> findingRange(entries, value, GreaterSmallerEqualsType.SMALLER_THAN);
            case SMALLER_THAN_EQUALS -> findingRange(entries, value, GreaterSmallerEqualsType.SMALLER_THAN_EQUALS);
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
            final var auxList = new ArrayList<>(entries.subList(0, indexIndex));
            if (indexIndex <= entries.size() + 1) {
                auxList.addAll(entries.subList(indexIndex + 1, entries.size()));
            }
            resultStream = auxList.stream();
        } else {
            resultStream = entries.stream();
        }
        return resultStream.flatMap(tFieldIndexEntry -> tFieldIndexEntry.getIds().stream()).collect(Collectors.toSet());
    }

    private static <T, K> List<FieldIndexEntry<K>> castToJsonCustomList(List<FieldIndexEntry<T>> entries,
            Class<K> jsonCustomClass) {
        return entries.stream().map(entry -> new FieldIndexEntry<>(entry.getDatabaseName(), entry.getCollectionName(),
                jsonCustomClass.cast(entry.getValue()), entry.getIds())).toList();
    }

    private static <T> List<FieldIndexEntry<Double>> castToDoubleList(List<FieldIndexEntry<T>> entries) {
        return entries.stream()
                .map(doubleFieldIndexEntry -> new FieldIndexEntry<>(doubleFieldIndexEntry.getDatabaseName(),
                        doubleFieldIndexEntry.getCollectionName(),
                        ((Number) doubleFieldIndexEntry.getValue()).doubleValue(), doubleFieldIndexEntry.getIds()))
                .toList();
    }

    private static <T> Set<String> findingRange(List<FieldIndexEntry<T>> entries, T value,
            GreaterSmallerEqualsType type) {
        final int index = boundaryIndex(entries, value, type);
        if (index < 0) {
            return Set.of();
        }
        return type == GreaterSmallerEqualsType.GREATER_THAN || type == GreaterSmallerEqualsType.GREATER_THAN_EQUALS
                ? toIdSet(entries, index, entries.size())
                : toIdSet(entries, 0, index + 1);
    }

    private static <T> int boundaryIndex(List<FieldIndexEntry<T>> entries, T value, GreaterSmallerEqualsType type) {
        if (value instanceof Number n) {
            return binarySearchBoundary(castToDoubleList(entries), n.doubleValue(), type, Double::compareTo);
        }
        if (value instanceof JsonCustom<?> as) {
            @SuppressWarnings("unchecked")
            final var customEntries = (List<FieldIndexEntry<JsonCustom<Object>>>) (List<?>) castToJsonCustomList(
                    entries, as.getClass());
            @SuppressWarnings("unchecked")
            final var target = (JsonCustom<Object>) as;
            return binarySearchBoundary(customEntries, target, type,
                    (entryValue, operand) -> entryValue.compare(operand.getCustomValue()));
        }
        return -1;
    }

    // Relies on the entries being sorted by value, which FieldIndexLoader guarantees.
    private static <V> int binarySearchBoundary(List<FieldIndexEntry<V>> entries, V value,
            GreaterSmallerEqualsType type, ToIntBiFunction<V, V> compare) {
        int start = 0;
        int end = entries.size() - 1;
        if (end == 0) {
            return -1;
        }
        switch (type) {
            case SMALLER_THAN -> {
                if (compare.applyAsInt(entries.get(end).getValue(), value) < 0) {
                    return end;
                }
            }
            case SMALLER_THAN_EQUALS -> {
                if (compare.applyAsInt(entries.get(end).getValue(), value) <= 0) {
                    return end;
                }
            }
            case GREATER_THAN -> {
                if (compare.applyAsInt(entries.get(start).getValue(), value) > 0) {
                    return start;
                }
            }
            case GREATER_THAN_EQUALS -> {
                if (compare.applyAsInt(entries.get(start).getValue(), value) >= 0) {
                    return start;
                }
            }
            default -> {
            }
        }
        final var searchingSmaller = type == GreaterSmallerEqualsType.SMALLER_THAN
                || type == GreaterSmallerEqualsType.SMALLER_THAN_EQUALS;
        int ans = -1;
        while (start <= end) {
            final int mid = (start + end) / 2;
            final int cmp = compare.applyAsInt(entries.get(mid).getValue(), value);
            final boolean overshot = searchingSmaller
                    ? (type == GreaterSmallerEqualsType.SMALLER_THAN ? cmp >= 0 : cmp > 0)
                    : (type == GreaterSmallerEqualsType.GREATER_THAN ? cmp <= 0 : cmp < 0);
            if (searchingSmaller == overshot) {
                end = mid - 1;
                if (!searchingSmaller) {
                    ans = mid;
                }
            } else {
                start = mid + 1;
                if (searchingSmaller) {
                    ans = mid;
                }
            }
        }
        return ans;
    }

    private static <T> Set<String> toIdSet(List<FieldIndexEntry<T>> entries, int start, int foundIndex) {
        return entries.subList(start, foundIndex).stream()
                .flatMap(tFieldIndexEntry -> tFieldIndexEntry.getIds().stream()).collect(Collectors.toSet());
    }

    public static <T> Set<String> findingInNotIn(List<FieldIndexEntry<T>> entries, FieldOperatorType operatorType,
            List<T> value) {
        return switch (operatorType) {
            case EQUALS, GREATER_THAN, GREATER_THAN_EQUALS, NOT_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS, CONTAINS ->
                throw new UnsupportedOperationException();
            case IN -> findingIn(entries, value);
            case NOT_IN -> findingNotIn(entries, value);
        };
    }

    private static <T> Set<String> findingIn(List<FieldIndexEntry<T>> entries, List<T> value) {
        return entries.stream().filter(tFieldIndexEntry -> value.contains(tFieldIndexEntry.getValue()))
                .flatMap(tFieldIndexEntry -> tFieldIndexEntry.getIds().stream()).collect(Collectors.toSet());
    }

    private static <T> Set<String> findingNotIn(List<FieldIndexEntry<T>> entries, List<T> value) {
        return entries.stream().filter(tFieldIndexEntry -> !value.contains(tFieldIndexEntry.getValue()))
                .flatMap(tFieldIndexEntry -> tFieldIndexEntry.getIds().stream()).collect(Collectors.toSet());
    }

    private static <T> Set<String> findingContains(List<FieldIndexEntry<T>> entries, T value) {
        if (value instanceof String s) {
            return entries.stream().filter(tFieldIndexEntry -> ((String) tFieldIndexEntry.getValue()).contains(s))
                    .flatMap(tFieldIndexEntry -> tFieldIndexEntry.getIds().stream()).collect(Collectors.toSet());
        }
        return Set.of();
    }

    private enum GreaterSmallerEqualsType {
        GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS,
    }
}
