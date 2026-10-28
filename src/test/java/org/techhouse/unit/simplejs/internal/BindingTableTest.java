package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;

public class BindingTableTest {
    private static double num(Environment env, String name) {
        return ((JsNumber) env.get(name)).getValue();
    }

    @Test
    public void test_a_scope_keeps_every_binding_past_the_small_table() {
        final var scope = Environment.global().child();
        for (var i = 0; i < 20; i++) {
            scope.declareLexical("v" + i, "let");
            scope.initialize("v" + i, new JsNumber(i));
        }
        for (var i = 0; i < 20; i++) {
            assertEquals(i, num(scope, "v" + i));
        }
        scope.assign("v19", new JsNumber(-1));
        assertEquals(-1, num(scope, "v19"));
        assertFalse(scope.isDeclared("v20"));
    }

    @Test
    public void test_the_global_scope_lists_names_in_declaration_order_in_both_modes() {
        for (final var count : List.of(3, 8, 9, 30)) {
            final var global = Environment.global();
            final var expected = new ArrayList<String>();
            for (int i = count; i > 0; i--) {
                global.setGlobal("g" + i, new JsNumber(i));
                expected.add("g" + i);
            }
            assertEquals(expected, global.allGlobalNames());
            assertEquals(expected, global.enumerableGlobalNames());
            assertTrue(global.deleteGlobal("g" + count));
            expected.removeFirst();
            assertEquals(expected, global.allGlobalNames());
        }
    }

    @Test
    public void test_const_and_temporal_dead_zone_hold_in_both_modes() {
        for (final var filler : List.of(0, 12)) {
            final var scope = Environment.global().child();
            for (var i = 0; i < filler; i++) {
                scope.declareLexical("f" + i, "let");
            }
            scope.declareLexical("c", "const");
            scope.declareLexical("l", "let");
            assertThrows(ReferenceErrorException.class, () -> scope.get("c"));
            assertThrows(ReferenceErrorException.class, () -> scope.assign("l", new JsNumber(1)));
            scope.initialize("c", new JsNumber(1));
            assertThrows(TypeErrorException.class, () -> scope.assign("c", new JsNumber(2)));
            assertNull(scope.tryGet("l"));
        }
    }

    @Test
    public void test_a_var_assigned_before_initialisation_is_not_a_dead_zone() {
        final var function = Environment.global().functionChild();
        function.declareParam("p");
        function.assign("p", new JsNumber(4));
        assertEquals(4, num(function, "p"));
        function.declareParamValue("q", new JsNumber(5));
        assertEquals(5, num(function, "q"));
    }

    @Test
    public void test_global_flags_replace_a_binding_without_losing_its_kind() {
        final var global = Environment.global();
        global.declareLexical("lexical", "let");
        global.declareBuiltin("builtin", new JsNumber(1));
        global.setGlobalFlags("builtin", new JsObject.PropertyFlags(false, false, false), null);
        assertThrows(TypeErrorException.class, () -> global.assign("builtin", new JsNumber(2)));
        assertFalse(global.deleteGlobal("builtin"));
        assertEquals(List.of("builtin"), global.allGlobalNames());
        assertTrue(global.deleteGlobal("absent"));
    }
}
