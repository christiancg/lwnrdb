package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

public class IntrinsicsDescriptorTest {
    private static final List<String> PROTOTYPES = List.of("Object.prototype", "Function.prototype", "Array.prototype",
            "String.prototype", "Number.prototype", "Boolean.prototype", "BigInt.prototype", "Symbol.prototype",
            "RegExp.prototype", "Map.prototype", "WeakMap.prototype", "Set.prototype", "WeakSet.prototype",
            "Date.prototype", "Promise.prototype", "Error.prototype", "TypeError.prototype", "ArrayBuffer.prototype",
            "DataView.prototype", "Int8Array.prototype", "Uint8Array.prototype", "Float64Array.prototype",
            "Object.getPrototypeOf(Int8Array).prototype", "Iterator.prototype", "AsyncIterator.prototype",
            "DisposableStack.prototype", "AsyncDisposableStack.prototype",
            "Object.getPrototypeOf(function* () {}.prototype)", "Object.getPrototypeOf([][Symbol.iterator]())",
            "Object.getPrototypeOf(''[Symbol.iterator]())", "Object.getPrototypeOf(new Map()[Symbol.iterator]())",
            "Object.getPrototypeOf(new Set()[Symbol.iterator]())");

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean flag(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_no_intrinsic_prototype_entry_enumerates() {
        for (final var prototype : PROTOTYPES) {
            assertEquals("", str(offenders(prototype, "d.enumerable")), prototype);
            assertTrue(flag("Object.keys(" + prototype + ").length === 0"), prototype);
        }
    }

    // @@hasInstance and @@toPrimitive are the spec's two non-writable symbol-keyed methods and are
    // asserted separately below.
    @Test
    public void test_intrinsic_prototype_methods_are_writable_and_configurable() {
        for (final var prototype : PROTOTYPES) {
            assertEquals("",
                    str(offenders(prototype,
                            "keys[i] !== Symbol.hasInstance" + " && keys[i] !== Symbol.toPrimitive"
                                    + " && 'value' in d && typeof d.value === 'function'"
                                    + " && (!d.writable || !d.configurable)")),
                    prototype);
        }
    }

    @Test
    public void test_to_string_tag_is_non_writable_but_configurable() {
        for (final var prototype : PROTOTYPES) {
            assertTrue(flag("(function () {" + " var d = Object.getOwnPropertyDescriptor(" + prototype
                    + ", Symbol.toStringTag);" + " if (d === undefined) { return true; }"
                    + " if ('get' in d) { return d.enumerable === false && d.configurable === true; }"
                    + " return d.writable === false && d.enumerable === false && d.configurable === true;" + "})()"),
                    prototype);
        }
    }

    @Test
    public void test_well_known_symbol_method_attributes() {
        assertTrue(flag("(function () {"
                + " var d = Object.getOwnPropertyDescriptor(Date.prototype, Symbol.toPrimitive);"
                + " return d.writable === false && d.enumerable === false && d.configurable === true;" + "})()"));
        assertTrue(flag("(function () {"
                + " var d = Object.getOwnPropertyDescriptor(Symbol.prototype, Symbol.toPrimitive);"
                + " return d.writable === false && d.enumerable === false && d.configurable === true;" + "})()"));
        assertTrue(flag("(function () {"
                + " var d = Object.getOwnPropertyDescriptor(Function.prototype, Symbol.hasInstance);"
                + " return d.writable === false && d.enumerable === false && d.configurable === false;" + "})()"));
        assertTrue(flag("(function () {"
                + " var d = Object.getOwnPropertyDescriptor(Array.prototype, Symbol.unscopables);"
                + " return d.writable === false && d.enumerable === false && d.configurable === true;" + "})()"));
        for (final var symbol : List.of("iterator", "match", "replace", "search", "split", "matchAll")) {
            assertTrue(flag("(function () {" + " var d = Object.getOwnPropertyDescriptor(RegExp.prototype, Symbol."
                    + symbol + ")" + " || Object.getOwnPropertyDescriptor(Array.prototype, Symbol." + symbol + ");"
                    + " return d === undefined || (d.enumerable === false && d.configurable === true);" + "})()"),
                    symbol);
        }
        assertTrue(flag("(function () {"
                + " var d = Object.getOwnPropertyDescriptor(DisposableStack.prototype, Symbol.dispose);"
                + " return d.writable === true && d.enumerable === false && d.configurable === true;" + "})()"));
        assertTrue(flag("(function () {"
                + " var d = Object.getOwnPropertyDescriptor(AsyncDisposableStack.prototype, Symbol.asyncDispose);"
                + " return d.writable === true && d.enumerable === false && d.configurable === true;" + "})()"));
    }

    @Test
    public void test_a_patched_method_still_dispatches() {
        assertEquals("patched", str("Array.prototype.join = function () { return 'patched'; }; [1, 2].join()"));
        assertEquals("patched", str("Map.prototype.get = function () { return 'patched'; }; new Map().get(1)"));
        assertEquals("patched",
                str("Date.prototype.getTime = function () { return 'patched'; };" + " new Date(0).getTime()"));
    }

    @Test
    public void test_an_intrinsic_method_can_be_deleted() {
        assertTrue(flag("delete Array.prototype.push && [].push === undefined"));
        assertTrue(flag("delete Date.prototype.getTime && new Date(0).getTime === undefined"));
        assertTrue(flag("delete Object.prototype.hasOwnProperty" + " && ({}).hasOwnProperty === undefined"));
    }

    // The numeric constants (Math.PI and friends) are the spec's fully immutable exceptions.
    @Test
    public void test_namespaces_are_ordinary_objects() {
        for (final var namespace : List.of("Math", "JSON", "Reflect")) {
            assertTrue(flag("Object.getPrototypeOf(" + namespace + ") === Object.prototype"), namespace);
            assertTrue(flag("Object.keys(" + namespace + ").length === 0"), namespace);
            assertEquals("", str(offenders(namespace, "d.enumerable")), namespace);
            assertEquals("",
                    str(offenders(namespace, "typeof d.value === 'function'" + " && (!d.writable || !d.configurable)")),
                    namespace);
        }
        assertTrue(flag("(function () {" + " var d = Object.getOwnPropertyDescriptor(Math, 'PI');"
                + " return d.writable === false && d.enumerable === false && d.configurable === false;" + "})()"));
    }

    @Test
    public void test_constructor_prototype_chains() {
        for (final var name : List.of("TypeError", "RangeError", "SyntaxError", "URIError", "ReferenceError",
                "EvalError", "AggregateError", "SuppressedError")) {
            assertTrue(flag("Object.getPrototypeOf(" + name + ") === Error"), name);
            assertTrue(flag(name + ".length === " + expectedErrorLength(name)), name);
        }
        assertTrue(flag("Error.length === 1"));
        for (final var name : List.of("Int8Array", "Uint8Array", "Float64Array", "BigInt64Array")) {
            assertTrue(flag("Object.getPrototypeOf(" + name + ") === Object.getPrototypeOf(Int8Array)"), name);
            assertTrue(flag("!Object.prototype.hasOwnProperty.call(" + name + ", 'from')"), name);
            assertTrue(flag("!Object.prototype.hasOwnProperty.call(" + name + ", 'of')"), name);
            assertTrue(flag(name + ".from === Object.getPrototypeOf(Int8Array).from"), name);
        }
    }

    private static String expectedErrorLength(String name) {
        return switch (name) {
            case "AggregateError" -> "2";
            case "SuppressedError" -> "3";
            default -> "1";
        };
    }

    private static String offenders(String prototype, String predicate) {
        return "(function () {" + " var target = " + prototype + ";" + " var bad = [];"
                + " var keys = Object.getOwnPropertyNames(target).concat(Object.getOwnPropertySymbols(target));"
                + " for (var i = 0; i < keys.length; i++) {"
                + "   var d = Object.getOwnPropertyDescriptor(target, keys[i]);" + "   if (d && (" + predicate
                + ")) { bad.push(String(keys[i])); }" + " }" + " return bad.join(',');" + "})()";
    }
}
