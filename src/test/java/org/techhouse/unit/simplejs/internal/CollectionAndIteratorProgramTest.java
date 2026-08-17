package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

public class CollectionAndIteratorProgramTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // reads the accumulator array reference after the event loop has drained
    private static String joined() {
        final var array = (JsArray) Interpreter.run(
                "let out = [];\nconst it = {\n    i: 0,\n    async next() { this.i++; return { value: this.i, done: this.i > 2 }; },\n    [Symbol.asyncIterator]() { return this; }\n};\nasync function main() { out.push(...(await it.toArray())); }\nmain();\nout\n");
        final var sb = new StringBuilder();
        for (var i = 0; i < array.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(JsCoercion.toStr(array.get(i)));
        }
        return sb.toString();
    }

    // An iterator helper resolves on any object with a callable next
    @Test
    public void test_helper_on_an_iterator_like_object() {
        final var source = """
                const it = { i: 0, next() { this.i++; return { value: this.i, done: this.i > 3 }; } };
                [...it.map(x => x * 2)].join(',')
                """;
        assertEquals("2,4,6", str(source));
    }

    // toArray resolves on an iterator-like object too
    @Test
    public void test_to_array_on_an_iterator_like_object() {
        final var source = """
                const it = { i: 0, next() { this.i++; return { value: this.i, done: this.i > 2 }; } };
                it.toArray().join(',')
                """;
        assertEquals("1,2", str(source));
    }

    // Helpers chain onto their own lazy results
    @Test
    public void test_helpers_chain_on_a_generator() {
        final var source = """
                function* g() { yield 1; yield 2; yield 3; }
                g().filter(x => x > 1).map(x => x * 10).toArray().join(',')
                """;
        assertEquals("20,30", str(source));
    }

    // An async helper resolves on an object with a callable next and an async iterator
    @Test
    public void test_async_helper_on_an_async_iterator_like_object() {
        assertEquals("1,2", joined());
    }

    // union collects the members of both sets
    @Test
    public void test_set_union() {
        assertEquals("1,2,3", str("[...new Set([1, 2]).union(new Set([3]))].join(',')"));
    }

    // intersection keeps only the shared members
    @Test
    public void test_set_intersection() {
        assertEquals("2", str("[...new Set([1, 2]).intersection(new Set([2, 3]))].join(',')"));
    }

    // difference removes the other set's members
    @Test
    public void test_set_difference() {
        assertEquals("1", str("[...new Set([1, 2]).difference(new Set([2]))].join(',')"));
    }

    // symmetricDifference keeps the members of exactly one set
    @Test
    public void test_set_symmetric_difference() {
        assertEquals("1,3", str("[...new Set([1, 2]).symmetricDifference(new Set([2, 3]))].join(',')"));
    }

    // The containment predicates compare membership both ways
    @Test
    public void test_set_containment_predicates() {
        assertTrue(bool("new Set([1]).isSubsetOf(new Set([1, 2]))"));
        assertTrue(bool("new Set([1, 2]).isSupersetOf(new Set([1]))"));
        assertTrue(bool("new Set([1]).isDisjointFrom(new Set([2]))"));
    }

    // A set operation rejects an argument that is not set-like
    @Test
    public void test_set_operation_rejects_an_array() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Set([1]).union([2])"));
    }

    // forEach visits the members in insertion order
    @Test
    public void test_set_for_each() {
        assertEquals("12", str("let out = ''; new Set([1, 2]).forEach(v => { out += v; }); out"));
    }

    // A set's entries pair each member with itself
    @Test
    public void test_set_entries() {
        assertEquals("1-1", str("[...new Set([1]).entries()].map(e => e.join('-')).join(',')"));
    }

    // A set's keys are its values
    @Test
    public void test_set_keys() {
        assertEquals("1,2", str("[...new Set([1, 2]).keys()].join(',')"));
    }

    // delete reports whether the member was present, and clear empties the set
    @Test
    public void test_set_delete_and_clear() {
        final var source = """
                const s = new Set([1, 2]);
                const deleted = s.delete(1);
                deleted + ':' + s.size + ':' + (s.clear(), s.size)
                """;
        assertEquals("true:1:0", str(source));
    }

    // Map.groupBy buckets an iterable under the callback's keys
    @Test
    public void test_map_group_by() {
        assertEquals("1,3", str("[...Map.groupBy([1, 2, 3], x => x % 2).get(1)].join(',')"));
    }

    // Object.groupBy buckets an iterable into a plain object
    @Test
    public void test_object_group_by() {
        assertEquals("1,3", str("Object.groupBy([1, 2, 3], x => x % 2 ? 'odd' : 'even').odd.join(',')"));
    }

    // A map's forEach receives the value and the key
    @Test
    public void test_map_for_each() {
        assertEquals("1a", str("let out = ''; new Map([[1, 'a']]).forEach((v, k) => { out += k + v; }); out"));
    }

    // A map's entries pair keys with values
    @Test
    public void test_map_entries() {
        assertEquals("1-a", str("[...new Map([[1, 'a']]).entries()].map(e => e.join('-')).join(',')"));
    }

    // A symbol-keyed method is inherited through the class heritage
    @Test
    public void test_symbol_method_through_the_heritage() {
        assertTrue(bool(
                "const key = Symbol('k'); class A { [key]() { return true; } } class B extends A {} new B()[key]()"));
    }
}
