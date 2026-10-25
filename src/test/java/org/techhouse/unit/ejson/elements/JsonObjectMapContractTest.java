package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class JsonObjectMapContractTest {
    private static final int OPERATIONS = 400;

    private static List<Map.Entry<String, JsonBaseElement>> entriesOf(JsonObject object) {
        return new ArrayList<>(object.entrySet());
    }

    private static void assertMatchesReference(LinkedHashMap<String, JsonBaseElement> reference, JsonObject object,
            String context) {
        assertEquals(reference.size(), object.size(), context);
        assertEquals(new ArrayList<>(reference.entrySet()), entriesOf(object), context);
        assertEquals(reference.hashCode(), object.hashCode(), context);
        assertEquals(reference.entrySet().hashCode(), object.entrySet().hashCode(), context);
        var index = 0;
        for (final var entry : reference.entrySet()) {
            assertSame(entry.getKey(), object.keyAt(index), context);
            assertSame(entry.getValue(), object.valueAt(index), context);
            assertEquals(index, object.indexOf(entry.getKey()), context);
            assertTrue(object.has(entry.getKey()), context);
            assertSame(entry.getValue(), object.get(entry.getKey()), context);
            index++;
        }
    }

    @Test
    public void randomOperationsMatchALinkedHashMapReference() {
        for (final var seed : new long[]{1L, 7L, 20261014L}) {
            final var random = new Random(seed);
            final var reference = new LinkedHashMap<String, JsonBaseElement>();
            final var object = new JsonObject();
            for (var operation = 0; operation < OPERATIONS; operation++) {
                final var key = "k" + random.nextInt(random.nextBoolean() ? 8 : 40);
                if (random.nextInt(5) == 0) {
                    reference.remove(key);
                    object.remove(key);
                } else {
                    final var value = new JsonNumber(random.nextInt(4));
                    reference.put(key, value);
                    object.add(key, value);
                }
                assertMatchesReference(reference, object, "seed " + seed + " op " + operation);
                assertNull(object.get("absent"));
                assertEquals(-1, object.indexOf("absent"));
            }
        }
    }

    @Test
    public void randomOperationsOnASharedShapeMatchALinkedHashMapAndLeaveTheSiblingAlone() {
        for (final var members : new int[]{3, 20}) {
            final var keys = new String[members];
            final var reference = new LinkedHashMap<String, JsonBaseElement>();
            final var siblingReference = new LinkedHashMap<String, JsonBaseElement>();
            final var values = new JsonBaseElement[members];
            final var siblingValues = new JsonBaseElement[members];
            for (var i = 0; i < members; i++) {
                keys[i] = "k" + i;
                values[i] = new JsonNumber(i);
                siblingValues[i] = new JsonNumber(100 + i);
                reference.put(keys[i], values[i]);
                siblingReference.put(keys[i], siblingValues[i]);
            }
            final var shape = Objects.requireNonNull(JsonObject.shapeOf(keys, 0, members));
            final var object = JsonObject.withShape(shape, values);
            final var sibling = JsonObject.withShape(shape, siblingValues);
            final var random = new Random(members);
            for (var operation = 0; operation < OPERATIONS; operation++) {
                final var key = "k" + random.nextInt(members + 10);
                if (random.nextInt(4) == 0) {
                    reference.remove(key);
                    object.remove(key);
                } else {
                    final var value = new JsonNumber(random.nextInt(4));
                    reference.put(key, value);
                    object.add(key, value);
                }
                final var context = members + " members, op " + operation;
                assertMatchesReference(reference, object, context);
                assertMatchesReference(siblingReference, sibling, "sibling, " + context);
            }
        }
    }

    @Test
    public void aDuplicateKeyOverwritesInItsFirstPosition() {
        final var object = new JsonObject();
        object.addProperty("a", 1);
        object.addProperty("b", 2);
        object.addProperty("a", 3);

        assertEquals(List.of("a", "b"), List.of(object.keyAt(0), object.keyAt(1)));
        assertEquals(new JsonNumber(3), object.get("a"));
    }

    @Test
    public void equalityAndHashIgnoreMemberOrderButNotValues() {
        final var first = new JsonObject();
        final var second = new JsonObject();
        for (var i = 0; i < 20; i++) {
            first.addProperty("m" + i, i);
            second.addProperty("m" + (19 - i), 19 - i);
        }

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        second.addProperty("m3", 99);
        assertNotEquals(first, second);
        assertNotEquals(new Object(), first);
    }

    @Test
    public void entriesKeepTheMapEntryContract() {
        final var object = new JsonObject();
        object.addProperty("key", "value");
        final var reference = Map.entry("key", (JsonBaseElement) new JsonString("value"));

        final var entry = object.entrySet().iterator().next();

        assertEquals(reference, entry);
        assertEquals(reference.hashCode(), entry.hashCode());
        assertThrows(UnsupportedOperationException.class, () -> entry.setValue(JsonNull.INSTANCE));
    }

    @Test
    public void nullsAreNormalisedAndMissingIndexesRefused() {
        final var object = new JsonObject();
        object.add("a", (JsonBaseElement) null);
        object.add("b", (String) null);
        object.addProperty("c", (Number) null);

        assertSame(JsonNull.INSTANCE, object.get("a"));
        assertSame(JsonNull.INSTANCE, object.get("b"));
        assertSame(JsonNull.INSTANCE, object.get("c"));
        assertThrows(IndexOutOfBoundsException.class, () -> object.keyAt(3));
        assertThrows(NullPointerException.class, () -> object.add(null, JsonNull.INSTANCE));
        assertFalse(object.has(null));
    }

    @Test
    public void copiesKeepOrderAndShareOrCopyValuesAsAsked() {
        final var nested = new JsonObject();
        nested.addProperty("inner", 1);
        final var object = new JsonObject();
        object.add("_id", new JsonString("x"));
        object.add("nested", nested);
        object.addProperty("plain", "p");
        for (var i = 0; i < 20; i++) {
            object.addProperty("extra" + i, i);
        }

        final var withoutId = object.copyWithout("_id");
        final var forWrites = object.copyForWrites(Set.of("nested"));
        final var deep = object.deepCopy();

        assertEquals("nested", withoutId.keyAt(0));
        assertFalse(withoutId.has("_id"));
        assertEquals(object.size() - 1, withoutId.size());
        assertSame(nested, withoutId.get("nested"));
        assertEquals(nested, forWrites.get("nested"));
        assertNotSame(nested, forWrites.get("nested"));
        assertSame(object.get("plain"), forWrites.get("plain"));
        assertEquals(object, deep);
        assertNotSame(nested, deep.get("nested"));
        assertEquals(entriesOf(object).stream().map(Map.Entry::getKey).toList(),
                entriesOf(deep).stream().map(Map.Entry::getKey).toList());
    }

    @Test
    public void removingBelowTheIndexThresholdStillFindsEveryMember() {
        final var object = new JsonObject();
        for (var i = 0; i < 18; i++) {
            object.addProperty("r" + i, i);
        }
        assertTrue(object.indexSlots() > 0);
        object.remove("r0");
        object.remove("r5");

        assertEquals(0, object.indexSlots());
        for (var i = 1; i < 18; i++) {
            assertEquals(i != 5, object.has("r" + i), "r" + i);
        }
        object.remove("missing");
        assertEquals(16, object.size());
    }
}
