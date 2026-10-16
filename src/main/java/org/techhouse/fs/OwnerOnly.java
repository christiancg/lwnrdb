package org.techhouse.fs;

import java.io.IOException;
import java.nio.file.Path;
import org.techhouse.fs.io.OwnerOnlyFiles;
import org.techhouse.fs.io.OwnerOnlyFiles.Creation;
import org.techhouse.log.Logger;

final class OwnerOnly {
    private static final Logger logger = Logger.logFor(OwnerOnly.class);

    private OwnerOnly() {
    }

    static void createDirectories(Path directory) throws IOException {
        warnIfDefault(OwnerOnlyFiles.createDirectories(directory), directory);
    }

    static boolean createDirectory(Path directory) {
        return OwnerOnlyFiles.createDirectory(directory);
    }

    static void ensureOwnerOnlyFile(Path file) {
        warnIfDefault(OwnerOnlyFiles.ensureOwnerOnlyFile(file), file);
    }

    static boolean createNewOwnerOnlyFile(Path file) throws IOException {
        final var creation = OwnerOnlyFiles.createNewOwnerOnlyFile(file);
        warnIfDefault(creation, file);
        return creation != Creation.ALREADY_EXISTED;
    }

    static void warnIfDefault(Creation creation, Path path) {
        if (creation == Creation.DEFAULT_PERMISSIONS) {
            logger.warning("Could not create " + path + " as owner-only; creating it with default permissions");
        }
    }
}
