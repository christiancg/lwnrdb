package org.techhouse.unit.cache.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.heap.DocumentHeapSizer;
import org.techhouse.cache.heap.HeapLayout;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class DocumentHeapSizerShapeTest {
    private static final HeapLayout LAYOUT = new HeapLayout(8, 12, 4);
    private static final int WIDE = 20;

    private static String[] keys(int count) {
        final var keys = new String[count];
        for (var i = 0; i < count; i++) {
            keys[i] = String.valueOf(("sizerKey" + i).toCharArray());
        }
        return keys;
    }

    private static JsonBaseElement[] values(int count) {
        final var values = new JsonBaseElement[count];
        for (var i = 0; i < count; i++) {
            values[i] = i % 2 == 0 ? new JsonNumber(i) : new JsonString("value" + i);
        }
        return values;
    }

    private static JsonObject owned(String[] keys, JsonBaseElement[] values) {
        final var object = new JsonObject(keys.length);
        for (var i = 0; i < keys.length; i++) {
            object.add(keys[i], values[i]);
        }
        return object;
    }

    private static long keyBytes(String[] keys) {
        var total = 0L;
        for (final var key : keys) {
            total += LAYOUT.stringSize(key);
        }
        return total;
    }

    @Test
    public void test_an_unpinned_shared_shape_is_charged_in_full() {
        for (final var count : new int[]{3, WIDE}) {
            final var keys = keys(count);
            final var values = values(count);
            final var shaped = JsonObject.withShape(Objects.requireNonNull(JsonObject.shapeOf(keys, 0, count)), values);

            assertEquals(DocumentHeapSizer.sizeOf(owned(keys, values), LAYOUT),
                    DocumentHeapSizer.sizeOf(shaped, LAYOUT), "members " + count);
        }
    }

    @Test
    public void test_a_pinned_shape_charges_no_key_array() {
        final var keys = keys(3);
        final var values = values(3);
        final var shape = Objects.requireNonNull(JsonObject.shapeOf(keys, 0, 3));
        shape.pin();

        final var expected = DocumentHeapSizer.sizeOf(owned(keys, values), LAYOUT) - LAYOUT.referenceArraySize(3)
                - keyBytes(keys);
        assertEquals(expected, DocumentHeapSizer.sizeOf(JsonObject.withShape(shape, values), LAYOUT));
    }

    @Test
    public void test_a_pinned_wide_shape_charges_no_slots() {
        final var keys = keys(WIDE);
        final var values = values(WIDE);
        final var ownedObject = owned(keys, values);
        final var shape = Objects.requireNonNull(JsonObject.shapeOf(keys, 0, WIDE));
        shape.pin();

        final var expected = DocumentHeapSizer.sizeOf(ownedObject, LAYOUT) - LAYOUT.referenceArraySize(WIDE)
                - LAYOUT.arraySize(Integer.BYTES, ownedObject.indexSlots()) - keyBytes(keys);
        assertEquals(expected, DocumentHeapSizer.sizeOf(JsonObject.withShape(shape, values), LAYOUT));
    }

    @Test
    public void test_a_mutated_pinned_object_is_charged_in_full_again() {
        final var keys = keys(3);
        final var values = values(3);
        final var shape = Objects.requireNonNull(JsonObject.shapeOf(keys, 0, 3));
        shape.pin();
        final var object = JsonObject.withShape(shape, values);

        object.remove(keys[2]);

        final var survivors = new String[]{keys[0], keys[1]};
        final var expected = DocumentHeapSizer.sizeOf(owned(survivors, new JsonBaseElement[]{values[0], values[1]}),
                LAYOUT) - 2 * LAYOUT.referenceArraySize(2) + 2 * LAYOUT.referenceArraySize(3);
        assertEquals(expected, DocumentHeapSizer.sizeOf(object, LAYOUT));
    }
}
