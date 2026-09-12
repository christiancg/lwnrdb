package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterGeneratorTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter.run("""
                function* g() {}
                Object.getPrototypeOf(g()) === g.prototype
                """)).getValue();
    }

    @Test
    public void test_generator_next_sequence() {
        final var source = """
                function* g() { yield 1; yield 2; }
                let it = g();
                let r1 = it.next();
                let r2 = it.next();
                let r3 = it.next();
                r1.value + ',' + r1.done + '|' + r2.value + ',' + r2.done + '|' + r3.value + ',' + r3.done
                """;
        assertEquals("1,false|2,false|undefined,true", str(source));
    }

    @Test
    public void test_generator_consumed_by_for_of() {
        assertEquals(6, num("function* g() { yield 1; yield 2; yield 3; } let s = 0; for (const x of g()) s += x; s"));
    }

    @Test
    public void test_generator_two_way_next_value() {
        final var source = """
                function* g() { let x = yield 1; yield x + 10; }
                let it = g();
                it.next();
                it.next(5).value
                """;
        assertEquals(15, num(source));
    }

    @Test
    public void test_yield_delegate_array() {
        assertEquals(6, num("function* g() { yield* [1, 2, 3]; } let s = 0; for (const x of g()) s += x; s"));
    }

    @Test
    public void test_yield_delegate_generator_return_value() {
        final var source = """
                function* inner() { yield 1; return 99; }
                function* outer() { let r = yield* inner(); yield r; }
                let it = outer();
                it.next();
                it.next().value
                """;
        assertEquals(99, num(source));
    }

    @Test
    public void test_generator_return_runs_finally() {
        final var source = """
                let log = [];
                function* g() { try { yield 1; yield 2; } finally { log.push('cleanup'); } }
                let it = g();
                it.next();
                let r = it.return(42);
                log.join(',') + '|' + r.value + ',' + r.done
                """;
        assertEquals("cleanup|42,true", str(source));
    }

    @Test
    public void test_generator_throw_caught_in_body() {
        final var source = """
                function* g() { try { yield 1; } catch (e) { yield 'caught:' + e; } }
                let it = g();
                it.next();
                it.throw('boom').value
                """;
        assertEquals("caught:boom", str(source));
    }

    @Test
    public void test_yield_outside_generator_is_syntax_error() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("function f() { return yield 1; } f()"));
    }

    @Test
    public void test_generator_class_method() {
        final var source = """
                class C { *gen() { yield 1; yield 2; } }
                let s = 0;
                for (const x of new C().gen()) s += x;
                s
                """;
        assertEquals(3, num(source));
    }

    @Test
    public void test_empty_generator() {
        assertEquals("undefined,true", str("function* g() {} let r = g().next(); r.value + ',' + r.done"));
    }

    @Test
    public void test_generator_prototype_is_patchable() {
        assertEquals("object", str("function* g() { yield 1; } typeof Object.getPrototypeOf(g())"));
        assertEquals(9, num("""
                function* g() { yield 1; }
                const it = g();
                Object.getPrototypeOf(it).next = function() { return {value: 9, done: false}; };
                it.next().value
                """));
        assertEquals("1,2,true", str("""
                function* g() { yield 1; yield 2; }
                const it = g();
                it.next().value + ',' + it.next().value + ',' + it.next().done
                """));
        assertEquals("2,4", str("function* g() { yield 1; yield 2; } g().map(x => x * 2).toArray().join(',')"));
    }

    @Test
    public void test_generator_function_prototype_has_no_own_properties() {
        assertEquals(0, num("function* g() {} Object.getOwnPropertyNames(g.prototype).length"));
    }

    @Test
    public void test_generator_instance_prototype_is_one_level_below_the_shared_intrinsic() {
        assertEquals("Generator", str("""
                function* g() {}
                let generatorPrototype = Object.getPrototypeOf(Object.getPrototypeOf(g()));
                generatorPrototype[Symbol.toStringTag]
                """));
        assertTrue(bool());
    }
}
