package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.ArrayBuiltins;
import org.techhouse.simplejs.builtins.BigIntBuiltins;
import org.techhouse.simplejs.builtins.DateBuiltins;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.MapBuiltins;
import org.techhouse.simplejs.builtins.NumberBuiltins;
import org.techhouse.simplejs.builtins.ObjectProtoBuiltins;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.builtins.SetBuiltins;
import org.techhouse.simplejs.builtins.StringBuiltins;
import org.techhouse.simplejs.builtins.SymbolBuiltins;
import org.techhouse.simplejs.builtins.TypedArrayBuiltins;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public class IntrinsicsTest {
    private static Intrinsics intrinsics() {
        return new Intrinsics((fn, thisArg, args) -> ((JsNativeFunction) fn).invoke(thisArg, args), null,
                new org.techhouse.simplejs.internal.EventLoop(), (_, _, _) -> JsUndefined.getInstance());
    }

    // Every runtime value type resolves to a prototype, and anything else falls back to Object.prototype
    @Test
    public void test_proto_for_every_value_type() {
        final var realm = intrinsics();
        final var buffer = new JsArrayBuffer(8);
        final Map<JsValue, JsObject> expected = Map.ofEntries(Map.entry(new JsArray(), realm.arrayProto()),
                Map.entry(new JsString("a"), realm.stringProto()), Map.entry(new JsNumber(1), realm.numberProto()),
                Map.entry(JsBoolean.of(true), realm.booleanProto()),
                Map.entry(new JsBigInt(BigInteger.ONE), realm.bigintProto()),
                Map.entry(new JsSymbol("s"), realm.symbolProto()),
                Map.entry(RegexTranslator.compile("a", ""), realm.regexpProto()),
                Map.entry(new JsMap(false), realm.mapProto()), Map.entry(new JsSet(false), realm.setProto()),
                Map.entry(new JsDate(0), realm.dateProto()), Map.entry(buffer, realm.arrayBufferProto()),
                Map.entry(new JsDataView(buffer, 0, 8), realm.dataViewProto()),
                Map.entry(new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance()), realm.functionProto()),
                Map.entry(new JsObject(), realm.objectProto()),
                Map.entry(JsUndefined.getInstance(), realm.objectProto()));
        for (final var entry : expected.entrySet()) {
            assertSame(entry.getValue(), realm.protoFor(entry.getKey()),
                    () -> entry.getKey().getClass().getSimpleName());
        }
        for (final var kind : JsTypedArray.Kind.values()) {
            assertSame(realm.typedArrayProto(kind), realm.protoFor(new JsTypedArray(kind, buffer, 0, 0)));
        }
    }

    // Each family's NAMES list agrees exactly with the keys installed on its prototype
    @Test
    public void test_names_match_prototype_keys() {
        final var realm = intrinsics();
        assertKeys(realm.arrayProto(), ArrayBuiltins.NAMES);
        assertKeys(realm.stringProto(), StringBuiltins.NAMES);
        assertKeys(realm.numberProto(), NumberBuiltins.NAMES);
        assertKeys(realm.bigintProto(), BigIntBuiltins.NAMES);
        assertKeys(realm.symbolProto(), SymbolBuiltins.NAMES);
        assertKeys(realm.regexpProto(), RegexBuiltins.NAMES);
        assertKeys(realm.dateProto(), DateBuiltins.NAMES);
        assertKeys(realm.objectProto(), ObjectProtoBuiltins.NAMES);
        assertKeys(realm.functionProto(), FunctionProtoBuiltins.NAMES);
        assertKeys(realm.promiseProto(), org.techhouse.simplejs.builtins.PromiseBuiltins.PROTO_NAMES);
        assertKeys(realm.iteratorProto(), org.techhouse.simplejs.builtins.GeneratorBuiltins.PROTO_NAMES);
        assertKeys(realm.asyncIteratorProto(), org.techhouse.simplejs.builtins.GeneratorBuiltins.PROTO_NAMES);
        assertKeys(realm.arrayBufferProto(), TypedArrayBuiltins.BUFFER_NAMES);
        assertKeys(realm.dataViewProto(), TypedArrayBuiltins.VIEW_NAMES);
        assertKeys(realm.mapProto(), MapBuiltins.NAMES);
        assertKeys(realm.setProto(), SetBuiltins.NAMES);
    }

    private static void assertKeys(JsObject proto, List<String> names) {
        for (final var name : names) {
            assertTrue(proto.has(name), () -> "prototype is missing " + name);
            assertFalse(proto.isEnumerable(name), () -> name + " must be non-enumerable");
            assertTrue(proto.getFlags(name).configurable(), () -> name + " must be configurable");
        }
        for (final var key : proto.keys()) {
            assertTrue("constructor".equals(key) || "name".equals(key) || names.contains(key),
                    () -> "prototype has an unlisted key " + key);
        }
    }

    // Every NAMES entry resolves to a real method for a matching receiver
    @Test
    public void test_names_resolve_to_methods() {
        final var buffer = new JsArrayBuffer(8);
        for (final var name : ArrayBuiltins.NAMES) {
            assertNotNull(ArrayBuiltins.getMethod(new JsArray(), name, null, null), name);
        }
        for (final var name : StringBuiltins.NAMES) {
            assertNotNull(StringBuiltins.getMethod(new JsString("a"), name, null, null), name);
        }
        for (final var name : NumberBuiltins.NAMES) {
            assertNotNull(NumberBuiltins.getMethod(new JsNumber(1), name), name);
        }
        for (final var name : BigIntBuiltins.NAMES) {
            assertNotNull(BigIntBuiltins.getMethod(new JsBigInt(BigInteger.ONE), name), name);
        }
        for (final var name : SymbolBuiltins.NAMES) {
            assertNotNull(SymbolBuiltins.getMethod(new JsSymbol("s"), name), name);
        }
        for (final var name : RegexBuiltins.NAMES) {
            assertNotNull(RegexBuiltins.getMethod(RegexTranslator.compile("a", ""), name), name);
        }
        for (final var name : DateBuiltins.NAMES) {
            assertNotNull(DateBuiltins.getMethod(new JsDate(0), name), name);
        }
        for (final var name : ObjectProtoBuiltins.NAMES) {
            assertNotNull(ObjectProtoBuiltins.getMethod(new JsObject(), name, null), name);
        }
        for (final var name : FunctionProtoBuiltins.NAMES) {
            assertNotNull(FunctionProtoBuiltins.getMethod(new JsNativeFunction("f", (_, _) -> null), name, null), name);
        }
        for (final var name : TypedArrayBuiltins.BUFFER_NAMES) {
            assertNotNull(TypedArrayBuiltins.bufferMethod(buffer, name), name);
        }
        for (final var name : TypedArrayBuiltins.VIEW_NAMES) {
            assertNotNull(TypedArrayBuiltins.dataViewMethod(new JsDataView(buffer, 0, 8), name), name);
        }
        for (final var name : TypedArrayBuiltins.NAMES) {
            assertNotNull(
                    TypedArrayBuiltins.getMethod(new JsTypedArray(JsTypedArray.Kind.INT8, buffer, 0, 0), name, null),
                    name);
        }
        for (final var name : MapBuiltins.NAMES) {
            assertNotNull(MapBuiltins.getMethod(new JsMap(false), name, null), name);
        }
        for (final var name : SetBuiltins.NAMES) {
            assertNotNull(SetBuiltins.getMethod(new JsSet(false), name, null), name);
        }
    }

    // An unknown name is not resolved by any family
    @Test
    public void test_unknown_name_is_not_resolved() {
        assertNull(ArrayBuiltins.getMethod(new JsArray(), "nope", null, null));
        assertNull(StringBuiltins.getMethod(new JsString("a"), "nope", null, null));
        assertNull(NumberBuiltins.getMethod(new JsNumber(1), "nope"));
        assertNull(BigIntBuiltins.getMethod(new JsBigInt(BigInteger.ONE), "nope"));
        assertNull(SymbolBuiltins.getMethod(new JsSymbol("s"), "nope"));
        assertNull(SymbolBuiltins.getProperty(new JsSymbol("s"), "nope"));
        assertNull(RegexBuiltins.getMethod(RegexTranslator.compile("a", ""), "nope"));
        assertNull(ObjectProtoBuiltins.getMethod(new JsObject(), "nope", null));
        assertNull(FunctionProtoBuiltins.getMethod(new JsNativeFunction("f", (_, _) -> null), "nope", null));
        assertNull(FunctionProtoBuiltins.metadata(new JsNativeFunction("f", (_, _) -> null), "nope"));
    }

    // Invoking a delegating wrapper with the wrong receiver names the method
    @Test
    public void test_wrong_receiver_throws_type_error() {
        final var realm = intrinsics();
        final var push = (JsNativeFunction) realm.arrayProto().get("push");
        final var error = assertThrows(TypeErrorException.class,
                () -> push.invoke(new JsNumber(1), List.of(new JsNumber(2))));
        assertTrue(error.getMessage().startsWith("Array.prototype.push"), error.getMessage());
        final var toFixed = (JsNativeFunction) realm.numberProto().get("toFixed");
        assertThrows(TypeErrorException.class, () -> toFixed.invoke(new JsString("a"), List.of()));
        final var mapGet = (JsNativeFunction) realm.mapProto().get("get");
        assertThrows(TypeErrorException.class, () -> mapGet.invoke(new JsObject(), List.of()));
    }

    // Every prototype chain terminates at Object.prototype
    @Test
    public void test_prototype_chain_roots_at_object_proto() {
        final var realm = intrinsics();
        final List<JsObject> protos = List.of(realm.arrayProto(), realm.stringProto(), realm.numberProto(),
                realm.booleanProto(), realm.bigintProto(), realm.symbolProto(), realm.regexpProto(), realm.mapProto(),
                realm.setProto(), realm.dateProto(), realm.promiseProto(), realm.iteratorProto(),
                realm.asyncIteratorProto(), realm.arrayBufferProto(), realm.dataViewProto(), realm.functionProto(),
                realm.errorProto("TypeError"));
        for (final var proto : protos) {
            var current = proto;
            var depth = 0;
            while (current.getProto() != null && depth < 10) {
                current = current.getProto();
                depth++;
            }
            assertSame(realm.objectProto(), current);
        }
        assertNull(realm.objectProto().getProto());
    }

    // Error prototypes share Error.prototype as their base and an unknown name falls back to it
    @Test
    public void test_error_prototypes() {
        final var realm = intrinsics();
        assertSame(realm.errorProto("Error"), realm.errorProto("TypeError").getProto());
        assertSame(realm.errorProto("Error"), realm.errorProto("NoSuchError"));
        final var error = realm.makeError("RangeError", "bad");
        assertSame(realm.errorProto("RangeError"), error.getProto());
        assertEquals("bad", ((JsString) error.get("message")).getValue());
        assertTrue(error.isErrorData());
    }

    // Array.prototype methods accept array-like receivers by snapshotting them
    @Test
    public void test_array_prototype_accepts_array_likes() {
        final var realm = intrinsics();
        final var join = (JsNativeFunction) realm.arrayProto().get("join");
        assertEquals("a-b", ((JsString) join.invoke(new JsString("ab"), List.of(new JsString("-")))).getValue());
    }
}
