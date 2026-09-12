package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.ArrayBuiltins;
import org.techhouse.simplejs.builtins.BigIntBuiltins;
import org.techhouse.simplejs.builtins.DateBuiltins;
import org.techhouse.simplejs.builtins.DbDateTimeBuiltins;
import org.techhouse.simplejs.builtins.DbTimeBuiltins;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.builtins.GeoBuiltins;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.MapBuiltins;
import org.techhouse.simplejs.builtins.NumberBuiltins;
import org.techhouse.simplejs.builtins.ObjectProtoBuiltins;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.builtins.SetBuiltins;
import org.techhouse.simplejs.builtins.StringBuiltins;
import org.techhouse.simplejs.builtins.SymbolBuiltins;
import org.techhouse.simplejs.builtins.TypedArrayBuiltins;
import org.techhouse.simplejs.builtins.VectorBuiltins;
import org.techhouse.simplejs.builtins.typedarray.ArrayBufferBuiltins;
import org.techhouse.simplejs.builtins.typedarray.DataViewBuiltins;
import org.techhouse.simplejs.builtins.typedarray.TypedArrayIteration;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsDbDateTime;
import org.techhouse.simplejs.values.JsDbTime;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGeo;
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
import org.techhouse.simplejs.values.JsVector;
import org.techhouse.utils.GeoPoint;

public class IntrinsicsPrototypeTest {

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

