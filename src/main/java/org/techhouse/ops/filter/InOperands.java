package org.techhouse.ops.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.CaseFolding;

final class InOperands {
    private final boolean exact;
    private final Set<String> exactStrings;
    private final int[] foldHashes;
    private final String[] foldedStrings;
    private final double[] numbers;
    private final boolean holdsTrue;
    private final boolean holdsFalse;
    private final List<JsonCustom<?>> customs;
    private final List<JsonBaseElement> others;

    private InOperands(boolean exact, List<String> strings, double[] numbers, boolean holdsTrue, boolean holdsFalse,
            List<JsonCustom<?>> customs, List<JsonBaseElement> others) {
        this.exact = exact;
        this.numbers = numbers;
        this.holdsTrue = holdsTrue;
        this.holdsFalse = holdsFalse;
        this.customs = customs;
        this.others = others;
        if (exact) {
            this.exactStrings = new HashSet<>(strings);
            this.foldHashes = new int[0];
            this.foldedStrings = new String[0];
        } else {
            this.exactStrings = Set.of();
            final var byHash = strings.stream().sorted(Comparator.comparingInt(CaseFolding::foldHash)).toList();
            this.foldedStrings = byHash.toArray(String[]::new);
            this.foldHashes = byHash.stream().mapToInt(CaseFolding::foldHash).toArray();
        }
    }

    static InOperands of(JsonArray operands, boolean exact) {
        final var strings = new ArrayList<String>();
        final var numbers = new ArrayList<Double>();
        final var customs = new ArrayList<JsonCustom<?>>();
        final var others = new ArrayList<JsonBaseElement>();
        var holdsTrue = false;
        var holdsFalse = false;
        for (final var operand : operands) {
            switch (operand) {
                case JsonCustom<?> custom -> customs.add(custom);
                case JsonString string -> strings.add(string.getValue());
                case JsonNumber number -> numbers.add(number.doubleValue() + 0.0);
                case JsonBoolean bool -> {
                    holdsTrue |= bool.getValue();
                    holdsFalse |= !bool.getValue();
                }
                default -> others.add(operand);
            }
        }
        final var sortedNumbers = numbers.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sortedNumbers);
        return new InOperands(exact, strings, sortedNumbers, holdsTrue, holdsFalse, List.copyOf(customs),
                List.copyOf(others));
    }

    boolean exact() {
        return exact;
    }

    boolean contains(JsonBaseElement stored) {
        return switch (stored) {
            case JsonCustom<?> custom -> containsCustom(custom);
            case JsonString string -> containsString(string.getValue());
            case JsonNumber number -> Arrays.binarySearch(numbers, number.doubleValue() + 0.0) >= 0;
            case JsonBoolean bool -> bool.getValue() ? holdsTrue : holdsFalse;
            default -> containsOther(stored);
        };
    }

    private boolean containsCustom(JsonCustom<?> stored) {
        for (final var operand : customs) {
            if (operand.getClass().equals(stored.getClass())
                    && FieldPredicateFactory.compareCustom(operand, stored) == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean containsString(String stored) {
        if (exact) {
            return exactStrings.contains(stored);
        }
        final var hash = CaseFolding.foldHash(stored);
        var at = lowerBound(hash);
        while (at < foldHashes.length && foldHashes[at] == hash) {
            if (CaseFolding.equal(foldedStrings[at], stored)) {
                return true;
            }
            at++;
        }
        return false;
    }

    private int lowerBound(int hash) {
        var low = 0;
        var high = foldHashes.length;
        while (low < high) {
            final var mid = (low + high) >>> 1;
            if (foldHashes[mid] < hash) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private boolean containsOther(JsonBaseElement stored) {
        if (stored.isJsonPrimitive()) {
            return false;
        }
        for (final var operand : others) {
            if (FieldPredicateFactory.elementsMatch(operand, stored, exact)) {
                return true;
            }
        }
        return false;
    }
}
