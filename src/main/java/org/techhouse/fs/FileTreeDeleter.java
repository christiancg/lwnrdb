package org.techhouse.fs;

import java.io.File;

final class FileTreeDeleter {
    private static final int SWEEPS = 2;

    private FileTreeDeleter() {
    }

    static boolean delete(File root) {
        for (var sweep = 0; sweep < SWEEPS; sweep++) {
            if (sweep(root)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sweep(File file) {
        final var children = file.listFiles();
        if (children != null) {
            for (final var child : children) {
                sweep(child);
            }
        }
        return file.delete() || !file.exists();
    }
}
