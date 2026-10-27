package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.JsonParser;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.JsonUtils;

public class JsonObjectShapeTest {
    private static final int WIDE = 20;
    private static final int PAST_THE_SHAPE_BOUND = 40;

    private static JsonObject parse(String json) {
        parseOnce(json);
        return parseOnce(json);
    }

    private static JsonObject parseOnce(String json) {
        return JsonParser.parse(json, 64, Integer.MAX_VALUE).asJsonObject();
    }

    private static String wideDocument(int value) {
        final var json = new StringBuilder("{");
        for (var i = 0; i < WIDE; i++) {
            json.append(i == 0 ? "" : ",").append("\"field").append(i).append("\":").append(value + i);
        }
        return json.append('}').toString();
    }

    private static Object keysOf(JsonObject object) throws Exception {
        return TestUtils.getPrivateField(object, "keys", String[].class);
    }

    @Test
    public void test_two_parsed_documents_of_one_shape_share_keys() throws Exception {
        final var first = parse("{\"_id\":\"a\",\"shapeName\":\"x\",\"shapeCount\":1}");
        final var second = parse("{\"_id\":\"b\",\"shapeName\":\"y\",\"shapeCount\":2}");

        assertNotNull(first.shape());
        assertSame(first.shape(), second.shape());
        assertSame(keysOf(first), keysOf(second));
        assertEquals("y", second.get("shapeName").asJsonString().getValue());
    }

    @Test
    public void test_adding_a_key_copies_the_shape_first() throws Exception {
        final var first = parse("{\"_id\":\"a\",\"addName\":\"x\"}");
        final var second = parse("{\"_id\":\"b\",\"addName\":\"y\"}");

        second.addProperty("extra", 5);

        assertNull(second.shape());
        assertNotSame(keysOf(first), keysOf(second));
        assertFalse(first.has("extra"));
        assertEquals(2, first.size());
        assertEquals(3, second.size());
    }

    @Test
    public void test_replacing_a_value_keeps_the_shape() {
        final var first = parse("{\"_id\":\"a\",\"keepName\":\"x\"}");
        final var second = parse("{\"_id\":\"b\",\"keepName\":\"y\"}");

        second.addProperty("keepName", "z");

        assertSame(first.shape(), second.shape());
        assertEquals("x", first.get("keepName").asJsonString().getValue());
        assertEquals("z", second.get("keepName").asJsonString().getValue());
    }

    @Test
    public void test_removing_a_key_copies_the_shape_first() {
        final var first = parse("{\"_id\":\"a\",\"dropName\":\"x\",\"dropCount\":1}");
        final var second = parse("{\"_id\":\"b\",\"dropName\":\"y\",\"dropCount\":2}");

        second.remove("dropName");

        assertNull(second.shape());
        assertTrue(first.has("dropName"));
        assertEquals("_id", first.keyAt(0));
        assertEquals("dropName", first.keyAt(1));
        assertEquals("dropCount", second.keyAt(1));
    }

    @Test
    public void test_growing_past_sixteen_copies_slots() throws Exception {
        final var first = parse(wideDocument(0));
        final var second = parse(wideDocument(100));
        assertSame(first.shape(), second.shape());
        final var shared = first.shape();

        second.addProperty("late", 1);

        assertSame(shared, first.shape());
        assertNull(second.shape());
        assertInstanceOf(int[].class, TestUtils.getPrivateField(second, "memberIndex", Object.class));
        assertEquals(first.indexSlots(), second.indexSlots());
        assertFalse(first.has("late"));
        assertTrue(second.has("late"));
        for (var i = 0; i < WIDE; i++) {
            assertTrue(second.has("field" + i));
        }
    }

    @Test
    public void test_a_wide_shared_shape_finds_every_key() {
        final var first = parse(wideDocument(0));
        final var second = parse(wideDocument(1000));

        for (var i = 0; i < WIDE; i++) {
            assertEquals(i, first.get("field" + i).asJsonNumber().getValue().intValue());
            assertEquals(1000 + i, second.get("field" + i).asJsonNumber().getValue().intValue());
        }
        assertNull(second.get("field" + WIDE));
    }

    @Test
    public void test_a_mutation_never_shows_in_a_sibling() {
        final var first = parse("{\"_id\":\"a\",\"siblingName\":\"x\",\"siblingCount\":1}");
        final var second = parse("{\"_id\":\"b\",\"siblingName\":\"y\",\"siblingCount\":2}");
        final var third = parse("{\"_id\":\"c\",\"siblingName\":\"z\",\"siblingCount\":3}");

        second.remove("siblingCount");
        third.addProperty("siblingExtra", true);

        assertEquals(3, first.size());
        assertEquals(Set.of("_id", "siblingName", "siblingCount"),
                Set.of(first.keyAt(0), first.keyAt(1), first.keyAt(2)));
        assertEquals(1, first.get("siblingCount").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_copies_reuse_the_shape_without_sharing_values() {
        final var original = parse("{\"_id\":\"a\",\"copyName\":{\"inner\":1}}");

        final var deep = original.deepCopy();
        final var forWrites = original.copyForWrites(JsonUtils.writeTreeOf(List.of("copyName.inner")));

        assertSame(original.shape(), deep.shape());
        assertSame(original.shape(), forWrites.shape());
        assertEquals(original, deep);
        assertNotSame(original.get("copyName"), deep.get("copyName"));
        assertNotSame(original.get("copyName"), forWrites.get("copyName"));
        assertSame(original.get("_id"), forWrites.get("_id"));
    }

    @Test
    public void test_duplicate_keys_do_not_share_a_shape() {
        final var duplicated = parse("{\"dupKey\":1,\"dupKey\":2}");

        assertNull(duplicated.shape());
        assertEquals(1, duplicated.size());
        assertEquals(2, duplicated.get("dupKey").asJsonNumber().getValue().intValue());
        assertNull(JsonObject.shapeOf(new String[]{"a", "b", "a"}, 0, 3));
    }

    @Test
    public void test_wide_duplicate_keys_have_no_shape() {
        final var keys = new String[WIDE];
        for (var i = 0; i < WIDE; i++) {
            keys[i] = "wide" + i;
        }
        keys[WIDE - 1] = "wide3";

        assertNull(JsonObject.shapeOf(keys, 0, WIDE));
        assertNotNull(JsonObject.shapeOf(keys, 0, WIDE - 1));
    }

    @Test
    public void test_an_empty_object_has_no_shape() {
        final var empty = parse("{}");

        assertNull(empty.shape());
        assertTrue(empty.isEmpty());
    }

    @Test
    public void test_with_shape_needs_one_value_per_key() {
        final var shape = Objects.requireNonNull(JsonObject.shapeOf(new String[]{"one", "two"}, 0, 2));

        assertThrows(IllegalArgumentException.class,
                () -> JsonObject.withShape(shape, new JsonBaseElement[]{new JsonNumber(1)}));
        final var object = JsonObject.withShape(shape, new JsonBaseElement[]{new JsonNumber(1), new JsonString("two")});
        assertEquals("two", object.get("two").asJsonString().getValue());
    }

    @Test
    public void test_an_object_past_the_shape_bound_is_built_member_by_member() {
        final var json = new StringBuilder("{");
        for (var i = 0; i < PAST_THE_SHAPE_BOUND; i++) {
            json.append(i == 0 ? "" : ",").append("\"many").append(i).append("\":").append(i);
        }
        final var object = parse(json.append('}').toString());

        assertNull(object.shape());
        assertEquals(PAST_THE_SHAPE_BOUND, object.size());
        assertEquals(39, object.get("many39").asJsonNumber().getValue().intValue());
    }
}
