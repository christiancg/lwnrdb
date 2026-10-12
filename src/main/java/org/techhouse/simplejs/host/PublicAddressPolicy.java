package org.techhouse.simplejs.host;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

public final class PublicAddressPolicy {
    private static final int IPV4_BYTES = 4;
    private static final int IPV6_BYTES = 16;
    private static final int EMBEDDED_TAIL = 12;
    private static final int SIX_TO_FOUR_OFFSET = 2;
    private static final int MAPPED_MARKER = 10;

    private record Range(byte[] prefix, int bits) {
        boolean contains(byte[] address) {
            if (address.length != prefix.length) {
                return false;
            }
            final var whole = bits / Byte.SIZE;
            for (var i = 0; i < whole; i++) {
                if (address[i] != prefix[i]) {
                    return false;
                }
            }
            final var rest = bits % Byte.SIZE;
            if (rest == 0) {
                return true;
            }
            final var mask = (0xFF << (Byte.SIZE - rest)) & 0xFF;
            return (address[whole] & mask) == (prefix[whole] & mask);
        }
    }

    private static final List<Range> BLOCKED = List.of(range("0.0.0.0", 8), range("10.0.0.0", 8),
            range("100.64.0.0", 10), range("127.0.0.0", 8), range("169.254.0.0", 16), range("172.16.0.0", 12),
            range("192.0.0.0", 24), range("192.168.0.0", 16), range("198.18.0.0", 15), range("224.0.0.0", 4),
            range("240.0.0.0", 4), range("::", 128), range("::1", 128), range("fc00::", 7), range("fe80::", 10),
            range("fec0::", 10), range("ff00::", 8));

    private static final List<Range> EMBEDDING_AT_TAIL = List.of(range("::ffff:0:0", 96), range("::", 96),
            range("64:ff9b::", 96));
    private static final Range SIX_TO_FOUR = range("2002::", 16);

    private PublicAddressPolicy() {
    }

    public static boolean isPublic(InetAddress address) {
        if (address == null) {
            return false;
        }
        final var bytes = address.getAddress();
        if (isBlocked(bytes)) {
            return false;
        }
        final var embedded = embeddedIpv4(bytes);
        return embedded == null || !isBlocked(embedded);
    }

    private static boolean isBlocked(byte[] bytes) {
        for (final var range : BLOCKED) {
            if (range.contains(bytes)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] embeddedIpv4(byte[] bytes) {
        if (bytes.length != IPV6_BYTES) {
            return null;
        }
        for (final var range : EMBEDDING_AT_TAIL) {
            if (range.contains(bytes)) {
                return Arrays.copyOfRange(bytes, EMBEDDED_TAIL, IPV6_BYTES);
            }
        }
        if (SIX_TO_FOUR.contains(bytes)) {
            return Arrays.copyOfRange(bytes, SIX_TO_FOUR_OFFSET, SIX_TO_FOUR_OFFSET + IPV4_BYTES);
        }
        return null;
    }

    private static Range range(String literal, int bits) {
        final var raw = InetAddress.ofLiteral(literal).getAddress();
        if (literal.indexOf(':') < 0 || raw.length == IPV6_BYTES) {
            return new Range(raw, bits);
        }
        final var mapped = new byte[IPV6_BYTES];
        mapped[MAPPED_MARKER] = (byte) 0xFF;
        mapped[MAPPED_MARKER + 1] = (byte) 0xFF;
        System.arraycopy(raw, 0, mapped, EMBEDDED_TAIL, IPV4_BYTES);
        return new Range(mapped, bits);
    }
}
