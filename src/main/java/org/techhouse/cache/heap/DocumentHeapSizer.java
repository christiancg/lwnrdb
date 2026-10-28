package org.techhouse.cache.heap;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.KeyTable;

public final class DocumentHeapSizer {
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
            case JsonNumber number -> layout.shallowSize(number.getClass());
            case JsonBoolean _ -> 0L;
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
        final var capacity = object.capacity();
        final var sharedKeys = object.shape() != null && object.shape().isPinned();
        var total = layout.shallowSize(JsonObject.class);
        if (capacity > 0) {
            total += (sharedKeys ? 1 : 2) * layout.referenceArraySize(capacity);
        }
        if (object.indexSlots() > 0 && !sharedKeys) {
            total += layout.arraySize(Integer.BYTES, object.indexSlots());
        }
        for (var i = 0; i < object.size(); i++) {
            total += (sharedKeys ? 0L : keySize(object.keyAt(i), layout)) + sizeOf(object.valueAt(i), layout);
        }
        return total;
    }

    private static long keySize(String key, HeapLayout layout) {
        return KeyTable.isPinned(key) ? 0L : layout.stringSize(key);
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
        return layout.shallowSize(custom.getClass()) + stringSize(custom.getValue(), layout)
                + parsedValueSize(custom.getCustomValue(), layout);
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
