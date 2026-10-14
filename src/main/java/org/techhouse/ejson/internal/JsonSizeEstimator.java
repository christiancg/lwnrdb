package org.techhouse.ejson.internal;

import java.util.List;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public final class JsonSizeEstimator {
    private static final int NULL_CHARS = 4;
    private static final int TRUE_CHARS = 4;
    private static final int FALSE_CHARS = 5;
    private static final int FRACTION_CHARS = 18;
    private static final double TWO_POW_53 = 9007199254740992d;

    private JsonSizeEstimator() {
    }

    public static long estimate(List<? extends JsonBaseElement> elements) {
        if (elements == null) {
            return NULL_CHARS;
        }
        long total = 2;
        for (final var element : elements) {
            total += estimate(element) + 1;
        }
        return total;
    }

    public static long estimate(JsonBaseElement element) {
        return switch (element) {
            case JsonObject object -> estimateObject(object);
            case JsonArray array -> estimateArray(array);
            case JsonString string -> lengthOf(string.getValue()) + 2;
            case JsonNumber number -> estimateNumber(number);
            case JsonBoolean bool -> Boolean.TRUE.equals(bool.getValue()) ? TRUE_CHARS : FALSE_CHARS;
            case null, default -> NULL_CHARS;
        };
    }

    private static long estimateObject(JsonObject object) {
        long total = 2;
        for (var i = 0; i < object.size(); i++) {
            total += lengthOf(object.keyAt(i)) + 4 + estimate(object.valueAt(i));
        }
        return total;
    }

    private static long estimateArray(JsonArray array) {
        long total = 2;
        for (var i = 0; i < array.size(); i++) {
            total += estimate(array.get(i)) + 1;
        }
        return total;
    }

    private static int lengthOf(String value) {
        return value == null ? NULL_CHARS : value.length();
    }

    private static long estimateNumber(JsonNumber number) {
        final var asDouble = number.doubleValue();
        final var magnitude = Math.abs(asDouble);
        if (magnitude < TWO_POW_53 && magnitude == Math.floor(magnitude)) {
            return digitsOf((long) magnitude) + (asDouble < 0 ? 1 : 0);
        }
        return FRACTION_CHARS;
    }

    private static int digitsOf(long magnitude) {
        var digits = 1;
        var rest = magnitude / 10;
        while (rest > 0) {
            digits++;
            rest /= 10;
        }
        return digits;
    }
}
