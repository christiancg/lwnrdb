package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

/**
 * The generic and error-shaped corners of the standard library: the methods that accept an array-like or a
 * Set-like rather than the real thing, the ones that build a subclass instance, and the ones that refuse.
 */
public class StandardLibraryEdgesTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static String attempt(String expression) {
        return str("(() => { try { return String(" + expression + "); } catch (e) { return e.constructor.name; } })()");
    }

    // null and undefined join as empty, everything else through its own toLocaleString
    @Test
    public void test_array_to_locale_string_skips_the_nullish_elements() {
        assertEquals("1,,,obj,x",
                str("[1, null, undefined, { toLocaleString() { return 'obj'; } }, 'x'].toLocaleString()"));
    }

    @Test
    public void test_array_from_reads_an_array_like_by_index() {
        assertEquals("a||c", str("Array.from({ length: 3, 0: 'a', 2: 'c' }).join('|')"));
    }

    @Test
    public void test_array_from_builds_a_subclass_instance() {
        assertEquals("2:true", str("""
                class MyArray extends Array {}
                const made = MyArray.from([1, 2]);
                [made.length, made instanceof MyArray].join(':')
                """));
    }

    // An array-like only spreads into concat when it says it is concat-spreadable
    @Test
    public void test_concat_spreads_an_array_like_that_opts_in() {
        assertEquals("1,x,y", str("""
                const spreadable = { length: 2, 0: 'x', 1: 'y', [Symbol.isConcatSpreadable]: true };
                [1].concat(spreadable).join(',')
                """));
    }

    @Test
    public void test_reverse_keeps_a_hole_a_hole() {
        assertEquals("3:false:3", str("const a = [1, , 3]; a.reverse(); [a.length, 1 in a, a[0]].join(':')"));
    }

    @Test
    public void test_string_raw_accepts_a_generic_raw_bearing_object() {
        assertEquals("a1b2c", str("String.raw({ raw: ['a', 'b', 'c'] }, 1, 2)"));
        assertEquals("", str("String.raw({ raw: [] })"));
    }

    @Test
    public void test_string_raw_refuses_an_object_with_no_raw() {
        assertEquals("TypeError", attempt("String.raw({})"));
    }

    @Test
    public void test_parse_int_handles_prefixes_radixes_and_refusals() {
        assertEquals("-31,5,35,NaN,7", str("""
                [
                    Number.parseInt('  -0x1f'),
                    Number.parseInt('101', 2),
                    Number.parseInt('z', 36),
                    Number.parseInt('12', 1),
                    Number.parseInt('+7')
                ].join(',')
                """));
    }

    @Test
    public void test_to_exponential_and_to_precision_pick_their_forms() {
        assertEquals("1.23e+4,0e+0,1.2e-4",
                str("[(12345).toExponential(2), (0).toExponential(), (0.00012).toExponential(1)].join(',')"));
        assertEquals("123.5,0.00012,1.2e+2",
                str("[(123.456).toPrecision(4), (0.000123).toPrecision(2), (123).toPrecision(2)].join(',')"));
    }

    // The seven set methods take any Set-like: a size, a has and a keys
    @Test
    public void test_the_set_methods_accept_a_set_like() {
        assertEquals("1234|2|13|134|true|false|true|true", str("""
                const setLike = { size: 2, has: v => v === 2 || v === 4, keys: () => [2, 4][Symbol.iterator]() };
                const set = new Set([1, 2, 3]);
                [
                    [...set.union(setLike)].join(''),
                    [...set.intersection(setLike)].join(''),
                    [...set.difference(setLike)].join(''),
                    [...set.symmetricDifference(setLike)].join(''),
                    set.isDisjointFrom(new Set([9])),
                    set.isDisjointFrom(setLike),
                    set.isSubsetOf(new Set([1, 2, 3, 4])),
                    set.isSupersetOf(new Set([1]))
                ].join('|')
                """));
    }

    @Test
    public void test_a_set_method_refuses_something_that_is_not_set_like() {
        assertEquals("TypeError", attempt("new Set([1]).isDisjointFrom({ size: 'x' })"));
    }

    // A date-only string is UTC, so these are the same instants wherever the host happens to be
    @Test
    public void test_date_parse_reads_the_iso_forms() {
        assertEquals("1767312000000,1767323045000,1767315845123,NaN", str("""
                [
                    Date.parse('2026-01-02'),
                    Date.parse('2026-01-02T03:04:05Z'),
                    Date.parse('2026-01-02T03:04:05.123+02:00'),
                    Date.parse('nonsense')
                ].join(',')
                """));
    }

    @Test
    public void test_to_json_is_null_for_an_invalid_date() {
        assertEquals("1970-01-01T00:00:00.000Z:true",
                str("[new Date(0).toJSON(), new Date(NaN).toJSON() === null].join(':')"));
    }

    @Test
    public void test_a_date_coerces_by_hint_and_refuses_an_unknown_one() {
        assertEquals("0:string", str("""
                [new Date(0)[Symbol.toPrimitive]('number'), typeof new Date(0)[Symbol.toPrimitive]('string')].join(':')
                """));
        assertEquals("TypeError", attempt("new Date(0)[Symbol.toPrimitive]('bogus')"));
    }

    @Test
    public void test_bigint_arithmetic_stays_exact_and_refuses_mixing() {
        assertEquals("3,2,2,1024,-3", str("[1n + 2n, 5n / 2n, 5n % 3n, 2n ** 10n, -3n].join(',')"));
        assertEquals("TypeError", attempt("1n + 1"));
    }

    @Test
    public void test_loose_equality_across_the_type_boundaries() {
        assertEquals("true,true,true,true,true,true", str("""
                [null == undefined, 1 == '1', true == 1, 1n == 1, [] == '', ({}) == '[object Object]'].join(',')
                """));
    }

    // Disposal runs in reverse registration order, whichever way the resource was registered
    @Test
    public void test_a_disposable_stack_disposes_in_reverse_order() {
        assertEquals("defer,adopt:resource,use", str("""
                const order = [];
                {
                    using stack = new DisposableStack();
                    stack.use({ [Symbol.dispose]() { order.push('use'); } });
                    stack.adopt('resource', v => order.push('adopt:' + v));
                    stack.defer(() => order.push('defer'));
                }
                order.join(',')
                """));
    }

    @Test
    public void test_move_transfers_the_resources_and_disposes_the_original() {
        assertEquals("true:false:moved", str("""
                const order = [];
                const stack = new DisposableStack();
                stack.defer(() => order.push('moved'));
                const target = stack.move();
                const states = [stack.disposed, target.disposed];
                target.dispose();
                [states[0], states[1], order.join(',')].join(':')
                """));
    }

    @Test
    public void test_a_disposable_stack_refuses_what_it_cannot_dispose() {
        assertEquals("TypeError", attempt("new DisposableStack().use({})"));
        assertEquals("TypeError", attempt("new DisposableStack().adopt(1, 'nope')"));
    }

    @Test
    public void test_a_disposed_stack_takes_no_more_resources() {
        assertEquals("ReferenceError", attempt("""
                (() => { const stack = new DisposableStack(); stack.dispose(); return stack.defer(() => {}); })()
                """));
    }

    // Disposal is reverse-order, so the RangeError is raised last and becomes the error the
    // SuppressedError reports, carrying the TypeError raised before it as the suppressed one
    @Test
    public void test_two_throwing_disposers_aggregate_into_a_suppressed_error() {
        assertEquals("SuppressedError:RangeError:TypeError", str("""
                const stack = new DisposableStack();
                stack.defer(() => { throw new RangeError('first'); });
                stack.defer(() => { throw new TypeError('second'); });
                let outcome = 'no-throw';
                try {
                    stack.dispose();
                } catch (e) {
                    outcome = [e.constructor.name, e.error.constructor.name, e.suppressed.constructor.name].join(':');
                }
                outcome
                """));
    }
}
