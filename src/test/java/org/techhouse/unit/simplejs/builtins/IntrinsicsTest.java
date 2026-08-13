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
import org.techhouse.simplejs.internal.Interpreter;
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
            assertNotNull(ObjectProtoBuiltins.getMethod(new JsObject(), name, null, null), name);
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
        assertNull(ObjectProtoBuiltins.getMethod(new JsObject(), "nope", null, null));
        assertNull(FunctionProtoBuiltins.getMethod(new JsNativeFunction("f", (_, _) -> null), "nope", null));
        assertNull(FunctionProtoBuiltins.metadata(new JsNativeFunction("f", (_, _) -> null), "nope"));
    }

    // Invoking a delegating wrapper with the wrong receiver names the method. A raw number is no
    // longer "wrong" for Array.prototype methods (ToObject boxes it into an empty array-like), so
    // undefined - which ToObject rejects - stands in as a receiver that is genuinely incompatible.
    @Test
    public void test_wrong_receiver_throws_type_error() {
        final var realm = intrinsics();
        final var push = (JsNativeFunction) realm.arrayProto().get("push");
        final var error = assertThrows(TypeErrorException.class,
                () -> push.invoke(JsUndefined.getInstance(), List.of(new JsNumber(2))));
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

    // Array.prototype methods accept a plain array-like receiver
    @Test
    public void test_array_method_on_plain_array_like() {
        assertEquals("[2,4]", run("JSON.stringify(Array.prototype.map.call({length: 2, 0: 1, 1: 2}, x => x * 2))"));
    }

    // reduce folds over a plain array-like
    @Test
    public void test_array_reduce_on_plain_array_like() {
        assertEquals("6",
                run("String(Array.prototype.reduce.call({length: 3, 0: 1, 1: 2, 2: 3}, (a, b) => a + b, 0))"));
    }

    // a missing index of an array-like is a hole, which join renders as empty
    @Test
    public void test_array_join_on_plain_array_like_with_holes() {
        assertEquals("a--c", run("Array.prototype.join.call({length: 3, 0: 'a', 2: 'c'}, '-')"));
    }

    // a backwards-walking method reads the array-like's indices in descending order
    @Test
    public void test_reverse_walking_array_method_on_plain_array_like() {
        assertEquals("cba",
                run("Array.prototype.reduceRight.call({length: 3, 0: 'a', 1: 'b', 2: 'c'}, (a, b) => a + b)"));
    }

    // a mutating method is refused on a plain array-like rather than discarding the write
    @Test
    public void test_mutating_array_method_on_plain_array_like_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Array.prototype.push.call({length: 0}, 1)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Array.prototype.copyWithin.call({length: 43}, 42, 0)"));
    }

    // undefined/null still report an incompatible-receiver TypeError - ToObject rejects them
    // outright, unlike every other value (which ToObject always succeeds on).
    @Test
    public void test_array_method_on_null_or_undefined_still_throws() {
        final var error = assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Array.prototype.push.call(undefined)"));
        assertTrue(error.getMessage().contains("Array.prototype.push"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Array.prototype.push.call(null)"));
    }

    // a generic (non-mutating) Array method works on any plain object per spec, treating a missing
    // "length" as 0 (LengthOfArrayLike) rather than rejecting the receiver
    @Test
    public void test_non_mutating_array_method_on_lengthless_object_iterates_zero_elements() {
        assertEquals("[]", run("JSON.stringify(Array.prototype.map.call({}, x => x))"));
        assertEquals("true", run("String(Array.prototype.every.call({}, x => false))"));
    }

    // Boolean.prototype.valueOf/toString accept a real boolean receiver and reject anything else
    @Test
    public void test_boolean_prototype_valueof_and_incompatible_receiver() {
        assertTrue(bool("(true).valueOf() === true"));
        assertEquals("true", run("(true).toString()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Boolean.prototype.valueOf.call(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Boolean.prototype.toString.call(5)"));
    }

    // a boxed Boolean wrapper (new Boolean(...)) unwraps through valueOf/toString
    @Test
    public void test_boolean_wrapper_unwraps() {
        assertTrue(bool("(new Boolean(true)).valueOf() === true"));
        assertEquals("true", run("(new Boolean(true)).toString()"));
    }

    // a boxed Number wrapper (new Number(...)) unwraps through a Number.prototype method
    @Test
    public void test_number_wrapper_unwraps() {
        assertEquals("5.00", run("(new Number(5)).toFixed(2)"));
    }

    // every prototype family reports the same incompatible-receiver TypeError for a plain number
    @Test
    public void test_incompatible_receiver_throws_for_every_prototype_family() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("String.prototype.charAt.call(5, 0)"));
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
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let g = (async function*(){})(); Object.getPrototypeOf(g).next.call(5)"));
    }

    // a subclass of BigInt with no JsObject internal state wraps the produced primitive, so a
    // BigInt.prototype method resolves the receiver via the unwrap fallback rather than direct instanceof
    @Test
    public void test_bigint_subclass_unwraps() {
        assertEquals("5", run("class B extends BigInt { constructor(v) { super(v); } } new B(5).toString()"));
    }

    // a subclass of Symbol wraps the produced primitive for Symbol.prototype methods
    @Test
    public void test_symbol_subclass_unwraps() {
        assertEquals("Symbol(x)", run("class S extends Symbol { constructor(d) { super(d); } } new S('x').toString()"));
    }

    // a subclass of RegExp wraps the produced primitive for RegExp.prototype methods
    @Test
    public void test_regexp_subclass_unwraps() {
        assertTrue(bool("class R extends RegExp { constructor(p) { super(p); } } new R('a').test('a')"));
    }

    // a subclass of Promise wraps the produced primitive for Promise.prototype methods
    @Test
    public void test_promise_subclass_unwraps() {
        assertEquals("object", run("class P extends Promise { constructor(e) { super(e); } } "
                + "typeof new P((res) => res(1)).then(() => {})"));
    }

    // a subclass of Set wraps the produced primitive for Set.prototype methods
    @Test
    public void test_set_subclass_unwraps() {
        assertTrue(bool("class MySet extends Set { constructor(v) { super(v); } } new MySet([1, 2]).has(1)"));
    }

    // a subclass of Date wraps the produced primitive for Date.prototype methods
    @Test
    public void test_date_subclass_unwraps() {
        assertEquals(0, num("class D extends Date { constructor(v) { super(v); } } new D(0).getTime()"));
    }

    // a subclass of ArrayBuffer wraps the produced primitive for ArrayBuffer.prototype methods
    @Test
    public void test_array_buffer_subclass_unwraps() {
        assertEquals(4,
                num("class Buf extends ArrayBuffer { constructor(n) { super(n); } } new Buf(4).slice(0).byteLength"));
    }

    // a subclass of DataView wraps the produced primitive for DataView.prototype methods
    @Test
    public void test_data_view_subclass_unwraps() {
        assertEquals(0,
                num("class V extends DataView { constructor(b) { super(b); } } new V(new ArrayBuffer(4)).getInt8(0)"));
    }

    // a subclass of a TypedArray wraps the produced primitive for %TypedArray%.prototype methods
    @Test
    public void test_typed_array_subclass_unwraps() {
        assertEquals(1, num("class T extends Int8Array { constructor(n) { super(n); } } new T(4).fill(1)[0]"));
    }
}
