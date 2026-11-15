package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonString;

public class JsonStringPlainAsciiTest {
    @Test
    public void test_an_empty_string_is_plain() {
        assertTrue(new JsonString().isPlainAscii());
        assertTrue(new JsonString("").isPlainAscii());
    }

    @Test
    public void test_letters_digits_space_and_del_are_plain() {
        assertTrue(new JsonString("Hello, World 0123456789 ~!@#$%^&*()_+-=[]{}|;':,./<>?`").isPlainAscii());
        assertTrue(new JsonString("del\u007f").isPlainAscii());
    }

    @Test
    public void test_a_quote_or_backslash_is_not_plain() {
        assertFalse(new JsonString("say \"hi\"").isPlainAscii());
        assertFalse(new JsonString("C:\\path").isPlainAscii());
    }

    @Test
    public void test_a_control_character_is_not_plain() {
        for (final var text : List.of("nul\u0000", "line\nbreak", "tab\t", "unit\u001f")) {
            assertFalse(new JsonString(text).isPlainAscii(), text);
        }
    }

    @Test
    public void test_non_ascii_and_surrogates_are_not_plain() {
        for (final var text : List.of("café", "中文", "😀", "lone\ud800", "\u0080")) {
            assertFalse(new JsonString(text).isPlainAscii(), text);
        }
    }

    @Test
    public void test_a_null_value_is_not_plain() {
        assertFalse(new JsonString(null).isPlainAscii());
    }

    @Test
    public void test_the_classification_is_stable_across_calls() {
        final var plain = new JsonString("stable");
        final var escaped = new JsonString("not \"stable\"");

        for (var call = 0; call < 3; call++) {
            assertTrue(plain.isPlainAscii());
            assertFalse(escaped.isPlainAscii());
        }
    }

    @Test
    public void test_classifying_does_not_change_equality_or_hash() {
        final var classified = new JsonString("same");
        final var unclassified = new JsonString("same");

        assertTrue(classified.isPlainAscii());

        assertEquals(unclassified, classified);
        assertEquals(classified, unclassified);
        assertEquals(unclassified.hashCode(), classified.hashCode());
    }
}
