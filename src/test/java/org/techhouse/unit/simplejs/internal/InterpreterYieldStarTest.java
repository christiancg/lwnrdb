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

    @Test
    public void delegationLooksUpNextOnTheIteratorObject() {
        assertEquals(1, num());
    }

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

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
    public void asyncDelegationClosesTheSyncIteratorWhenAValueRejects() {
        final var source = """
                let log = [];
                const iterable = {
                  [Symbol.iterator]() {
                    return {
                      next() { return { value: Promise.reject('boom'), done: false }; },
                      return() { log.push('return'); return {}; }
                    };
                  }
                };
                async function* g() { yield* iterable; }
                g().next().then(
                  function() { log.push('fulfilled'); },
                  function(e) { log.push('rejected:' + e); }
                );
                log
                """;
        assertEquals("return|rejected:boom", joinedLog(source));
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
    public void asyncDelegationDoesNotCloseOnADoneStep() {
        final var source = """
                let log = [];
                const iterable = {
                  [Symbol.iterator]() {
                    return {
                      next() { return { value: Promise.reject('boom'), done: true }; },
                      return() { log.push('return'); return {}; }
                    };
                  }
                };
                async function* g() { yield* iterable; }
                g().next().then(
                  function() { log.push('fulfilled'); },
                  function(e) { log.push('rejected:' + e); }
                );
                log
                """;
        assertEquals("rejected:boom", joinedLog(source));
    }

    private static String joinedLog(String source) {
        final var array = (org.techhouse.simplejs.values.JsArray) Interpreter.run(source);
        final var sb = new StringBuilder();
        for (var i = 0; i < array.length(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(org.techhouse.simplejs.internal.JsCoercion.toStr(array.get(i)));
        }
        return sb.toString();
    }
}
