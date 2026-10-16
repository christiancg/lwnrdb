package org.techhouse.fs.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class LogFolder {
    private LogFolder() {
    }

    public static List<Path> listRegularFiles(Path folder) throws IOException {
        try (var entries = Files.list(folder)) {
            return entries.filter(Files::isRegularFile).sorted().toList();
        }
    }

    public static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static boolean delete(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean createFileIfAbsent(Path file) throws IOException {
        if (Files.exists(file)) {
            return true;
        }
        OwnerOnlyFiles.ensureOwnerOnlyFile(file);
        return Files.exists(file) || file.toFile().createNewFile();
    }
}
