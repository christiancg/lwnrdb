package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;

public class GlobalProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // A top-level var is visible as a property of globalThis
    @Test
    public void test_top_level_var_visible_on_global_this() {
        assertEquals(1, num("var x = 1; globalThis.x"));
    }

    // A top-level function declaration is visible on globalThis
    @Test
    public void test_function_declaration_visible_on_global_this() {
        assertEquals(3, num("function add(a, b){ return a + b; } globalThis.add(1, 2)"));
    }

    // Writing globalThis.y creates a global binding readable as a bare identifier
    @Test
    public void test_write_global_this_creates_global() {
        assertEquals(2, num("globalThis.y = 2; y"));
    }

    // A later assignment to a global is reflected when read back through globalThis
    @Test
    public void test_global_this_reflects_reassignment() {
        assertEquals(5, num("var z = 1; z = 5; globalThis.z"));
    }

    // globalThis reflects the installed builtins and is self-referential
    @Test
    public void test_global_this_builtins_and_self_reference() {
        assertTrue(bool("globalThis.Math === Math"));
        assertTrue(bool("globalThis.globalThis === globalThis"));
    }

    // The in operator consults the global environment for globalThis
    @Test
    public void test_in_operator_on_global_this() {
        assertTrue(bool("var present = 1; 'present' in globalThis"));
        assertFalse(bool("'absentGlobalName' in globalThis"));
    }

    // A missing global property reads as undefined rather than throwing
    @Test
    public void test_missing_global_property_is_undefined() {
        assertTrue(bool("globalThis.definitelyMissing === undefined"));
    }

    // Object.keys(globalThis) lists user-declared globals
    @Test
    public void test_object_keys_lists_user_globals() {
        assertTrue(bool("var userGlobal = 1; Object.keys(globalThis).includes('userGlobal')"));
    }

    // Object.keys(globalThis) does not enumerate builtins
    @Test
    public void test_object_keys_excludes_builtins() {
        assertFalse(bool("Object.keys(globalThis).includes('Array')"));
        assertFalse(bool("Object.keys(globalThis).includes('globalThis')"));
    }

    // for-in over globalThis iterates user-declared globals
    @Test
    public void test_for_in_iterates_user_globals() {
        final var source = """
                var picked = 7;
                let found = false;
                for (const k in globalThis) { if (k === 'picked') found = true; }
                found
                """;
        assertTrue(bool(source));
    }

    // A property added through globalThis is enumerable
    @Test
    public void test_global_this_assignment_enumerable() {
        assertTrue(bool("globalThis.added = 5; Object.keys(globalThis).includes('added')"));
    }

    // Object.values(globalThis) reads the values of user globals
    @Test
    public void test_object_values_reads_user_globals() {
        assertEquals(42, num("var single = 42; Object.values(globalThis).filter(v => v === 42).length * 42"));
    }

    // Object.entries(globalThis) pairs user global names with values
    @Test
    public void test_object_entries_user_globals() {
        final var source = """
                var pairKey = 9;
                let sum = 0;
                for (const [k, v] of Object.entries(globalThis)) { if (k === 'pairKey') sum += v; }
                sum
                """;
        assertEquals(9, num(source));
    }

    // a lexical let is not a property of the global object
    @Test
    public void test_lexical_global_not_enumerated() {
        assertFalse(bool("let lexicalOnly = 1; Object.keys(globalThis).includes('lexicalOnly')"));
    }
}
