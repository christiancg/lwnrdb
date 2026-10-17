package org.techhouse.conn;

import java.nio.charset.StandardCharsets;

public record RequestLine(byte[] bytes, int start, int end) {
    public String text() {
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }

    public boolean isBlank() {
        for (var index = start; index < end; index++) {
            final var b = bytes[index];
            if (b < 0) {
                return text().isBlank();
            }
            if (!Character.isWhitespace(b)) {
                return false;
            }
        }
        return true;
    }
}
