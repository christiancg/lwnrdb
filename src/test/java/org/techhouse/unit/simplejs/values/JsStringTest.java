package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.test.TestUtils;

public class JsStringTest {
    private static final String LONG = "a".repeat(JsString.BUFFER_THRESHOLD);

    @Test
    public void test_a_short_result_stays_a_plain_string() {
        final var joined = new JsString("ab").concat("cd");
        assertEquals("abcd", joined.getValue());
        assertFalse(joined.appendsInPlace());
    }

    @Test
    public void test_the_tail_of_a_buffer_appends_in_place() {
        final var first = new JsString(LONG).concat("b");
        assertTrue(first.appendsInPlace());
        final var second = first.concat("c");
        assertTrue(second.appendsInPlace());
        assertFalse(first.appendsInPlace());
        assertEquals(LONG + "bc", second.getValue());
        assertEquals(LONG + "b", first.getValue());
    }

    @Test
    public void test_appending_to_a_prefix_forks_and_leaves_the_others_unchanged() {
        final var prefix = new JsString(LONG).concat("b");
        final var tail = prefix.concat("c");
        final var fork = prefix.concat("d");
        assertEquals(LONG + "bc", tail.getValue());
        assertEquals(LONG + "bd", fork.getValue());
        assertEquals(LONG + "b", prefix.getValue());
        assertTrue(fork.appendsInPlace());
        assertTrue(tail.appendsInPlace());
    }

    @Test
    public void test_length_and_char_reads_do_not_flatten() {
        final var built = new JsString(LONG).concat("xyz");
        assertEquals(LONG.length() + 3, built.length());
        assertEquals('y', built.charAt(LONG.length() + 1));
        assertEquals('a', built.charAt(0));
        assertTrue(built.appendsInPlace());
    }

    @Test
    public void test_flattening_is_cached_and_a_view_behind_the_tail_drops_its_buffer() {
        final var view = new JsString(LONG).concat("b");
        final var tail = view.concat("c");
        final var flat = view.getValue();
        assertSame(flat, view.getValue());
        assertFalse(view.appendsInPlace());
        assertEquals(LONG + "bcd", tail.concat("d").getValue());
        assertEquals('b', view.charAt(LONG.length()));
    }

    @Test
    public void test_the_tail_keeps_its_buffer_after_flattening() {
        final var tail = new JsString(LONG).concat("b");
        assertEquals(LONG + "b", tail.getValue());
        assertTrue(tail.appendsInPlace());
        assertEquals(LONG + "bc", tail.concat("c").getValue());
    }

    @Test
    public void test_an_empty_tail_returns_the_same_string() {
        final var text = new JsString("abc");
        assertSame(text, text.concat(""));
    }

    @Test
    public void test_a_null_text_has_no_length() {
        final var empty = new JsString(null);
        assertEquals(0, empty.length());
        assertNull(empty.getValue());
    }

    @Test
    public void test_a_fork_reserves_exactly_the_combined_length() throws Exception {
        final var prefix = new JsString(LONG).concat("b");
        final var tail = prefix.concat("c");
        final var fork = prefix.concat("de");

        assertEquals(LONG.length() + 3, TestUtils.getPrivateField(fork, "buffer", StringBuilder.class).capacity(),
                "a fork must cost what its characters cost, not twice that");
        final var flat = new JsString(LONG).concat("xy");
        assertEquals(LONG.length() + 2, TestUtils.getPrivateField(flat, "buffer", StringBuilder.class).capacity());
        assertEquals(LONG + "bde", fork.getValue());
        assertEquals(LONG + "bc", tail.getValue());
    }
}
