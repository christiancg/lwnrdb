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
        return ((JsString) Interpreter.run("let s = '';\nfor (let i = 0; i < 3; i++) {\n    s += i;\n}\ns\n")).getValue();
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
}
