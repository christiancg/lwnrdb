package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

public class SymbolBuiltinsTest {
    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_symbol_uniqueness() {
        assertTrue(bool("Symbol('x') !== Symbol('x')"));
    }

    @Test
    public void test_symbol_typeof() {
        assertEquals("symbol", str("typeof Symbol('x')"));
    }

    @Test
    public void test_symbol_for_registry() {
        assertTrue(bool("Symbol.for('shared') === Symbol.for('shared')"));
        assertTrue(bool("Symbol.for('a') !== Symbol.for('b')"));
    }

    @Test
    public void test_symbol_key_for() {
        assertEquals("registered", str("Symbol.keyFor(Symbol.for('registered'))"));
    }

    @Test
    public void test_symbol_key_for_unregistered() {
        assertEquals("undefined", str("typeof Symbol.keyFor(Symbol('local'))"));
    }

    @Test
    public void test_well_known_iterator() {
        assertEquals("symbol", str("typeof Symbol.iterator"));
        assertTrue(bool("Symbol.iterator === Symbol.iterator"));
    }

    @Test
    public void test_well_known_symbols_registered() {
        assertEquals("symbol", str("typeof Symbol.hasInstance"));
        assertEquals("symbol", str("typeof Symbol.toStringTag"));
        assertEquals("symbol", str("typeof Symbol.match"));
        assertEquals("symbol", str("typeof Symbol.replace"));
        assertEquals("symbol", str("typeof Symbol.search"));
        assertEquals("symbol", str("typeof Symbol.split"));
        assertTrue(bool("Symbol.match !== Symbol.replace && Symbol.search !== Symbol.split"));
        assertTrue(bool("Symbol.hasInstance !== Symbol.toStringTag"));
    }

    @Test
    public void test_description_and_to_string() {
        assertEquals("x", str("Symbol('x').description"));
        assertEquals("Symbol(x)", str("Symbol('x').toString()"));
        assertEquals("Symbol()", str("Symbol().toString()"));
        assertTrue(bool("Symbol().description === undefined"));
        assertTrue(bool("const s = Symbol('y'); s.valueOf() === s"));
        assertTrue(bool("Symbol('x').nope === undefined"));
    }

    @Test
    public void test_new_well_known_symbols() {
        assertTrue(bool("typeof Symbol.matchAll === 'symbol'"));
        assertTrue(bool("typeof Symbol.isConcatSpreadable === 'symbol'"));
        assertTrue(bool("Symbol.matchAll === Symbol.matchAll"));
        assertTrue(bool("Symbol.matchAll !== Symbol.isConcatSpreadable"));
    }

    @Test
    public void test_symbol_match_all_hook() {
        assertEquals("got:abc", str("'abc'.matchAll({ [Symbol.matchAll](s) { return 'got:' + s } })"));
    }

    @Test
    public void descriptionIsAnAccessorOnThePrototype() {
        final var descriptor = "let d = Object.getOwnPropertyDescriptor(Symbol.prototype, 'description'); ";
        assertTrue(bool(descriptor + "typeof d.get === 'function' && d.set === undefined"));
        assertTrue(bool(descriptor + "d.enumerable === false && d.configurable === true"));
        assertEquals("get description",
                str("Object.getOwnPropertyDescriptor(" + "Symbol.prototype, 'description').get.name"));
        assertEquals("x", str("Symbol('x').description"));
        assertTrue(bool("Symbol().description === undefined"));
        assertTrue(bool("Symbol(undefined).description === undefined"));
        assertEquals("", str("Symbol('').description"));
    }

    @Test
    public void wellKnownSymbolsAreNonWritableAndNonConfigurable() {
        final var names = new String[]{"asyncDispose", "asyncIterator", "dispose", "hasInstance", "isConcatSpreadable",
                "iterator", "match", "matchAll", "replace", "search", "split", "toPrimitive", "toStringTag",
                "unscopables"};
        for (final var name : names) {
            final var descriptor = "let d = Object.getOwnPropertyDescriptor(Symbol, '" + name + "'); ";
            assertTrue(bool(descriptor + "d.writable === false"), name + " should not be writable");
            assertTrue(bool(descriptor + "d.enumerable === false"), name + " should not be enumerable");
            assertTrue(bool(descriptor + "d.configurable === false"), name + " should not be configurable");
        }
    }

    @Test
    public void symbolPrototypeMembersAcceptAWrapperReceiver() {
        assertEquals("x", str("Object(Symbol('x')).description"));
        assertEquals("Symbol(x)", str("Object(Symbol('x')).toString()"));
        assertTrue(bool("let s = Symbol('x'); Object(s).valueOf() === s"));
        assertTrue(bool("let s = Symbol('x'); Object(s)[Symbol.toPrimitive]() === s"));
        assertEquals("Symbol(x)", str("Symbol.prototype.toString.call(Object(Symbol('x')))"));
    }

    @Test
    public void symbolDescriptionRunsToString() {
        assertEquals("42", str("Symbol({ toString() { return '42' } }).description"));
        assertEquals("7", str("Symbol.keyFor(Symbol.for({ toString() { return '7' } }))"));
        assertEquals("1", str("Symbol(1).description"));
    }

    @Test
    public void symbolKeyForRejectsNonSymbols() {
        assertTrue(bool(threwTypeError("Symbol.keyFor(null)")));
        assertTrue(bool(threwTypeError("Symbol.keyFor('1')")));
        assertTrue(bool(threwTypeError("Symbol.keyFor(Object(Symbol('s')))")));
    }

    private static String threwTypeError(String expression) {
        return "(function() { try { " + expression
                + "; return false } catch (e) { return e instanceof TypeError } })()";
    }

    @Test
    public void symbolPrototypeHasToPrimitiveAndToStringTag() {
        assertTrue(bool("typeof Symbol.prototype[Symbol.toPrimitive] === 'function'"));
        assertTrue(bool("let s = Symbol('x'); s[Symbol.toPrimitive]() === s"));
        assertEquals("Symbol", str("Symbol.prototype[Symbol.toStringTag]"));
        final var descriptor = "let d = Object.getOwnPropertyDescriptor(Symbol.prototype, Symbol.toPrimitive); ";
        assertTrue(bool(descriptor + "d.writable === false && d.enumerable === false && d.configurable === true"));
    }

    // Symbol.for's registered symbol is CanBeHeldWeakly-ineligible: it lives in the registry for the
    // whole run, so it must not be usable as a WeakMap/WeakSet key, while a plain Symbol() still is.
    @Test
    public void registeredSymbolIsRejectedAsAWeakKeyButAPlainSymbolIsAccepted() {
        assertTrue(bool(threwTypeError("new WeakMap().set(Symbol.for('k'), 1)")));
        assertTrue(bool(threwTypeError("new WeakSet().add(Symbol.for('k'))")));
        assertTrue(bool("""
                (function() {
                    const s = Symbol('plain');
                    const m = new WeakMap();
                    m.set(s, 1);
                    return m.get(s) === 1;
                })()
                """));
        assertTrue(bool("""
                (function() {
                    const s = Symbol('plain');
                    const set = new WeakSet();
                    set.add(s);
                    return set.has(s);
                })()
                """));
    }

    @Test
    public void wellKnownSymbolsAreNotTreatedAsRegistered() {
        assertTrue(bool("""
                (function() {
                    const m = new WeakMap();
                    m.set(Symbol.iterator, 1);
                    return m.get(Symbol.iterator) === 1;
                })()
                """));
    }
}
