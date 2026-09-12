package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

public class IteratorBuiltinsTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

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

    @Test
    public void test_iterator_prototype_symbol_iterator() {
        assertEquals("1,2", str("function* g(){yield 1;yield 2;} [...g().map(x => x)].join(',')"));
    }

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

    @Test
    public void test_iterator_subclass_construction_succeeds() {
        final var source = """
                class SubIterator extends Iterator {}
                let s = new SubIterator();
                (s instanceof SubIterator) + ',' + (s instanceof Iterator)
                """;
        assertEquals("true,true", str(source));
    }

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

    @Test
    public void test_iterator_concat_rejects_non_iterable_argument() {
        assertEquals("TypeError", str("let n; try { Iterator.concat({}); } catch (e) { n = e.name; } n"));
        assertEquals("TypeError", str("let n; try { Iterator.concat(null); } catch (e) { n = e.name; } n"));
    }

    @Test
    public void test_iterator_concat_not_constructible() {
        assertEquals("TypeError", str("let n; try { new Iterator.concat(); } catch (e) { n = e.name; } n"));
    }

    @Test
    public void test_iterator_concat_result_is_instance_of_iterator() {
        assertEquals("true", str("String(Iterator.concat([1]) instanceof Iterator)"));
    }

    @Test
    public void test_iterator_helper_step_result_has_object_prototype() {
        final var source = """
                function* g() { yield 1; }
                JSON.stringify([
                    Object.getPrototypeOf(g().map(x => x).next()) === Object.prototype,
                    Object.getPrototypeOf(Iterator.concat([1]).next()) === Object.prototype,
                ])
                """;
        assertEquals("[true,true]", str(source));
    }

    @Test
    public void test_iterator_zip_basic() {
        assertEquals("[[1,\"a\"],[2,\"b\"]]", str("JSON.stringify(Iterator.zip([[1, 2], ['a', 'b']]).toArray())"));
    }

    @Test
    public void test_iterator_zip_shortest_mode_stops_early() {
        assertEquals("[[1,\"a\"]]", str("JSON.stringify(Iterator.zip([[1, 2, 3], ['a']]).toArray())"));
    }

    // padding is positional: the second input's pad comes from padding[1], not padding[0]
    @Test
    public void test_iterator_zip_longest_mode_pads() {
        final var source = "JSON.stringify(Iterator.zip([[1, 2, 3], ['a']], "
                + "{ mode: 'longest', padding: [undefined, 'pad'] }).toArray())";
        assertEquals("[[1,\"a\"],[2,\"pad\"],[3,\"pad\"]]", str(source));
    }

    @Test
    public void test_iterator_zip_strict_mode_throws_on_length_mismatch() {
        final var source = "let n; try { Iterator.zip([[1, 2], ['a']], { mode: 'strict' }).toArray(); }"
                + " catch (e) { n = e.name; } n";
        assertEquals("TypeError", str(source));
    }

    @Test
    public void test_iterator_zip_rejects_invalid_mode() {
        assertEquals("TypeError",
                str("let n; try { Iterator.zip([], { mode: 'bogus' }); } catch (e) { n = e.name; } n"));
    }

    @Test
    public void test_iterator_zip_keyed_basic() {
        assertEquals("[{\"x\":1,\"y\":\"a\"},{\"x\":2,\"y\":\"b\"}]",
                str("JSON.stringify(Iterator.zipKeyed({x: [1, 2], y: ['a', 'b']}).toArray())"));
    }

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

    @Test
    public void test_iterator_chunks() {
        assertEquals("[[0,1],[2,3],[4]]",
                str("function* g(){yield 0;yield 1;yield 2;yield 3;yield 4;} JSON.stringify(g().chunks(2).toArray())"));
        assertEquals("[[0,1,2,3,4]]",
                str("function* g(){yield 0;yield 1;yield 2;yield 3;yield 4;} JSON.stringify(g().chunks(9).toArray())"));
    }

    @Test
    public void test_iterator_windows() {
        assertEquals("[[0,1],[1,2],[2,3],[3,4]]", str(
                "function* g(){yield 0;yield 1;yield 2;yield 3;yield 4;} JSON.stringify(g().windows(2).toArray())"));
        assertEquals("[]", str("function* g(){yield 0;yield 1;} JSON.stringify(g().windows(9).toArray())"));
    }

    @Test
    public void test_iterator_chunk_size_validation() {
        assertEquals("TypeError", str("function* g(){yield 1;} let caught = 'none';"
                + " try { g().chunks(1.5); } catch (e) { caught = e.constructor.name; } caught"));
        assertEquals("RangeError", str("function* g(){yield 1;} let caught = 'none';"
                + " try { g().chunks(0); } catch (e) { caught = e.constructor.name; } caught"));
    }

    @Test
    public void test_iterator_includes() {
        assertEquals("true,false",
                str("String([3, 6, 9].values().includes(6)) + ',' + [3, 6, 9].values().includes(5)"));
        assertEquals("false,true",
                str("String([4, 5, 6, 7].values().includes(4, 1)) + ',' + [4, 5, 6, 7].values().includes(6, 2)"));
        assertEquals("true", str("String([NaN].values().includes(NaN))"));
    }

    @Test
    public void test_iterator_join() {
        assertEquals("one,two", str("['one', 'two'].values().join()"));
        assertEquals("", str("[].values().join()"));
        assertEquals("one,,two,", str("['one', null, 'two', undefined].values().join()"));
        assertEquals("a-b", str("['a', 'b'].values().join('-')"));
    }

    @Test
    public void test_helpers_cache_next_method_once() {
        final var source = """
                let gets = 0;
                const counting = {
                    get next() {
                        gets++;
                        const inner = (function* () { yield 1; yield 2; })();
                        return function () { return inner.next(); };
                    }
                };
                const mapped = Iterator.prototype.map.call(counting, v => v);
                let total = 0;
                let step = mapped.next();
                while (!step.done) {
                    total += step.value;
                    step = mapped.next();
                }
                gets + ':' + total
                """;
        assertEquals("1:3", str(source));
    }

    @Test
    public void test_zip_terminates() {
        final var source = """
                const zipped = Iterator.zip([['a', 'b', 'c'], ['d', 'e']]);
                const rounds = [];
                let step = zipped.next();
                while (!step.done) {
                    rounds.push(step.value.join(''));
                    step = zipped.next();
                }
                rounds.join('|') + ':' + zipped.next().done
                """;
        assertEquals("ad|be:true", str(source));
    }

    @Test
    public void test_iterator_helper_lengths() {
        assertEquals("1,1,1,1", str("[Iterator.prototype.chunks.length, Iterator.prototype.windows.length,"
                + " Iterator.prototype.includes.length, Iterator.prototype.join.length].join(',')"));
    }

    @Test
    public void test_argument_validation_closes_underlying() {
        final var source = """
                let closed = 'no';
                const closable = {
                    __proto__: Iterator.prototype,
                    get next() { throw new Error('next should not be read'); },
                    return() { closed = 'yes'; return {}; }
                };
                let thrown = 'none';
                try { closable.map(); } catch (e) { thrown = e.name; }
                thrown + ':' + closed
                """;
        assertEquals("TypeError:yes", str(source));
    }

    @Test
    public void test_predicate_throw_closes_underlying() {
        final var source = """
                let returns = 0;
                class TestIterator extends Iterator {
                    next() { return { done: false, value: 1 }; }
                    return() { ++returns; return {}; }
                }
                const mapped = new TestIterator().map(() => { throw new Error('boom'); });
                let thrown = 'none';
                try { mapped.next(); } catch (e) { thrown = e.message; }
                thrown + ':' + returns
                """;
        assertEquals("boom:1", str(source));
    }

    @Test
    public void test_helper_reentry_throws() {
        final var source = """
                function* g() { while (true) { yield 1; } }
                const iter = g().map(() => iter.next());
                let thrown = 'none';
                try { iter.next(); } catch (e) { thrown = e.name; }
                thrown
                """;
        assertEquals("TypeError", str(source));
    }

    @Test
    public void test_helper_result_is_iterator() {
        final var source = """
                const helper = [1, 2].values().map(x => x);
                (helper instanceof Iterator) + ':' + Object.prototype.toString.call(helper)
                """;
        assertEquals("true:[object Iterator Helper]", str(source));
    }

    @Test
    public void test_iterator_prototype_dispose() {
        final var source = """
                let called = 'no';
                const iter = Object.create(Iterator.prototype);
                iter.return = function () { called = 'yes'; return { done: true }; };
                const returned = iter[Symbol.dispose]();
                called + ':' + (returned === undefined)
                """;
        assertEquals("yes:true", str(source));
    }

    @Test
    public void test_iterator_prototype_accessors() {
        final var source = """
                const tag = Object.getOwnPropertyDescriptor(Iterator.prototype, Symbol.toStringTag);
                const ctor = Object.getOwnPropertyDescriptor(Iterator.prototype, 'constructor');
                let thrown = 'none';
                try { tag.set.call(Iterator.prototype, 'x'); } catch (e) { thrown = e.name; }
                [typeof tag.get, typeof tag.set, tag.configurable, tag.enumerable, typeof ctor.get,
                    Iterator.prototype[Symbol.toStringTag], thrown].join(',')
                """;
        assertEquals("function,function,true,false,function,Iterator,TypeError", str(source));
    }

    @Test
    public void test_iterator_prototype_property_flags() {
        final var source = """
                const desc = Object.getOwnPropertyDescriptor(Iterator, 'prototype');
                [desc.writable, desc.enumerable, desc.configurable].join(',')
                """;
        assertEquals("false,false,false", str(source));
    }

    @Test
    public void test_iterator_from_wrapper() {
        final var source = """
                const wrapped = Iterator.from({ next() { return { done: true }; } });
                const wrapProto = Object.getPrototypeOf(wrapped);
                function* g() {}
                const gen = g();
                let thrown = 'none';
                try { wrapProto.return.call({}); } catch (e) { thrown = e.name; }
                [Object.getPrototypeOf(wrapProto) === Iterator.prototype,
                    Iterator.from(gen) === gen, thrown].join(',')
                """;
        assertEquals("true,true,TypeError", str(source));
    }

    @Test
    public void test_builtin_iterator_prototypes() {
        final var source = """
                const tags = [[].values(), 'a'[Symbol.iterator](), new Map().values(), new Set().values()]
                    .map(it => Object.prototype.toString.call(it));
                const arrayProto = Object.getPrototypeOf([].values());
                const linked = Object.getPrototypeOf(arrayProto) === Iterator.prototype;
                tags.join('|') + ':' + linked
                """;
        assertEquals("[object Array Iterator]|[object String Iterator]|[object Map Iterator]|"
                + "[object Set Iterator]:true", str(source));
    }

    @Test
    public void test_builtin_iterator_prototype_flags() {
        final var source = """
                const proto = Object.getPrototypeOf([].values());
                const tag = Object.getOwnPropertyDescriptor(proto, Symbol.toStringTag);
                const next = Object.getOwnPropertyDescriptor(proto, 'next');
                let thrown = 'none';
                try { Object.create([].values()).next(); } catch (e) { thrown = e.name; }
                [tag.writable, tag.enumerable, tag.configurable, tag.value, next.writable,
                    next.enumerable, next.configurable, next.value.length, thrown].join(',')
                """;
        assertEquals("false,false,true,Array Iterator,true,false,true,0,TypeError", str(source));
    }

    @Test
    public void test_array_iterator_is_live_but_latches() {
        final var source = """
                const array = [];
                const it = array[Symbol.iterator]();
                array.push('a');
                const first = it.next();
                const second = it.next();
                array.push('b');
                const third = it.next();
                [first.value, second.done, third.done].join(',')
                """;
        assertEquals("a,true,true", str(source));
    }

    @Test
    public void test_windows_allow_partial() {
        final var source = """
                function* g() { yield 0; yield 1; yield 2; }
                const partial = g().windows(100, 'allow-partial').toArray();
                let thrown = 'none';
                try { g().windows(1, 'bad'); } catch (e) { thrown = e.name; }
                partial.length + ':' + partial[0].join('') + ':' + thrown
                """;
        assertEquals("1:012:TypeError", str(source));
    }
}
