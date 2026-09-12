package org.techhouse.utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntBiFunction;
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
        if (indexIndex < 0) {
            return toIdSet(entries, 0, entries.size());
        }
        final var ids = new HashSet<String>();
        for (var i = 0; i < entries.size(); i++) {
            if (i != indexIndex) {
                ids.addAll(entries.get(i).getIds());
            }
        }
        return ids;
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
        return Double.compare(((Number) entryValue).doubleValue(), target);
    }

    private static <T> int compareAsCustom(T entryValue, JsonCustom<Object> target) {
        @SuppressWarnings("unchecked")
        final var entryCustom = (JsonCustom<Object>) entryValue;
        return entryCustom.compare(target.getCustomValue());
    }

    // Relies on the entries being sorted by value, which FieldIndexLoader guarantees.
    private static <T, V> int binarySearchBoundary(List<FieldIndexEntry<T>> entries, V value,
            GreaterSmallerEqualsType type, ToIntBiFunction<T, V> compare) {
        if (entries.isEmpty()) {
            return -1;
        }
        int start = 0;
        int end = entries.size() - 1;
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
        final var ids = new HashSet<String>();
        for (var i = start; i < foundIndex; i++) {
            ids.addAll(entries.get(i).getIds());
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
        final var operands = new HashSet<T>(value);
        final var ids = new HashSet<String>();
        for (final var entry : entries) {
            if (operands.contains(entry.getValue()) == present) {
                ids.addAll(entry.getIds());
            }
        }
        return ids;
    }

    private static <T> Set<String> findingContains(List<FieldIndexEntry<T>> entries, T value) {
        if (value instanceof String s) {
            final var ids = new HashSet<String>();
            for (final var entry : entries) {
                if (((String) entry.getValue()).contains(s)) {
                    ids.addAll(entry.getIds());
                }
            }
            return ids;
        }
        return Set.of();
    }

    private enum GreaterSmallerEqualsType {
        GREATER_THAN, GREATER_THAN_EQUALS, SMALLER_THAN, SMALLER_THAN_EQUALS
    }
}
