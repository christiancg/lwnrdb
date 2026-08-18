package org.techhouse.unit.simplejs.internal;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsNumber;

import static org.junit.jupiter.api.Assertions.*;

public class EnvironmentTest {
    // A var declaration can be assigned and read back
    @Test
    public void test_var_declare_assign_get() {
        final var env = Environment.global();
        env.declareVar("x");
        env.assign("x", new JsNumber(7));
        assertEquals(7, ((JsNumber) env.get("x")).getValue());
    }

    // A lexical binding is readable only after initialization
    @Test
    public void test_lexical_initialize_get() {
        final var env = Environment.global();
        env.declareLexical("y", "let");
        env.initialize("y", new JsNumber(3));
        assertEquals(3, ((JsNumber) env.get("y")).getValue());
    }

    // Reading a lexical binding in its temporal dead zone throws ReferenceError
    @Test
    public void test_temporal_dead_zone() {
        final var env = Environment.global();
        env.declareLexical("z", "const");
        assertThrows(ReferenceErrorException.class, () -> env.get("z"));
    }

    // Reassigning a const after initialization throws TypeError
    @Test
    public void test_const_reassignment_throws() {
        final var env = Environment.global();
        env.declareLexical("c", "const");
        env.initialize("c", new JsNumber(1));
        assertThrows(TypeErrorException.class, () -> env.assign("c", new JsNumber(2)));
    }

    // Reading an undeclared name throws ReferenceError
    @Test
    public void test_undeclared_get_throws() {
        final var env = Environment.global();
        assertThrows(ReferenceErrorException.class, () -> env.get("nope"));
    }

    // Assigning to an undeclared name throws ReferenceError
    @Test
    public void test_undeclared_assign_throws() {
        final var env = Environment.global();
        assertThrows(ReferenceErrorException.class, () -> env.assign("nope", new JsNumber(1)));
    }

    // A child block scope shadows a parent binding of the same name
    @Test
    public void test_child_scope_shadowing() {
        final var env = Environment.global();
        env.declareLexical("x", "let");
        env.initialize("x", new JsNumber(1));
        final var child = env.child();
        child.declareLexical("x", "let");
        child.initialize("x", new JsNumber(2));
        assertEquals(2, ((JsNumber) child.get("x")).getValue());
        assertEquals(1, ((JsNumber) env.get("x")).getValue());
    }

    // var declared from a block scope hoists to the enclosing function scope
    @Test
    public void test_var_hoists_to_function_scope() {
        final var env = Environment.global();
        final var child = env.child();
        child.declareVar("h");
        child.assign("h", new JsNumber(5));
        assertEquals(5, ((JsNumber) env.get("h")).getValue());
    }

    // A home class defined on a parent scope resolves from a descendant scope
    @Test
    public void test_home_class_resolves_up_chain() {
        final var env = Environment.global();
        final var home = new JsNumber(1);
        env.defineHomeClass(home);
        assertSame(home, env.child().child().resolveHomeClass());
    }

    // Resolving a home class with none defined returns null
    @Test
    public void test_home_class_absent_returns_null() {
        assertNull(Environment.global().resolveHomeClass());
    }

    // GlobalDeclarationInstantiation: a top-level lexical (let/const/class) declaration is not a
    // property of the global object, so it must not replace - or be visible through - a same-named
    // builtin/var binding's own Global Object Record entry, even though a bare identifier lookup
    // (tryGet/get/assign) should still see the lexical shadow.
    @Test
    public void test_global_lexical_declaration_does_not_replace_the_global_property() {
        final var env = Environment.global();
        final var builtin = new JsNumber(1);
        env.declareBuiltin("Array", builtin);
        env.declareLexical("Array", "let");
        env.initialize("Array", org.techhouse.simplejs.values.JsUndefined.getInstance());

        // The bare identifier now resolves to the lexical shadow.
        assertSame(org.techhouse.simplejs.values.JsUndefined.getInstance(), env.tryGet("Array"));
        // But the global object's own property is untouched.
        assertSame(builtin, env.tryGetGlobalProperty("Array"));
        assertTrue(env.hasGlobalProperty("Array"));
    }

    // A block/function-scoped lexical declaration is unaffected by the global-only split: it still
    // lives in the ordinary bindings map for that scope (only the root environment separates the
    // two records).
    @Test
    public void test_non_global_lexical_declaration_is_unaffected() {
        final var child = Environment.global().child();
        child.declareLexical("y", "let");
        child.initialize("y", new JsNumber(5));
        assertEquals(5, ((JsNumber) child.get("y")).getValue());
    }
}
