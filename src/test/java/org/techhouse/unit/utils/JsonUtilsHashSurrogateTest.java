package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.JsonUtils;

public class JsonUtilsHashSurrogateTest {

    private static JsonArray arrayOf(String value) {
        final var array = new JsonArray();
        array.add(new JsonString(value));
        return array;
    }

    private static JsonObject keyed(String key) {
        final var object = new JsonObject();
        object.add(key, new JsonNumber(1));
        return object;
    }

    private static Set<String> hashesOf(List<JsonBaseElement> elements) {
        return elements.stream().map(JsonUtils::hashElement).collect(Collectors.toSet());
    }

    @Test
    public void test_lone_surrogates_hash_apart_from_question_marks() {
        assertEquals(3, hashesOf(List.of(arrayOf("\ud800"), arrayOf("\ud801"), arrayOf("?"))).size());
        assertEquals(2, hashesOf(List.of(keyed("\udc00"), keyed("?"))).size());
        assertEquals(2, hashesOf(List.of(arrayOf("a\ud800"), arrayOf("a?"))).size());
    }

    @Test
    public void test_lone_surrogates_are_spelled_as_unicode_escapes() {
        assertEquals("[\"\\ud800\"]", JsonUtils.canonicalize(arrayOf("\ud800")));
        assertEquals("{\"\\udc00\":1}", JsonUtils.canonicalize(keyed("\udc00")));
    }

    @Test
    public void test_a_surrogate_pair_keeps_its_canonical_spelling() {
        assertEquals("[\"\ud83d\ude00\"]", JsonUtils.canonicalize(arrayOf("\ud83d\ude00")));
        assertEquals(JsonUtils.sha256("[\"\ud83d\ude00\"]"), JsonUtils.hashElement(arrayOf("\ud83d\ude00")));
    }

    @Test
    public void test_backslash_u_text_does_not_collide_with_an_escaped_surrogate() {
        assertNotEquals(JsonUtils.hashElement(arrayOf("\\ud800")), JsonUtils.hashElement(arrayOf("\ud800")));
    }
}
