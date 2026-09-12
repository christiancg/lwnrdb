package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class IntrinsicsWrapperTest {
    private static String run(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static Intrinsics intrinsics() {
        return new Intrinsics((fn, thisArg, args) -> ((JsNativeFunction) fn).invoke(thisArg, args), null,
                new org.techhouse.simplejs.internal.EventLoop(), (_, _, _) -> JsUndefined.getInstance());
    }

    // A raw number is no longer "wrong" for Array.prototype methods (ToObject boxes it into an empty
    // array-like), so undefined - which ToObject rejects - stands in as a genuinely incompatible receiver.
    @Test
    public void test_wrong_receiver_throws_type_error() {
        final var realm = intrinsics();
        final var push = (JsNativeFunction) realm.arrayProto.get("push");
        final var error = assertThrows(TypeErrorException.class,
                () -> push.invoke(JsUndefined.getInstance(), List.of(new JsNumber(2))));
        assertTrue(error.getMessage().startsWith("Array.prototype.push"), error.getMessage());
        final var toFixed = (JsNativeFunction) realm.numberProto.get("toFixed");
        assertThrows(TypeErrorException.class, () -> toFixed.invoke(new JsString("a"), List.of()));
        final var mapGet = (JsNativeFunction) realm.mapProto.get("get");
        assertThrows(TypeErrorException.class, () -> mapGet.invoke(new JsObject(), List.of()));
    }

    @Test
    public void test_boolean_prototype_valueof_and_incompatible_receiver() {
        assertTrue(bool("(true).valueOf() === true"));
        assertEquals("true", run("(true).toString()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Boolean.prototype.valueOf.call(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Boolean.prototype.toString.call(5)"));
    }

    @Test
    public void test_boolean_wrapper_unwraps() {
        assertTrue(bool("(new Boolean(true)).valueOf() === true"));
        assertEquals("true", run("(new Boolean(true)).toString()"));
    }

    @Test
    public void test_number_wrapper_unwraps() {
        assertEquals("5.00", run("(new Number(5)).toFixed(2)"));
    }

    // String.prototype methods are spec-generic (ToString the receiver) and only reject null/undefined
    // (RequireObjectCoercible), unlike every other family here.
    @Test
    public void test_incompatible_receiver_throws_for_every_prototype_family() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("String.prototype.charAt.call(null, 0)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("BigInt.prototype.toString.call(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Symbol.prototype.toString.call(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("RegExp.prototype.test.call(5, 'a')"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Promise.prototype.then.call(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Set.prototype.has.call(5, 1)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Date.prototype.getTime.call(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("ArrayBuffer.prototype.slice.call(5, 0)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("DataView.prototype.getUint8.call(5, 0)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Int8Array.prototype.fill.call(5, 1)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Uint8Array.prototype.toBase64.call(new Int8Array(1))"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Error.prototype.toString.call(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Function.prototype.call.call(5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let g = (function*(){})(); Object.getPrototypeOf(g).next.call(5)"));
    }

    // AsyncGenerator.prototype.{next,return,throw} build their PromiseCapability before the brand check
    // (spec IfAbruptRejectPromise), so an incompatible receiver rejects rather than throwing synchronously.
    @Test
    public void test_async_generator_incompatible_receiver_rejects_instead_of_throwing() {
        assertEquals("caught", firstLogEntry("""
                let g = (async function*(){})();
                async function check() {
                    try {
                        await Object.getPrototypeOf(g).next.call(5);
                        log.push('no-throw');
                    } catch (e) {
                        log.push(e instanceof TypeError ? 'caught' : 'wrong-error');
                    }
                }
                check();
                """));
    }

    @Test
    public void test_async_disposable_stack_dispose_async_incompatible_receiver_rejects() {
        assertEquals("caught", firstLogEntry("""
                async function check() {
                    try {
                        await AsyncDisposableStack.prototype.disposeAsync.call({});
                        log.push('no-throw');
                    } catch (e) {
                        log.push(e instanceof TypeError ? 'caught' : 'wrong-error');
                    }
                }
                check();
                """));
    }

    private static String firstLogEntry(String source) {
        final var array = (JsArray) Interpreter.run("let log = [];\n" + source + "\nlog");
        return ((JsString) array.get(0)).getValue();
    }

    @Test
    public void test_string_prototype_methods_coerce_non_string_receiver() {
        assertEquals("5", run("String.prototype.charAt.call(5, 0)"));
        assertEquals("[object Object]", run("String.prototype.trim.call({})"));
    }

    @Test
    public void test_symbol_subclass_unwraps() {
        // The Symbol constructor is spec'd to reject any invocation via `new` - including a subclass's
        // super() call, which still carries the active new.target through to it.
        assertThrows(TypeErrorException.class,
                () -> run("class S extends Symbol { constructor(d) { super(d); } } new S('x').toString()"));
    }

    @Test
    public void prototypesCarrySymbolToStringTag() {
        assertEquals("Map", run("Map.prototype[Symbol.toStringTag]"));
        assertEquals("Set", run("Set.prototype[Symbol.toStringTag]"));
        assertEquals("WeakMap", run("WeakMap.prototype[Symbol.toStringTag]"));
        assertEquals("ArrayBuffer", run("ArrayBuffer.prototype[Symbol.toStringTag]"));
        assertEquals("DataView", run("DataView.prototype[Symbol.toStringTag]"));
        assertEquals("BigInt", run("BigInt.prototype[Symbol.toStringTag]"));
        assertEquals("Math", run("Math[Symbol.toStringTag]"));
        assertEquals("JSON", run("JSON[Symbol.toStringTag]"));
        assertEquals("Reflect", run("Reflect[Symbol.toStringTag]"));
        assertEquals("Int8Array", run("Object.prototype.toString.call(new Int8Array(1)).slice(8, -1)"));
        assertTrue(bool("Object.getOwnPropertyDescriptor(Map.prototype, Symbol.toStringTag).writable === false"));
        assertTrue(bool("Object.getOwnPropertyDescriptor(Map.prototype, Symbol.toStringTag).configurable === true"));
        assertTrue(bool("Array.prototype[Symbol.unscopables].values === true"));
        assertTrue(bool("Object.getPrototypeOf(Array.prototype[Symbol.unscopables]) === null"));
    }

    @Test
    public void numberStringBooleanPrototypesHavePrimitiveSlots() {
        assertEquals("0", run("Number.prototype.toString()"));
        assertEquals(0, num("Number.prototype.valueOf()"));
        assertEquals("", run("String.prototype.toString()"));
        assertEquals("false", run("Boolean.prototype.toString()"));
        assertTrue(bool("String.prototype.toString !== Object.prototype.toString"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("String.prototype.toString.call(1)"));
    }

    @Test
    public void frozenObjectsRejectSymbolKeyedWrites() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const s = Symbol('s');
                const o = {};
                o[s] = 1;
                Object.freeze(o);
                o[s] = 2;
                """));
        assertEquals(5, num("""
                const s = Symbol('s');
                const o = {};
                Object.defineProperty(o, s, { value: 1, writable: false, configurable: true });
                Object.defineProperty(o, s, { value: 5 });
                o[s]
                """));
    }

    @Test
    public void stringWrapperOwnsItsCodeUnits() {
        final var wrapper = (JsObject) intrinsics().toObject(new JsString("ab"));
        assertEquals(List.of("0", "1", "length"), List.copyOf(wrapper.keys()));
        assertEquals("a", ((JsString) wrapper.get("0")).getValue());
        assertEquals(new JsObject.PropertyFlags(false, true, false), wrapper.getFlags("0"));
        assertEquals(2, ((JsNumber) wrapper.get("length")).getValue());
        assertEquals(new JsObject.PropertyFlags(false, false, false), wrapper.getFlags("length"));
    }

    @Test
    public void stringGenericDelegationRejectsNonCallableWellKnownSymbolMethod() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var searchValue = { [Symbol.replace]: {} };
                ''.replaceAll.call('x', searchValue, 'y')
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var separator = { [Symbol.split]: 42 };
                ''.split.call('x', separator)
                """));
    }
}
