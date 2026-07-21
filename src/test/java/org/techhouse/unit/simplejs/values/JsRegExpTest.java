package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;

public class JsRegExpTest {
    // A compiled regex exposes its source and parses flag predicates
    @Test
    public void test_source_and_flags() {
        final var re = RegexTranslator.compile("a.c", "gi");
        assertEquals("a.c", re.getSource());
        assertEquals("gi", re.getFlags());
        assertTrue(re.isGlobal());
        assertTrue(re.isIgnoreCase());
        assertFalse(re.isMultiline());
        assertFalse(re.isDotAll());
        assertFalse(re.isSticky());
    }

    // toStr renders /source/flags
    @Test
    public void test_to_string() {
        assertEquals("/a.c/gi", JsCoercion.toStr(RegexTranslator.compile("a.c", "gi")));
        assertEquals("/x/", JsCoercion.toStr(RegexTranslator.compile("x", "")));
    }

    // lastIndex is mutable state used by global matching
    @Test
    public void test_last_index_stateful() {
        final var re = RegexTranslator.compile("a", "g");
        assertEquals(0, re.getLastIndex());
        re.setLastIndex(3);
        assertEquals(3, re.getLastIndex());
    }

    // multiline, dotAll, sticky and indices flags parse
    @Test
    public void test_more_flags() {
        final var re = RegexTranslator.compile("a", "msyd");
        assertTrue(re.isMultiline());
        assertTrue(re.isDotAll());
        assertTrue(re.isSticky());
        assertTrue(re.hasIndices());
    }

    // an invalid pattern throws a SyntaxError
    @Test
    public void test_invalid_pattern_throws() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("(", ""));
    }

    // an unknown or duplicated flag throws a SyntaxError
    @Test
    public void test_invalid_flag_throws() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("a", "q"));
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("a", "gg"));
    }
}
