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

    // Symbol() creates a distinct symbol each call
    @Test
    public void test_symbol_uniqueness() {
        assertTrue(bool("Symbol('x') !== Symbol('x')"));
    }

    // typeof a symbol is "symbol"
    @Test
    public void test_symbol_typeof() {
        assertEquals("symbol", str("typeof Symbol('x')"));
    }

    // Symbol.for returns the same registered symbol for a given key
    @Test
    public void test_symbol_for_registry() {
        assertTrue(bool("Symbol.for('shared') === Symbol.for('shared')"));
        assertTrue(bool("Symbol.for('a') !== Symbol.for('b')"));
    }

    // Symbol.keyFor reverse-looks-up a registered symbol
    @Test
    public void test_symbol_key_for() {
        assertEquals("registered", str("Symbol.keyFor(Symbol.for('registered'))"));
    }

    // Symbol.keyFor of an unregistered symbol is undefined
    @Test
    public void test_symbol_key_for_unregistered() {
        assertEquals("undefined", str("typeof Symbol.keyFor(Symbol('local'))"));
    }

    // The well-known Symbol.iterator is a stable symbol value
    @Test
    public void test_well_known_iterator() {
        assertEquals("symbol", str("typeof Symbol.iterator"));
        assertTrue(bool("Symbol.iterator === Symbol.iterator"));
    }

    // The Phase 2 well-known symbols are registered as stable, distinct symbol values
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

    // A symbol exposes its description and a readable toString
    @Test
    public void test_description_and_to_string() {
        assertEquals("x", str("Symbol('x').description"));
        assertEquals("Symbol(x)", str("Symbol('x').toString()"));
        assertEquals("Symbol()", str("Symbol().toString()"));
        assertTrue(bool("Symbol().description === undefined"));
        assertTrue(bool("const s = Symbol('y'); s.valueOf() === s"));
        assertTrue(bool("Symbol('x').nope === undefined"));
    }

    // The two newly exposed well-known symbols have a stable identity
    @Test
    public void test_new_well_known_symbols() {
        assertTrue(bool("typeof Symbol.matchAll === 'symbol'"));
        assertTrue(bool("typeof Symbol.isConcatSpreadable === 'symbol'"));
        assertTrue(bool("Symbol.matchAll === Symbol.matchAll"));
        assertTrue(bool("Symbol.matchAll !== Symbol.isConcatSpreadable"));
    }

    // String.matchAll delegates to a Symbol.matchAll method on a plain object
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

    // Every well-known symbol is a non-writable, non-configurable own property of the constructor
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

    // Symbol.prototype members accept a wrapper receiver, not only a primitive symbol
    @Test
    public void symbolPrototypeMembersAcceptAWrapperReceiver() {
        assertEquals("x", str("Object(Symbol('x')).description"));
        assertEquals("Symbol(x)", str("Object(Symbol('x')).toString()"));
        assertTrue(bool("let s = Symbol('x'); Object(s).valueOf() === s"));
        assertTrue(bool("let s = Symbol('x'); Object(s)[Symbol.toPrimitive]() === s"));
        assertEquals("Symbol(x)", str("Symbol.prototype.toString.call(Object(Symbol('x')))"));
    }

    // Symbol() and Symbol.for run ToString over their argument, so user code decides the description
    @Test
    public void symbolDescriptionRunsToString() {
        assertEquals("42", str("Symbol({ toString() { return '42' } }).description"));
        assertEquals("7", str("Symbol.keyFor(Symbol.for({ toString() { return '7' } }))"));
        assertEquals("1", str("Symbol(1).description"));
    }

    // Symbol.keyFor rejects a non-symbol, including a symbol wrapper object
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
}
