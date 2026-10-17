package org.techhouse.fs.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class TextFiles {
    private TextFiles() {
    }

    public static List<String> readLinesIfExists(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }
}
