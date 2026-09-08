package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

public class Uint8ArrayBase64ProgramTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // fromBase64 decodes a padded string
    @Test
    public void test_from_base64() {
        assertEquals("1,2", str("Uint8Array.fromBase64('AQI=').join(',')"));
    }

    // The base64url alphabet accepts the URL-safe digits
    @Test
    public void test_from_base64_url_alphabet() {
        assertEquals("251,255", str("Uint8Array.fromBase64('-_8=', { alphabet: 'base64url' }).join(',')"));
    }

    // A primitive options argument is a TypeError
    @Test
    public void test_from_base64_rejects_primitive_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('AQI=', 5)"));
    }

    // An unknown lastChunkHandling value is a TypeError
    @Test
    public void test_from_base64_rejects_unknown_last_chunk_handling() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Uint8Array.fromBase64('AQ==', { lastChunkHandling: 'nope' })"));
    }

    // The default handling accepts a trailing partial chunk
    @Test
    public void test_from_base64_loose_accepts_a_partial_chunk() {
        assertEquals("1,2", str("Uint8Array.fromBase64('AQI').join(',')"));
    }

    // Strict handling rejects a trailing partial chunk
    @Test
    public void test_from_base64_strict_rejects_a_partial_chunk() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("Uint8Array.fromBase64('AQI', { lastChunkHandling: 'strict' })"));
    }

    // stop-before-partial ignores the trailing partial chunk
    @Test
    public void test_from_base64_stop_before_partial() {
        assertEquals("", str("Uint8Array.fromBase64('AQI', { lastChunkHandling: 'stop-before-partial' }).join(',')"));
    }

    // Strict handling accepts a properly padded final chunk
    @Test
    public void test_from_base64_strict_accepts_padding() {
        assertEquals("1", str("Uint8Array.fromBase64('AQ==', { lastChunkHandling: 'strict' }).join(',')"));
    }

    // Strict handling rejects non-zero bits beyond the decoded bytes
    @Test
    public void test_from_base64_strict_rejects_extra_bits() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("Uint8Array.fromBase64('AR==', { lastChunkHandling: 'strict' })"));
    }

    // setFromBase64 reports how much it read and wrote
    @Test
    public void test_set_from_base64_reports_progress() {
        final var source = """
                const t = new Uint8Array(4);
                const result = t.setFromBase64('AQI=');
                t.join(',') + ':' + result.read + ':' + result.written
                """;
        assertEquals("1,2,0,0:4:2", str(source));
    }

    // setFromHex fills the target and reports its progress
    @Test
    public void test_set_from_hex_reports_progress() {
        final var source = """
                const t = new Uint8Array(2);
                const result = t.setFromHex('0a0b');
                t.join(',') + ':' + result.read + ':' + result.written
                """;
        assertEquals("10,11:4:2", str(source));
    }

    // toBase64 emits padded standard base64
    @Test
    public void test_to_base64() {
        assertEquals("AQI=", str("new Uint8Array([1, 2]).toBase64()"));
    }

    // toBase64 can emit the URL-safe alphabet
    @Test
    public void test_to_base64_url_alphabet() {
        assertEquals("__8=", str("new Uint8Array([255, 255]).toBase64({ alphabet: 'base64url' })"));
    }

    // omitPadding drops the trailing padding characters
    @Test
    public void test_to_base64_omit_padding() {
        assertEquals("AQ", str("new Uint8Array([1]).toBase64({ omitPadding: true })"));
    }

    // fromHex decodes pairs of hex digits
    @Test
    public void test_from_hex() {
        assertEquals("10,11", str("Uint8Array.fromHex('0a0b').join(',')"));
    }

    // An odd-length hex string is a SyntaxError
    @Test
    public void test_from_hex_rejects_an_odd_length() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromHex('abc')"));
    }

    // A non-hex digit is a SyntaxError
    @Test
    public void test_from_hex_rejects_a_bad_digit() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromHex('zz')"));
    }

    // toHex emits lowercase hex digits
    @Test
    public void test_to_hex() {
        assertEquals("0a0b", str("new Uint8Array([10, 11]).toHex()"));
    }

    // The base64 family lives on Uint8Array only
    @Test
    public void test_family_is_uint8_only() {
        assertEquals("undefined", str("String(Int8Array.prototype.toBase64)"));
    }

    // Padding before any data is a SyntaxError
    @Test
    public void test_from_base64_rejects_leading_padding() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('=AAA')"));
    }

    // A truncated padding sequence is a SyntaxError
    @Test
    public void test_from_base64_rejects_incomplete_padding() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('AQ=')"));
    }

    // Data after the padding is a SyntaxError
    @Test
    public void test_from_base64_rejects_trailing_data() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('AQI==')"));
    }

    // Strict handling rejects extra bits in a three-character final chunk
    @Test
    public void test_from_base64_strict_rejects_extra_bits_in_a_three_character_chunk() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("Uint8Array.fromBase64('AQJ=', { lastChunkHandling: 'strict' })"));
    }

    // ASCII whitespace inside the input is ignored
    @Test
    public void test_from_base64_ignores_whitespace() {
        assertEquals("1,2", str("Uint8Array.fromBase64(' AQ I= ').join(',')"));
    }

    // A character outside the alphabet is a SyntaxError
    @Test
    public void test_from_base64_rejects_a_foreign_character() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('A!I=')"));
    }

    // An unknown alphabet name is a TypeError
    @Test
    public void test_to_base64_rejects_an_unknown_alphabet() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Uint8Array([1]).toBase64({ alphabet: 'nope' })"));
    }

    // Primitive options are rejected by every member of the family
    @Test
    public void test_primitive_options_are_rejected() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Uint8Array([1]).toBase64(5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const t = new Uint8Array(2); t.setFromBase64('AQ==', 5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Uint8Array.fromHex('0a', 5)"));
    }

    // Uppercase hex digits are accepted
    @Test
    public void test_from_hex_accepts_uppercase() {
        assertEquals("10,11", str("Uint8Array.fromHex('0A0B').join(',')"));
    }
}
