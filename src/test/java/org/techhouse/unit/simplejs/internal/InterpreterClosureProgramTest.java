package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;

public class InterpreterClosureProgramTest {
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

    // An async generator that awaits is consumed by for-await end to end
    @Test
    public void test_async_generator_pipeline() {
        assertEquals("0,1,4,9", drained());
    }

    // a var nested in a statement that never runs still has its binding from scope entry
    @Test
    public void test_var_hoisting_reaches_nested_statements() {
        assertEquals(1, num("if (false) { var a = 2; } a === undefined ? 1 : 0"));
        assertEquals(1, num("while (false) { var b = 2; } b === undefined ? 1 : 0"));
        assertEquals(1, num("for (var k in undefined) { var c = 2; } c === undefined ? 1 : 0"));
        assertEquals(1, num("switch (0) { case 1: var d = 2; } d === undefined ? 1 : 0"));
        assertEquals(1, num("try { } finally { } l: { var e = 2; } e === 2 ? 1 : 0"));
        assertEquals(1, num("function f() { if (false) { var g = 2; } return g === undefined ? 1 : 0; } f()"));
    }
}
