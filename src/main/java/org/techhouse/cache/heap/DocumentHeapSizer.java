package org.techhouse.cache.heap;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public final class DocumentHeapSizer {
    private static final Class<?> LINKED_HASH_MAP_ENTRY = HeapLayout.jdkClass("java.util.LinkedHashMap$Entry");
    private static final long SMALLEST_SHARED_BOX = -128L;
    private static final long LARGEST_SHARED_BOX = 127L;

    private DocumentHeapSizer() {
    }

    public static long sizeOf(JsonBaseElement element, HeapLayout layout) {
        return switch (element) {
            case null -> 0L;
            case JsonNull _ -> 0L;
            case JsonObject object -> objectSize(object, layout);
            case JsonArray array -> arraySize(array, layout);
            case JsonCustom<?> custom -> customSize(custom, layout);
            case JsonString string -> layout.shallowSize(string.getClass()) + stringSize(string.getValue(), layout);
            case JsonNumber number -> layout.shallowSize(number.getClass()) + boxSize(number.getValue(), layout);
            case JsonBoolean bool -> layout.shallowSize(bool.getClass());
            default -> layout.shallowSize(element.getClass());
        };
    }

    public static long indexedValueSize(Object value, HeapLayout layout) {
        return switch (value) {
            case null -> 0L;
            case JsonCustom<?> custom -> customSize(custom, layout);
            case String string -> layout.stringSize(string);
            case Boolean _ -> 0L;
            case Number number -> boxSize(number, layout);
            default -> parsedValueSize(value, layout);
        };
    }

    private static long objectSize(JsonObject object, HeapLayout layout) {
        var total = layout.shallowSize(JsonObject.class) + layout.shallowSize(LinkedHashMap.class)
                + layout.hashTableSize(object.size());
        final var entryBytes = layout.shallowSize(LINKED_HASH_MAP_ENTRY);
        for (final var member : object.entrySet()) {
            total += entryBytes + layout.stringSize(member.getKey()) + sizeOf(member.getValue(), layout);
        }
        return total;
    }

    private static long arraySize(JsonArray array, HeapLayout layout) {
        var total = layout.shallowSize(JsonArray.class) + layout.shallowSize(ArrayList.class)
                + layout.arrayListArraySize(array.size());
        for (final var element : array) {
            total += sizeOf(element, layout);
        }
        return total;
    }

    private static long customSize(JsonCustom<?> custom, HeapLayout layout) {
        final var derived = switch (custom) {
            case JsonGeo geo -> stringSize(geo.cachedGeoHash(), layout);
            case JsonVector vector -> stringSize(vector.cachedSimHash(), layout);
            default -> 0L;
        };
        return layout.shallowSize(custom.getClass()) + stringSize(custom.getValue(), layout)
                + parsedValueSize(custom.getCustomValue(), layout) + derived;
    }

    private static long parsedValueSize(Object value, HeapLayout layout) {
        return switch (value) {
            case null -> 0L;
            case double[] components -> layout.arraySize(Double.BYTES, components.length);
            case LocalDateTime _ -> layout.shallowSize(LocalDateTime.class) + layout.shallowSize(LocalDate.class)
                    + layout.shallowSize(LocalTime.class);
            default -> layout.shallowSize(value.getClass());
        };
    }

    private static long boxSize(Number value, HeapLayout layout) {
        if (value == null) {
            return 0L;
        }
        if (isSharedBox(value)) {
            return 0L;
        }
        return layout.shallowSize(value.getClass());
    }

    private static boolean isSharedBox(Number value) {
        final var cachedByValueOf = value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte;
        return cachedByValueOf && value.longValue() >= SMALLEST_SHARED_BOX && value.longValue() <= LARGEST_SHARED_BOX;
    }

    private static long stringSize(String value, HeapLayout layout) {
        return value == null ? 0L : layout.stringSize(value);
    }
}
