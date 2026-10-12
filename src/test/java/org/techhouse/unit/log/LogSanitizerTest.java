package org.techhouse.unit.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.log.LogSanitizer;

public class LogSanitizerTest {

    @Test
    public void cr_lf_tab_are_escaped() {
        assertEquals("a\\rb\\nc\\td", LogSanitizer.neutralise("a\rb\nc\td"));
    }

    @Test
    public void other_control_chars_become_u_escapes() {
        assertEquals("x\\u0000y\\u001bz\\u007f",
                LogSanitizer.neutralise("x" + (char) 0 + "y" + (char) 0x1b + "z" + (char) 0x7f));
    }

    @Test
    public void line_separators_u2028_u2029_u0085_escaped() {
        assertEquals("a\\u2028b\\u2029c\\u0085d",
                LogSanitizer.neutralise("a" + (char) 0x2028 + "b" + (char) 0x2029 + "c" + (char) 0x85 + "d"));
    }

    @Test
    public void clean_string_returned_unchanged() {
        final var clean = "nothing to see here \\ backslash stays";
        assertSame(clean, LogSanitizer.neutralise(clean));
    }

    @Test
    public void null_returns_null() {
        assertNull(LogSanitizer.neutralise(null));
    }

    @Test
    public void unicode_text_untouched() {
        final var text = "héllo wörld ✓ 日本語 " + new String(Character.toChars(0x1F600));
        assertSame(text, LogSanitizer.neutralise(text));
    }

    @Test
    public void only_control_characters() {
        assertEquals("\\n\\n\\r", LogSanitizer.neutralise("\n\n\r"));
    }

    @Test
    public void lone_surrogate_is_left_alone() {
        final var text = "a" + (char) 0xD800 + "b";
        assertSame(text, LogSanitizer.neutralise(text));
    }

    @Test
    public void a_large_message_stays_one_line() {
        final var big = "line\n".repeat(200_000);
        final var neutralised = LogSanitizer.neutralise(big);
        assertEquals(big.length() + 200_000, neutralised.length());
        assertTrue(neutralised.indexOf('\n') < 0);
    }
}