    @Test
    public void test_proto_for_every_value_type() {
        final var realm = intrinsics();
        final var buffer = new JsArrayBuffer(8);
        final Map<JsValue, JsObject> expected = Map.ofEntries(Map.entry(new JsArray(), realm.arrayProto),
                Map.entry(new JsString("a"), realm.stringProto), Map.entry(new JsNumber(1), realm.numberProto),
                Map.entry(JsBoolean.of(true), realm.booleanProto),
                Map.entry(new JsBigInt(BigInteger.ONE), realm.bigintProto),
                Map.entry(new JsSymbol("s"), realm.symbolProto),
                Map.entry(RegexTranslator.compile("a", ""), realm.regexpProto),
                Map.entry(new JsMap(false), realm.mapProto), Map.entry(new JsSet(false), realm.setProto),
                Map.entry(new JsDate(0), realm.dateProto), Map.entry(buffer, realm.arrayBufferProto),
                Map.entry(new JsDataView(buffer, 0, 8), realm.dataViewProto),
                Map.entry(new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance()), realm.functionProto),
                Map.entry(new JsGeo(new GeoPoint(1, 2)), realm.geoProto),
                Map.entry(new JsVector(new double[]{1, 2}), realm.vectorProto),
                Map.entry(new JsDbDateTime(LocalDateTime.of(2020, 1, 2, 3, 4, 5)), realm.dbDateTimeProto),
                Map.entry(new JsDbTime(LocalTime.of(3, 4, 5)), realm.dbTimeProto),
                Map.entry(new JsObject(), realm.objectProto), Map.entry(JsUndefined.getInstance(), realm.objectProto));
        for (final var entry : expected.entrySet()) {
            assertSame(entry.getValue(), realm.protoFor(entry.getKey()),
                    () -> entry.getKey().getClass().getSimpleName());
        }
        for (final var kind : JsTypedArray.Kind.values()) {
            assertSame(realm.typedArrayProto(kind), realm.protoFor(new JsTypedArray(kind, buffer, 0, 0)));
        }
    }

    // The real Function constructor does not exist yet when Intrinsics builds the function-kind constructors,
    // so their own [[Prototype]] starts unset and is wired later by linkFunctionKindConstructors.
    @Test
    public void test_link_function_kind_constructors_wires_own_prototype() {
        final var realm = intrinsics();
        final var asyncFn = new JsFunction("f", List.of(), null, false, false, true, false, null);
        final var generatorFn = new JsFunction("g", List.of(), null, false, false, false, true, null);
        final var asyncGeneratorFn = new JsFunction("ag", List.of(), null, false, false, true, true, null);
        final var asyncCtor = (JsNativeFunction) realm.protoFor(asyncFn).get("constructor");
        final var generatorCtor = (JsNativeFunction) realm.protoFor(generatorFn).get("constructor");
        final var asyncGeneratorCtor = (JsNativeFunction) realm.protoFor(asyncGeneratorFn).get("constructor");
        assertNull(asyncCtor.getOwnProto());
        assertNull(generatorCtor.getOwnProto());
        assertNull(asyncGeneratorCtor.getOwnProto());

        final var functionCtor = new JsNativeFunction("Function", (_, _) -> JsUndefined.getInstance());
        realm.linkFunctionKindConstructors(functionCtor);

        assertSame(functionCtor, asyncCtor.getOwnProto());
        assertSame(functionCtor, generatorCtor.getOwnProto());
        assertSame(functionCtor, asyncGeneratorCtor.getOwnProto());
    }

    @Test
    public void test_names_match_prototype_keys() {
        final var realm = intrinsics();
        assertKeys(realm.arrayProto, ArrayBuiltins.NAMES);
        assertKeys(realm.stringProto,
                Stream.concat(StringBuiltins.NAMES.stream(), Stream.of("toString", "valueOf")).toList());
        assertKeys(realm.numberProto, NumberBuiltins.NAMES);
        assertKeys(realm.bigintProto, BigIntBuiltins.NAMES);
        assertKeys(realm.symbolProto, SymbolBuiltins.NAMES, SymbolBuiltins.PROTO_ACCESSORS);
        assertKeys(realm.regexpProto, RegexBuiltins.NAMES, RegexBuiltins.PROTO_ACCESSORS);
        assertKeys(realm.dateProto, DateBuiltins.NAMES);
        assertKeys(realm.objectProto, ObjectProtoBuiltins.NAMES, List.of("__proto__"));
        assertKeys(realm.functionProto, FunctionProtoBuiltins.NAMES, List.of("caller", "arguments"));
        assertKeys(realm.promiseProto, org.techhouse.simplejs.builtins.PromiseBuiltins.PROTO_NAMES);
        assertKeys(realm.iteratorProto, org.techhouse.simplejs.builtins.GeneratorBuiltins.PROTO_NAMES);
        assertKeys(realm.asyncIteratorProto, org.techhouse.simplejs.builtins.GeneratorBuiltins.PROTO_NAMES);
        assertKeys(realm.arrayBufferProto, TypedArrayBuiltins.BUFFER_NAMES, ArrayBufferBuiltins.bufferAccessorNames());
        assertKeys(realm.dataViewProto, TypedArrayBuiltins.VIEW_NAMES, DataViewBuiltins.viewAccessorNames());
        assertKeys(realm.mapProto, MapBuiltins.NAMES, List.of("size"));
        assertKeys(realm.setProto, SetBuiltins.NAMES, List.of("size"));
        assertKeys(realm.geoProto, GeoBuiltins.NAMES, GeoBuiltins.FIELD_ACCESSORS);
        assertKeys(realm.vectorProto, VectorBuiltins.NAMES, VectorBuiltins.FIELD_ACCESSORS);
        assertKeys(realm.dbDateTimeProto, DbDateTimeBuiltins.NAMES, DbDateTimeBuiltins.FIELD_ACCESSORS);
        assertKeys(realm.dbTimeProto, DbTimeBuiltins.NAMES, DbTimeBuiltins.FIELD_ACCESSORS);
    }

    private static void assertKeys(JsObject proto, List<String> names) {
        assertKeys(proto, names, List.of());
    }

    private static void assertKeys(JsObject proto, List<String> names, List<String> accessors) {
        for (final var name : names) {
            assertTrue(proto.has(name), () -> "prototype is missing " + name);
            assertFalse(proto.isEnumerable(name), () -> name + " must be non-enumerable");
            assertTrue(proto.getFlags(name).configurable(), () -> name + " must be configurable");
        }
        for (final var name : accessors) {
            assertNotNull(proto.getAccessorGetter(name), () -> "prototype is missing accessor " + name);
            assertFalse(proto.isEnumerable(name), () -> name + " must be non-enumerable");
            assertTrue(proto.getFlags(name).configurable(), () -> name + " must be configurable");
        }
        for (final var key : proto.keys()) {
            // String.prototype is itself a String wrapper, so it owns the exotic `length` of its
            assertTrue(
                    "constructor".equals(key) || "name".equals(key) || "message".equals(key) || "length".equals(key)
                            || names.contains(key) || accessors.contains(key),
                    () -> "prototype has an unlisted key " + key);
        }
    }

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
            assertNotNull(DateBuiltins.getMethod(new JsDate(0), name, null), name);
        }
        for (final var name : ObjectProtoBuiltins.NAMES) {
            assertNotNull(ObjectProtoBuiltins.getMethod(new JsObject(), name, null, null), name);
        }
        for (final var name : FunctionProtoBuiltins.NAMES) {
            assertNotNull(FunctionProtoBuiltins.getMethod(new JsNativeFunction("f", (_, _) -> null), name, null, null),
                    name);
        }
        for (final var name : TypedArrayBuiltins.BUFFER_NAMES) {
            assertNotNull(ArrayBufferBuiltins.bufferMethod(buffer, name), name);
        }
        for (final var name : TypedArrayBuiltins.VIEW_NAMES) {
            assertNotNull(DataViewBuiltins.dataViewMethod(new JsDataView(buffer, 0, 8), name), name);
        }
        for (final var name : TypedArrayBuiltins.NAMES) {
            assertNotNull(TypedArrayIteration.getMethod(new JsTypedArray(JsTypedArray.Kind.INT8, buffer, 0, 0), name,
                    null, null), name);
        }
        for (final var name : MapBuiltins.NAMES) {
            assertNotNull(MapBuiltins.getMethod(new JsMap(false), name, null), name);
        }
        for (final var name : SetBuiltins.NAMES) {
            assertNotNull(SetBuiltins.getMethod(new JsSet(false), name, null, null), name);
        }
    }

    @Test
    public void test_prototype_chain_roots_at_object_proto() {
        final var realm = intrinsics();
        final List<JsObject> protos = List.of(realm.arrayProto, realm.stringProto, realm.numberProto,
                realm.booleanProto, realm.bigintProto, realm.symbolProto, realm.regexpProto, realm.mapProto,
                realm.setProto, realm.dateProto, realm.promiseProto, realm.iteratorProto, realm.asyncIteratorProto,
                realm.arrayBufferProto, realm.dataViewProto, realm.functionProto, realm.errorProto("TypeError"));
        for (final var proto : protos) {
            var current = (JsValue) proto;
            var depth = 0;
            while (current.getProto() != null && depth < 10) {
                current = current.getProto();
                depth++;
            }
            assertSame(realm.objectProto, current);
        }
        assertNull(realm.objectProto.getProto());
    }

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

    @Test
    public void namespaceMembersAreNonEnumerable() {
        assertEquals(0, num("Object.keys(Math).length"));
        assertEquals(0, num("Object.keys(JSON).length"));
        assertEquals(0, num("Object.keys(Reflect).length"));
        assertEquals(0, num("Object.keys(console).length"));
        assertEquals(0, num("Object.keys(Iterator.prototype).length"));
        assertTrue(bool("Object.getOwnPropertyNames(Math).indexOf('PI') >= 0"));
        assertTrue(bool("Object.getOwnPropertyDescriptor(Math, 'PI').writable === false"));
        assertTrue(bool("Object.getOwnPropertyDescriptor(Math, 'floor').configurable === true"));
    }

    @Test
    public void toObjectBoxesPrimitivesOntoTheirPrototype() {
        final var realm = intrinsics();
        final var object = new JsObject();
        assertSame(object, realm.toObject(object));
        final Map<JsValue, JsObject> expected = Map.of(new JsString("ab"), realm.stringProto, new JsNumber(1),
                realm.numberProto, JsBoolean.TRUE, realm.booleanProto, new JsBigInt(BigInteger.ONE), realm.bigintProto,
                new JsSymbol("s"), realm.symbolProto);
        for (final var entry : expected.entrySet()) {
            final var wrapper = (JsObject) realm.toObject(entry.getKey());
            assertSame(entry.getKey(), wrapper.getPrimitive());
            assertSame(entry.getValue(), wrapper.getProto());
        }
        assertThrows(TypeErrorException.class, () -> realm.toObject(JsUndefined.getInstance()));
        assertNotSame(realm.toObject(new JsNumber(1)), realm.toObject(new JsNumber(1)));
    }

    @Test
    public void syncGeneratorResultObjectsLinkObjectPrototype() {
        assertTrue(bool("""
                function* g() { yield 1; }
                var it = g();
                Object.getPrototypeOf(it.next()) === Object.prototype
                """));
        assertTrue(bool("""
                function* g() { yield 1; }
                var it = g();
                Object.getPrototypeOf(it.return(2)) === Object.prototype
                """));
    }

    @Test
    public void generatorPrototypeOwnsItsConstructorBackLink() {
        assertTrue(bool("""
                function* g() {}
                var generatorPrototype = Object.getPrototypeOf(g.prototype);
                generatorPrototype.constructor === Object.getPrototypeOf(g)
                """));
        assertTrue(bool("""
                async function* g() {}
                var asyncGeneratorPrototype = Object.getPrototypeOf(g.prototype);
                asyncGeneratorPrototype.constructor === Object.getPrototypeOf(g)
                """));
        assertTrue(bool("""
                function* g() {}
                var d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(g.prototype), 'constructor');
                d.writable === false && d.enumerable === false && d.configurable === true
                """));
    }
}
