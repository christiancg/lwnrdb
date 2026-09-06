package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class SyntaxShapeProgramTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lexWithPositions(source));
    }

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // A parenthesised single parameter is an arrow, not a grouping
    @Test
    public void test_single_parameter_arrow() {
        assertEquals(2, num("const f = (a) => a * 2; f(1)"));
    }

    // A parenthesised parameter list is an arrow
    @Test
    public void test_multi_parameter_arrow() {
        assertEquals(3, num("const f = (a, b) => a + b; f(1, 2)"));
    }

    // An empty parameter list is an arrow
    @Test
    public void test_zero_parameter_arrow() {
        assertEquals(1, num("const f = () => 1; f()"));
    }

    // A default parameter value marks the parens as an arrow head
    @Test
    public void test_default_parameter_arrow() {
        assertEquals(7, num("const f = (a = 7) => a; f()"));
    }

    // An object pattern parameter marks the parens as an arrow head
    @Test
    public void test_object_pattern_parameter_arrow() {
        assertEquals(5, num("const f = ({ a }) => a; f({ a: 5 })"));
    }

    // An array pattern parameter marks the parens as an arrow head
    @Test
    public void test_array_pattern_parameter_arrow() {
        assertEquals(6, num("const f = ([a]) => a; f([6])"));
    }

    // A rest parameter marks the parens as an arrow head
    @Test
    public void test_rest_parameter_arrow() {
        assertEquals(2, num("const f = (...rest) => rest.length; f(1, 2)"));
    }

    // A trailing comma in the parameter list is allowed
    @Test
    public void test_trailing_comma_in_arrow_parameters() {
        assertEquals(4, num("const f = (a,) => a; f(4)"));
    }

    // An async arrow with parenthesised parameters returns a promise
    @Test
    public void test_async_arrow() {
        assertTrue(bool("(async (a) => a + 1)(1) instanceof Promise"));
    }

    // The same parens without an arrow stay a sequence expression
    @Test
    public void test_parens_without_an_arrow_are_a_sequence() {
        assertEquals(2, num("(1, 2)"));
    }

    // A parenthesised expression keeps its value
    @Test
    public void test_parenthesised_expression() {
        assertEquals(3, num("(1 + 2)"));
    }

    // An optional member access short-circuits the rest of the chain
    @Test
    public void test_optional_member_chain() {
        assertEquals("undefined", str("const o = null; String(o?.a.b.c)"));
    }

    // An optional computed access short-circuits too
    @Test
    public void test_optional_computed_access() {
        assertEquals("undefined", str("const o = null; String(o?.['a'])"));
    }

    // An optional call on a nullish callee does not evaluate its arguments
    @Test
    public void test_optional_call_does_not_evaluate_arguments() {
        final var source = """
                let touched = false;
                const f = null;
                f?.((touched = true));
                String(touched)
                """;
        assertEquals("false", str(source));
    }

    // Deleting through an optional chain is allowed
    @Test
    public void test_delete_through_an_optional_chain() {
        assertTrue(bool("const o = { a: 1 }; delete o?.a"));
    }

    // Deleting through a short-circuited optional chain reports true
    @Test
    public void test_delete_through_a_nullish_optional_chain() {
        assertTrue(bool("const o = null; delete o?.a"));
    }

    // Assigning to an optional chain is rejected while parsing
    @Test
    public void test_assignment_to_an_optional_chain_is_rejected() {
        assertThrows(UnexpectedTokenException.class, () -> parse("const o = {}; o?.a = 1"));
    }

    // A nested object pattern destructures through both levels
    @Test
    public void test_nested_object_pattern() {
        assertEquals(1, num("const { a: { b } } = { a: { b: 1 } }; b"));
    }

    // A shorthand pattern property may carry a default
    @Test
    public void test_object_pattern_default() {
        assertEquals(9, num("const { a = 9 } = {}; a"));
    }

    // A computed pattern key reads the property it names
    @Test
    public void test_object_pattern_computed_key() {
        assertEquals(2, num("const key = 'k'; const { [key]: value } = { k: 2 }; value"));
    }

    // A renamed pattern property may hold a nested pattern with a default
    @Test
    public void test_object_pattern_nested_with_default() {
        assertEquals(3, num("const { a: [b] = [3] } = {}; b"));
    }

    // A rest property collects the remaining own keys
    @Test
    public void test_object_pattern_rest() {
        assertEquals("b", str("const { a, ...rest } = { a: 1, b: 2 }; Object.keys(rest).join(',')"));
    }

    // new resolves a dotted callee before constructing
    @Test
    public void test_new_with_a_member_callee() {
        assertEquals(4, num("const ns = { C: function (v) { this.v = v; } }; new ns.C(4).v"));
    }

    // new resolves a computed callee before constructing
    @Test
    public void test_new_with_a_computed_callee() {
        assertEquals(5, num("const ns = { C: function (v) { this.v = v; } }; new ns['C'](5).v"));
    }

    // A call on a freshly constructed object binds to that object
    @Test
    public void test_method_call_on_a_new_expression() {
        final var source = """
                function C() { this.v = 6; }
                C.prototype.get = function () { return this.v; };
                new C().get()
                """;
        assertEquals(6, num(source));
    }

    // new over a parenthesised callee expression constructs the returned function
    @Test
    public void test_new_over_a_parenthesised_callee() {
        final var source = """
                function make() { return function (v) { this.v = v; }; }
                new (make())(7).v
                """;
        assertEquals(7, num(source));
    }

    // A tagged template in the new callee position constructs the tag's result
    @Test
    public void test_new_over_a_tagged_template() {
        final var source = """
                function tag() { return function (v) { this.v = 8; }; }
                new tag`x`().v
                """;
        assertEquals(8, num(source));
    }

    // A dynamic import expression parses
    @Test
    public void test_dynamic_import_parses() {
        assertEquals(1, parse("import('args')").getBody().size());
    }

    // A dynamic import with options parses
    @Test
    public void test_dynamic_import_with_options_parses() {
        assertEquals(1, parse("import('args', { with: { type: 'json' } })").getBody().size());
    }

    // import.meta parses as a meta property
    @Test
    public void test_import_meta_parses() {
        assertEquals(1, parse("import.meta").getBody().size());
    }

    // import.meta carries the module url at runtime
    @Test
    public void test_import_meta_url() {
        assertEquals("simplejs:main", str("import.meta.url"));
    }

    // new.target is undefined in an ordinary call
    @Test
    public void test_new_target_in_a_plain_call() {
        assertEquals("undefined", str("function f() { return String(new.target); } f()"));
    }

    // new.target names the constructor in a new expression
    @Test
    public void test_new_target_in_a_construction() {
        assertEquals("true", str("function f() { this.same = new.target === f; } String(new f().same)"));
    }
}
