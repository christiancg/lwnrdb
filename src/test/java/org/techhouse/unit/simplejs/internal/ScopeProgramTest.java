package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class ScopeProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // with parameter expressions the body gets its own variable environment, so a closure created in
    // a parameter default never sees the body's `var`
    @Test
    public void test_parameter_and_body_var_environments_are_separate() {
        final var source = """
                var x = 'outside';
                var probeParams, probeBody;
                function f(_ = probeParams = function() { return x; }) {
                  var x = 'inside';
                  probeBody = function() { return x; };
                }
                f();
                probeParams() + '|' + probeBody()
                """;
        assertEquals("outside|inside", str(source));
    }

    // a body `var` that shadows a parameter is initialised from that parameter
    @Test
    public void test_body_var_is_initialised_from_the_parameter() {
        final var source = """
                var probe;
                function f(a = 1) {
                  var a;
                  probe = a;
                }
                f();
                probe
                """;
        assertEquals(1, num(source));
    }

    // a simple parameter list keeps one environment, which is what makes `arguments` mapped
    @Test
    public void test_simple_parameters_keep_one_environment() {
        assertEquals(9, num("function f(a) { arguments[0] = 9; return a; } f(1)"));
        assertEquals(9, num("function f(a) { a = 9; return arguments[0]; } f(1)"));
        assertEquals(1, num("function f(a = 1) { arguments[0] = 9; return a; } f()"));
    }

    // assigning a let before its declaration runs is a ReferenceError, just like reading it
    @Test
    public void test_assigning_a_let_before_initialisation_throws() {
        final var source = """
                var out = 'none';
                function probe() { x = 1; }
                { try { probe(); } catch (e) { out = e.name; } let x; }
                out
                """;
        assertEquals("ReferenceError", str(source));
    }

    // a for-of head's lexical bindings are in TDZ while the source expression runs
    @Test
    public void test_for_of_head_bindings_are_in_tdz() {
        final var source = """
                var out = 'none';
                try { for (let x of [x]) ; } catch (e) { out = e.name; }
                out
                """;
        assertEquals("ReferenceError", str(source));
    }

    // each for-of iteration gets a fresh binding, observed through closures
    @Test
    public void test_for_of_bindings_are_per_iteration() {
        final var source = """
                var fns = [];
                for (let x of [1, 2, 3]) { fns.push(function() { return x; }); }
                fns[0]() + fns[1]() + fns[2]()
                """;
        assertEquals(6, num(source));
    }

    // a classic for's let binding is per-iteration too
    @Test
    public void test_classic_for_bindings_are_per_iteration() {
        final var source = """
                var fns = [];
                for (let i = 0; i < 3; i++) { fns.push(function() { return i; }); }
                fns[0]() + fns[1]() + fns[2]()
                """;
        assertEquals(3, num(source));
    }

    // a switch's case clauses share one lexical scope that does not leak outside
    @Test
    public void test_switch_case_block_is_its_own_scope() {
        final var source = """
                var out = 'none';
                switch (0) { case 0: let inner = 'in'; out = inner; }
                try { inner; out += '|leak'; } catch (e) { out += '|' + e.name; }
                out
                """;
        assertEquals("in|ReferenceError", str(source));
    }

    // the switch discriminant evaluates in the outer scope, before the block environment holding the
    // cases' lexical declarations is created - a closure captured there sees the outer binding, while
    // one captured by a case test (which runs inside the block environment) sees the inner one
    @Test
    public void test_switch_discriminant_evaluates_outside_the_case_block_scope() {
        final var source = """
                let x = 'outside';
                var probeExpr, probeSelector;
                switch (probeExpr = function() { return x; }, null) {
                    case probeSelector = function() { return x; }, null:
                        let x = 'inside';
                }
                probeExpr() + '|' + probeSelector()
                """;
        assertEquals("outside|inside", str(source));
    }

    // for-in walks the prototype chain, own keys first, and a shadowing own name appears once
    @Test
    public void test_for_in_walks_the_prototype_chain() {
        final var source = """
                var parent = { a: 1, b: 2 };
                var child = Object.create(parent);
                child.b = 3;
                child.c = 4;
                var keys = [];
                for (var k in child) { keys.push(k); }
                keys.join(',')
                """;
        assertEquals("b,c,a", str(source));
    }

    // a non-enumerable own property shadows an enumerable inherited one
    @Test
    public void test_for_in_shadowing_hides_an_inherited_key() {
        final var source = """
                var parent = { a: 1 };
                var child = Object.create(parent);
                Object.defineProperty(child, 'a', { value: 2, enumerable: false });
                var keys = [];
                for (var k in child) { keys.push(k); }
                keys.length
                """;
        assertEquals(0, num(source));
    }
}
