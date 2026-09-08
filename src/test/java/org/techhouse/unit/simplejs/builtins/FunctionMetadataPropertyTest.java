package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

// A builtin function's `name`/`length` are non-writable, non-enumerable, configurable: a plain
// write is rejected, and delete succeeds - but a later plain assignment is *also* rejected, because
// Function.prototype's own "name"/"length" (non-writable data properties) are then found on the
// prototype chain and block the write exactly like Node's strict-mode semantics do (this engine is
// always-strict), rather than silently creating a normal own property the way the old, buggy
// "hard return undefined for a deleted metadata key" behavior made it look like it should.
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
        // Function.prototype's own "name" (="") is now what Array.name reads as, and it is
        // non-writable, so a later plain assignment is rejected rather than creating a new own
        // property - matching Node's strict-mode behavior for the same script.
        assertEquals("", str("delete Array.name; Array.name"));
        assertTrue(bool("delete Array.name; var threw = false; "
                + "try { Array.name = 'reassigned'; } catch (e) { threw = e instanceof TypeError; } threw"));
        assertEquals("", str("delete Array.name; try { Array.name = 'reassigned'; } catch (e) {} Array.name"));
        assertFalse(bool("delete Array.name; try { Array.name = 'reassigned'; } catch (e) {} "
                + "Object.prototype.hasOwnProperty.call(Array, 'name')"));
    }
}
