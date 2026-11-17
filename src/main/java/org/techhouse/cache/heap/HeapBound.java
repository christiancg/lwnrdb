package org.techhouse.cache.heap;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.KeyTable;

public final class HeapBound {
    private static final int UTF16_BYTES_PER_CHAR = 2;

    private final HeapLayout layout;
    private final long objectBytes;
    private final long stringElementBytes;
    private final long stringBytes;
    private final long numberBytes;

    public HeapBound(HeapLayout layout) {
        this.layout = layout;
        this.objectBytes = layout.shallowSize(JsonObject.class);
        this.stringElementBytes = layout.shallowSize(JsonString.class);
        this.stringBytes = layout.shallowSize(String.class);
        this.numberBytes = layout.shallowSize(JsonNumber.class);
    }

    public long atMost(JsonBaseElement element) {
        if (element == null || element instanceof JsonBoolean || element.getClass() == JsonNull.class) {
            return 0L;
        }
        final var type = element.getClass();
        if (type == JsonString.class) {
            return stringElementBytes + stringAtMost(((JsonString) element).getValue());
        }
        if (type == JsonNumber.class) {
            return numberBytes;
        }
        if (type == JsonObject.class) {
            return objectAtMost((JsonObject) element);
        }
        return DocumentHeapSizer.sizeAtMost(element, layout);
    }

    private long objectAtMost(JsonObject object) {
        final var capacity = object.capacity();
        final var sharedKeys = object.shape() != null && object.shape().isPinned();
        var total = objectBytes;
        if (capacity > 0) {
            total += (sharedKeys ? 1 : 2) * layout.referenceArraySize(capacity);
        }
        if (object.indexSlots() > 0 && !sharedKeys) {
            total += layout.arraySize(Integer.BYTES, object.indexSlots());
        }
        for (var i = 0; i < object.size(); i++) {
            if (!sharedKeys && !KeyTable.isPinned(object.keyAt(i))) {
                total += stringAtMost(object.keyAt(i));
            }
            total += atMost(object.valueAt(i));
        }
        return total;
    }

    private long stringAtMost(String value) {
        return value == null ? 0L : stringBytes + layout.arraySize(UTF16_BYTES_PER_CHAR, value.length());
    }
}
