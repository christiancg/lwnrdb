package org.techhouse.fs.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.techhouse.fs.io.OwnerOnlyFiles.Creation;

public final class KeystoreFiles {
    private KeystoreFiles() {
    }

    public static boolean exists(Path keystore) {
        return Files.exists(keystore);
    }

    public static byte[] read(Path keystore) throws IOException {
        return Files.readAllBytes(keystore);
    }

    public static Creation writeOwnerOnly(Path keystore, byte[] content) throws IOException {
        final var parent = keystore.toAbsolutePath().getParent();
        final var folder = parent == null ? Creation.ALREADY_EXISTED : OwnerOnlyFiles.createDirectories(parent);
        final var file = OwnerOnlyFiles.ensureOwnerOnlyFile(keystore);
        try (var out = Files.newOutputStream(keystore)) {
            out.write(content);
        }
        return folder == Creation.DEFAULT_PERMISSIONS ? folder : file;
    }
}
