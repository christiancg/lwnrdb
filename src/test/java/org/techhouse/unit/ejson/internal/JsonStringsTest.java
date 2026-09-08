package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.JsonStrings;

// U+0020 is spelled as an escape because it is the boundary of RFC 8259's control-character
// escaping range: everything below it is escaped, the space itself passes through.
@SuppressWarnings("UnnecessaryUnicodeEscape")
public class JsonStringsTest {
    // The two structural characters are escaped
    @Test
    public void test_escapes_quote_and_backslash() {
        assertEquals("he said \\\"hi\\\"", JsonStrings.escape("he said \"hi\""));
        assertEquals("C:\\\\tmp", JsonStrings.escape("C:\\tmp"));
    }

    // The named two-character escapes
    @Test
    public void test_escapes_named_control_characters() {
        assertEquals("\\b", JsonStrings.escape("\b"));
        assertEquals("\\f", JsonStrings.escape("\f"));
        assertEquals("\\n", JsonStrings.escape("\n"));
        assertEquals("\\r", JsonStrings.escape("\r"));
        assertEquals("\\t", JsonStrings.escape("\t"));
        assertEquals("line1\\nline2", JsonStrings.escape("line1\nline2"));
    }

    // Remaining control characters become a four-hex-digit unicode escape
    @Test
    public void test_escapes_other_control_characters_as_unicode() {
        assertEquals("\\u0000", JsonStrings.escape("\u0000"));
        assertEquals("\\u0001", JsonStrings.escape("\u0001"));
        assertEquals("\\u001f", JsonStrings.escape("\u001F"));
        assertEquals("a\\u000bb", JsonStrings.escape("a\u000Bb"));
    }

    // Printable ASCII and non-ASCII pass through untouched
    @Test
    public void test_passes_through_printable_characters() {
        final var plain = "plain text 123";
        assertSame(plain, JsonStrings.escape(plain));
        assertEquals("café 😀", JsonStrings.escape("café 😀"));
        assertEquals("\u0020", JsonStrings.escape("\u0020"));
    }

    // Null and empty input are returned as given
    @Test
    public void test_null_and_empty_input() {
        assertNull(JsonStrings.escape(null));
        assertEquals("", JsonStrings.escape(""));
    }

    // A value that needs escaping only at the end still keeps its prefix
    @Test
    public void test_escape_after_clean_prefix() {
        assertEquals("abc\\ndef", JsonStrings.escape("abc\ndef"));
    }

    // An unpaired surrogate cannot be encoded as UTF-8, so it is escaped rather than emitted raw
    @Test
    public void test_escapes_lone_surrogates() {
        assertEquals("\\ud834", JsonStrings.escape("\uD834"));
        assertEquals("\\udd1e", JsonStrings.escape("\uDD1E"));
        assertEquals("a\\ud834b", JsonStrings.escape("a\uD834b"));
        assertEquals("\\ud834\\ud834", JsonStrings.escape("\uD834\uD834"));
    }

    // A well-formed surrogate pair still passes through untouched
    @Test
    public void test_keeps_surrogate_pairs() {
        final var pair = "𝄞";
        assertSame(pair, JsonStrings.escape(pair));
        assertEquals("a𝄞b", JsonStrings.escape("a𝄞b"));
    }
}
