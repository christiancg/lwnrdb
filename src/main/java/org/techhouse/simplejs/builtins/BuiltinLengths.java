package org.techhouse.simplejs.builtins;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The spec-declared `length` of every intrinsic prototype method, keyed "Label.method" so one table
// covers every family the Intrinsics wrappers build. A builtin's length is observable
// (verifyProperty(Array.prototype, "join", {value: 1})) and cannot be derived from the Java lambda,
// which always takes a single varargs list. An unlisted name falls back to 1, the commonest arity.
public final class BuiltinLengths {
    private static final int DEFAULT_LENGTH = 1;
    private static final Map<String, Integer> LENGTHS = new HashMap<>();
    // The namespace/constructor objects whose own function properties are static builtins. Their
    // lengths come from the same table (keyed by the owner's global name), defaulting to 1.
    private static final List<String> STATIC_OWNERS = List.of("Object", "Array", "String", "Number", "Boolean", "Math",
            "JSON", "Promise", "RegExp", "Symbol", "Map", "Set", "WeakMap", "WeakSet", "Date", "Reflect", "Proxy",
            "ArrayBuffer", "DataView", "BigInt", "Iterator", "AsyncIterator", "DisposableStack",
            "AsyncDisposableStack");

    private static final List<String> PLAIN_GLOBALS = List.of("Function", "parseInt", "parseFloat", "isNaN", "isFinite",
            "encodeURI", "decodeURI", "encodeURIComponent", "decodeURIComponent", "escape", "unescape",
            "structuredClone", "queueMicrotask", "fetch");

    private static final Map<String, Integer> GLOBAL_LENGTHS = new HashMap<>();

    static {
        zeroArg("Array.prototype", "toString", "toLocaleString", "pop", "shift", "reverse", "keys", "values", "entries",
                "toReversed", "flat");
        put("Array.prototype", 2, "slice", "splice", "copyWithin", "toSpliced", "with");
        zeroArg("String.prototype", "toUpperCase", "toLowerCase", "toLocaleUpperCase", "toLocaleLowerCase", "trim",
                "trimStart", "trimEnd", "trimLeft", "trimRight", "normalize", "isWellFormed", "toWellFormed");
        put("String.prototype", 2, "slice", "substring", "split", "replace", "replaceAll", "substr");
        zeroArg("Number.prototype", "valueOf", "toLocaleString");
        zeroArg("BigInt.prototype", "toString", "valueOf", "toLocaleString");
        zeroArg("Symbol.prototype", "toString", "valueOf");
        zeroArg("RegExp.prototype", "toString");
        zeroArg("Map.prototype", "clear", "keys", "values", "entries");
        put("Map.prototype", 2, "set", "getOrInsert", "getOrInsertComputed");
        put("WeakMap.prototype", 2, "set", "getOrInsert", "getOrInsertComputed");
        zeroArg("Set.prototype", "clear", "keys", "values", "entries");
        zeroArg("Object.prototype", "toString", "valueOf", "toLocaleString");
        put("Object.prototype", 2, "__defineGetter__", "__defineSetter__");
        put("Promise.prototype", 2, "then");
        put("Promise.prototype", 1, "catch", "finally");
        zeroArg("Function.prototype", "toString");
        put("Function.prototype", 2, "apply");
        zeroArg("DisposableStack.prototype", "dispose", "move");
        zeroArg("AsyncDisposableStack.prototype", "disposeAsync", "move");
        put("DisposableStack.prototype", 2, "adopt");
        put("AsyncDisposableStack.prototype", 2, "adopt");
        zeroArg("ArrayBuffer.prototype", "transfer", "transferToFixedLength");
        put("ArrayBuffer.prototype", 2, "slice");
        zeroArg("TypedArray.prototype", "toString", "reverse", "keys", "values", "entries", "toReversed",
                "toLocaleString");
        put("TypedArray.prototype", 2, "copyWithin", "with", "slice", "subarray");
        zeroArg("Uint8Array.prototype", "toBase64", "toHex");
        installDate();
        installDataView();
        installStatics();
    }

