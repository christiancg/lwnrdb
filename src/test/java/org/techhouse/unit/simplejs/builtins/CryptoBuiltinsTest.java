package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

public class CryptoBuiltinsTest {
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // randomUUID answers a version 4 UUID and never repeats
    @Test
    public void test_random_uuid() {
        assertTrue(bool("/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/"
                + ".test(crypto.randomUUID())"));
        assertTrue(bool("crypto.randomUUID() !== crypto.randomUUID()"));
    }

    // getRandomValues fills the view in place and hands the same object back
    @Test
    public void test_get_random_values_fills_in_place() {
        assertTrue(bool("""
                const a = new Uint8Array(32);
                crypto.getRandomValues(a) === a && a.some(b => b !== 0)
                """));
    }

    // A float typed array carries no integer element type to fill, so it is rejected
    @Test
    public void test_get_random_values_rejects_a_float_array() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("crypto.getRandomValues(new Float64Array(4))"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("crypto.getRandomValues([1, 2, 3])"));
    }

    // The 65536-byte quota is a RangeError, not WHATWG's QuotaExceededError
    @Test
    public void test_get_random_values_quota() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("crypto.getRandomValues(new Uint8Array(65537))"));
    }

    // A length-tracking view over a resizable buffer is filled to its current length
    @Test
    public void test_get_random_values_on_a_length_tracking_view() {
        assertTrue(bool("""
                const buffer = new ArrayBuffer(8, { maxByteLength: 16 });
                const view = new Uint8Array(buffer);
                buffer.resize(16);
                crypto.getRandomValues(view).length === 16
                """));
    }

    // hash defaults to hex and also encodes base64
    @Test
    public void test_hash_encodings() {
        assertEquals(ABC_SHA256, str("crypto.hash('sha-256', 'abc')"));
        assertEquals(ABC_SHA256, str("crypto.hash('sha-256', 'abc', 'hex')"));
        assertEquals("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0",
                str("crypto.hash('sha-256', 'abc', 'base64')").replace("=", ""));
    }

    // A Uint8Array digests the same bytes a string of the same content does
    @Test
    public void test_hash_accepts_a_typed_array() {
        assertEquals(ABC_SHA256, str("crypto.hash('sha-256', new Uint8Array([97, 98, 99]))"));
    }

    // sha-1 and sha-512 are the other two supported algorithms
    @Test
    public void test_hash_other_algorithms() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", str("crypto.hash('sha-1', 'abc')"));
        assertEquals(128, str("crypto.hash('sha-512', 'abc')").length());
    }

    // An unknown algorithm or encoding is a TypeError
    @Test
    public void test_hash_rejects_unknown_names() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("crypto.hash('md5', 'abc')"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("crypto.hash('sha-256', 'abc', 'rot13')"));
    }

    // The namespace is non-enumerable, matching Math/JSON, so it cannot leak into Object.keys
    @Test
    public void test_namespace_is_non_enumerable() {
        assertTrue(bool("!Object.keys(globalThis).includes('crypto')"));
        assertTrue(bool("Object.getOwnPropertyNames(globalThis).includes('crypto')"));
        assertEquals("[object Crypto]", str("Object.prototype.toString.call(crypto)"));
    }
}
