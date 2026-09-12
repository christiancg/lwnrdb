package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

// Deleting a builtin's `name`/`length` uncovers Function.prototype's own non-writable ones, which then
// block a later plain assignment - the always-strict engine matches Node's strict-mode semantics here.
public class FunctionMetadataPropertyTest {
    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_native_function_name_write_is_rejected() {
        assertTrue(bool("var threw = false; "
                + "try { Array.name = 'X'; } catch (e) { threw = e instanceof TypeError; } threw"));
        assertEquals("Array", str("Array.name"));
    }

    @Test
    public void test_native_function_length_write_is_rejected() {
        assertTrue(bool("var threw = false; "
                + "try { Array.length = 5; } catch (e) { threw = e instanceof TypeError; } threw"));
    }

    @Test
    public void test_user_function_name_and_length_write_is_rejected() {
        assertTrue(bool("function f(a, b) {} var threw = false; "
                + "try { f.name = 'x'; } catch (e) { threw = e instanceof TypeError; } threw"));
        assertTrue(bool("function f(a, b) {} var threw = false; "
                + "try { f.length = 5; } catch (e) { threw = e instanceof TypeError; } threw"));
    }

    @Test
    public void test_delete_removes_own_property_then_reads_through_function_prototype() {
        assertTrue(bool("delete Array.name"));
        assertFalse(bool("delete Array.name; Object.prototype.hasOwnProperty.call(Array, 'name')"));
        // Function.prototype's own "name" (="") is what Array.name reads as after the delete, and it is
        // non-writable, so a later plain assignment is rejected rather than creating a new own property.
        assertEquals("", str("delete Array.name; Array.name"));
        assertTrue(bool("delete Array.name; var threw = false; "
                + "try { Array.name = 'reassigned'; } catch (e) { threw = e instanceof TypeError; } threw"));
        assertEquals("", str("delete Array.name; try { Array.name = 'reassigned'; } catch (e) {} Array.name"));
        assertFalse(bool("delete Array.name; try { Array.name = 'reassigned'; } catch (e) {} "
                + "Object.prototype.hasOwnProperty.call(Array, 'name')"));
    }
}
