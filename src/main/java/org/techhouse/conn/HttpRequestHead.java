package org.techhouse.conn;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

record HttpRequestHead(String method, String path, Map<String, String> headers) {
    private static final String VERSION_PREFIX = "HTTP/";

    static final class TooLarge extends IOException {
        TooLarge(int maxBytes) {
            super("The request head is longer than " + maxBytes + " bytes");
        }
    }

    static final class Malformed extends IOException {
        Malformed(String detail) {
            super(detail);
        }
    }

    String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    static HttpRequestHead read(InputStream in, int maxBytes) throws IOException {
        final var budget = new int[]{maxBytes};
        final var requestLine = readLine(in, budget, maxBytes);
        final var parts = requestLine.split(" ", -1);
        if (parts.length != 3 || parts[0].isEmpty() || !parts[1].startsWith("/")
                || !parts[2].startsWith(VERSION_PREFIX)) {
            throw new Malformed("Not an HTTP request line: " + requestLine);
        }
        final var headers = new HashMap<String, String>();
        for (var line = readLine(in, budget, maxBytes); !line.isEmpty(); line = readLine(in, budget, maxBytes)) {
            final var colon = line.indexOf(':');
            if (colon <= 0) {
                throw new Malformed("Not a header line: " + line);
            }
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        return new HttpRequestHead(parts[0], pathWithoutQuery(parts[1]), Map.copyOf(headers));
    }

    private static String pathWithoutQuery(String target) {
        final var query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    private static String readLine(InputStream in, int[] budget, int maxBytes) throws IOException {
        final var line = new ByteArrayOutputStream();
        while (true) {
            final var next = in.read();
            if (next < 0) {
                throw new EOFException("The connection ended before the request head did");
            }
            if (--budget[0] < 0) {
                throw new TooLarge(maxBytes);
            }
            if (next == '\n') {
                final var bytes = line.toByteArray();
                final var length = bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                        ? bytes.length - 1
                        : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(next);
        }
    }
}
