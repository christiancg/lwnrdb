package org.techhouse.fs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.techhouse.config.Globals;

final class LegacyStorageOracle {
    private LegacyStorageOracle() {
    }

    static List<String> decodeLines(byte[] bytes) throws IOException {
        try (var reader = new BufferedReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            return reader.lines().toList();
        }
    }

    static byte[] joinLines(List<String> lines) {
        var chars = 0;
        for (final var line : lines) {
            chars += line.length() + Globals.NEWLINE.length();
        }
        final var builder = new StringBuilder(chars);
        for (final var line : lines) {
            builder.append(line).append(Globals.NEWLINE);
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}
