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

    @Test
    public void test_from_base64() {
        assertEquals("1,2", str("Uint8Array.fromBase64('AQI=').join(',')"));
    }

    @Test
    public void test_from_base64_url_alphabet() {
        assertEquals("251,255", str("Uint8Array.fromBase64('-_8=', { alphabet: 'base64url' }).join(',')"));
    }

    @Test
    public void test_from_base64_rejects_primitive_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('AQI=', 5)"));
    }

    @Test
    public void test_from_base64_rejects_unknown_last_chunk_handling() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Uint8Array.fromBase64('AQ==', { lastChunkHandling: 'nope' })"));
    }

    @Test
    public void test_from_base64_loose_accepts_a_partial_chunk() {
        assertEquals("1,2", str("Uint8Array.fromBase64('AQI').join(',')"));
    }

    @Test
    public void test_from_base64_strict_rejects_a_partial_chunk() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("Uint8Array.fromBase64('AQI', { lastChunkHandling: 'strict' })"));
    }

    @Test
    public void test_from_base64_stop_before_partial() {
        assertEquals("", str("Uint8Array.fromBase64('AQI', { lastChunkHandling: 'stop-before-partial' }).join(',')"));
    }

    @Test
    public void test_from_base64_strict_accepts_padding() {
        assertEquals("1", str("Uint8Array.fromBase64('AQ==', { lastChunkHandling: 'strict' }).join(',')"));
    }

    @Test
    public void test_from_base64_strict_rejects_extra_bits() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("Uint8Array.fromBase64('AR==', { lastChunkHandling: 'strict' })"));
    }

    @Test
    public void test_set_from_base64_reports_progress() {
        final var source = """
                const t = new Uint8Array(4);
                const result = t.setFromBase64('AQI=');
                t.join(',') + ':' + result.read + ':' + result.written
                """;
        assertEquals("1,2,0,0:4:2", str(source));
    }

    @Test
    public void test_set_from_hex_reports_progress() {
        final var source = """
                const t = new Uint8Array(2);
                const result = t.setFromHex('0a0b');
                t.join(',') + ':' + result.read + ':' + result.written
                """;
        assertEquals("10,11:4:2", str(source));
    }

    @Test
    public void test_to_base64() {
        assertEquals("AQI=", str("new Uint8Array([1, 2]).toBase64()"));
    }

    @Test
    public void test_to_base64_url_alphabet() {
        assertEquals("__8=", str("new Uint8Array([255, 255]).toBase64({ alphabet: 'base64url' })"));
    }

    @Test
    public void test_to_base64_omit_padding() {
        assertEquals("AQ", str("new Uint8Array([1]).toBase64({ omitPadding: true })"));
    }

    @Test
    public void test_from_hex() {
        assertEquals("10,11", str("Uint8Array.fromHex('0a0b').join(',')"));
    }

    @Test
    public void test_from_hex_rejects_an_odd_length() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromHex('abc')"));
    }

    @Test
    public void test_from_hex_rejects_a_bad_digit() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromHex('zz')"));
    }

    @Test
    public void test_to_hex() {
        assertEquals("0a0b", str("new Uint8Array([10, 11]).toHex()"));
    }

    @Test
    public void test_family_is_uint8_only() {
        assertEquals("undefined", str("String(Int8Array.prototype.toBase64)"));
    }

    @Test
    public void test_from_base64_rejects_leading_padding() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('=AAA')"));
    }

    @Test
    public void test_from_base64_rejects_incomplete_padding() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('AQ=')"));
    }

    @Test
    public void test_from_base64_rejects_trailing_data() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('AQI==')"));
    }

    @Test
    public void test_from_base64_strict_rejects_extra_bits_in_a_three_character_chunk() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("Uint8Array.fromBase64('AQJ=', { lastChunkHandling: 'strict' })"));
    }

    @Test
    public void test_from_base64_ignores_whitespace() {
        assertEquals("1,2", str("Uint8Array.fromBase64(' AQ I= ').join(',')"));
    }

    @Test
    public void test_from_base64_rejects_a_foreign_character() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('A!I=')"));
    }

    @Test
    public void test_to_base64_rejects_an_unknown_alphabet() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Uint8Array([1]).toBase64({ alphabet: 'nope' })"));
    }

    @Test
    public void test_primitive_options_are_rejected() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Uint8Array([1]).toBase64(5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const t = new Uint8Array(2); t.setFromBase64('AQ==', 5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Uint8Array.fromHex('0a', 5)"));
    }

    @Test
    public void test_from_hex_accepts_uppercase() {
        assertEquals("10,11", str("Uint8Array.fromHex('0A0B').join(',')"));
    }
}
