package org.techhouse.fs;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ForceFailures {
    private static final Set<String> failed = ConcurrentHashMap.newKeySet();

    private ForceFailures() {
    }

    static void failed(Path path) {
        failed.add(keyOf(path));
    }

    static void cleared(Path path) {
        if (!failed.isEmpty()) {
            failed.remove(keyOf(path));
        }
    }

    static boolean hasFailed(Path path) {
        return !failed.isEmpty() && failed.contains(keyOf(path));
    }

    static List<File> in(File folder) {
        if (failed.isEmpty()) {
            return List.of();
        }
        final var parent = folder.toPath().toAbsolutePath().normalize();
        final var files = new ArrayList<File>();
        for (final var path : failed) {
            final var file = Path.of(path);
            if (parent.equals(file.getParent())) {
                files.add(file.toFile());
            }
        }
        return files;
    }

    static void forgetUnder(File folder) {
        if (failed.isEmpty()) {
            return;
        }
        final var prefix = keyOf(folder.toPath()) + File.separator;
        failed.removeIf(path -> path.startsWith(prefix));
    }

    private static String keyOf(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
