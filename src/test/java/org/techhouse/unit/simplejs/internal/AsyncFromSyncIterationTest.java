package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;

// %AsyncFromSyncIteratorPrototype%: for-await over a plain sync iterable, including the failure
// modes that close the sync iterator before the rejection propagates.
public class AsyncFromSyncIterationTest {
    private static String joined(String source) {
        final var array = (JsArray) Interpreter.run(source);
        final var sb = new StringBuilder();
        for (var i = 0; i < array.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(JsCoercion.toStr(array.get(i)));
        }
        return sb.toString();
    }

    private static String drive(String prelude, String body) {
        return driveTail(prelude, body, "");
    }

    private static String driveTail(String prelude, String body, String tail) {
        return joined("let out = [];\n" + prelude + "\nasync function main() {\n  try {\n" + body
                + "\n  } catch (e) { out.push('E:' + e.name); }\n" + tail + "\n}\nmain();\nout\n");
    }

    @Test
    public void forAwaitConsumesAPlainSyncIterable() {
        final var prelude = """
                const src = { [Symbol.iterator]() {
                    let i = 0;
                    return { next: () => i < 3 ? { value: ++i, done: false } : { value: undefined, done: true } };
                } };
                """;
        assertEquals("1,2,3,done", drive(prelude, "for await (const x of src) out.push(x); out.push('done');"));
    }

    // AsyncFromSyncIteratorContinuation awaits only the value, so a promise-valued step resolves
    @Test
    public void forAwaitAwaitsEachSyncStepValue() {
        final var prelude = """
                const src = { [Symbol.iterator]() {
                    let i = 0;
                    return { next: () => i < 2
                        ? { value: Promise.resolve(++i * 10), done: false }
                        : { value: undefined, done: true } };
                } };
                """;
        assertEquals("10,20,done", drive(prelude, "for await (const x of src) out.push(x); out.push('done');"));
    }

    @Test
    public void forAwaitRejectsANonIterableSource() {
        assertEquals("E:TypeError", drive("const src = {};", "for await (const x of src) out.push(x);"));
        assertEquals("E:TypeError", drive("const src = 7;", "for await (const x of src) out.push(x);"));
    }

    @Test
    public void forAwaitRejectsASymbolIteratorReturningANonObject() {
        final var prelude = "const src = { [Symbol.iterator]() { return 1; } };";
        assertEquals("E:TypeError", drive(prelude, "for await (const x of src) out.push(x);"));
    }

    @Test
    public void forAwaitRejectsAnIteratorWithoutNext() {
        final var prelude = "const src = { [Symbol.iterator]() { return {}; } };";
        assertEquals("E:TypeError", drive(prelude, "for await (const x of src) out.push(x);"));
    }

    @Test
    public void forAwaitRejectsASyncStepThatIsNotAnObject() {
        final var prelude = "const src = { [Symbol.iterator]() { return { next: () => 1 }; } };";
        assertEquals("E:TypeError", drive(prelude, "for await (const x of src) out.push(x);"));
    }

    @Test
    public void forAwaitPropagatesAThrowingDoneGetter() {
        final var prelude = """
                const src = { [Symbol.iterator]() {
                    return { next: () => ({ get done() { throw new RangeError('boom'); } }) };
                } };
                """;
        assertEquals("E:RangeError", drive(prelude, "for await (const x of src) out.push(x);"));
    }

    @Test
    public void forAwaitPropagatesAThrowingValueGetter() {
        final var prelude = """
                const src = { [Symbol.iterator]() {
                    return { next: () => ({ done: false, get value() { throw new RangeError('boom'); } }) };
                } };
                """;
        assertEquals("E:RangeError", drive(prelude, "for await (const x of src) out.push(x);"));
    }

    // step 8 of AsyncFromSyncIteratorContinuation: a rejected value on a not-done step closes the
    // sync iterator before the rejection reaches the consumer
    @Test
    public void aRejectedStepValueClosesTheSyncIterator() {
        final var prelude = """
                let closed = false;
                const src = { [Symbol.iterator]() {
                    return {
                        next: () => ({ value: Promise.reject(new RangeError('nope')), done: false }),
                        return() { closed = true; return {}; }
                    };
                } };
                """;
        assertEquals("E:RangeError,true",
                driveTail(prelude, "for await (const x of src) out.push(x);", "out.push(closed);"));
    }

    @Test
    public void aThrowingReturnDuringCloseDoesNotMaskTheOriginalRejection() {
        final var prelude = """
                const src = { [Symbol.iterator]() {
                    return {
                        next: () => ({ value: Promise.reject(new RangeError('nope')), done: false }),
                        return() { throw new TypeError('from return'); }
                    };
                } };
                """;
        assertEquals("E:RangeError", drive(prelude, "for await (const x of src) out.push(x);"));
    }

    @Test
    public void aRejectedValueOnADoneStepDoesNotCloseTheSyncIterator() {
        final var prelude = """
                let closed = false;
                const src = { [Symbol.iterator]() {
                    return {
                        next: () => ({ value: Promise.reject(new RangeError('nope')), done: true }),
                        return() { closed = true; return {}; }
                    };
                } };
                """;
        assertEquals("E:RangeError,false",
                driveTail(prelude, "for await (const x of src) out.push(x);", "out.push(closed);"));
    }

    @Test
    public void breakingOutOfForAwaitClosesTheSyncIterator() {
        final var prelude = """
                let closed = false;
                const src = { [Symbol.iterator]() {
                    let i = 0;
                    return { next: () => ({ value: ++i, done: false }), return() { closed = true; return {}; } };
                } };
                """;
        assertEquals("1,true", drive(prelude, "for await (const x of src) { out.push(x); break; } out.push(closed);"));
    }

    @Test
    public void breakingOutOfForAwaitToleratesAnIteratorWithoutReturn() {
        final var prelude = """
                const src = { [Symbol.iterator]() {
                    let i = 0;
                    return { next: () => ({ value: ++i, done: false }) };
                } };
                """;
        assertEquals("1,ok", drive(prelude, "for await (const x of src) { out.push(x); break; } out.push('ok');"));
    }

    @Test
    public void anAsyncIteratorStepMustResolveToAnObject() {
        final var prelude = """
                const src = { [Symbol.asyncIterator]() {
                    return { next: () => Promise.resolve(1) };
                } };
                """;
        assertEquals("E:TypeError", drive(prelude, "for await (const x of src) out.push(x);"));
    }

    @Test
    public void aPresentButNonCallableAsyncIteratorIsATypeError() {
        assertEquals("E:TypeError",
                drive("const src = { [Symbol.asyncIterator]: 1 };", "for await (const x of src) out.push(x);"));
    }

    @Test
    public void anAsyncIteratorTakesPrecedenceOverTheSyncOne() {
        final var prelude = """
                const src = {
                    [Symbol.iterator]() { throw new RangeError('sync used'); },
                    [Symbol.asyncIterator]() {
                        let i = 0;
                        return { next: () => Promise.resolve(i < 2
                            ? { value: ++i, done: false }
                            : { value: undefined, done: true }) };
                    }
                };
                """;
        assertEquals("1,2,done", drive(prelude, "for await (const x of src) out.push(x); out.push('done');"));
    }
}