    private static void installStatics() {
        put("Object", 2, "assign", "create", "defineProperties", "getOwnPropertyDescriptor", "setPrototypeOf", "is",
                "hasOwn", "groupBy");
        put("Object", 3, "defineProperty");
        put("Array", 0, "of");
        put("Reflect", 2, "construct", "deleteProperty", "get", "getOwnPropertyDescriptor", "has", "setPrototypeOf");
        put("Reflect", 3, "apply", "defineProperty", "set");
        put("Promise", 0, "withResolvers");
        put("Number", 2, "parseInt");
        put("Date", 0, "now");
        put("Date", 7, "UTC");
        put("JSON", 2, "parse");
        put("JSON", 3, "stringify");
        put("Math", 0, "random");
        put("Math", 2, "atan2", "imul", "pow", "max", "min", "hypot");
        put("BigInt", 2, "asIntN", "asUintN");
        put("Iterator", 0, "concat");
        put("Map", 2, "groupBy");
        put("Proxy", 2, "revocable");
        globals(0, "Symbol", "Map", "Set", "WeakMap", "WeakSet", "DisposableStack", "AsyncDisposableStack");
        globals(1, "Object", "Function", "Array", "String", "Number", "Boolean", "Promise", "ArrayBuffer", "BigInt",
                "parseFloat", "isNaN", "isFinite", "encodeURI", "decodeURI", "encodeURIComponent", "decodeURIComponent",
                "escape", "unescape", "structuredClone", "queueMicrotask", "fetch");
        globals(2, "RegExp", "parseInt", "Proxy");
        globals(3, "DataView");
        globals(7, "Date");
    }

    private BuiltinLengths() {
    }

    private static void installDate() {
        zeroArg("Date.prototype", "getTime", "valueOf", "toISOString", "toString", "toDateString", "toTimeString",
                "toUTCString", "toLocaleString", "toLocaleDateString", "toLocaleTimeString", "getTimezoneOffset",
                "getFullYear", "getUTCFullYear", "getMonth", "getUTCMonth", "getDate", "getUTCDate", "getDay",
                "getUTCDay", "getHours", "getUTCHours", "getMinutes", "getUTCMinutes", "getSeconds", "getUTCSeconds",
                "getMilliseconds", "getUTCMilliseconds");
        put("Date.prototype", 2, "setMonth", "setUTCMonth", "setSeconds", "setUTCSeconds");
        put("Date.prototype", 3, "setFullYear", "setUTCFullYear", "setMinutes", "setUTCMinutes");
        put("Date.prototype", 4, "setHours", "setUTCHours");
    }

    private static void installDataView() {
        for (final var element : new String[]{"Int8", "Uint8", "Int16", "Uint16", "Int32", "Uint32", "Float16",
                "Float32", "Float64", "BigInt64", "BigUint64"}) {
            put("DataView.prototype", 2, "set" + element);
        }
    }

    private static void zeroArg(String label, String... names) {
        put(label, 0, names);
    }

    private static void put(String label, int length, String... names) {
        for (final var name : names) {
            LENGTHS.put(label + "." + name, length);
        }
    }

    public static int lengthOf(String label, String name) {
        return LENGTHS.getOrDefault(label + "." + name, DEFAULT_LENGTH);
    }

    private static void globals(int length, String... names) {
        for (final var name : names) {
            GLOBAL_LENGTHS.put(name, length);
        }
    }

    public static List<String> staticOwners() {
        return STATIC_OWNERS;
    }

    public static List<String> plainGlobals() {
        return PLAIN_GLOBALS;
    }

    public static int globalLength(String name, int fallback) {
        return GLOBAL_LENGTHS.getOrDefault(name, fallback);
    }
}
