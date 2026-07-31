package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    // reads an accumulator array reference after the event loop has drained
    private static String drained() {
        final var array = (JsArray) Interpreter.run(
                "let out = [];\nasync function* range(n) {\n    for (let i = 0; i < n; i++) {\n        yield await Promise.resolve(i * i);\n    }\n}\nasync function main() {\n    for await (const sq of range(4)) out.push(sq);\n}\nmain();\nout\n");
        final var sb = new StringBuilder();
        for (var i = 0; i < array.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(JsCoercion.toStr(array.get(i)));
        }
        return sb.toString();
    }

    private static String str() {
        return ((JsString) Interpreter.run("let s = '';\nfor (let i = 0; i < 3; i++) {\n    s += i;\n}\ns\n"))
                .getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // A for loop builds a Fibonacci sequence in an array
    @Test
    public void test_fibonacci_by_array() {
        final var source = """
                let fib = [0, 1];
                for (let i = 2; i < 10; i++) {
                    fib[i] = fib[i - 1] + fib[i - 2];
                }
                fib[9]
                """;
        assertEquals(34, num(source));
    }

    // A while loop computes a factorial
    @Test
    public void test_factorial_by_while() {
        final var source = """
                let n = 5;
                let f = 1;
                while (n > 1) {
                    f *= n;
                    n--;
                }
                f
                """;
        assertEquals(120, num(source));
    }

    // Nested labeled loops break out of the outer loop on a condition
    @Test
    public void test_nested_labeled_break() {
        final var source = """
                let count = 0;
                search: for (let i = 0; i < 3; i++) {
                    for (let j = 0; j < 3; j++) {
                        count++;
                        if (i + j === 3) break search;
                    }
                }
                count
                """;
        assertEquals(6, num(source));
    }

    // Mixed-precedence arithmetic evaluates left-to-right within precedence levels
    @Test
    public void test_operator_precedence() {
        assertEquals(5, num("let x = 1 + 2 * 3 - 4 / 2; x"));
    }

    // A loop accumulates into an object property
    @Test
    public void test_object_accumulation() {
        final var source = """
                let acc = { total: 0 };
                for (let i = 1; i <= 4; i++) {
                    acc.total += i;
                }
                acc.total
                """;
        assertEquals(10, num(source));
    }

    // A loop builds a string with the += operator
    @Test
    public void test_string_building() {
        assertEquals("012", str());
    }

    // A recursive function declaration computes a factorial
    @Test
    public void test_recursive_factorial() {
        final var source = """
                function factorial(n) {
                    if (n <= 1) return 1;
                    return n * factorial(n - 1);
                }
                factorial(5)
                """;
        assertEquals(120, num(source));
    }

    // A recursive Fibonacci function returns the expected term
    @Test
    public void test_recursive_fibonacci() {
        final var source = """
                function fib(n) {
                    if (n < 2) return n;
                    return fib(n - 1) + fib(n - 2);
                }
                fib(10)
                """;
        assertEquals(55, num(source));
    }

    // A closure-based accumulator keeps private state across calls
    @Test
    public void test_closure_accumulator() {
        final var source = """
                function makeAdder(step) {
                    let total = 0;
                    return function (n) {
                        total += n * step;
                        return total;
                    };
                }
                let add = makeAdder(2);
                add(1);
                add(2);
                add(3)
                """;
        assertEquals(12, num(source));
    }

    // try/catch/finally recovers from a thrown error and still runs cleanup
    @Test
    public void test_try_catch_finally_recovery() {
        final var source = """
                let log = '';
                function risky(n) {
                    if (n < 0) throw new RangeError('negative');
                    return n * 2;
                }
                let result = 0;
                try {
                    result = risky(-1);
                } catch (e) {
                    result = -1;
                    log += e.name;
                } finally {
                    log += ':done';
                }
                log + '=' + result
                """;
        assertEquals("RangeError:done=-1", str(source));
    }

    // A switch-based dispatcher selects a branch with fall-through
    @Test
    public void test_switch_dispatcher() {
        final var source = """
                function classify(n) {
                    let kind = '';
                    switch (n % 2) {
                        case 0:
                            kind = 'even';
                            break;
                        default:
                            kind = 'odd';
                    }
                    return kind;
                }
                classify(3) + ',' + classify(4)
                """;
        assertEquals("odd,even", str(source));
    }

    // Object destructuring with a default feeds an array method pipeline
    @Test
    public void test_destructure_and_map() {
        final var source = """
                let args = {a: 3};
                const {a, b = 2} = args;
                [a, b].map(x => x * 2).join(',')
                """;
        assertEquals("6,4", str(source));
    }

    // A map/filter/reduce pipeline computes a total
    @Test
    public void test_pipeline() {
        final var source = """
                let nums = [1, 2, 3, 4, 5, 6];
                nums.filter(n => n % 2 === 0).map(n => n * n).reduce((a, b) => a + b, 0)
                """;
        assertEquals(56, num(source));
    }

    // Object.entries rebuilds a transformed object
    @Test
    public void test_entries_rebuild() {
        final var source = """
                let prices = {apple: 1, pear: 2};
                let doubled = {};
                Object.entries(prices).forEach(([k, v]) => { doubled[k] = v * 2; });
                doubled.apple + ',' + doubled.pear
                """;
        assertEquals("2,4", str(source));
    }

    // A regex literal drives a global replace end to end
    @Test
    public void test_regex_global_replace() {
        assertEquals("a#b#", str("'a1b2'.replace(/\\d/g, '#')"));
    }

    // An async generator that awaits is consumed by for-await end to end
    @Test
    public void test_async_generator_pipeline() {
        assertEquals("0,1,4,9", drained());
    }

    // Named capture groups are read from a match result
    @Test
    public void test_regex_named_capture() {
        final var source = """
                const m = '2024-01'.match(/(?<year>\\d+)-(?<month>\\d+)/);
                m.groups.year + '/' + m.groups.month
                """;
        assertEquals("2024/01", str(source));
    }

    // JSON round-trips through a transformation
    @Test
    public void test_json_transform() {
        final var source = """
                let doc = JSON.parse('{"items":[1,2,3]}');
                doc.items.push(4);
                JSON.parse(JSON.stringify(doc)).items.length
                """;
        assertEquals(4, num(source));
    }

    // Object spread merges two sources with override
    @Test
    public void test_object_spread_merge() {
        final var source = """
                let base = {a: 1, b: 2};
                let override = {b: 9, c: 3};
                let merged = {...base, ...override};
                merged.a + ',' + merged.b + ',' + merged.c
                """;
        assertEquals("1,9,3", str(source));
    }

    // A Stack class encapsulates a private field behind push/pop and a size getter
    @Test
    public void test_stack_class_with_private_field() {
        final var source = """
                class Stack {
                    #items = [];
                    push(x) { this.#items.push(x); return this; }
                    pop() { return this.#items.pop(); }
                    get size() { return this.#items.length; }
                }
                let s = new Stack();
                s.push(1).push(2).push(3);
                let top = s.pop();
                top + ':' + s.size
                """;
        assertEquals("3:2", str(source));
    }

    // An inheritance chain overrides area() and reuses the base via super
    @Test
    public void test_shape_inheritance_with_super() {
        final var source = """
                class Shape {
                    constructor(name) { this.name = name; }
                    area() { return 0; }
                    describe() { return this.name + '=' + this.area(); }
                }
                class Circle extends Shape {
                    constructor(r) { super('circle'); this.r = r; }
                    area() { return Math.floor(super.area() + this.r * this.r * 3); }
                }
                new Circle(2).describe()
                """;
        assertEquals("circle=12", str(source));
    }

    // A static field and method maintain a shared instance counter
    @Test
    public void test_static_instance_counter() {
        final var source = """
                class Widget {
                    static count = 0;
                    constructor() { Widget.count++; }
                    static total() { return Widget.count; }
                }
                new Widget();
                new Widget();
                new Widget();
                Widget.total()
                """;
        assertEquals(3, num(source));
    }

    // A tagged template invokes the tag with the strings array followed by the interpolated values
    @Test
    public void test_tagged_template_passes_strings_and_values() {
        final var source = """
                function t(s, ...v) { return s.join('|') + '#' + v.join(','); }
                t`a${1}b${2}c`
                """;
        assertEquals("a|b|c#1,2", str(source));
    }

    // The strings array carries a raw companion that preserves escape sequences
    @Test
    public void test_tagged_template_raw_property() {
        final var source = """
                function t(s) { return s.raw[0] + '/' + s[0]; }
                t`\\n`
                """;
        assertEquals("\\n/\n", str(source));
    }

    // A member-tagged template binds this to the receiver object
    @Test
    public void test_tagged_template_this_binding() {
        final var source = """
                const obj = { name: 'x', tag: function(s) { return this.name; } };
                obj.tag`hi`
                """;
        assertEquals("x", str(source));
    }

    // A template with no substitutions passes a single-element strings array and no extra args
    @Test
    public void test_tagged_template_no_substitutions() {
        final var source = """
                function t(s, ...v) { return s.length + ':' + v.length; }
                t`hello`
                """;
        assertEquals("1:0", str(source));
    }

    // A nested tagged template inside an interpolation evaluates correctly
    @Test
    public void test_tagged_template_nested() {
        final var source = """
                function u(s) { return s[0].toUpperCase(); }
                function t(s, v) { return s[0] + v + s[1]; }
                t`<${ u`x` }>`
                """;
        assertEquals("<X>", str(source));
    }

    // Tagging a non-function value throws a TypeError
    @Test
    public void test_tagged_template_non_function_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    const notFn = 5;
                    notFn`x`;
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }

    // String.raw builds a string from the raw quasis and substitutions
    @Test
    public void test_string_raw_tag() {
        assertEquals("a\\n1b", str("String.raw`a\\n${1}b`"));
    }

    // classic for (let ...) closures capture a fresh per-iteration binding
    @Test
    public void test_classic_for_let_per_iteration_binding() {
        final var source = """
                let fns = [];
                for (let i = 0; i < 3; i++) fns.push(() => i);
                fns[0]() + fns[1]() + fns[2]()
                """;
        assertEquals(3, num(source));
    }

    // classic for (var ...) shares one binding across iterations
    @Test
    public void test_classic_for_var_shares_binding() {
        final var source = """
                let fns = [];
                for (var i = 0; i < 3; i++) fns.push(() => i);
                fns[0]() + fns[1]() + fns[2]()
                """;
        assertEquals(9, num(source));
    }

    // break and continue still work with per-iteration bindings
    @Test
    public void test_classic_for_break_continue() {
        final var source = """
                let s = 0;
                for (let i = 0; i < 10; i++) {
                    if (i === 2) continue;
                    if (i === 5) break;
                    s += i;
                }
                s
                """;
        assertEquals(8, num(source));
    }

    // an empty-header and lexical-header loop that immediately breaks does not crash
    @Test
    public void test_classic_for_empty_and_lexical_headers() {
        assertEquals(1, num("let n = 0; for (;;) { n = 1; break; } n"));
        assertEquals(1, num("let n = 0; for (let i = 0;;) { n = 1; break; } n"));
    }
}
