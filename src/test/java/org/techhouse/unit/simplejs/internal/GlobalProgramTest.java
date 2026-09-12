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

    // `eval` exists with the spec-mandated descriptor shape (test262 15.2.3.4-4-1.js, 15.2.3.3-4-4.js)
    // but always throws: there is no runtime code generation, so only its shape is ever observable.
    @Test
    public void test_eval_exists_with_correct_descriptor_but_throws_when_called() {
        assertTrue(bool("typeof eval === 'function'"));
        assertTrue(bool("""
                const d = Object.getOwnPropertyDescriptor(globalThis, 'eval');
                d.writable === true && d.enumerable === false && d.configurable === true && d.value === eval
                """));
        assertTrue(bool("""
                let threw = false;
                try { eval('1 + 1'); } catch (e) { threw = e instanceof TypeError; }
                threw
                """));
    }

    @Test
    public void test_top_level_var_visible_on_global_this() {
        assertEquals(1, num("var x = 1; globalThis.x"));
    }

    @Test
    public void test_function_declaration_visible_on_global_this() {
        assertEquals(3, num("function add(a, b){ return a + b; } globalThis.add(1, 2)"));
    }

    @Test
    public void test_write_global_this_creates_global() {
        assertEquals(2, num("globalThis.y = 2; y"));
    }

    @Test
    public void test_global_this_reflects_reassignment() {
        assertEquals(5, num("var z = 1; z = 5; globalThis.z"));
    }

    @Test
    public void test_global_this_builtins_and_self_reference() {
        assertTrue(bool("globalThis.Math === Math"));
        assertTrue(bool("globalThis.globalThis === globalThis"));
    }

    @Test
    public void test_in_operator_on_global_this() {
        assertTrue(bool("var present = 1; 'present' in globalThis"));
        assertFalse(bool("'absentGlobalName' in globalThis"));
    }

    @Test
    public void test_missing_global_property_is_undefined() {
        assertTrue(bool("globalThis.definitelyMissing === undefined"));
    }

    @Test
    public void test_object_keys_lists_user_globals() {
        assertTrue(bool("var userGlobal = 1; Object.keys(globalThis).includes('userGlobal')"));
    }

    @Test
    public void test_object_keys_excludes_builtins() {
        assertFalse(bool("Object.keys(globalThis).includes('Array')"));
        assertFalse(bool("Object.keys(globalThis).includes('globalThis')"));
    }

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

    @Test
    public void test_global_this_assignment_enumerable() {
        assertTrue(bool("globalThis.added = 5; Object.keys(globalThis).includes('added')"));
    }

    @Test
    public void test_object_values_reads_user_globals() {
        assertEquals(42, num("var single = 42; Object.values(globalThis).filter(v => v === 42).length * 42"));
    }

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

    @Test
    public void test_lexical_global_not_enumerated() {
        assertFalse(bool("let lexicalOnly = 1; Object.keys(globalThis).includes('lexicalOnly')"));
    }

    @Test
    public void test_top_level_let_shadows_a_builtin_without_replacing_its_global_property() {
        assertTrue(bool("let Array; Array === undefined"));
        assertEquals("function", str("let Array; typeof globalThis.Array"));
        assertTrue(bool("let Array; globalThis.Array.isArray([1, 2, 3])"));
    }

    @Test
    public void test_global_property_descriptor_unaffected_by_a_lexical_shadow() {
        final var source = """
                let Array;
                let d = Object.getOwnPropertyDescriptor(globalThis, 'Array');
                d.configurable + ',' + d.enumerable + ',' + d.writable
                """;
        assertEquals("true,false,true", str(source));
    }

    @Test
    public void test_global_this_inherits_object_prototype_methods() {
        assertTrue(bool("var topLevelVar = 1; this.hasOwnProperty('topLevelVar')"));
        assertEquals("function", str("typeof globalThis.hasOwnProperty"));
    }

    @Test
    public void test_lexical_top_level_bindings_are_not_own_properties_of_global_this() {
        assertFalse(bool("let topLevelLet = 1; this.hasOwnProperty('topLevelLet')"));
        assertFalse(bool("const topLevelConst = 1; this.hasOwnProperty('topLevelConst')"));
        assertFalse(bool("class TopLevelClass {} this.hasOwnProperty('TopLevelClass')"));
        assertTrue(bool("var topLevelVar2 = 1; this.hasOwnProperty('topLevelVar2')"));
    }

    private static String str(String source) {
        return ((org.techhouse.simplejs.values.JsString) Interpreter.run(source)).getValue();
    }
}
