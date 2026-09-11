package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class IteratorHelperBuiltinsTest {
    private static double num() {
        return ((JsNumber) Interpreter.run("function* g(){yield 1;yield 2;yield 3;} g().reduce((a, b) => a + b)"))
                .getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // take with a negative count throws a RangeError
    @Test
    public void test_iterator_take_negative_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    function* g() { yield 1; }
                    g().take(-1);
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("RangeError", str(source));
    }

    // a non-function map callback throws a TypeError
    @Test
    public void test_iterator_map_non_function_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    function* g() { yield 1; }
                    g().map(5).next();
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }

    // reduce with no initial value over an empty iterator throws a TypeError
    @Test
    public void test_iterator_reduce_empty_no_initial_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    function* g() {}
                    g().reduce((a, b) => a + b);
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }

    // reduce with no initial value uses the first element as the seed
    @Test
    public void test_iterator_reduce_no_initial_seed() {
        assertEquals(6, num());
    }

    // every returns true when all elements pass and find returns undefined when none match
    @Test
    public void test_iterator_every_true_and_find_missing() {
        assertEquals("true", str("function* g(){yield 1;yield 2;} String(g().every(x => x > 0))"));
        assertEquals("undefined", str("function* g(){yield 1;yield 2;} String(g().find(x => x > 5))"));
    }

    // take of more than is available yields the whole source, drop of more empties it
    @Test
    public void test_iterator_take_and_drop_beyond_length() {
        assertEquals("1,2", str("function* g(){yield 1;yield 2;} g().take(10).toArray().join(',')"));
        assertEquals("", str("function* g(){yield 1;yield 2;} g().drop(10).toArray().join(',')"));
    }

    // flatMap over an empty mapped iterable skips to the next source value
    @Test
    public void test_iterator_flat_map_empty_inner() {
        assertEquals("1,3", str(
                "function* g(){yield 1;yield 2;yield 3;} g().flatMap(x => x === 2 ? [] : [x]).toArray().join(',')"));
    }

    // take/drop coerce and range-check their limit before touching the iterator, then close it
    @Test
    public void test_take_limit_validated_before_iteration() {
        final var source = """
                let closed = 'no';
                const closable = {
                    __proto__: Iterator.prototype,
                    get next() { throw new Error('next should not be read'); },
                    return() { closed = 'yes'; return {}; }
                };
                let thrown = 'none';
                try { closable.take(NaN); } catch (e) { thrown = e.name; }
                const coerced = [0, 1, 2].values().take({ valueOf() { return 2; } }).toArray().join('');
                thrown + ':' + closed + ':' + coerced
                """;
        assertEquals("RangeError:yes:01", str(source));
    }
}
