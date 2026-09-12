package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class DestructuringIteratorCloseTest {
    private static final String ITERABLE = """
            let closed = 0;
            let iterable = {};
            iterable[Symbol.iterator] = function() {
              let i = 0;
              return {
                next: function() { i += 1; return { value: i, done: i > 3 }; },
                return: function() { closed += 1; return {}; }
              };
            };
            """;

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_array_pattern_closes_unexhausted_iterator() {
        assertEquals(1, num(ITERABLE + "let [a] = iterable; closed"));
    }

    @Test
    public void test_array_pattern_does_not_close_exhausted_iterator() {
        assertEquals(0, num(ITERABLE + "let [a, b, c, d] = iterable; closed"));
    }

    @Test
    public void test_rest_element_does_not_close() {
        assertEquals(0, num(ITERABLE + "let [a, ...rest] = iterable; closed"));
    }

    @Test
    public void test_empty_pattern_closes_iterator() {
        assertEquals(1, num(ITERABLE + "let [] = iterable; closed"));
    }

    @Test
    public void test_throwing_target_closes_and_keeps_the_original_error() {
        final var source = """
                let log = [];
                let iterable = {};
                iterable[Symbol.iterator] = function() {
                  return {
                    next: function() { log.push('next'); return { value: 1, done: false }; },
                    return: function() { log.push('return'); throw new Error('from-return'); }
                  };
                };
                let obj = {};
                let caught = '';
                try {
                  [ obj[(function() { throw new Error('from-target'); })()] ] = iterable;
                } catch (e) { caught = e.message; }
                log.join('|') + '#' + caught
                """;
        assertEquals("return#from-target", str(source));
    }

    @Test
    public void test_non_object_return_result_is_a_type_error() {
        final var source = """
                let iterable = {};
                iterable[Symbol.iterator] = function() {
                  return {
                    next: function() { return { value: 1, done: false }; },
                    return: function() { return null; }
                  };
                };
                let caught = 'none';
                try { let [a] = iterable; } catch (e) { caught = '' + (e instanceof TypeError); }
                caught
                """;
        assertEquals("true", str(source));
    }

    @Test
    public void test_for_of_body_throw_closes_iterator() {
        final var source = ITERABLE + """
                try { for (const x of iterable) { throw new Error('body'); } } catch (e) { }
                closed
                """;
        assertEquals(1, num(source));
    }

    @Test
    public void test_for_of_break_closes_iterator() {
        assertEquals(1, num(ITERABLE + "for (const x of iterable) { break; } closed"));
    }
}
