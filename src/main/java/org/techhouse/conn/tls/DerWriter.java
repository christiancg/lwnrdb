package org.techhouse.conn.tls;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Minimal ASN.1 DER encoder, written with only public JDK APIs so the database keeps its
 * zero-dependency promise (the same spirit as the hand-rolled {@code ejson} serializer). It exposes
 * just the building blocks needed to assemble a self-signed X.509 certificate.
 */
public final class DerWriter {
    static final int TAG_INTEGER = 0x02;
    static final int TAG_BIT_STRING = 0x03;
    static final int TAG_OCTET_STRING = 0x04;
    static final int TAG_NULL = 0x05;
    static final int TAG_OID = 0x06;
    static final int TAG_UTF8_STRING = 0x0C;
    static final int TAG_UTC_TIME = 0x17;
    static final int TAG_SEQUENCE = 0x30;
    static final int TAG_SET = 0x31;

    private static final DateTimeFormatter UTC_TIME_FORMAT = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'",
            Locale.ROOT);

    private DerWriter() {
    }

    /** Wraps the given content in a tag-length-value triple. */
    public static byte[] tlv(int tag, byte[] content) {
        final var out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
        } else {
            final var bytes = BigInteger.valueOf(length).toByteArray();
            // toByteArray may add a leading zero to keep the value positive; strip it.
            var start = 0;
            if (bytes.length > 1 && bytes[0] == 0) {
                start = 1;
            }
            final var count = bytes.length - start;
            out.write(0x80 | count);
            out.write(bytes, start, count);
        }
    }

    public static byte[] integer(BigInteger value) {
        return tlv(TAG_INTEGER, value.toByteArray());
    }

    public static byte[] sequence(byte[]... items) {
        return tlv(TAG_SEQUENCE, concat(items));
    }

    public static byte[] set(byte[]... items) {
        return tlv(TAG_SET, concat(items));
    }

    public static byte[] bitString(byte[] content) {
        final var withUnusedBits = new byte[content.length + 1];
        withUnusedBits[0] = 0; // number of unused bits in the final byte
        System.arraycopy(content, 0, withUnusedBits, 1, content.length);
        return tlv(TAG_BIT_STRING, withUnusedBits);
    }

    public static byte[] octetString(byte[] content) {
        return tlv(TAG_OCTET_STRING, content);
    }

    public static byte[] nullValue() {
        return tlv(TAG_NULL, new byte[0]);
    }

    public static byte[] utf8String(String value) {
        return tlv(TAG_UTF8_STRING, value.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] utcTime(Instant instant) {
        final var text = ZonedDateTime.ofInstant(instant, ZoneOffset.UTC).format(UTC_TIME_FORMAT);
        return tlv(TAG_UTC_TIME, text.getBytes(StandardCharsets.US_ASCII));
    }

    /** Encodes an explicit context-specific constructed tag {@code [tagNumber]}. */
    public static byte[] explicit(int tagNumber, byte[] content) {
        return tlv(0xA0 | tagNumber, content);
    }

    /** Encodes a primitive context-specific tag {@code [tagNumber]} (used for GeneralName values). */
    public static byte[] contextPrimitive(int tagNumber, byte[] content) {
        return tlv(0x80 | tagNumber, content);
    }

    public static byte[] oid(int... arcs) {
        final var out = new ByteArrayOutputStream();
        out.write(40 * arcs[0] + arcs[1]);
        for (var i = 2; i < arcs.length; i++) {
            writeBase128(out, arcs[i]);
        }
        return tlv(TAG_OID, out.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream out, int value) {
        if (value < 0x80) {
            out.write(value);
            return;
        }
        final var stack = new byte[5];
        var count = 0;
        var remaining = value;
        while (remaining > 0) {
            stack[count++] = (byte) (remaining & 0x7F);
            remaining >>>= 7;
        }
        for (var i = count - 1; i >= 0; i--) {
            final var isLast = i == 0;
            out.write((stack[i] & 0x7F) | (isLast ? 0 : 0x80));
        }
    }

    public static byte[] concat(byte[]... items) {
        final var out = new ByteArrayOutputStream();
        for (var item : items) {
            out.writeBytes(item);
        }
        return out.toByteArray();
    }
}
