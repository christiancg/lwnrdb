package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterYieldStarTest {
    private static double num() {
        return ((JsNumber) Interpreter.run(
                "let reads = 0;\nlet steps = 0;\nlet iterable = {};\niterable[Symbol.iterator] = function() {\n  return {\n    get next() {\n      reads += 1;\n      return function() { steps += 1; return { value: 1, done: steps > 1 }; };\n    }\n  };\n};\nfunction* g() { yield* iterable; }\nlet it = g();\nit.next();\nit.next();\nreads\n"))
                .getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // a throw completion at the delegating yield is forwarded to the inner iterator's throw method
    @Test
    public void delegationForwardsThrowToInnerIterator() {
        final var source = """
                let log = [];
                let iterable = {};
                iterable[Symbol.iterator] = function() {
                  return {
                    next: function() { return { value: 1, done: false }; },
                    throw: function(v) { log.push('throw:' + v); return { value: 9, done: true }; }
                  };
                };
                function* g() { let x = yield* iterable; log.push('resumed:' + x); }
                let it = g();
                it.next();
                it.throw('boom');
                log.join('|')
                """;
        assertEquals("throw:boom|resumed:9", str(source));
    }

    // a return completion is forwarded to the inner iterator's return method
    @Test
    public void delegationForwardsReturnToInnerIterator() {
        final var source = """
                let log = [];
                let iterable = {};
                iterable[Symbol.iterator] = function() {
                  return {
                    next: function() { return { value: 1, done: false }; },
                    return: function(v) { log.push('return:' + v); return { value: 7, done: true }; }
                  };
                };
                function* g() { yield* iterable; }
                let it = g();
                it.next();
                let r = it.return(42);
                log.join('|') + '#' + r.value + ',' + r.done
                """;
        assertEquals("return:42#7,true", str(source));
    }

    // the value passed to the outer next() reaches the inner iterator's next method
    @Test
    public void delegationPropagatesSentValue() {
        final var source = """
                let seen = [];
                let iterable = {};
                iterable[Symbol.iterator] = function() {
                  return { next: function(v) { seen.push(v); return { value: 1, done: seen.length > 2 }; } };
                };
                function* g() { yield* iterable; }
                let it = g();
                it.next('a');
                it.next('b');
                it.next('c');
                seen.map(String).join(',')
                """;
        assertEquals("undefined,b,c", str(source));
    }

    // the delegating expression evaluates to the inner iterator's return value
    @Test
    public void delegationReturnsInnerReturnValue() {
        final var source = """
                function* inner() { yield 1; return 'inner-done'; }
                function* g() { let x = yield* inner(); yield x; }
                let it = g();
                it.next();
                it.next().value
                """;
        assertEquals("inner-done", str(source));
    }

    // the next method is read off the iterator object, once, when the iterator is opened
    @Test
    public void delegationLooksUpNextOnTheIteratorObject() {
        assertEquals(1, num());
    }

    // a synchronous generator hands its consumer the inner iterator's result object untouched
    @Test
    public void delegationPassesTheInnerResultObjectThrough() {
        final var source = """
                let result = { value: 5 };
                let iterable = {};
                iterable[Symbol.iterator] = function() {
                  return { next: function() { return result; } };
                };
                function* g() { yield* iterable; }
                let step = g().next();
                (step === result) + ',' + step.done
                """;
        assertEquals("true,undefined", str(source));
    }

    // an inner iterator without a throw method is closed and the protocol violation is a TypeError
    @Test
    public void delegationWithoutThrowMethodClosesAndThrowsTypeError() {
        final var source = """
                let log = [];
                let iterable = {};
                iterable[Symbol.iterator] = function() {
                  return {
                    next: function() { return { value: 1, done: false }; },
                    return: function() { log.push('return'); return {}; }
                  };
                };
                function* g() { try { yield* iterable; } catch (e) { log.push(e instanceof TypeError); } }
                let it = g();
                it.next();
                it.throw('boom');
                log.join('|')
                """;
        assertEquals("return|true", str(source));
    }
}
