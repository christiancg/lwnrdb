package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

// ES2025 allows the same capture-group name in different alternatives; java.util.regex does not, so
// repeats are renamed at translation time and resolved back through the alias table.
public class RegexNamedGroupTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // A pattern repeating a group name in another alternative compiles
    @Test
    public void test_duplicate_named_groups_compile() {
        final var regexp = RegexTranslator.compile("(?<y>a)|(?<y>b)", "");
        assertEquals("(?<y>a)|(?<y>b)", regexp.getSource());
        assertEquals(2, regexp.getGroupAliases().get("y").size());
        assertEquals(2, regexp.getGroupAliases().get("y").stream().distinct().count());
    }

    // Whichever alternative participates supplies the group value
    @Test
    public void test_duplicate_named_groups_exec() {
        assertEquals("a", str("/(?<y>a)|(?<y>b)/.exec('a').groups.y"));
        assertEquals("b", str("/(?<y>a)|(?<y>b)/.exec('b').groups.y"));
        assertEquals("a,b", str("[...'ab'.matchAll(/(?<y>a)|(?<y>b)/g)].map(m => m.groups.y).join(',')"));
    }

    // A group that did not participate reports undefined
    @Test
    public void test_non_participating_group_is_undefined() {
        assertEquals("undefined", str("String(/(?<y>a)|(?<z>b)/.exec('a').groups.z)"));
        assertEquals("undefined", str("String(/(?<y>a)|(?<y>b)/.exec('a').groups.q)"));
    }

    // The $<name> replacement token resolves through the aliases
    @Test
    public void test_duplicate_named_groups_in_replacement() {
        assertEquals("b", str("'b'.replace(/(?<y>a)|(?<y>b)/, '$<y>')"));
        assertEquals("a", str("'a'.replace(/(?<y>a)|(?<y>b)/, '$<y>')"));
        assertEquals("", str("'a'.replace(/(?<y>a)|(?<z>b)/, '$<z>')"));
    }

    // The d flag reports the indices of the participating alias
    @Test
    public void test_duplicate_named_groups_with_indices() {
        assertEquals("0,1", str("/(?<y>a)|(?<y>b)/d.exec('b').indices.groups.y.join(',')"));
        assertEquals("undefined", str("String(/(?<y>a)|(?<z>b)/d.exec('a').indices.groups.z)"));
    }

    // A nested duplicate name is renamed like any other
    @Test
    public void test_nested_duplicate_name() {
        assertEquals("b", str("/(?<o>(?<i>a))|(?<i>b)/.exec('b').groups.i"));
        assertEquals("a", str("/(?<o>(?<i>a))|(?<i>b)/.exec('a').groups.i"));
    }

    // A single named group is unchanged, and lookbehind is not treated as a group declaration
    @Test
    public void test_single_group_and_lookbehind_unchanged() {
        assertEquals("ab", str("/(?<x>ab)/.exec('ab').groups.x"));
        assertEquals(List.of("x"), List.copyOf(RegexTranslator.compile("(?<x>ab)", "").getGroupAliases().keySet()));
        assertTrue(RegexTranslator.compile("(?<=a)b", "").getGroupAliases().isEmpty());
        assertEquals("b", str("/(?<=a)b/.exec('ab')[0]"));
        assertTrue(bool("/(?<!a)b/.test('cb')"));
    }

    // A backreference to a duplicated name resolves to the first alias
    @Test
    public void test_backreference_resolves_to_first_alias() {
        assertTrue(bool("/(?<q>a)\\k<q>/.test('aa')"));
        assertTrue(bool("/(?<q>a)\\k<q>|(?<q>b)/.test('aa')"));
    }

    // A bracket inside a character class is not mistaken for a group declaration
    @Test
    public void test_group_syntax_inside_character_class_is_literal() {
        assertTrue(bool("/[(?<x>]/.test('<')"));
        assertTrue(RegexTranslator.compile("[(?<x>]", "").getGroupAliases().isEmpty());
    }
}
