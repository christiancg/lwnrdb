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
}
