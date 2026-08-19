package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
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

    // A descriptor like {get: undefined, set: undefined} is still a genuine accessor property per
    // spec, not a data property - defineAccessor must register the key even with neither side
    // present, and getAccessorGetter/Setter must keep reporting null (not a callable) for each
    // absent side, since callers rely on that null to know there is nothing to invoke.
    @Test
    public void test_accessor_with_both_sides_null_is_a_genuine_accessor() {
        final var table = new PropertyTable();
        table.defineAccessor("prop", null, null);
        assertTrue(table.hasAccessor("prop"));
        assertNull(table.getAccessorGetter("prop"));
        assertNull(table.getAccessorSetter("prop"));
        assertFalse(table.has("prop"));
        assertTrue(table.keys().contains("prop"));
    }

    // Converting a no-sides accessor back into a data property (clearAccessor, mirroring
    // OrdinaryProperties' data-branch) must drop the accessor registration, or hasAccessor would
    // keep reporting true for what is now a plain data property.
    @Test
    public void test_clear_accessor_drops_the_no_sides_marker() {
        final var table = new PropertyTable();
        table.defineAccessor("prop", null, null);
        table.clearAccessor("prop");
        assertFalse(table.hasAccessor("prop"));
        table.defineValue("prop", new JsNumber(1));
        assertTrue(table.has("prop"));
    }

    // delete() must purge the no-sides accessor marker the same way it purges a real getter/setter,
    // or a deleted-then-recreated data property at the same key would still read as an accessor.
    @Test
    public void test_delete_purges_the_no_sides_accessor_marker() {
        final var table = new PropertyTable();
        table.defineAccessor("prop", null, null);
        assertTrue(table.delete("prop"));
        assertFalse(table.hasAccessor("prop"));
    }

    // The symbol-keyed path mirrors the string-keyed one: {get: undefined, set: undefined} on a
    // symbol key is a real accessor too, and clearSymbolAccessor/isNotDeleteSymbol must both drop it.
    @Test
    public void test_symbol_accessor_with_both_sides_null_is_a_genuine_accessor() {
        final var table = new PropertyTable();
        final var symbol = new JsSymbol("x");
        table.defineSymbolAccessor(symbol, null, null);
        assertTrue(table.hasSymbolAccessor(symbol));
        assertNull(table.getSymbolAccessorGetter(symbol));
        assertNull(table.getSymbolAccessorSetter(symbol));
        assertTrue(table.symbolKeys().contains(symbol));
        table.clearSymbolAccessor(symbol);
        assertFalse(table.hasSymbolAccessor(symbol));
    }

    @Test
    public void test_delete_symbol_purges_the_no_sides_accessor_marker() {
        final var table = new PropertyTable();
        final var symbol = new JsSymbol("x");
        table.defineSymbolAccessor(symbol, null, null);
        assertFalse(table.isNotDeleteSymbol(symbol));
        assertFalse(table.hasSymbolAccessor(symbol));
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

    // Every value type that carries a table, so the extensibility default is asserted for all of them
    // at once rather than being inferred from one representative.
    private static Map<String, JsValue> tableOwningTypes() {
        final var buffer = new JsArrayBuffer(8);
        final Map<String, JsValue> types = new LinkedHashMap<>();
        types.put("JsObject", new JsObject());
        types.put("JsArray", new JsArray());
        types.put("JsFunction", new JsFunction("f", List.of(), null, false, false, false, false, Environment.global()));
        types.put("JsNativeFunction", new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance()));
        types.put("JsClass", new JsClass("C", null, Environment.global()));
        types.put("JsPromise", new JsPromise(new EventLoop()));
        types.put("JsTypedArray", new JsTypedArray(JsTypedArray.Kind.INT8, buffer, 0, 8));
        types.put("JsArrayBuffer", buffer);
        types.put("JsDataView", new JsDataView(buffer, 0, 8));
        types.put("JsDate", new JsDate(0));
        types.put("JsMap", new JsMap());
        types.put("JsSet", new JsSet());
        types.put("JsRegExp", new JsRegExp("a", "", Pattern.compile("a")));
        types.put("JsArguments", new JsArguments(List.of(), null, null));
        types.put("JsGlobalObject", new JsGlobalObject(Environment.global()));
        types.put("JsGenerator", new JsGenerator(null));
        types.put("JsAsyncGenerator", new JsAsyncGenerator(null));
        return types;
    }

    // A freshly built value of every table-owning type is extensible even though nothing has been
    // written to its table yet - the integrity-level checks read extensibility before any key.
    @Test
    public void test_untouched_table_is_extensible_on_every_owning_type() {
        final var types = tableOwningTypes();
        assertEquals(17, types.size());
        for (final var entry : types.entrySet()) {
            assertNotNull(entry.getValue().ownProperties(), entry.getKey());
            assertTrue(entry.getValue().isExtensible(), entry.getKey());
            assertTrue(entry.getValue().ownProperties().isExtensible(), entry.getKey());
            // Nothing has been sealed, so no type reports itself sealed or frozen out of the box.
            assertFalse(entry.getValue().ownProperties().isSealed(), entry.getKey());
            assertFalse(entry.getValue().ownProperties().isFrozen(), entry.getKey());
        }
    }

    // Primitives answer a null table, which is what identifies them at every property choke point.
    @Test
    public void test_primitives_have_no_table_and_are_not_extensible() {
        for (final var primitive : List.of(new JsNumber(1), new JsString("s"), JsBoolean.TRUE,
                JsUndefined.getInstance(), new JsSymbol("s"))) {
            assertNull(primitive.ownProperties(), primitive.getType().name());
            assertFalse(primitive.isExtensible(), primitive.getType().name());
        }
    }

    // freeze/seal have to materialise a descriptor entry for every existing key: without it the key
    // keeps reporting the all-true default and the object never reads back as frozen.
    @Test
    public void test_freeze_and_seal_force_flag_allocation() {
        final var sealed = new PropertyTable();
        sealed.set("a", new JsNumber(1));
        assertEquals(JsObject.PropertyFlags.DEFAULT, sealed.getFlags("a"));
        sealed.seal();
        assertFalse(sealed.getFlags("a").configurable());
        assertTrue(sealed.getFlags("a").writable());
        final var frozen = new PropertyTable();
        frozen.set("a", new JsNumber(1));
        frozen.freeze();
        assertFalse(frozen.getFlags("a").writable());
        assertFalse(frozen.getFlags("a").configurable());
    }

    // An extensible table is never sealed or frozen whatever its keys say, which is why the
    // extensibility test precedes the property walk.
    @Test
    public void test_extensible_table_is_never_sealed_or_frozen() {
        final var table = new PropertyTable();
        table.set("a", new JsNumber(1));
        table.setFlags("a", HIDDEN);
        assertTrue(table.isExtensible());
        assertFalse(table.isSealed());
        assertFalse(table.isFrozen());
    }

    // freeze reaches symbol keys too, so a frozen object refuses o[sym] = v.
    @Test
    public void test_freeze_covers_symbol_keys() {
        final var table = new PropertyTable();
        final var symbol = new JsSymbol("s");
        table.setSymbol(symbol, new JsNumber(1));
        table.freeze();
        assertFalse(table.getSymbolFlags(symbol).writable());
        assertFalse(table.getSymbolFlags(symbol).configurable());
        assertFalse(table.setSymbol(symbol, new JsNumber(2)));
    }

    // Symbol-keyed flags round-trip through the descriptor protocol exactly as string-keyed ones do.
    @Test
    public void test_symbol_flags_round_trip_through_descriptors() {
        final var object = new JsObject();
        final var symbol = new JsSymbol("tag");
        object.setSymbol(symbol, new JsString("v"));
        object.setSymbolFlags(symbol, new JsObject.PropertyFlags(false, false, true));
        final var descriptor = object.getOwnProperty(symbol);
        assertNotNull(descriptor);
        assertFalse(descriptor.writableOr(true));
        assertFalse(descriptor.enumerableOr(true));
        assertTrue(descriptor.configurableOr(false));
        assertFalse(object.setSymbol(symbol, new JsString("w")));
        assertEquals("v", ((JsString) object.getSymbol(symbol)).getValue());
    }

    // A builtin's statics, plus its length/name metadata, are real own keys - the integrity-level
    // property walk has to have something to inspect for Object.isFrozen(Object) to answer false.
    @Test
    public void test_native_function_statics_and_metadata_are_own_keys() {
        final var fn = new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance());
        fn.setProperty("helper", new JsNumber(1));
        final var keys = fn.ownPropertyKeys().stream().map(key -> ((JsString) key).getValue()).toList();
        assertTrue(keys.contains("length"), keys.toString());
        assertTrue(keys.contains("name"), keys.toString());
        assertTrue(keys.contains("helper"), keys.toString());
        assertFalse(fn.ownProperties().isEnumerable("helper"));
        assertTrue(fn.ownProperties().getFlags("helper").configurable());
    }

    // Object.isFrozen/isSealed answer from extensibility first, so a builtin - extensible, with own
    // statics - is neither, and Object.isExtensible agrees with them.
    @Test
    public void test_builtins_are_extensible_and_never_frozen() {
        for (final var target : List.of("Object", "Math", "Date", "globalThis", "Array.prototype")) {
            assertSame(JsBoolean.FALSE, Interpreter.run("Object.isFrozen(" + target + ")"), target);
            assertSame(JsBoolean.FALSE, Interpreter.run("Object.isSealed(" + target + ")"), target);
            assertSame(JsBoolean.TRUE, Interpreter.run("Object.isExtensible(" + target + ")"), target);
        }
    }

    // A primitive is trivially frozen and sealed, and the integrity-level setters return it unchanged.
    @Test
    public void test_integrity_level_of_a_primitive() {
        assertSame(JsBoolean.TRUE, Interpreter.run("Object.isFrozen(1)"));
        assertSame(JsBoolean.TRUE, Interpreter.run("Object.isSealed('s')"));
        assertSame(JsBoolean.TRUE, Interpreter.run("Object.freeze(1) === 1"));
        assertSame(JsBoolean.TRUE, Interpreter.run("Object.seal(true) === true"));
        assertSame(JsBoolean.TRUE, Interpreter.run("Object.preventExtensions(1) === 1"));
    }

    // seal/freeze run through [[DefineOwnProperty]], so they reach an exotic key set: a Date's own
    // properties, a function's statics and an array's indices plus its length are all covered.
    @Test
    public void test_integrity_level_reaches_exotic_receivers() {
        assertSame(JsBoolean.TRUE, Interpreter
                .run("const d = new Date(0); d.x = 1;" + " Object.seal(d); Object.isSealed(d) && !Object.isFrozen(d)"));
        assertSame(JsBoolean.TRUE, Interpreter.run("function f() {} f.x = 1; Object.freeze(f); Object.isFrozen(f)"));
        assertSame(JsBoolean.TRUE,
                Interpreter.run("const a = [1, 2]; Object.freeze(a);"
                        + " Object.isFrozen(a) && !Object.getOwnPropertyDescriptor(a, '0').writable"
                        + " && !Object.getOwnPropertyDescriptor(a, 'length').writable"));
        assertSame(JsBoolean.TRUE,
                Interpreter.run("const m = new Map(); m.x = 1; Object.freeze(m); Object.isFrozen(m)"));
        // An empty non-extensible object has nothing left to mutate; an array still owns a writable
        // length, so preventExtensions alone does not freeze it.
        assertSame(JsBoolean.TRUE, Interpreter.run("Object.isFrozen(Object.preventExtensions({}))"));
        assertSame(JsBoolean.FALSE, Interpreter.run("Object.isFrozen(Object.preventExtensions([]))"));
        assertSame(JsBoolean.TRUE, Interpreter.run("Object.isFrozen(Object.freeze([]))"));
    }
}
