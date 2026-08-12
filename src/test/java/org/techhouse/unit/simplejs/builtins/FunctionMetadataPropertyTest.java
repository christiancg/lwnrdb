package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

// A builtin function's `name`/`length` are non-writable, non-enumerable, configurable: a plain
// write is rejected, but delete succeeds and a later plain assignment then creates a normal
// property.
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
    public void test_delete_removes_own_property_then_plain_assignment_succeeds() {
        assertTrue(bool("delete Array.name"));
        assertFalse(bool("delete Array.name; Object.prototype.hasOwnProperty.call(Array, 'name')"));
        assertEquals("reassigned", str("delete Array.name; Array.name = 'reassigned'; Array.name"));
    }
}
