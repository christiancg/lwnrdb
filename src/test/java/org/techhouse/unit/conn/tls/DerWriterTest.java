package org.techhouse.unit.conn.tls;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.tls.DerWriter;

public class DerWriterTest {

    @Test
    public void test_tlv_short_form_length() {
        final var encoded = DerWriter.tlv(0x04, new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{0x04, 0x03, 1, 2, 3}, encoded);
    }

    @Test
    public void test_tlv_long_form_length_two_bytes() {
        final var content = new byte[300];
        final var encoded = DerWriter.tlv(0x04, content);
        // 300 = 0x012C, long form: 0x82 (2 length bytes) 0x01 0x2C
        assertEquals(0x04, encoded[0] & 0xFF);
        assertEquals(0x82, encoded[1] & 0xFF);
        assertEquals(0x01, encoded[2] & 0xFF);
        assertEquals(0x2C, encoded[3] & 0xFF);
        assertEquals(300 + 4, encoded.length);
    }

    @Test
    public void test_tlv_long_form_length_with_high_bit_strips_leading_zero() {
        // length 200 = 0xC8; BigInteger.toByteArray() prepends a 0x00 because the high bit is set.
        final var content = new byte[200];
        final var encoded = DerWriter.tlv(0x04, content);
        assertEquals(0x81, encoded[1] & 0xFF); // one length byte
        assertEquals(0xC8, encoded[2] & 0xFF);
        assertEquals(200 + 3, encoded.length);
    }

    @Test
    public void test_integer_encoding() {
        assertArrayEquals(new byte[]{0x02, 0x01, 0x05}, DerWriter.integer(BigInteger.valueOf(5)));
    }

    @Test
    public void test_sequence_wraps_items() {
        final var encoded = DerWriter.sequence(DerWriter.integer(BigInteger.ONE), DerWriter.integer(BigInteger.TWO));
        assertEquals(0x30, encoded[0] & 0xFF);
        assertEquals(6, encoded[1] & 0xFF); // two 3-byte integers
    }

    @Test
    public void test_set_tag() {
        final var encoded = DerWriter.set(DerWriter.integer(BigInteger.ONE));
        assertEquals(0x31, encoded[0] & 0xFF);
    }

    @Test
    public void test_bit_string_prepends_unused_bits_byte() {
        final var encoded = DerWriter.bitString(new byte[]{(byte) 0xAB});
        assertArrayEquals(new byte[]{0x03, 0x02, 0x00, (byte) 0xAB}, encoded);
    }

    @Test
    public void test_octet_string_tag() {
        final var encoded = DerWriter.octetString(new byte[]{1});
        assertEquals(0x04, encoded[0] & 0xFF);
    }

    @Test
    public void test_null_value() {
        assertArrayEquals(new byte[]{0x05, 0x00}, DerWriter.nullValue());
    }

    @Test
    public void test_utf8_string() {
        final var encoded = DerWriter.utf8String("AB");
        assertArrayEquals(new byte[]{0x0C, 0x02, 'A', 'B'}, encoded);
    }

    @Test
    public void test_explicit_context_tag() {
        final var encoded = DerWriter.explicit(0, DerWriter.integer(BigInteger.valueOf(2)));
        assertEquals(0xA0, encoded[0] & 0xFF);
    }

    @Test
    public void test_context_primitive_tag() {
        final var encoded = DerWriter.contextPrimitive(2, new byte[]{1, 2});
        assertArrayEquals(new byte[]{(byte) 0x82, 0x02, 1, 2}, encoded);
    }

    @Test
    public void test_oid_with_small_and_large_arcs() {
        // 1.2.840.113549.1.1.11 (sha256WithRSAEncryption)
        final var encoded = DerWriter.oid(1, 2, 840, 113549, 1, 1, 11);
        final var expected = new byte[]{0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01,
                0x0B};
        assertArrayEquals(expected, encoded);
    }

    @Test
    public void test_concat() {
        assertArrayEquals(new byte[]{1, 2, 3, 4}, DerWriter.concat(new byte[]{1, 2}, new byte[]{3, 4}));
    }
}
