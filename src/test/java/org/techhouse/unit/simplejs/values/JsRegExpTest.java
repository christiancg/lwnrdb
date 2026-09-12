package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.values.JsRegExp;

public class JsRegExpTest {
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

    @Test
    public void test_to_string() {
        assertEquals("/a.c/gi", JsCoercion.toStr(RegexTranslator.compile("a.c", "gi")));
        assertEquals("/x/", JsCoercion.toStr(RegexTranslator.compile("x", "")));
    }

    @Test
    public void test_last_index_stateful() {
        final var re = RegexTranslator.compile("a", "g");
        assertEquals(0, ((org.techhouse.simplejs.values.JsNumber) re.getLastIndex()).getValue());
        re.setLastIndex(3);
        assertEquals(3, ((org.techhouse.simplejs.values.JsNumber) re.getLastIndex()).getValue());
    }

    @Test
    public void test_more_flags() {
        final var re = RegexTranslator.compile("a", "msyd");
        assertTrue(re.isMultiline());
        assertTrue(re.isDotAll());
        assertTrue(re.isSticky());
        assertTrue(re.hasIndices());
    }

    @Test
    public void test_invalid_pattern_throws() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("(", ""));
    }

    @Test
    public void test_invalid_flag_throws() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("a", "q"));
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("a", "gg"));
    }

    @Test
    public void test_group_aliases() {
        assertTrue(new JsRegExp("a", "", RegexTranslator.compile("a", "").getProgram()).getGroupAliases().isEmpty());
        final var duplicated = RegexTranslator.compile("(?<y>a)|(?<y>b)", "");
        assertEquals(2, duplicated.getGroupAliases().get("y").size());
        final var single = RegexTranslator.compile("(?<y>a)", "");
        assertEquals(1, single.getGroupAliases().get("y").size());
    }

}
