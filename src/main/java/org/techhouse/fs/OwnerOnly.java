package org.techhouse.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.fs.io.OwnerOnlyFiles;
import org.techhouse.fs.io.OwnerOnlyFiles.Creation;
import org.techhouse.log.Logger;

final class OwnerOnly {
    private static final Logger logger = Logger.logFor(OwnerOnly.class);

    private OwnerOnly() {
    }

    static void createDirectories(Path directory) throws IOException {
        final var missing = missingLevels(directory);
        warnIfDefault(OwnerOnlyFiles.createDirectories(directory), directory);
        for (final var level : missing) {
            SyncedWrites.entriesOfLanded(level.getParent(), "Creating " + level);
        }
    }

    static boolean createDirectory(Path directory) {
        final var created = OwnerOnlyFiles.createDirectory(directory);
        if (created) {
            SyncedWrites.entriesOfLanded(directory.toAbsolutePath().getParent(), "Creating " + directory);
        }
        return created;
    }

    static void ensureOwnerOnlyFile(Path file) throws IOException {
        final var creation = OwnerOnlyFiles.ensureOwnerOnlyFile(file);
        warnIfDefault(creation, file);
        if (creation == Creation.OWNER_ONLY) {
            SyncedWrites.entries(file.toAbsolutePath().getParent());
        }
    }

    static boolean createNewOwnerOnlyFile(Path file) throws IOException {
        final var creation = OwnerOnlyFiles.createNewOwnerOnlyFile(file);
        warnIfDefault(creation, file);
        final var created = creation != Creation.ALREADY_EXISTED;
        if (created) {
            SyncedWrites.entries(file.toAbsolutePath().getParent());
        }
        return created;
    }

    static void warnIfDefault(Creation creation, Path path) {
        if (creation == Creation.DEFAULT_PERMISSIONS) {
            logger.warning("Could not create " + path + " as owner-only; creating it with default permissions");
        }
    }

    private static List<Path> missingLevels(Path directory) {
        final var missing = new ArrayList<Path>();
        for (var level = directory.toAbsolutePath(); level != null && !Files.exists(level); level = level.getParent()) {
            missing.addFirst(level);
        }
        return missing;
    }
}
