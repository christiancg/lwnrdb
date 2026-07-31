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
}
