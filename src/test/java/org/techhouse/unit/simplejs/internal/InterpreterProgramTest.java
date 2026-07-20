package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
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
}
