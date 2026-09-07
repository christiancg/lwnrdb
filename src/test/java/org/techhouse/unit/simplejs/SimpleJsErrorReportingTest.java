package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

/**
 * The single place a failure becomes a reportable name + message. Every engine exception has to arrive as a
 * name a caller can switch on: an unnamed one would reach the wire as an internal error.
 */
public class SimpleJsErrorReportingTest {
    private static final SimpleJs simpleJs = new SimpleJs();

    private static ScriptResult run(String source) {
        return simpleJs.run(source, SimpleHostBindings.empty());
    }

    private static String nameOf(String source) {
        final var result = run(source);
        assertTrue(result.isError(), source);
        return result.getErrorName();
    }

    @Test
    public void test_a_type_error_from_the_engine_is_reported_as_one() {
        assertEquals("TypeError", nameOf("return null.x;"));
    }

    @Test
    public void test_an_unresolved_identifier_is_a_reference_error() {
        assertEquals("ReferenceError", nameOf("return undefinedVariable;"));
    }

    @Test
    public void test_an_out_of_range_argument_is_a_range_error() {
        assertEquals("RangeError", nameOf("return (1).toFixed(101);"));
    }

    @Test
    public void test_an_early_error_is_a_syntax_error() {
        assertEquals("SyntaxError", nameOf("class A { constructor() { return this.#p; } }"));
    }

    @Test
    public void test_an_unexpected_token_is_a_syntax_error() {
        assertEquals("SyntaxError", nameOf("let ="));
    }

    @Test
    public void test_an_unexpected_character_is_a_syntax_error() {
        assertEquals("SyntaxError", nameOf("return 1 @ 2;"));
    }

    @Test
    public void test_an_unterminated_string_is_a_syntax_error() {
        assertEquals("SyntaxError", nameOf("return 'abc"));
    }

    @Test
    public void test_an_unterminated_template_is_a_syntax_error() {
        assertEquals("SyntaxError", nameOf("return `abc"));
    }

    @Test
    public void test_an_unterminated_comment_is_a_syntax_error() {
        assertEquals("SyntaxError", nameOf("/* unterminated"));
    }

    @Test
    public void test_an_unterminated_regex_is_a_syntax_error() {
        assertEquals("SyntaxError", nameOf("return /abc"));
    }

    // A promise returned at top level is awaited, so one that can never settle is a failure, not a null
    @Test
    public void test_a_result_promise_that_never_settles_is_reported() {
        assertEquals("ScriptPendingResultError", nameOf("return new Promise(() => {});"));
    }

    @Test
    public void test_a_thrown_error_reports_its_own_name_and_message() {
        final var result = run("throw new TypeError('boom');");
        assertEquals("TypeError", result.getErrorName());
        assertEquals("boom", result.getErrorMessage());
    }

    // A thrown value with no `name` anywhere on its chain falls back to its constructor's name
    @Test
    public void test_a_thrown_object_falls_back_to_its_constructor_name() {
        assertEquals("Object", nameOf("throw { message: 'boom' };"));
    }

    @Test
    public void test_a_thrown_class_instance_falls_back_to_the_class_name() {
        assertEquals("MyErr", nameOf("class MyErr { constructor(m) { this.message = m; } } throw new MyErr('boom');"));
    }

    @Test
    public void test_a_thrown_legacy_constructor_instance_falls_back_to_the_function_name() {
        assertEquals("Legacy", nameOf("function Legacy(m) { this.message = m; } throw new Legacy('boom');"));
    }

    @Test
    public void test_a_thrown_object_can_fall_back_to_a_builtin_constructor_name() {
        assertEquals("Map", nameOf("const o = Object.create(null); o.constructor = Map; throw o;"));
    }

    @Test
    public void test_a_thrown_object_with_an_own_name_uses_it() {
        final var result = run("throw { name: 'Weird', message: 'boom' };");
        assertEquals("Weird", result.getErrorName());
        assertEquals("boom", result.getErrorMessage());
    }

    @Test
    public void test_a_thrown_primitive_is_reported_as_a_plain_error() {
        final var result = run("throw 'plain string';");
        assertEquals("Error", result.getErrorName());
        assertEquals("plain string", result.getErrorMessage());
    }

    @Test
    public void test_a_thrown_object_with_no_chain_at_all_still_reports() {
        assertEquals("Error", nameOf("throw Object.create(null);"));
    }
}
