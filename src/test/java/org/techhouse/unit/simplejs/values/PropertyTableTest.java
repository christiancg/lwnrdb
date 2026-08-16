package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.PropertyTable;

public class PropertyTableTest {
    private static final JsObject.PropertyFlags HIDDEN = new JsObject.PropertyFlags(false, false, false);

    // set followed by setFlags preserves the flags the key was given
    @Test
    public void test_data_property_round_trips_flags() {
        final var table = new PropertyTable();
        table.set("a", new JsNumber(1));
        table.setFlags("a", HIDDEN);
        assertEquals(HIDDEN, table.getFlags("a"));
        assertFalse(table.isWritable("a"));
        assertFalse(table.isEnumerable("a"));
        assertTrue(table.isNotConfigurable("a"));
    }

    // the descriptor map is sparse: an absent entry means all-true
    @Test
    public void test_absent_descriptor_defaults_to_all_true() {
        final var table = new PropertyTable();
        assertEquals(JsObject.PropertyFlags.DEFAULT, table.getFlags("missing"));
        table.set("a", new JsNumber(1));
        assertEquals(JsObject.PropertyFlags.DEFAULT, table.getFlags("a"));
    }

    // OrdinaryOwnPropertyKeys: canonical array indices ascending, then insertion order
    @Test
    public void test_key_order_is_canonical_index_then_insertion() {
        final var table = new PropertyTable();
        table.set("b", new JsNumber(1));
        table.set("2", new JsNumber(2));
        table.defineAccessor("a", new JsString("getter"), null);
        table.set("0", new JsNumber(3));
        assertEquals(List.of("0", "2", "b", "a"), List.copyOf(table.keys()));
    }

    // an internal set must not install a data property that shadows an existing accessor
    @Test
    public void test_accessor_key_is_not_shadowed_by_internal_set() {
        final var table = new PropertyTable();
        final var getter = new JsString("getter");
        table.defineAccessor("a", getter, null);
        assertFalse(table.set("a", new JsNumber(1)));
        assertTrue(table.hasAccessor("a"));
        assertSame(getter, table.getAccessorGetter("a"));
        assertFalse(table.has("a"));
    }

    // a symbol accessor is registered as an own symbol key
    @Test
    public void test_symbol_accessor_is_visible_to_symbol_keys() {
        final var table = new PropertyTable();
        final var symbol = new JsSymbol("x");
        table.defineSymbolAccessor(symbol, new JsString("getter"), null);
        assertTrue(table.symbolKeys().contains(symbol));
        assertTrue(table.hasSymbolAccessor(symbol));
    }

    // a non-writable key rejects a write and a non-extensible table rejects a new key
    @Test
    public void test_non_writable_write_and_new_key_on_non_extensible_are_rejected() {
        final var table = new PropertyTable();
        table.set("a", new JsNumber(1));
        table.setFlags("a", HIDDEN);
        assertFalse(table.set("a", new JsNumber(2)));
        assertEquals(1, ((JsNumber) table.get("a")).getValue());
        table.preventExtensions();
        assertFalse(table.set("b", new JsNumber(1)));
        assertFalse(table.has("b"));
    }

    // an empty non-extensible table is both sealed and frozen
    @Test
    public void test_freeze_seal_prevent_extensions_interact() {
        final var empty = new PropertyTable();
        assertFalse(empty.isSealed());
        empty.preventExtensions();
        assertTrue(empty.isSealed());
        assertTrue(empty.isFrozen());

        final var table = new PropertyTable();
        table.set("a", new JsNumber(1));
        table.seal();
        assertTrue(table.isSealed());
        assertFalse(table.isFrozen());
        table.freeze();
        assertTrue(table.isFrozen());
        assertFalse(table.set("a", new JsNumber(2)));
    }

    // a non-configurable key rejects delete; a configurable one drops value, flags and accessors
    @Test
    public void test_delete_honours_configurable() {
        final var table = new PropertyTable();
        table.set("a", new JsNumber(1));
        table.setFlags("a", HIDDEN);
        assertFalse(table.delete("a"));
        table.setFlags("a", JsObject.PropertyFlags.DEFAULT);
        assertTrue(table.delete("a"));
        assertFalse(table.has("a"));
        assertSame(JsUndefined.getInstance(), table.get("a"));
        assertNull(table.getAccessorGetter("a"));
    }

    // symbol data properties carry their own flags and honour configurable on delete
    @Test
    public void test_symbol_flags_and_delete() {
        final var table = new PropertyTable();
        final var symbol = new JsSymbol("x");
        table.setSymbol(symbol, new JsNumber(1));
        assertEquals(JsObject.PropertyFlags.DEFAULT, table.getSymbolFlags(symbol));
        table.setSymbolFlags(symbol, HIDDEN);
        assertTrue(table.isNotDeleteSymbol(symbol));
        table.setSymbolFlags(symbol, JsObject.PropertyFlags.DEFAULT);
        assertFalse(table.isNotDeleteSymbol(symbol));
        assertFalse(table.symbolKeys().contains(symbol));
    }
}
