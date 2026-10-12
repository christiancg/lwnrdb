package org.techhouse.utils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Set;
import org.techhouse.log.Logger;

public final class OwnerOnlyPermissions {
    private static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OTHERS = PosixFilePermissions.fromString("---rwxrwx");
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Logger logger = Logger.logFor(OwnerOnlyPermissions.class);

    private OwnerOnlyPermissions() {
    }

    public static boolean supported() {
        return POSIX;
    }

    public static void createDirectories(Path directory) throws IOException {
        createDirectories(directory, POSIX);
    }

    static void createDirectories(Path directory, boolean posix) throws IOException {
        if (posix) {
            try {
                createMissingLevelsOwnerOnly(directory.toAbsolutePath());
                return;
            } catch (IOException | UnsupportedOperationException e) {
                logger.warning("Could not create " + directory + " as owner-only; creating it with default permissions",
                        e);
            }
        }
        Files.createDirectories(directory);
    }

    private static void createMissingLevelsOwnerOnly(Path absolute) throws IOException {
        final var missing = new ArrayDeque<Path>();
        for (var level = absolute; level != null && !Files.exists(level); level = level.getParent()) {
            missing.push(level);
        }
        while (!missing.isEmpty()) {
            final var level = missing.pop();
            try {
                Files.createDirectory(level, PosixFilePermissions.asFileAttribute(DIRECTORY));
            } catch (FileAlreadyExistsException raced) {
                if (!Files.isDirectory(level)) {
                    throw raced;
                }
            }
        }
    }

    public static OutputStream newOwnerOnlyOutputStream(Path file) throws IOException {
        return newOwnerOnlyOutputStream(file, POSIX);
    }

    static OutputStream newOwnerOnlyOutputStream(Path file, boolean posix) throws IOException {
        if (posix && !Files.exists(file)) {
            createOwnerOnlyFile(file);
        }
        return Files.newOutputStream(file);
    }

    public static void ensureOwnerOnlyFile(Path file) {
        if (POSIX && !Files.exists(file)) {
            createOwnerOnlyFile(file);
        }
    }

    public static void ensureOwnerOnlyFileQuietly(Path file) {
        if (POSIX && !Files.exists(file)) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE));
            } catch (IOException | UnsupportedOperationException ignored) {
            }
        }
    }

    public static boolean createNewOwnerOnlyFile(Path file) throws IOException {
        if (Files.exists(file)) {
            return false;
        }
        ensureOwnerOnlyFile(file);
        return Files.exists(file) || file.toFile().createNewFile();
    }

    private static void createOwnerOnlyFile(Path file) {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE));
        } catch (IOException | UnsupportedOperationException e) {
            if (!Files.exists(file)) {
                logger.warning("Could not create " + file + " as owner-only; creating it with default permissions", e);
            }
        }
    }

    public static boolean isAccessibleByOthers(Path path) {
        return isAccessibleByOthers(path, POSIX);
    }

    static boolean isAccessibleByOthers(Path path, boolean posix) {
        if (!posix) {
            return false;
        }
        try {
            return Files.getPosixFilePermissions(path).stream().anyMatch(OTHERS::contains);
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
