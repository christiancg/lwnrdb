package org.techhouse.fs.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Folders {
    private Folders() {
    }

    public record FolderCheck(boolean created, boolean writable, String failure) {
        public boolean ok() {
            return failure == null && writable;
        }
    }

    public static FolderCheck ensureWritable(Path folder) {
        final var existed = Files.exists(folder);
        try {
            OwnerOnlyFiles.createDirectories(folder);
        } catch (IOException | RuntimeException e) {
            return new FolderCheck(false, false, String.valueOf(e.getMessage()));
        }
        return new FolderCheck(!existed, Files.isWritable(folder), null);
    }
}
