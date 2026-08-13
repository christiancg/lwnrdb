package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class IteratorBuiltinsTest {
    private static double num() {
        return ((JsNumber) Interpreter.run("function* g(){yield 1;yield 2;yield 3;} g().reduce((a, b) => a + b)"))
                .getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // calling the abstract Iterator constructor throws a TypeError
    @Test
    public void test_iterator_direct_call_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    Iterator();
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }

    // Iterator.from on a non-iterable throws a TypeError
    @Test
    public void test_iterator_from_non_iterable_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    Iterator.from(5).next();
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
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

    // the Iterator prototype's helpers are reachable and iterable
    @Test
    public void test_iterator_prototype_symbol_iterator() {
        assertEquals("1,2", str("function* g(){yield 1;yield 2;} [...g().map(x => x)].join(',')"));
    }

    // new Iterator() still throws when called directly, not through a subclass
    @Test
    public void test_iterator_direct_new_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    new Iterator();
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }

    // a class extending Iterator can be constructed via the super() chain
    @Test
    public void test_iterator_subclass_construction_succeeds() {
        final var source = """
                class SubIterator extends Iterator {}
                let s = new SubIterator();
                (s instanceof SubIterator) + ',' + (s instanceof Iterator)
                """;
        assertEquals("true,true", str(source));
    }

    // the helpers dispatch correctly on a subclass instance whose next() is a prototype method
    @Test
    public void test_iterator_subclass_helpers_dispatch() {
        final var source = """
                class Counter extends Iterator {
                    #i = 0;
                    next() {
                        return this.#i < 3 ? { value: this.#i++, done: false } : { value: undefined, done: true };
                    }
                }
                new Counter().map(x => x * 2).toArray().join(',')
                """;
        assertEquals("0,2,4", str(source));
    }

    // Iterator.concat lazily drains each iterable in argument order, opening each one's iterator
    // only when reached (not eagerly for every argument up front)
    @Test
    public void test_iterator_concat_lazy_in_order() {
        final var source = """
                let opened = [];
                function makeIterable(name, values) {
                    return { [Symbol.iterator]() { opened.push(name); return values[Symbol.iterator](); } };
                }
                let result = Iterator.concat(makeIterable('a', [1, 2]), makeIterable('b', [3, 4]));
                let openedBeforeIteration = opened.join(',');
                let values = result.toArray().join(',');
                JSON.stringify([openedBeforeIteration, values, opened.join(',')])
                """;
        assertEquals("[\"\",\"1,2,3,4\",\"a,b\"]", str(source));
    }

    // Iterator.concat validates every argument is an object with a callable Symbol.iterator before
    // returning, and throws a catchable TypeError otherwise
    @Test
    public void test_iterator_concat_rejects_non_iterable_argument() {
        assertEquals("TypeError", str("let n; try { Iterator.concat({}); } catch (e) { n = e.name; } n"));
        assertEquals("TypeError", str("let n; try { Iterator.concat(null); } catch (e) { n = e.name; } n"));
    }

    // `new Iterator.concat()` throws - it is a non-constructor
    @Test
    public void test_iterator_concat_not_constructible() {
        assertEquals("TypeError", str("let n; try { new Iterator.concat(); } catch (e) { n = e.name; } n"));
    }

    // Iterator.concat's result is a real instance of the Iterator global (its [[Prototype]] is
    // Iterator.prototype), and Iterator itself now has a correctly-wired [[Prototype]] field
    // (`instanceof` reads that field directly, not the "prototype" own property)
    @Test
    public void test_iterator_concat_result_is_instance_of_iterator() {
        assertEquals("true", str("String(Iterator.concat([1]) instanceof Iterator)"));
    }

    // An iterator helper's own `return` forwards to (and closes) the underlying source iterator
    @Test
    public void test_iterator_helper_return_forwards_to_source() {
        final var source = """
                let returned = false;
                let source = {
                    [Symbol.iterator]() { return this; },
                    next() { return { value: 1, done: false }; },
                    return() { returned = true; return { done: true }; },
                };
                let mapped = Iterator.from(source).map(x => x);
                mapped.next();
                mapped.return();
                String(returned)
                """;
        assertEquals("true", str(source));
    }
}
